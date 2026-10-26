package com.hasitha.ergocheck

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

enum class Status { OK, CAUTION, RISK }

/** A body landmark in pixel coordinates with a visibility score (0..1). */
data class Pt(val x: Float, val y: Float, val vis: Float)

data class Finding(
    val check: String,
    val value: Double,
    val status: Status,
    val message: String,
    val advice: String,
    val ax: Float,
    val ay: Float,
)

data class Analysis(
    val view: String,
    val info: String,
    val findings: List<Finding>,
    val notes: List<String>,
) {
    val overall: String
        get() {
            val risks = findings.count { it.status == Status.RISK }
            val cautions = findings.count { it.status == Status.CAUTION }
            return when {
                findings.isEmpty() -> "No measurements possible"
                risks >= 2 || (risks >= 1 && cautions >= 2) -> "HIGH concern - corrections recommended soon"
                risks == 1 || cautions >= 2 -> "MODERATE concern - some corrections recommended"
                cautions == 1 -> "LOW concern - minor adjustment suggested"
                else -> "GOOD - no significant posture problems detected"
            }
        }

    fun report(): String {
        val sb = StringBuilder()
        sb.appendLine("View analysed: $view$info")
        notes.forEach { sb.appendLine("Note: $it") }
        sb.appendLine()
        if (findings.isEmpty()) {
            sb.appendLine("No measurements could be made (key body parts not visible). Retake the photo with more of the body in frame.")
        }
        for (f in findings) {
            sb.appendLine("[${f.status}] ${f.check}: ${f.message}")
            if (f.advice.isNotEmpty()) sb.appendLine("  -> ${f.advice}")
        }
        if (findings.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Overall: $overall")
        }
        sb.appendLine()
        sb.append("Automated screening from a 2D photo - not a medical diagnosis or a formal RULA/REBA assessment.")
        return sb.toString()
    }
}

private typealias P = Pair<Float, Float>

object ErgoAnalyzer {
    const val MIN_VIS = 0.5f

    /** MediaPipe Pose landmark order (33 points). */
    val LANDMARK_NAMES = listOf(
        "nose", "left_eye_inner", "left_eye", "left_eye_outer",
        "right_eye_inner", "right_eye", "right_eye_outer",
        "left_ear", "right_ear", "mouth_left", "mouth_right",
        "left_shoulder", "right_shoulder", "left_elbow", "right_elbow",
        "left_wrist", "right_wrist", "left_pinky", "right_pinky",
        "left_index", "right_index", "left_thumb", "right_thumb",
        "left_hip", "right_hip", "left_knee", "right_knee",
        "left_ankle", "right_ankle", "left_heel", "right_heel",
        "left_foot_index", "right_foot_index",
    )

