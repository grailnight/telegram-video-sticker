package io.github.grailnight.videosticker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Movie
import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class StickerException(val messageRes: Int) : Exception()
class CancelledException : Exception()

/**
 * Turns a clip of a video or GIF into a Telegram video sticker or emoji:
 * WebM/VP9 (optionally with alpha), <= 3 s, <= 30 fps, no audio, <= 256 KB.
 *
 * All frames are rendered first (crop, caption, shape mask) and kept in native memory, then
 * encoded by libvpx; a too-large result is re-encoded at a lower bitrate without re-decoding.
 */
class StickerMaker(private val context: Context) {

    data class Params(
        val source: Uri,
        val isGif: Boolean,
        /** Start of the fragment in the source, and its length in source time. */
        val startUs: Long,
        val windowUs: Long,
        val speed: Float,
        /** Play forward, then backward. */
        val boomerang: Boolean,
        /** Area to keep, 0..1 coordinates of the displayed frame (top-left origin). */
        val crop: RectF,
        /** 100x100 custom emoji instead of a 512 px sticker. */
        val emoji: Boolean,
        val shape: Shape,
        val caption: String,
        val captionPosition: CaptionPosition
    )

    data class Result(
        val file: File,
        val width: Int,
        val height: Int,
        val durationUs: Long,
        val frames: Int,
        val fps: Int,
        val sizeBytes: Long,
        val fitsLimit: Boolean,
        val hasAlpha: Boolean
    )

    interface Listener {
        /** [attempt] is 0 while frames are being prepared, then the number of the encode attempt. */
        fun onProgress(attempt: Int, fraction: Float)
        fun isCancelled(): Boolean
    }

    fun convert(params: Params, output: File, listener: Listener): Result {
        val crop = sanitizeCrop(params.crop)
        val speed = params.speed.coerceIn(0.1f, 16f)
        val source = if (params.isGif) openGif(params.source) else null
        val video = if (params.isGif) null else probeVideo(params.source, params.startUs, params.windowUs)

        val displayWidth = source?.width() ?: video!!.displayWidth
        val displayHeight = source?.height() ?: video!!.displayHeight
        val size = if (params.emoji) EMOJI_SIDE to EMOJI_SIDE
        else outputSize(crop.width() * displayWidth, crop.height() * displayHeight)

        // Output frame rate: the source rate after the speed change, capped at 30.
        val sourceFps = if (params.isGif) GIF_SAMPLE_FPS else video!!.fps
        val fps = (sourceFps * speed).roundToInt().coerceIn(1, MAX_FPS)
        val forwardUs = (params.windowUs / speed).toLong().coerceAtLeast(1)
        val frameLimit = if (params.boomerang) (MAX_DURATION_S * fps + 2) / 2 else MAX_DURATION_S * fps
        val wanted = (forwardUs * fps / 1_000_000.0).roundToInt().coerceIn(1, frameLimit)

        val caption = Overlays.captionBitmap(size.first, size.second, params.caption, params.captionPosition)
        val mask = Overlays.maskBitmap(size.first, size.second, params.shape)

        NativeEncoder(size.first, size.second).use { encoder ->
            val renderer = FrameRenderer(size.first, size.second, crop, caption, mask)
            try {
                val progress = { done: Int -> listener.onProgress(0, done.toFloat() / wanted) }
                if (source != null) {
                    captureGif(source, renderer, encoder, params.startUs, speed, fps, wanted, listener, progress)
                } else {
                    captureVideo(params.source, renderer, encoder, max(0L, params.startUs), speed, fps, wanted, listener, progress)
                }
            } finally {
                runCatching { renderer.release() }
                caption?.recycle()
                mask?.recycle()
            }

            val count = encoder.frameCount
            if (count == 0) throw StickerException(R.string.error_no_frames)
            val order = playbackOrder(count, params.boomerang)
            val durationS = order.size.toDouble() / fps

            val maxBitrate = if (params.emoji) MAX_BITRATE_EMOJI else MAX_BITRATE
            var bitrate = (TARGET_BYTES * 8 / durationS).toInt().coerceIn(MIN_BITRATE, maxBitrate)
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
            output.parentFile?.mkdirs()

            var bytes = 0L
            for (attempt in 1..MAX_ATTEMPTS) {
                output.delete()
                bytes = encoder.encode(
                    output.absolutePath, order, fps, bitrate, CPU_USED, threads
                ) { done, total ->
                    listener.onProgress(attempt, done.toFloat() / total)
                    !listener.isCancelled()
                }
                if (bytes == NativeEncoder.ERR_CANCELLED.toLong()) throw CancelledException()
                if (bytes == NativeEncoder.ERR_MEMORY.toLong()) throw OutOfMemoryError("encoder")
                if (bytes <= 0) {
                    Log.e(TAG, "encode failed: $bytes")
                    throw StickerException(R.string.error_encode)
                }
                if (bytes <= MAX_BYTES || bitrate <= MIN_BITRATE) break
                // Too big: scale the bitrate by the overshoot, with some margin.
                bitrate = max(MIN_BITRATE, (bitrate * (MAX_BYTES.toDouble() / bytes) * 0.9).toInt())
            }

            return Result(
                file = output,
                width = size.first,
                height = size.second,
                durationUs = (durationS * 1_000_000).toLong(),
                frames = order.size,
                fps = fps,
                sizeBytes = bytes,
                fitsLimit = bytes <= MAX_BYTES,
                hasAlpha = encoder.hasAlpha
            )
        }
    }

