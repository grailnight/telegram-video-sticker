package io.github.grailnight.videosticker

import android.app.Activity
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.VideoView
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {

    private lateinit var pickButton: Button
    private lateinit var videoInfo: TextView
    private lateinit var startInput: EditText
    private lateinit var durationInput: EditText
    private lateinit var squareCheck: CheckBox
    private lateinit var convertButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var resultPanel: View
    private lateinit var preview: VideoView
    private lateinit var saveButton: Button
    private lateinit var shareButton: Button

    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cancelFlag = AtomicBoolean(false)

    private var sourceUri: Uri? = null
    private var sourceDurationUs = 0L
    private var resultFile: File? = null
    private var converting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        pickButton = findViewById(R.id.pickButton)
        videoInfo = findViewById(R.id.videoInfo)
        startInput = findViewById(R.id.startInput)
        durationInput = findViewById(R.id.durationInput)
        squareCheck = findViewById(R.id.squareCheck)
        convertButton = findViewById(R.id.convertButton)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)
        resultPanel = findViewById(R.id.resultPanel)
        preview = findViewById(R.id.preview)
        saveButton = findViewById(R.id.saveButton)
        shareButton = findViewById(R.id.shareButton)

        pickButton.setOnClickListener { pickVideo() }
        convertButton.setOnClickListener { if (converting) cancelFlag.set(true) else startConversion() }
        saveButton.setOnClickListener { saveResult() }
        shareButton.setOnClickListener { shareResult() }
        preview.setOnPreparedListener { it.isLooping = true; it.setVolume(0f, 0f) }
        preview.setOnErrorListener { _, _, _ -> true }

        handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    override fun onDestroy() {
        cancelFlag.set(true)
        worker.shutdown()
        super.onDestroy()
    }

    private fun handleIncoming(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        @Suppress("DEPRECATION")
        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: return
        if (!converting) setSource(uri)
    }

    private fun pickVideo() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "video/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_PICK)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQUEST_PICK -> setSource(uri)
            REQUEST_SAVE -> copyResultTo(uri)
        }
    }

    private fun setSource(uri: Uri) {
        var width = 0
        var height = 0
        var durationMs = 0L
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(this, uri)
            width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) {
                val w = width; width = height; height = w
            }
        } catch (e: Exception) {
            status.text = getString(R.string.error_open)
            return
        } finally {
            runCatching { retriever.release() }
        }
        if (width <= 0 || height <= 0) {
            status.text = getString(R.string.error_no_track)
            return
        }

        sourceUri = uri
        sourceDurationUs = durationMs * 1000
        videoInfo.text = getString(R.string.video_info, displayName(uri), width, height, seconds(sourceDurationUs))
        convertButton.isEnabled = true
        status.text = ""
        showResult(null)
    }

    private fun displayName(uri: Uri): String {
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) return c.getString(0)
            }
        }
        return uri.lastPathSegment ?: "video"
    }

    private fun startConversion() {
        val uri = sourceUri ?: return
        val startUs = (parseSeconds(startInput.text.toString()) * 1_000_000).toLong().coerceAtLeast(0)
        var durationUs = (parseSeconds(durationInput.text.toString()) * 1_000_000).toLong()
        if (durationUs <= 0) durationUs = StickerTranscoder.MAX_DURATION_US
        durationUs = durationUs.coerceIn(StickerTranscoder.MIN_DURATION_US, StickerTranscoder.MAX_DURATION_US)
        if (sourceDurationUs > 0 && startUs >= sourceDurationUs) {
            status.text = getString(R.string.error_start)
            return
        }
        durationInput.setText(seconds(durationUs))

        val options = StickerTranscoder.Options(startUs, durationUs, squareCheck.isChecked)
        val output = File(File(cacheDir, "stickers").apply { mkdirs() }, "sticker_${System.currentTimeMillis()}.webm")
        File(cacheDir, "stickers").listFiles()?.forEach { it.delete() }

        showResult(null)
        setConverting(true)
        cancelFlag.set(false)
        status.text = getString(R.string.status_converting, 1)

        val listener = object : StickerTranscoder.Listener {
            override fun onProgress(attempt: Int, fraction: Float) {
                mainHandler.post {
                    if (!converting) return@post
                    progress.progress = (fraction * 1000).toInt()
                    status.text = getString(R.string.status_converting, attempt)
                }
            }

            override fun isCancelled() = cancelFlag.get()
        }

        worker.execute {
            val outcome = runCatching { StickerTranscoder(applicationContext).convert(uri, output, options, listener) }
            mainHandler.post {
                if (isDestroyed) return@post
                setConverting(false)
                outcome.onSuccess { result ->
                    val kb = String.format(Locale.US, "%.1f", result.sizeBytes / 1024.0)
                    status.text = if (result.fitsLimit) {
                        getString(R.string.status_done, result.width, result.height, seconds(result.durationUs), kb)
                    } else {
                        getString(R.string.status_too_big, kb)
                    }
                    showResult(result.file)
                }.onFailure { error ->
                    status.text = when (error) {
                        is CancelledException -> getString(R.string.status_cancelled)
                        is StickerException -> getString(error.messageRes)
                        else -> getString(R.string.error_generic, error.message ?: error.javaClass.simpleName)
                    }
                }
            }
        }
    }

    private fun setConverting(active: Boolean) {
        converting = active
        convertButton.text = getString(if (active) R.string.cancel else R.string.convert)
        pickButton.isEnabled = !active
        progress.visibility = if (active) View.VISIBLE else View.GONE
        progress.progress = 0
        if (active) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun showResult(file: File?) {
        resultFile = file
        if (file == null) {
            preview.stopPlayback()
            resultPanel.visibility = View.GONE
        } else {
            resultPanel.visibility = View.VISIBLE
            preview.setVideoPath(file.absolutePath)
            preview.start()
        }
    }

    private fun saveResult() {
        val file = resultFile ?: return
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            type = "video/webm"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_TITLE, file.name)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_SAVE)
    }

    private fun copyResultTo(target: Uri) {
        val file = resultFile ?: return
        try {
            contentResolver.openOutputStream(target)!!.use { out ->
                file.inputStream().use { it.copyTo(out) }
            }
            status.text = getString(R.string.status_saved)
        } catch (e: Exception) {
            status.text = getString(R.string.error_generic, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun shareResult() {
        val file = resultFile ?: return
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/webm"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share)))
    }

    private fun parseSeconds(text: String): Double =
        text.trim().replace(',', '.').toDoubleOrNull() ?: 0.0

    private fun seconds(us: Long): String =
        String.format(Locale.US, "%.2f", us / 1_000_000.0).trimEnd('0').trimEnd('.')

    private companion object {
        const val REQUEST_PICK = 1
        const val REQUEST_SAVE = 2
    }
}
