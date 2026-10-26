package com.hasitha.ergocheck

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min

object Annotator {
    private val CONNECTIONS = listOf(
        11 to 12, 11 to 13, 13 to 15, 15 to 19, 12 to 14, 14 to 16, 16 to 20,
        11 to 23, 12 to 24, 23 to 24, 23 to 25, 25 to 27, 27 to 31,
        24 to 26, 26 to 28, 28 to 32, 0 to 7, 0 to 8,
    )

    private fun color(s: Status) = when (s) {
        Status.OK -> Color.rgb(0, 170, 0)
        Status.CAUTION -> Color.rgb(255, 150, 0)
        Status.RISK -> Color.rgb(220, 0, 0)
    }

    /** Draws skeleton, labels and an overall banner on a copy of [src]. */
    fun draw(src: Bitmap, pts: Map<String, Pt>, analysis: Analysis): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val w = out.width.toFloat()
        val h = out.height.toFloat()
        val scale = max(0.6f, min(w, h) / 900f)

        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE; strokeWidth = 3f * scale; style = Paint.Style.STROKE
        }
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.WHITE; style = Paint.Style.FILL }

        val names = ErgoAnalyzer.LANDMARK_NAMES
        for ((a, b) in CONNECTIONS) {
            val pa = pts[names[a]]; val pb = pts[names[b]]
            if (pa != null && pb != null && pa.vis >= ErgoAnalyzer.MIN_VIS && pb.vis >= ErgoAnalyzer.MIN_VIS) {
                canvas.drawLine(pa.x, pa.y, pb.x, pb.y, line)
            }
        }
        for (n in names) {
            val p = pts[n] ?: continue
            if (p.vis >= ErgoAnalyzer.MIN_VIS) canvas.drawCircle(p.x, p.y, 4f * scale, dot)
        }

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 26f * scale; isFakeBoldText = true }
        val bg = Paint().apply { this.color = Color.BLACK; style = Paint.Style.FILL }
        val r = Rect()
        for (f in analysis.findings) {
            val c = color(f.status)
            dot.color = c
            canvas.drawCircle(f.ax, f.ay, 9f * scale, dot)
            val label = "${f.check}: ${Math.round(f.value)}"
            text.color = c
            text.getTextBounds(label, 0, label.length, r)
            val tx = (f.ax + 14f * scale).coerceIn(4f, max(4f, w - r.width() - 8f))
            val ty = f.ay.coerceIn(r.height() + 8f, h - 8f)
            canvas.drawRect(tx - 4f, ty - r.height() - 6f, tx + r.width() + 4f, ty + 6f, bg)
            canvas.drawText(label, tx, ty, text)
        }

        val banner = "Overall: ${analysis.overall}"
        text.color = Color.WHITE
        text.textSize = 30f * scale
        text.getTextBounds(banner, 0, banner.length, r)
        canvas.drawRect(0f, 0f, w, r.height() + 24f * scale, bg)
        canvas.drawText(banner, 10f, r.height() + 10f * scale, text)
        return out
    }
}