    // ------------------------------------------------------------------ GIF

    private fun openGif(uri: Uri): Movie {
        val movie = runCatching {
            context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                .let { Movie.decodeByteArray(it, 0, it.size) }
        }.getOrNull()
        if (movie == null || movie.width() <= 0 || movie.height() <= 0) throw StickerException(R.string.error_open)
        return movie
    }

    private fun captureGif(
        movie: Movie, renderer: FrameRenderer, encoder: NativeEncoder, startUs: Long, speed: Float,
        fps: Int, wanted: Int, listener: Listener, progress: (Int) -> Unit
    ) {
        val durationMs = max(1, movie.duration())
        val frame = Bitmap.createBitmap(movie.width(), movie.height(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(frame)
        try {
            for (index in 0 until wanted) {
                if (listener.isCancelled()) throw CancelledException()
                val timeMs = startUs / 1000.0 + index * 1000.0 / fps * speed
                movie.setTime((timeMs.toLong() % durationMs).toInt())
                frame.eraseColor(Color.TRANSPARENT)
                movie.draw(canvas, 0f, 0f)
                encoder.addFrame(renderer.renderBitmapFrame(frame), bottomUp = true)
                progress(index + 1)
            }
        } finally {
            frame.recycle()
        }
    }

    // ------------------------------------------------------------------ video

    private class VideoInfo(val displayWidth: Int, val displayHeight: Int, val fps: Float)

    private fun probeVideo(uri: Uri, startUs: Long, windowUs: Long): VideoInfo {
        val extractor = MediaExtractor()
        try {
            try {
                extractor.setDataSource(context, uri, null)
            } catch (e: Exception) {
                throw StickerException(R.string.error_open)
            }
            val track = videoTrack(extractor)
            val format = extractor.getTrackFormat(track)
            val width = format.getInteger(MediaFormat.KEY_WIDTH)
            val height = format.getInteger(MediaFormat.KEY_HEIGHT)
            val rotated = rotation(format).let { it == 90 || it == 270 }

            // Measure the real frame rate around the fragment by counting samples.
            extractor.selectTrack(track)
            extractor.seekTo(max(0L, startUs), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val from = max(0L, startUs)
            val to = from + min(max(windowUs, 500_000L), 2_000_000L)
            var samples = 0
            var first = -1L
            var last = -1L
            var guard = 0
            while (guard++ < 2000) {
                val time = extractor.sampleTime
                if (time < 0 || time >= to + 1_000_000) break
                if (time in from until to) {
                    samples++
                    if (first < 0 || time < first) first = time
                    if (time > last) last = time
                }
                if (!extractor.advance()) break
            }
            var fps = if (samples >= 3 && last > first) (samples - 1) * 1_000_000f / (last - first) else 0f
            if (fps <= 0f && format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                fps = runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }.getOrDefault(0f)
            }
            if (fps <= 0f) fps = MAX_FPS.toFloat()

            return VideoInfo(
                if (rotated) height else width,
                if (rotated) width else height,
                fps.coerceIn(1f, 240f)
            )
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun videoTrack(extractor: MediaExtractor): Int =
        (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        } ?: throw StickerException(R.string.error_no_track)

    private fun rotation(format: MediaFormat): Int =
        if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0

    private fun captureVideo(
        uri: Uri, renderer: FrameRenderer, encoder: NativeEncoder, startUs: Long, speed: Float,
        fps: Int, wanted: Int, listener: Listener, progress: (Int) -> Unit
    ) {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            try {
                extractor.setDataSource(context, uri, null)
            } catch (e: Exception) {
                throw StickerException(R.string.error_open)
            }
            val track = videoTrack(extractor)
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!

            // Rotation is applied by the codec when it renders to a Surface.
            try {
                decoder = MediaCodec.createDecoderByType(mime)
                decoder.configure(format, renderer.decoderSurface(), null, 0)
            } catch (e: Exception) {
                Log.e(TAG, "decoder unavailable for $mime", e)
                throw StickerException(R.string.error_open)
            }
            decoder.start()
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val intervalUs = 1_000_000.0 / fps
            val info = MediaCodec.BufferInfo()
            var extractorDone = false
            var kept = 0

            while (kept < wanted) {
                if (listener.isCancelled()) throw CancelledException()

                if (!extractorDone) {
                    val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inIndex)!!
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            extractorDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outIndex < 0) continue
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                // Position of this frame on the sticker's timeline.
                val outUs = (info.presentationTimeUs - startUs) / speed
                // Output frame `kept` is due at kept * interval: take the first source frame that reaches it.
                val keep = info.size > 0 && outUs >= (kept - 0.25) * intervalUs
                decoder.releaseOutputBuffer(outIndex, keep)
                if (keep) {
                    encoder.addFrame(renderer.renderVideoFrame(), bottomUp = true)
                    kept++
                    progress(kept)
                }
                if (eos) break
            }
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
        }
    }

    companion object {
        private const val TAG = "StickerMaker"
        const val STICKER_SIDE = 512
        const val EMOJI_SIDE = 100
        const val MAX_FPS = 30
        const val MAX_DURATION_S = 3
        const val MAX_DURATION_US = MAX_DURATION_S * 1_000_000L
        const val MIN_WINDOW_US = 200_000L
        /** Telegram allows 256 KB; stay under it whether that means 256 000 or 262 144 bytes. */
        const val MAX_BYTES = 255_000L

        private const val GIF_SAMPLE_FPS = 30f
        private const val TARGET_BYTES = 225_000L
        private const val MIN_BITRATE = 40_000
        private const val MAX_BITRATE = 2_500_000
        private const val MAX_BITRATE_EMOJI = 600_000
        private const val MAX_ATTEMPTS = 5
        private const val CPU_USED = 3
        private const val TIMEOUT_US = 10_000L

        /** Longest fragment of the source that still makes a sticker of at most 3 seconds. */
        fun maxWindowUs(speed: Float, boomerang: Boolean): Long =
            (MAX_DURATION_US * speed / (if (boomerang) 2 else 1)).toLong()

        /** Frame order for playback; a boomerang appends the frames in reverse, without repeating the ends. */
        fun playbackOrder(count: Int, boomerang: Boolean): IntArray {
            if (!boomerang || count < 3) return IntArray(count) { it }
            return IntArray(2 * count - 2) { if (it < count) it else 2 * count - 2 - it }
        }

        /** Clamps the crop to the frame; a degenerate selection falls back to the whole frame. */
        fun sanitizeCrop(crop: RectF): RectF {
            val left = crop.left.coerceIn(0f, 1f)
            val top = crop.top.coerceIn(0f, 1f)
            val right = crop.right.coerceIn(0f, 1f)
            val bottom = crop.bottom.coerceIn(0f, 1f)
            if (right - left < 0.01f || bottom - top < 0.01f) return RectF(0f, 0f, 1f, 1f)
            return RectF(left, top, right, bottom)
        }

        /** One side is exactly 512, the other is 512 or less (even, for the encoder). */
        fun outputSize(width: Float, height: Float): Pair<Int, Int> {
            fun shortSide(long: Float, short: Float): Int {
                val scaled = (STICKER_SIDE.toDouble() * short / long / 2).roundToInt() * 2
                return min(STICKER_SIDE, max(16, scaled))
            }
            return if (width > height) STICKER_SIDE to shortSide(width, height)
            else shortSide(height, width) to STICKER_SIDE
        }
    }
}