    // ---------------- geometry ----------------
    private fun angle3(a: P, b: P, c: P): Double? {
        val bax = (a.first - b.first).toDouble()
        val bay = (a.second - b.second).toDouble()
        val bcx = (c.first - b.first).toDouble()
        val bcy = (c.second - b.second).toDouble()
        val d = hypot(bax, bay) * hypot(bcx, bcy)
        if (d == 0.0) return null
        val cos = ((bax * bcx + bay * bcy) / d).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(cos))
    }

    /** Angle of base->top from vertical; positive = leaning toward [facing] (+1 right, -1 left). */
    private fun lean(base: P, top: P, facing: Int): Double {
        val dx = (top.first - base.first).toDouble() * facing
        val dy = (base.second - top.second).toDouble()
        return Math.toDegrees(atan2(dx, dy))
    }

    private fun tilt(p1: P, p2: P): Double {
        val dy = abs(p2.second - p1.second).toDouble()
        val dx = max(abs(p2.first - p1.first), 1e-6f).toDouble()
        return Math.toDegrees(atan2(dy, dx))
    }

    private fun level(v: Double, caution: Double, risk: Double): Status {
        val a = abs(v)
        return if (a >= risk) Status.RISK else if (a >= caution) Status.CAUTION else Status.OK
    }

    private fun range(v: Double, lo: Double, hi: Double, margin: Double): Status {
        if (v in lo..hi) return Status.OK
        val dist = if (v < lo) lo - v else v - hi
        return if (dist > margin) Status.RISK else Status.CAUTION
    }

    private fun f(name: String, value: Double, st: Status, msg: String, advice: String, anchor: P) =
        Finding(name, value, st, msg, if (st == Status.OK) "" else advice, anchor.first, anchor.second)

    private fun deg(v: Double) = v.roundToInt()

    // ---------------- view detection ----------------
    fun classifyView(pts: Map<String, Pt>): Pair<String, Double>? {
        val ls = pts["left_shoulder"] ?: return null
        val rs = pts["right_shoulder"] ?: return null
        val lh = pts["left_hip"] ?: return null
        val rh = pts["right_hip"] ?: return null
        val sw = hypot((ls.x - rs.x).toDouble(), (ls.y - rs.y).toDouble())
        val msx = (ls.x + rs.x) / 2.0
        val msy = (ls.y + rs.y) / 2.0
        val mhx = (lh.x + rh.x) / 2.0
        val mhy = (lh.y + rh.y) / 2.0
        val torso = hypot(msx - mhx, msy - mhy).takeIf { it > 0 } ?: 1e-6
        val ratio = sw / torso
        return (if (ratio < 0.55) "side" else "front") to ratio
    }

    fun analyze(pts: Map<String, Pt>, viewOpt: String): Analysis {
        val notes = mutableListOf<String>()
        val (auto, ratio) = classifyView(pts) ?: ("front" to 1.0)
        val view = if (viewOpt == "auto") auto else viewOpt
        if (viewOpt == "auto" && ratio in 0.35..0.75) {
            notes += "Camera angle looks oblique (neither pure side nor front); angles may be less accurate. Retake from exactly the side or the front."
        }
        if (viewOpt != "auto" && viewOpt != auto) {
            notes += "You chose '$viewOpt' but the photo looks like '$auto'."
        }
        return if (view == "side") {
            val (list, info) = analyzeSide(pts)
            Analysis("side", info, list, notes)
        } else {
            notes += "Left/right refer to the person's own sides, assuming they face the camera."
            Analysis("front", "", analyzeFront(pts), notes)
        }
    }

    // ---------------- SIDE view ----------------
    private fun analyzeSide(pts: Map<String, Pt>): Pair<List<Finding>, String> {
        fun score(side: String) = listOf("ear", "shoulder", "elbow", "wrist", "hip", "knee", "ankle")
            .sumOf { (pts["${side}_$it"]?.vis ?: 0f).toDouble() }
        val side = if (score("left") >= score("right")) "left" else "right"

        fun p(n: String): P? = pts["${side}_$n"]?.takeIf { it.vis >= MIN_VIS }?.let { it.x to it.y }

        val ear = p("ear"); val sh = p("shoulder"); val el = p("elbow"); val wr = p("wrist")
        val idx = p("index"); val hip = p("hip"); val knee = p("knee"); val ankle = p("ankle")

        var facing = 1
        val nose = pts["nose"]
        if (ear != null && nose != null && nose.vis >= MIN_VIS) {
            facing = if (nose.x > ear.first) 1 else -1
        } else {
            val fi = p("foot_index")
            if (fi != null && ankle != null) facing = if (fi.first > ankle.first) 1 else -1
        }

        val out = mutableListOf<Finding>()

        // 1. Head / neck forward tilt
        if (ear != null && sh != null) {
            val a = lean(sh, ear, facing)
            val st = level(if (a > 0) a else 0.0, 15.0, 30.0)
            out += f(
                "Head/neck forward tilt", a, st,
                if (a > 0) "Head is ${deg(a)} deg forward of the shoulders" else "Head is ${deg(abs(a))} deg behind the shoulders",
                "Raise the monitor/work so the top edge is at about eye level and arm's length away; bring documents closer or use a document holder; take micro-breaks and do chin-tuck stretches.",
                ear,
            )
        }

        // 2. Trunk lean
        if (sh != null && hip != null) {
            val a = lean(hip, sh, facing)
            if (a >= 0) {
                out += f(
                    "Trunk lean", a, level(a, 20.0, 45.0),
                    "Trunk leans forward ${deg(a)} deg",
                    "Sit back so the lower back touches the backrest with lumbar support; bring the work closer to the body or raise it (slightly tilted work surface); when standing, set the work height near elbow level instead of bending.",
                    sh,
                )
            } else {
                out += f(
                    "Trunk lean", a, level(abs(a), 15.0, 30.0),
                    "Trunk leans backward ${deg(abs(a))} deg",
                    "Bring the work within comfortable reach so you do not have to lean back; set the backrest angle to about 95-110 deg for working tasks.",
                    sh,
                )
            }
        }

        // 3. Upper-arm elevation
        if (hip != null && sh != null && el != null) {
            angle3(hip, sh, el)?.let { a ->
                out += f(
                    "Upper-arm elevation", a, level(a, 45.0, 90.0),
                    "Upper arm is ${deg(a)} deg away from the trunk",
                    "Keep frequently used items within the close reach zone (elbows near the body); lower the work height or raise the seat; use armrests or forearm support for sustained work.",
                    el,
                )
            }
        }

        // 4. Elbow angle
        if (sh != null && el != null && wr != null) {
            angle3(sh, el, wr)?.let { a ->
                val adv = if (a < 75) {
                    "Elbow is closed too much: lower the keyboard/work surface or raise the chair so forearms are roughly level."
                } else {
                    "Arm is too extended: bring the work closer or raise the work surface/keyboard so the elbow is about 90-110 deg."
                }
                out += f("Elbow angle", a, range(a, 75.0, 135.0, 25.0),
                    "Elbow angle is ${deg(a)} deg (target ~75-135 deg)", adv, el)
            }
        }

        // 5. Wrist deviation (approximate)
        if (el != null && wr != null && idx != null) {
            angle3(el, wr, idx)?.let { a ->
                val dev = 180 - a
                out += f(
                    "Wrist bend (approx.)", dev, level(dev, 25.0, 40.0),
                    "Wrist is bent about ${deg(dev)} deg from neutral",
                    "Keep the wrist straight: adjust keyboard/mouse height and tilt, use a wrist rest only during pauses, and reposition tools or work pieces so the hand is in line with the forearm.",
                    wr,
                )
            }
        }

        // 6. Lower body
        if (hip != null && knee != null) {
            val seated = tilt(hip, knee) < 35
            if (seated && sh != null) {
                angle3(sh, hip, knee)?.let { a ->
                    out += f(
                        "Hip angle (seated)", a, range(a, 90.0, 125.0, 15.0),
                        "Hip angle is ${deg(a)} deg (target ~90-125 deg)",
                        "Adjust seat height/seat-pan tilt and backrest so the hips are open slightly beyond 90 deg; avoid slumping or perching on the seat edge.",
                        hip,
                    )
                }
            }
            if (ankle != null) {
                angle3(hip, knee, ankle)?.let { a ->
                    if (seated) {
                        out += f(
                            "Knee angle (seated)", a, range(a, 85.0, 125.0, 20.0),
                            "Knee angle (seated) is ${deg(a)} deg (target ~85-125 deg)",
                            "Adjust seat height so thighs are about parallel to the floor and the feet rest flat on the floor or a footrest; keep 2-3 finger-widths between the seat edge and the back of the knee.",
                            knee,
                        )
                    } else {
                        val flex = 180 - a
                        out += f(
                            "Knee flexion (standing)", flex, level(flex, 20.0, 40.0),
                            "Knees are bent about ${deg(flex)} deg while standing",
                            "Avoid working with bent knees: raise low work to a comfortable height; use an anti-fatigue mat and shift weight / alternate posture regularly.",
                            knee,
                        )
                    }
                }
            }
        }

        return out to " (facing ${if (facing == 1) "right" else "left"}, $side side measured)"
    }

    // ---------------- FRONT view ----------------
    private fun analyzeFront(pts: Map<String, Pt>): List<Finding> {
        fun p(n: String): P? = pts[n]?.takeIf { it.vis >= MIN_VIS }?.let { it.x to it.y }
        fun mid(a: P, b: P): P = ((a.first + b.first) / 2f) to ((a.second + b.second) / 2f)

        val ls = p("left_shoulder"); val rs = p("right_shoulder")
        val le = p("left_ear"); val re = p("right_ear")
        val lh = p("left_hip"); val rh = p("right_hip")
        val out = mutableListOf<Finding>()

        if (ls != null && rs != null) {
            val a = tilt(ls, rs)
            val higher = if (ls.second < rs.second) "left" else "right"
            out += f("Shoulder level", a, level(a, 3.0, 6.0),
                "Shoulders are uneven by ${deg(a)} deg ($higher side higher)",
                "Avoid carrying loads on one side; set work/mouse/keyboard centrally; lower armrests on the raised side; check for one-sided reaching; do shoulder-mobility breaks.",
                mid(ls, rs))
        }
        if (le != null && re != null) {
            val a = tilt(le, re)
            val lower = if (le.second > re.second) "left" else "right"
            out += f("Head tilt", a, level(a, 5.0, 10.0),
                "Head is tilted about ${deg(a)} deg to the $lower",
                "Place the screen/work directly in front of you; if you use a phone while working, use a headset instead of holding it with the neck.",
                mid(le, re))
        }
        if (lh != null && rh != null) {
            val a = tilt(lh, rh)
            val lower = if (lh.second > rh.second) "left" else "right"
            out += f("Pelvis level", a, level(a, 3.0, 6.0),
                "Hips are uneven by ${deg(a)} deg ($lower side lower)",
                "Share weight evenly on both feet or the seat; avoid sitting on a wallet/phone or crossing the legs; check seat-height symmetry.",
                mid(lh, rh))
        }
        if (ls != null && rs != null && lh != null && rh != null) {
            val ms = mid(ls, rs)
            val a = lean(mid(lh, rh), ms, 1)
            val sideTxt = if (a > 0) "right" else "left"
            out += f("Trunk side-lean", a, level(a, 5.0, 10.0),
                "Trunk leans ${deg(abs(a))} deg to the image-$sideTxt",
                "Re-position the workstation so the task is in front of the body; use both hands evenly; support the back with a symmetrical backrest.",
                ms)
        }
        for (side in listOf("left", "right")) {
            val sh = p("${side}_shoulder"); val el = p("${side}_elbow"); val hip = p("${side}_hip")
            if (sh != null && el != null && hip != null) {
                angle3(hip, sh, el)?.let { a ->
                    out += f("${side.replaceFirstChar { it.uppercase() }} arm elevation", a, level(a, 45.0, 90.0),
                        "${side.replaceFirstChar { it.uppercase() }} upper arm is ${deg(a)} deg away from the trunk",
                        "Lower the work height or bring items closer; use forearm support; avoid sustained work with elbows above mid-chest.",
                        el)
                }
            }
        }
        return out
    }
}
