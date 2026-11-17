package io.github.grailnight.videosticker

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class StickerException(val messageRes: Int) : Exception()
class CancelledException : Exception()

/**
 * Converts a clip of a video into a Telegram video sticker:
 * WebM/VP9, 512 px on the longer side, <= 3 s, <= 30 fps, no audio, <= 256 KB.
 */
class StickerTranscoder(private val context: Context) {

    data class Options(val startUs: Long, val durationUs: Long, val squareCrop: Boolean)

    data class Result(
        val file: File,
        val width: Int,
        val height: Int,
        val durationUs: Long,
        val frames: Int,
        val sizeBytes: Long,
        val fitsLimit: Boolean
    )

    interface Listener {
        fun onProgress(attempt: Int, fraction: Float)
        fun isCancelled(): Boolean
    }

    fun convert(source: Uri, output: File, options: Options, listener: Listener): Result {
        val durationUs = options.durationUs.coerceIn(MIN_DURATION_US, MAX_DURATION_US)
        val budgetBits = TARGET_BYTES * 8.0
        var bitrate = (budgetBits / (durationUs / 1_000_000.0)).toInt().coerceIn(MIN_BITRATE, MAX_BITRATE)

        var best: Result? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            val result = encodeOnce(source, output, options.copy(durationUs = durationUs), bitrate, attempt, listener)
            best = result
            if (result.fitsLimit || bitrate <= MIN_BITRATE) break
            // Too big: scale the bitrate by the overshoot, with some margin.
            val ratio = MAX_BYTES.toDouble() / result.sizeBytes
            bitrate = max(MIN_BITRATE, (bitrate * ratio * 0.88).toInt())
        }
        return best!!
    }

    private fun encodeOnce(
        source: Uri,
        output: File,
        options: Options,
        bitrate: Int,
        attempt: Int,
        listener: Listener
    ): Result {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var inputSurface: EncoderInputSurface? = null
        var outputSurface: DecoderOutputSurface? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var success = false

        try {
            try {
                extractor.setDataSource(context, source, null)
            } catch (e: Exception) {
                throw StickerException(R.string.error_open)
            }
            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: throw StickerException(R.string.error_no_track)
            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val inputMime = inputFormat.getString(MediaFormat.KEY_MIME)!!

            val srcWidth = inputFormat.getInteger(MediaFormat.KEY_WIDTH)
            val srcHeight = inputFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val rotation = if (inputFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                inputFormat.getInteger(MediaFormat.KEY_ROTATION)
            } else 0
            val rotated = rotation == 90 || rotation == 270
            val displayWidth = if (rotated) srcHeight else srcWidth
            val displayHeight = if (rotated) srcWidth else srcHeight
            val size = outputSize(displayWidth, displayHeight, options.squareCrop)

            // Centre-crop for square stickers: stretch the quad past the viewport on the long axis.
            var scaleX = 1f
            var scaleY = 1f
            if (options.squareCrop) {
                val aspect = displayWidth.toFloat() / displayHeight
                if (aspect > 1f) scaleX = aspect else scaleY = 1f / aspect
            }

            // --- encoder ---
            val encoderFormat = MediaFormat.createVideoFormat(MIME_VP9, size.first, size.second).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, MAX_FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10)
            }
            try {
                encoder = MediaCodec.createEncoderByType(MIME_VP9)
                encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (e: Exception) {
                Log.e(TAG, "VP9 encoder unavailable", e)
                throw StickerException(R.string.error_no_vp9)
            }
            inputSurface = EncoderInputSurface(encoder.createInputSurface())
            inputSurface.makeCurrent()
            encoder.start()

            // --- decoder (rotation is applied by the codec when rendering to a Surface) ---
            outputSurface = DecoderOutputSurface(scaleX, scaleY)
            try {
                decoder = MediaCodec.createDecoderByType(inputMime)
                decoder.configure(inputFormat, outputSurface.surface, null, 0)
            } catch (e: Exception) {
                Log.e(TAG, "decoder unavailable for $inputMime", e)
                throw StickerException(R.string.error_open)
            }
            decoder.start()

            output.parentFile?.mkdirs()
            output.delete()
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM)

            val startUs = max(0L, options.startUs)
            // The last frame must start early enough that the clip never exceeds the duration.
            val lastFrameUs = options.durationUs - FRAME_INTERVAL_US
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val info = MediaCodec.BufferInfo()
            var extractorDone = false
            var decoderDone = false
            var encoderDone = false
            var muxerTrack = -1
            var nextFrameUs = 0L
            var frames = 0
            var lastPtsUs = 0L

            while (!encoderDone) {
                if (listener.isCancelled()) throw CancelledException()

                // Feed the decoder.
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

                // Pull decoded frames and render the ones we keep into the encoder.
                if (!decoderDone) {
                    val outIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (outIndex >= 0) {
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        val t = info.presentationTimeUs - startUs
                        val pastEnd = t > lastFrameUs
                        val keep = info.size > 0 && t >= 0 && !pastEnd && t >= nextFrameUs - 1000
                        decoder.releaseOutputBuffer(outIndex, keep)
                        if (keep) {
                            outputSurface.awaitNewImage()
                            outputSurface.draw(size.first, size.second)
                            inputSurface.setPresentationTime(t * 1000)
                            inputSurface.swapBuffers()
                            frames++
                            lastPtsUs = t
                            // Cap the frame rate at 30 fps by dropping frames that come too early.
                            nextFrameUs += FRAME_INTERVAL_US
                            if (nextFrameUs < t) nextFrameUs = t + FRAME_INTERVAL_US / 2
                            listener.onProgress(attempt, (t.toFloat() / options.durationUs).coerceIn(0f, 1f))
                        }
                        if (eos || pastEnd) {
                            decoderDone = true
                            encoder.signalEndOfInputStream()
                        }
                    }
                }

                // Drain the encoder into the WebM muxer.
                while (true) {
                    val encIndex = encoder.dequeueOutputBuffer(info, if (decoderDone) TIMEOUT_US else 0)
                    if (encIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (encIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        muxerTrack = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    } else if (encIndex >= 0) {
                        val data = encoder.getOutputBuffer(encIndex)!!
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (info.size > 0 && !isConfig && muxerStarted) {
                            data.position(info.offset)
                            data.limit(info.offset + info.size)
                            muxer.writeSampleData(muxerTrack, data, info)
                        }
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        encoder.releaseOutputBuffer(encIndex, false)
                        if (eos) {
                            encoderDone = true
                            break
                        }
                    }
                }
            }

            if (frames == 0 || !muxerStarted) throw StickerException(R.string.error_no_frames)
            muxer.stop()
            muxerStarted = false
            success = true

            val bytes = output.length()
            return Result(
                file = output,
                width = size.first,
                height = size.second,
                durationUs = lastPtsUs + FRAME_INTERVAL_US,
                frames = frames,
                sizeBytes = bytes,
                fitsLimit = bytes <= MAX_BYTES
            )
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { outputSurface?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { inputSurface?.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
            if (!success) output.delete()
        }
    }

    companion object {
        private const val TAG = "StickerTranscoder"
        const val MIME_VP9 = "video/x-vnd.on2.vp9"
        const val STICKER_SIDE = 512
        const val MAX_FPS = 30
        const val MAX_DURATION_US = 3_000_000L
        const val MIN_DURATION_US = 200_000L
        const val MAX_BYTES = 256 * 1024L

        private const val TARGET_BYTES = 225 * 1024L
        private const val FRAME_INTERVAL_US = 1_000_000L / MAX_FPS + 1
        private const val MIN_BITRATE = 60_000
        private const val MAX_BITRATE = 2_500_000
        private const val MAX_ATTEMPTS = 5
        private const val TIMEOUT_US = 10_000L

        /** One side is exactly 512, the other is 512 or less (even, for the encoder). */
        fun outputSize(width: Int, height: Int, square: Boolean): Pair<Int, Int> {
            if (square || width == height) return STICKER_SIDE to STICKER_SIDE
            fun shortSide(long: Int, short: Int): Int {
                val scaled = (STICKER_SIDE.toDouble() * short / long / 2).roundToInt() * 2
                return min(STICKER_SIDE, max(16, scaled))
            }
            return if (width > height) STICKER_SIDE to shortSide(width, height)
            else shortSide(height, width) to STICKER_SIDE
        }
    }
}
