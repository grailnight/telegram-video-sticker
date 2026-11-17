package io.github.grailnight.videosticker

import androidx.annotation.Keep
import java.io.Closeable
import java.nio.ByteBuffer

/** Frame store + VP9 (libvpx) encoder + WebM writer living in libstickerenc.so. */
class NativeEncoder(width: Int, height: Int) : Closeable {

    @Keep
    fun interface ProgressListener {
        /** Return false to cancel the encode. */
        @Keep
        fun onEncodeProgress(done: Int, total: Int): Boolean
    }

    private var handle = nativeCreate(width, height)
    var frameCount = 0
        private set

    init {
        check(handle != 0L) { "encoder init failed for ${width}x$height" }
    }

    /** Adds a premultiplied RGBA frame held in a direct buffer. */
    fun addFrame(rgba: ByteBuffer, bottomUp: Boolean) {
        val count = nativeAddFrame(handle, rgba, bottomUp)
        if (count == ERR_MEMORY) throw OutOfMemoryError("frame store")
        check(count > 0) { "addFrame failed: $count" }
        frameCount = count
    }

    val hasAlpha: Boolean
        get() = nativeHasAlpha(handle)

    /** Returns the file size, or a negative ERR_* code. */
    fun encode(
        path: String, order: IntArray, fps: Int, bitrate: Int, cpuUsed: Int, threads: Int,
        listener: ProgressListener?
    ): Long = nativeEncode(handle, path, order, fps, bitrate, cpuUsed, threads, listener)

    override fun close() {
        if (handle != 0L) nativeDestroy(handle)
        handle = 0
    }

    companion object {
        const val ERR_CANCELLED = -2
        const val ERR_MEMORY = -4

        init {
            System.loadLibrary("stickerenc")
        }

        @JvmStatic
        private external fun nativeCreate(width: Int, height: Int): Long

        @JvmStatic
        private external fun nativeDestroy(handle: Long)

        @JvmStatic
        private external fun nativeAddFrame(handle: Long, rgba: ByteBuffer, bottomUp: Boolean): Int

        @JvmStatic
        private external fun nativeHasAlpha(handle: Long): Boolean

        @JvmStatic
        private external fun nativeEncode(
            handle: Long, path: String, order: IntArray, fps: Int, bitrate: Int, cpuUsed: Int,
            threads: Int, listener: ProgressListener?
        ): Long
    }
}
