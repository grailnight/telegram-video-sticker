package io.github.grailnight.videosticker

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Movie
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.VideoView
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {

    private lateinit var scroll: ScrollView
    private lateinit var pickButton: Button
    private lateinit var videoInfo: TextView
    private lateinit var editPanel: View
    private lateinit var cropView: CropView
    private lateinit var playButton: Button
    private lateinit var squareCheck: CheckBox
    private lateinit var timeline: TimelineView
    private lateinit var startInput: EditText
    private lateinit var durationInput: EditText
    private lateinit var speedSpinner: Spinner
    private lateinit var boomerangCheck: CheckBox
    private lateinit var formatSpinner: Spinner
    private lateinit var shapeSpinner: Spinner
    private lateinit var captionInput: EditText
    private lateinit var captionSpinner: Spinner
    private lateinit var summary: TextView
    private lateinit var convertButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var resultPanel: View
    private lateinit var preview: VideoView
    private lateinit var saveButton: Button
    private lateinit var shareButton: Button
    private lateinit var importButton: Button

    private val worker = Executors.newSingleThreadExecutor()
    private val frameLoader = Executors.newSingleThreadExecutor()
    private val thumbLoader = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cancelFlag = AtomicBoolean(false)

    // Source
    private var sourceUri: Uri? = null
    private var isGif = false
    private var gifBytes: ByteArray? = null
    private var sourceDurationUs = 0L
    private var frameWidth = 0
    private var frameHeight = 0
    private var sourceGeneration = 0

    // Only touched on the frameLoader thread.
    private var frameRetriever: MediaMetadataRetriever? = null
    private var frameMovie: Movie? = null

    // Fragment
    private var startUs = 0L
    private var windowUs = 0L
    private var speed = 1f
    private var boomerang = false
    private var syncingFields = false
    private var squareBeforeEmoji = false

    // Preview playback
    private var playing = false
    private var player: MediaPlayer? = null
    private var playerSurface: Surface? = null
    private var playerReady = false
    private var gifPlayStartMs = 0L
    private var gifFramePending = false

    private var frameRequest = 0
    private var resultFile: File? = null
    private var converting = false

    private val reloadFrame = Runnable { loadFrame() }

    private val playerTick = object : Runnable {
        override fun run() {
            val mp = player ?: return
            if (!playing) return
            if (playerReady) {
                val position = runCatching { mp.currentPosition }.getOrDefault(0)
                val endMs = (startUs + windowUs) / 1000
                if (position >= endMs || position < startUs / 1000 - 500 || !mp.isPlaying) seekPlayerToStart()
            }
            mainHandler.postDelayed(this, 40)
        }
    }

    private val gifTick = object : Runnable {
        override fun run() {
            if (!playing || !isGif) return
            if (!gifFramePending) {
                gifFramePending = true
                val elapsedUs = ((SystemClock.uptimeMillis() - gifPlayStartMs) * 1000 * speed).toLong()
                val timeUs = startUs + elapsedUs % max(1L, windowUs)
                val generation = sourceGeneration
                frameLoader.execute {
                    val frame = runCatching { renderGifFrame(frameMovie, timeUs, PREVIEW_SIDE) }.getOrNull()
                    mainHandler.post {
                        gifFramePending = false
                        if (playing && generation == sourceGeneration && frame != null) cropView.setFrame(frame)
                    }
                }
            }
            mainHandler.postDelayed(this, 50)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        scroll = findViewById(R.id.scroll)
        pickButton = findViewById(R.id.pickButton)
        videoInfo = findViewById(R.id.videoInfo)
        editPanel = findViewById(R.id.editPanel)
        cropView = findViewById(R.id.cropView)
        playButton = findViewById(R.id.playButton)
        squareCheck = findViewById(R.id.squareCheck)
        timeline = findViewById(R.id.timeline)
        startInput = findViewById(R.id.startInput)
        durationInput = findViewById(R.id.durationInput)
        speedSpinner = findViewById(R.id.speedSpinner)
        boomerangCheck = findViewById(R.id.boomerangCheck)
        formatSpinner = findViewById(R.id.formatSpinner)
        shapeSpinner = findViewById(R.id.shapeSpinner)
        captionInput = findViewById(R.id.captionInput)
        captionSpinner = findViewById(R.id.captionSpinner)
        summary = findViewById(R.id.summary)
        convertButton = findViewById(R.id.convertButton)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)
        resultPanel = findViewById(R.id.resultPanel)
        preview = findViewById(R.id.preview)
        saveButton = findViewById(R.id.saveButton)
        shareButton = findViewById(R.id.shareButton)
        importButton = findViewById(R.id.importButton)

        pickButton.setOnClickListener { pickSource() }
        convertButton.setOnClickListener { if (converting) cancelFlag.set(true) else startConversion() }
        saveButton.setOnClickListener { saveResult() }
        shareButton.setOnClickListener { shareResult() }
        importButton.setOnClickListener { importToTelegram() }
        preview.setOnPreparedListener { it.isLooping = true; it.setVolume(0f, 0f) }
        preview.setOnErrorListener { _, _, _ -> true }

        // Area
        cropView.onCropChanged = { updateSummary() }
        playButton.setOnClickListener { if (playing) stopPlayback() else startPlayback() }
        squareCheck.setOnCheckedChangeListener { _, checked -> cropView.squareLocked = checked }
        findViewById<Button>(R.id.resetCropButton).setOnClickListener {
            if (squareCheck.isEnabled) squareCheck.isChecked = false
            cropView.resetCrop()
        }

        // Fragment
        timeline.onSelectionChanged = { start, window -> setSelection(start, window) }
        val fieldWatcher = watcher {
            if (!syncingFields && sourceUri != null) {
                val start = (parseSeconds(startInput.text.toString()) * 1_000_000).toLong()
                val window = (parseSeconds(durationInput.text.toString()) * 1_000_000).toLong()
                setSelection(start, if (window > 0) window else windowUs, fromFields = true)
            }
        }
        startInput.addTextChangedListener(fieldWatcher)
        durationInput.addTextChangedListener(fieldWatcher)

        setUpSpinner(speedSpinner, SPEEDS.map { speedLabel(it) }, SPEEDS.indexOf(1f)) { index ->
            speed = SPEEDS[index]
            setSelection(startUs, windowUs)
            applyPlayerSpeed()
        }
        boomerangCheck.setOnCheckedChangeListener { _, checked ->
            boomerang = checked
            setSelection(startUs, windowUs)
        }

        // Look
        setUpSpinner(
            formatSpinner, listOf(getString(R.string.format_sticker), getString(R.string.format_emoji)), 0
        ) { index ->
            val emoji = index == 1
            // Emoji are always square; going back to a sticker restores the previous choice.
            if (emoji && squareCheck.isEnabled) {
                squareBeforeEmoji = squareCheck.isChecked
                squareCheck.isChecked = true
            } else if (!emoji && !squareCheck.isEnabled && !converting) {
                squareCheck.isChecked = squareBeforeEmoji
            }
            squareCheck.isEnabled = !emoji && !converting
            updateSummary()
        }
        setUpSpinner(
            shapeSpinner,
            listOf(getString(R.string.shape_rect), getString(R.string.shape_rounded), getString(R.string.shape_circle)),
            0
        ) { index -> cropView.shape = Shape.values()[index] }
        captionInput.addTextChangedListener(watcher { cropView.captionText = captionInput.text.toString() })
        setUpSpinner(
            captionSpinner,
            listOf(getString(R.string.caption_top), getString(R.string.caption_center), getString(R.string.caption_bottom)),
            2
        ) { index -> cropView.captionPosition = CaptionPosition.values()[index] }

        handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    override fun onStop() {
        stopPlayback()
        super.onStop()
    }

    override fun onDestroy() {
        cancelFlag.set(true)
        stopPlayback()
        mainHandler.removeCallbacksAndMessages(null)
        worker.shutdown()
        thumbLoader.shutdownNow()
        frameLoader.execute { releaseFrameSource() }
        frameLoader.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ source

    private fun handleIncoming(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        @Suppress("DEPRECATION")
        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: return
        if (!converting) setSource(uri)
    }

    private fun pickSource() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "image/gif"))
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

    private fun looksLikeGif(uri: Uri): Boolean = runCatching {
        contentResolver.openInputStream(uri)!!.use { stream ->
            val head = ByteArray(4)
            stream.read(head) == 4 && String(head, Charsets.US_ASCII) == "GIF8"
        }
    }.getOrDefault(false)

    private fun setSource(uri: Uri) {
        stopPlayback()
        var width = 0
        var height = 0
        var durationMs = 0L
        var bytes: ByteArray? = null
        val gif = looksLikeGif(uri)

        if (gif) {
            val movie = runCatching {
                val data = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                bytes = data
                Movie.decodeByteArray(data, 0, data.size)
            }.getOrNull()
            if (movie == null) {
                status.text = getString(R.string.error_open)
                return
            }
            width = movie.width()
            height = movie.height()
            durationMs = if (movie.duration() > 0) movie.duration().toLong() else 1000L
        } else {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(this, uri)
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                val rotation =
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (rotation == 90 || rotation == 270) {
                    val w = width; width = height; height = w
                }
            } catch (e: Exception) {
                status.text = getString(R.string.error_open)
                return
            } finally {
                runCatching { retriever.release() }
            }
        }
        if (width <= 0 || height <= 0) {
            status.text = getString(R.string.error_no_track)
            return
        }

        sourceGeneration++
        sourceUri = uri
        isGif = gif
        gifBytes = bytes
        frameWidth = width
        frameHeight = height
        sourceDurationUs = max(1L, durationMs * 1000)
        videoInfo.text = getString(R.string.video_info, displayName(uri), width, height, seconds(sourceDurationUs))
        convertButton.isEnabled = true
        status.text = ""
        showResult(null)

        // Open the still-frame source on its own thread.
        val generation = sourceGeneration
        frameLoader.execute {
            releaseFrameSource()
            if (generation != sourceGeneration) return@execute
            runCatching {
                val data = bytes
                if (data != null) {
                    frameMovie = Movie.decodeByteArray(data, 0, data.size)
                } else {
                    frameRetriever = MediaMetadataRetriever().apply { setDataSource(applicationContext, uri) }
                }
            }
        }

        cropView.setFrameSize(width, height)
        editPanel.visibility = View.VISIBLE
        timeline.setClip(sourceDurationUs, THUMBNAILS)
        setSelection(0, StickerMaker.MAX_DURATION_US)
        loadThumbnails()
    }

    private fun releaseFrameSource() {
        runCatching { frameRetriever?.release() }
        frameRetriever = null
        frameMovie = null
    }

    private fun displayName(uri: Uri): String {
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) return c.getString(0)
            }
        }
        return uri.lastPathSegment ?: "video"
    }

    // ------------------------------------------------------------------ fragment

    private fun minWindowUs(): Long = min(StickerMaker.MIN_WINDOW_US, sourceDurationUs)

    /** Clamps and applies the fragment, then refreshes everything that shows it. */
    private fun setSelection(start: Long, window: Long, fromFields: Boolean = false) {
        if (sourceUri == null) return
        val limit = StickerMaker.maxWindowUs(speed, boomerang)
        val newStart = start.coerceIn(0, max(0L, sourceDurationUs - minWindowUs()))
        val newWindow = window.coerceIn(minWindowUs(), max(minWindowUs(), min(limit, sourceDurationUs - newStart)))
        val startMoved = newStart != startUs
        startUs = newStart
        windowUs = newWindow

        timeline.setLimits(minWindowUs(), limit)
        timeline.setSelection(startUs, windowUs)
        if (!fromFields) {
            syncingFields = true
            startInput.setText(seconds(startUs))
            durationInput.setText(seconds(windowUs))
            syncingFields = false
        }
        updateSummary()

        if (playing) {
            if (startMoved && !isGif) seekPlayerToStart()
        } else {
            mainHandler.removeCallbacks(reloadFrame)
            mainHandler.postDelayed(reloadFrame, 80)
        }
    }

    private fun stickerDurationUs(): Long {
        val forward = (windowUs / speed).toLong()
        return min(StickerMaker.MAX_DURATION_US, if (boomerang) forward * 2 else forward)
    }

    private fun updateSummary() {
        if (sourceUri == null) return
        val size = if (formatSpinner.selectedItemPosition == 1) {
            StickerMaker.EMOJI_SIDE to StickerMaker.EMOJI_SIDE
        } else {
            val crop = StickerMaker.sanitizeCrop(cropView.crop)
            StickerMaker.outputSize(crop.width() * frameWidth, crop.height() * frameHeight)
        }
        summary.text = getString(R.string.summary, size.first, size.second, seconds(stickerDurationUs()))
    }

    // ------------------------------------------------------------------ still frame and thumbnails

    /** Shows the frame at the start of the fragment in the crop view. */
    private fun loadFrame() {
        if (sourceUri == null || playing) return
        val request = ++frameRequest
        val timeUs = startUs.coerceAtMost(max(0L, sourceDurationUs - 50_000))
        val gif = isGif
        frameLoader.execute {
            if (request != frameRequest) return@execute
            val frame = runCatching {
                if (gif) renderGifFrame(frameMovie, timeUs, PREVIEW_SIDE)
                else frameRetriever?.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?.let { downscale(it, PREVIEW_SIDE) }
            }.getOrNull()
            mainHandler.post {
                if (!isDestroyed && request == frameRequest && frame != null && !playing) cropView.setFrame(frame)
            }
        }
    }

    private fun loadThumbnails() {
        val uri = sourceUri ?: return
        val generation = sourceGeneration
        val gif = isGif
        val bytes = gifBytes
        val duration = sourceDurationUs
        thumbLoader.execute {
            var retriever: MediaMetadataRetriever? = null
            try {
                val movie = if (gif && bytes != null) Movie.decodeByteArray(bytes, 0, bytes.size) else null
                if (!gif) retriever = MediaMetadataRetriever().apply { setDataSource(applicationContext, uri) }
                for (index in 0 until THUMBNAILS) {
                    if (generation != sourceGeneration || Thread.currentThread().isInterrupted) return@execute
                    val timeUs = duration * (2 * index + 1) / (2 * THUMBNAILS)
                    val thumb = if (gif) renderGifFrame(movie, timeUs, THUMBNAIL_SIDE)
                    else retriever?.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?.let { downscale(it, THUMBNAIL_SIDE) }
                    if (thumb != null) {
                        mainHandler.post { if (generation == sourceGeneration) timeline.setThumbnail(index, thumb) }
                    }
                }
            } catch (e: Exception) {
                // Thumbnails are decoration: the timeline works without them.
            } finally {
                runCatching { retriever?.release() }
            }
        }
    }

    private fun renderGifFrame(movie: Movie?, timeUs: Long, maxSide: Int): Bitmap? {
        if (movie == null) return null
        val scale = min(1f, maxSide.toFloat() / max(movie.width(), movie.height()))
        val bitmap = Bitmap.createBitmap(
            max(1, (movie.width() * scale).toInt()), max(1, (movie.height() * scale).toInt()), Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        canvas.scale(scale, scale)
        movie.setTime(((timeUs / 1000) % max(1, movie.duration())).toInt())
        movie.draw(canvas, 0f, 0f)
        return bitmap
    }

    private fun downscale(frame: Bitmap, maxSide: Int): Bitmap {
        val longSide = max(frame.width, frame.height)
        if (longSide <= maxSide) return frame
        val scale = maxSide.toFloat() / longSide
        return Bitmap.createScaledBitmap(
            frame, max(1, (frame.width * scale).toInt()), max(1, (frame.height * scale).toInt()), true
        )
    }

    // ------------------------------------------------------------------ fragment playback

    private fun startPlayback() {
        val uri = sourceUri ?: return
        if (converting) return
        stopPlayback()
        if (isGif) {
            playing = true
            gifPlayStartMs = SystemClock.uptimeMillis()
            gifFramePending = false
            mainHandler.post(gifTick)
        } else {
            val texture = cropView.videoView.surfaceTexture ?: return
            val surface = Surface(texture)
            val mp = MediaPlayer()
            try {
                mp.setDataSource(this, uri)
                mp.setSurface(surface)
                mp.setVolume(0f, 0f)
                mp.setOnPreparedListener {
                    if (player !== it) return@setOnPreparedListener
                    playerReady = true
                    applyPlayerSpeed()
                    seekPlayerToStart()
                    cropView.videoView.alpha = 1f
                }
                mp.setOnErrorListener { _, _, _ -> stopPlayback(); true }
                mp.prepareAsync()
            } catch (e: Exception) {
                runCatching { mp.release() }
                surface.release()
                return
            }
            player = mp
            playerSurface = surface
            playerReady = false
            playing = true
            mainHandler.post(playerTick)
        }
        playButton.text = getString(R.string.stop)
    }

    private fun seekPlayerToStart() {
        val mp = player ?: return
        if (!playerReady) return
        runCatching {
            mp.seekTo(startUs / 1000, MediaPlayer.SEEK_CLOSEST)
            if (!mp.isPlaying) mp.start()
        }
    }

    private fun applyPlayerSpeed() {
        val mp = player ?: return
        if (!playerReady) return
        // Setting a non-zero speed also starts playback.
        runCatching { mp.playbackParams = mp.playbackParams.setSpeed(speed) }
    }

    private fun stopPlayback() {
        if (!playing && player == null) return
        playing = false
        mainHandler.removeCallbacks(playerTick)
        mainHandler.removeCallbacks(gifTick)
        player?.let { mp -> runCatching { mp.release() } }
        player = null
        playerReady = false
        playerSurface?.release()
        playerSurface = null
        cropView.videoView.alpha = 0f
        playButton.text = getString(R.string.play)
        loadFrame()
    }

    // ------------------------------------------------------------------ conversion

    private fun startConversion() {
        val uri = sourceUri ?: return
        stopPlayback()
        if (startUs >= sourceDurationUs) {
            status.text = getString(R.string.error_start)
            return
        }
        setSelection(startUs, windowUs)

        val emoji = formatSpinner.selectedItemPosition == 1
        val params = StickerMaker.Params(
            source = uri,
            isGif = isGif,
            startUs = startUs,
            windowUs = windowUs,
            speed = speed,
            boomerang = boomerang,
            crop = cropView.crop,
            emoji = emoji,
            shape = cropView.shape,
            caption = captionInput.text.toString(),
            captionPosition = cropView.captionPosition
        )
        val folder = File(cacheDir, "stickers").apply { mkdirs() }
        folder.listFiles()?.forEach { it.delete() }
        val output = File(folder, "${if (emoji) "emoji" else "sticker"}_${System.currentTimeMillis()}.webm")

        showResult(null)
        setConverting(true)
        cancelFlag.set(false)
        status.text = getString(R.string.status_preparing)
        scrollToBottom()

        val listener = object : StickerMaker.Listener {
            override fun onProgress(attempt: Int, fraction: Float) {
                mainHandler.post {
                    if (!converting) return@post
                    progress.progress = (fraction * 1000).toInt()
                    status.text = if (attempt == 0) getString(R.string.status_preparing)
                    else getString(R.string.status_converting, attempt)
                }
            }

            override fun isCancelled() = cancelFlag.get()
        }

        worker.execute {
            val outcome = runCatching { StickerMaker(applicationContext).convert(params, output, listener) }
            mainHandler.post {
                if (isDestroyed) return@post
                setConverting(false)
                outcome.onSuccess { result ->
                    val kb = String.format(Locale.US, "%.1f", result.sizeBytes / 1024.0)
                    var text = if (result.fitsLimit) {
                        getString(R.string.status_done, result.width, result.height, seconds(result.durationUs), kb)
                    } else {
                        getString(R.string.status_too_big, kb)
                    }
                    if (result.hasAlpha) text += "\n" + getString(R.string.status_alpha_note)
                    status.text = text
                    importButton.visibility = if (emoji) View.GONE else View.VISIBLE
                    showResult(result.file)
                }.onFailure { error ->
                    output.delete()
                    status.text = when (error) {
                        is CancelledException -> getString(R.string.status_cancelled)
                        is StickerException -> getString(error.messageRes)
                        is OutOfMemoryError -> getString(R.string.error_memory)
                        else -> getString(R.string.error_generic, error.message ?: error.javaClass.simpleName)
                    }
                }
                scrollToBottom()
            }
        }
    }

    /** Progress, status and the result sit below the fold on most screens. */
    private fun scrollToBottom() {
        scroll.post { scroll.smoothScrollTo(0, scroll.getChildAt(0).height) }
    }

    private fun setConverting(active: Boolean) {
        converting = active
        convertButton.text = getString(if (active) R.string.cancel else R.string.convert)
        pickButton.isEnabled = !active
        for (view in listOf(
            cropView, timeline, playButton, startInput, durationInput, speedSpinner, boomerangCheck,
            formatSpinner, shapeSpinner, captionInput, captionSpinner
        )) view.isEnabled = !active
        squareCheck.isEnabled = !active && formatSpinner.selectedItemPosition != 1
        progress.visibility = if (active) View.VISIBLE else View.GONE
        progress.progress = 0
        if (active) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ------------------------------------------------------------------ result

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

    private fun resultUri(file: File): Uri = FileProvider.getUriForFile(this, "$packageName.files", file)

    private fun shareResult() {
        val file = resultFile ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/webm"
            putExtra(Intent.EXTRA_STREAM, resultUri(file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share)))
    }

    /** Telegram's sticker import: creates a new pack that contains this sticker. */
    private fun importToTelegram() {
        val file = resultFile ?: return
        val uri = resultUri(file)
        val intent = Intent("org.telegram.messenger.CREATE_STICKER_PACK").apply {
            putExtra(Intent.EXTRA_STREAM, arrayListOf(uri))
            putExtra("IMPORTER", packageName)
            putExtra("STICKER_EMOJIS", arrayListOf("🙂"))
            type = "image/*"
            clipData = ClipData.newRawUri("sticker", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            status.text = getString(R.string.error_no_telegram)
            scrollToBottom()
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun setUpSpinner(spinner: Spinner, labels: List<String>, selected: Int, onSelected: (Int) -> Unit) {
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinner.setSelection(selected, false)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) =
                onSelected(position)

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun watcher(afterChange: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) = afterChange()
    }

    private fun speedLabel(value: Float): String =
        String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.') + "×"

    private fun parseSeconds(text: String): Double =
        text.trim().replace(',', '.').toDoubleOrNull() ?: 0.0

    private fun seconds(us: Long): String =
        String.format(Locale.US, "%.2f", us / 1_000_000.0).trimEnd('0').trimEnd('.')

    private companion object {
        const val REQUEST_PICK = 1
        const val REQUEST_SAVE = 2
        const val PREVIEW_SIDE = 1024
        const val THUMBNAIL_SIDE = 160
        const val THUMBNAILS = 10
        val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f)
    }
}
