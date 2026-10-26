package com.hasitha.ergocheck

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var imageView: ImageView
    private lateinit var reportView: TextView
    private lateinit var viewGroup: RadioGroup
    private lateinit var progress: ProgressBar

    private val worker = Executors.newSingleThreadExecutor()
    private var landmarker: PoseLandmarker? = null
    private var photoUri: Uri? = null
    private var lastBitmap: Bitmap? = null

    private val takePicture =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            if (ok) photoUri?.let { loadAndAnalyze(it) }
        }

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { loadAndAnalyze(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        imageView = findViewById(R.id.imageView)
        reportView = findViewById(R.id.reportView)
        viewGroup = findViewById(R.id.viewGroup)
        progress = findViewById(R.id.progress)

        findViewById<Button>(R.id.btnCamera).setOnClickListener {
            val dir = File(cacheDir, "photos").apply { mkdirs() }
            val file = File.createTempFile("photo_", ".jpg", dir)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            photoUri = uri
            takePicture.launch(uri)
        }
        findViewById<Button>(R.id.btnGallery).setOnClickListener { pickImage.launch("image/*") }

        // Re-run the analysis if the user changes the view option
        viewGroup.setOnCheckedChangeListener { _, _ ->
            lastBitmap?.let { analyze(it) }
        }
    }

    private fun selectedView(): String = when (viewGroup.checkedRadioButtonId) {
        R.id.radioSide -> "side"
        R.id.radioFront -> "front"
        else -> "auto"
    }

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun loadAndAnalyze(uri: Uri) {
        setBusy(true)
        reportView.text = "Loading photo..."
        worker.execute {
            try {
                val bmp = decode(uri)
                lastBitmap = bmp
                runOnUiThread { analyze(bmp) }
            } catch (e: Exception) {
                runOnUiThread {
                    setBusy(false)
                    reportView.text = "Could not open the photo: ${e.message}"
                }
            }
        }
    }

    private fun decode(uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(contentResolver, uri)
        val bmp = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val scale = 1600f / max(w, h)
            if (scale < 1f) decoder.setTargetSize((w * scale).roundToInt(), (h * scale).roundToInt())
        }
        return bmp.copy(Bitmap.Config.ARGB_8888, true)
    }

    private fun analyze(bmp: Bitmap) {
        setBusy(true)
        reportView.text = "Analysing posture..."
        val viewOpt = selectedView()
        worker.execute {
            try {
                val lm = landmarker ?: createLandmarker().also { landmarker = it }
                val result = lm.detect(BitmapImageBuilder(bmp).build())
                val people = result.landmarks()
                if (people.isEmpty()) {
                    runOnUiThread {
                        setBusy(false)
                        imageView.setImageBitmap(bmp)
                        reportView.text = "No person detected. Use a clear photo with the whole body (or at least the upper body) visible."
                    }
                    return@execute
                }
                val pts = HashMap<String, Pt>()
                people[0].forEachIndexed { i, p ->
                    pts[ErgoAnalyzer.LANDMARK_NAMES[i]] =
                        Pt(p.x() * bmp.width, p.y() * bmp.height, p.visibility().orElse(0f))
                }
                val analysis = ErgoAnalyzer.analyze(pts, viewOpt)
                val annotated = Annotator.draw(bmp, pts, analysis)
                runOnUiThread {
                    setBusy(false)
                    imageView.setImageBitmap(annotated)
                    reportView.text = analysis.report()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setBusy(false)
                    reportView.text = "Analysis failed: ${e.message}"
                    Toast.makeText(this, "Analysis failed", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun createLandmarker(): PoseLandmarker {
        val base = BaseOptions.builder().setModelAssetPath("pose_landmarker_full.task").build()
        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.IMAGE)
            .setNumPoses(1)
            .build()
        return PoseLandmarker.createFromOptions(this, options)
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdown()
        landmarker?.close()
    }
}
