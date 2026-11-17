package io.github.grailnight.videosticker

import android.graphics.Bitmap
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Off-screen OpenGL compositor. For every output frame it draws the cropped source picture
 * (a decoder frame or a bitmap), then the caption, then cuts the shape mask, and hands back
 * premultiplied RGBA pixels (bottom row first).
 *
 * Create, use and release it on one thread. The crop is in 0..1 coordinates of the displayed
 * source frame with a top-left origin.
 */
class FrameRenderer(
    private val width: Int,
    private val height: Int,
    crop: RectF,
    caption: Bitmap?,
    mask: Bitmap?
) : SurfaceTexture.OnFrameAvailableListener {

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private val oesProgram: Int
    private val texProgram: Int
    private val captionTexture: Int
    private val maskTexture: Int
    private var bitmapTexture = 0
    private var bitmapTextureWidth = 0
    private var bitmapTextureHeight = 0

    private var oesTexture = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var decoderSurface: Surface? = null
    private var callbackThread: HandlerThread? = null
    private val lock = Object()
    private var frameAvailable = false

    private val stMatrix = FloatArray(16)

    // Texture space has its origin at the bottom-left of the displayed frame.
    private val cropUniform = floatArrayOf(crop.left, 1f - crop.bottom, crop.width(), crop.height())
    private val fullUniform = floatArrayOf(0f, 0f, 1f, 1f)

    private val vertices: FloatBuffer = ByteBuffer.allocateDirect(16 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            // x, y, u, v
            put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f))
            position(0)
        }

    private val pixels: ByteBuffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())

    init {
        initEgl()
        oesProgram = buildProgram(FRAGMENT_OES)
        texProgram = buildProgram(FRAGMENT_2D)
        captionTexture = caption?.let(::uploadBitmap) ?: 0
        maskTexture = mask?.let(::uploadBitmap) ?: 0
        GLES20.glViewport(0, 0, width, height)
        GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1)
    }

    private fun initEgl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }

        val configAttrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(
            EGL14.eglChooseConfig(display, configAttrs, 0, configs, 0, 1, count, 0) && count[0] > 0
        ) { "no EGL config" }

        context = EGL14.eglCreateContext(
            display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

        eglSurface = EGL14.eglCreatePbufferSurface(
            display, configs[0],
            intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE), 0
        )
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface failed" }
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent failed" }
    }

    /** Surface for a video decoder to render into. */
    fun decoderSurface(): Surface {
        decoderSurface?.let { return it }
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        oesTexture = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
        setTextureParams(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)

        val thread = HandlerThread("sticker-frames").apply { start() }
        callbackThread = thread
        val texture = SurfaceTexture(oesTexture)
        texture.setOnFrameAvailableListener(this, Handler(thread.looper))
        surfaceTexture = texture
        return Surface(texture).also { decoderSurface = it }
    }

    override fun onFrameAvailable(st: SurfaceTexture) {
        synchronized(lock) {
            frameAvailable = true
            lock.notifyAll()
        }
    }

    /** Waits for the frame the decoder just released and composites it. */
    fun renderVideoFrame(timeoutMs: Long = 5000): ByteBuffer {
        synchronized(lock) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!frameAvailable) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) throw RuntimeException("decoder frame timeout")
                lock.wait(left)
            }
            frameAvailable = false
        }
        val texture = surfaceTexture!!
        texture.updateTexImage()
        texture.getTransformMatrix(stMatrix)

        beginFrame()
        drawQuad(oesProgram, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture, stMatrix, cropUniform)
        return finishFrame()
    }

    /** Composites a frame given as a bitmap (GIF sources). */
    fun renderBitmapFrame(frame: Bitmap): ByteBuffer {
        if (bitmapTexture == 0) {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            bitmapTexture = ids[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bitmapTexture)
            setTextureParams(GLES20.GL_TEXTURE_2D)
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bitmapTexture)
        if (frame.width == bitmapTextureWidth && frame.height == bitmapTextureHeight) {
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, frame)
        } else {
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, frame, 0)
            bitmapTextureWidth = frame.width
            bitmapTextureHeight = frame.height
        }

        beginFrame()
        drawQuad(texProgram, GLES20.GL_TEXTURE_2D, bitmapTexture, FLIP_V, cropUniform)
        return finishFrame()
    }

    private fun beginFrame() {
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
    }

    private fun finishFrame(): ByteBuffer {
        if (captionTexture != 0 || maskTexture != 0) {
            GLES20.glEnable(GLES20.GL_BLEND)
            if (captionTexture != 0) {
                // Bitmap textures are premultiplied.
                GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                drawQuad(texProgram, GLES20.GL_TEXTURE_2D, captionTexture, FLIP_V, fullUniform)
            }
            if (maskTexture != 0) {
                // Multiply everything drawn so far by the mask's alpha.
                GLES20.glBlendFunc(GLES20.GL_ZERO, GLES20.GL_SRC_ALPHA)
                drawQuad(texProgram, GLES20.GL_TEXTURE_2D, maskTexture, FLIP_V, fullUniform)
            }
            GLES20.glDisable(GLES20.GL_BLEND)
        }
        pixels.position(0)
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
        pixels.position(0)
        return pixels
    }

    private fun drawQuad(program: Int, target: Int, texture: Int, texMatrix: FloatArray, crop: FloatArray) {
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(target, texture)

        val aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        val aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        vertices.position(0)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glEnableVertexAttribArray(aPosition)
        vertices.position(2)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glEnableVertexAttribArray(aTexCoord)

        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTexMatrix"), 1, false, texMatrix, 0)
        GLES20.glUniform4fv(GLES20.glGetUniformLocation(program, "uCrop"), 1, crop, 0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sTexture"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun uploadBitmap(bitmap: Bitmap): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        setTextureParams(GLES20.GL_TEXTURE_2D)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        return ids[0]
    }

    private fun setTextureParams(target: Int) {
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    fun release() {
        decoderSurface?.release()
        surfaceTexture?.release()
        callbackThread?.quitSafely()
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
            )
            EGL14.eglDestroySurface(display, eglSurface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
    }

    private fun buildProgram(fragmentSource: String): Int {
        val vs = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "GL link failed: " + GLES20.glGetProgramInfoLog(program) }
        return program
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "GL compile failed: " + GLES20.glGetShaderInfoLog(shader) }
        return shader
    }

    private companion object {
        /** Bitmap textures have their first row at the top: flip V. */
        val FLIP_V = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, -1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 1f, 0f, 1f
        )

        const val VERTEX_SHADER = """
            uniform mat4 uTexMatrix;
            uniform vec4 uCrop;
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vec2 cropped = uCrop.xy + aTexCoord * uCrop.zw;
                vTexCoord = (uTexMatrix * vec4(cropped, 0.0, 1.0)).xy;
            }
        """
        const val FRAGMENT_OES = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = vec4(texture2D(sTexture, vTexCoord).rgb, 1.0);
            }
        """
        const val FRAGMENT_2D = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """
    }
}
