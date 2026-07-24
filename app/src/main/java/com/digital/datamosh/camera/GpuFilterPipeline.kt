package com.digital.datamosh.camera

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * A small Camera2 -> OpenGL -> MediaCodec bridge used only when the camera does not provide
 * CONTROL_EFFECT_MODE_NEGATIVE. The preview remains a direct Camera2 stream; PreviewLayers
 * applies the matching display-only color matrix to it.
 */
internal class GpuFilterPipeline(
    private val onWarning: (String) -> Unit,
) {
    private val thread = HandlerThread("DatamoshFilter").apply { start() }
    private val handler = Handler(thread.looper)
    private var display = EGL14.EGL_NO_DISPLAY
    private var context = EGL14.EGL_NO_CONTEXT
    private var encoderEglSurface = EGL14.EGL_NO_SURFACE
    private var inputTextureId = 0
    private var inputTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    private var program = 0
    private var positionLocation = -1
    private var textureLocation = -1
    private var matrixLocation = -1
    private var invertedLocation = -1
    private var width = 0
    private var height = 0
    @Volatile private var inverted = false
    @Volatile private var released = false
    private var renderWarningSent = false

    private val vertices: FloatBuffer = ByteBuffer
        .allocateDirect(4 * 4 * java.lang.Float.BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(
                floatArrayOf(
                    -1f, -1f, 0f, 0f,
                    1f, -1f, 1f, 0f,
                    -1f, 1f, 0f, 1f,
                    1f, 1f, 1f, 1f,
                ),
            )
            position(0)
        }

    fun prepare(encoderSurface: Surface, width: Int, height: Int): Surface? {
        val result = AtomicReference<Surface?>()
        val latch = CountDownLatch(1)
        handler.post {
            result.set(
                runCatching {
                    setup(encoderSurface, width, height)
                    inputSurface
                }.onFailure {
                    onWarning("GPU inversion is unavailable: ${it.message ?: "OpenGL setup failed"}")
                    releaseGl()
                }.getOrNull(),
            )
            latch.countDown()
        }
        if (!latch.await(3, TimeUnit.SECONDS)) {
            onWarning("GPU inversion setup timed out")
            return null
        }
        return result.get()
    }

    fun setInverted(enabled: Boolean) {
        inverted = enabled
    }

    fun release() {
        if (released) return
        released = true
        val latch = CountDownLatch(1)
        handler.post {
            releaseGl()
            latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS)
        thread.quitSafely()
    }

    private fun setup(encoderSurface: Surface, width: Int, height: Int) {
        this.width = width
        this.height = height
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
        val versions = IntArray(2)
        check(EGL14.eglInitialize(display, versions, 0, versions, 1)) {
            "Could not initialize EGL"
        }
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0)) {
            "Could not choose EGL config"
        }
        val config = requireNotNull(configs.firstOrNull())
        context = EGL14.eglCreateContext(
            display,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "Could not create EGL context" }
        encoderEglSurface = EGL14.eglCreateWindowSurface(
            display,
            config,
            encoderSurface,
            intArrayOf(EGL14.EGL_NONE),
            0,
        )
        check(encoderEglSurface != EGL14.EGL_NO_SURFACE) {
            "Could not connect OpenGL to the encoder"
        }
        val surfaceWidth = IntArray(1)
        val surfaceHeight = IntArray(1)
        check(
            EGL14.eglQuerySurface(
                display,
                encoderEglSurface,
                EGL14.EGL_WIDTH,
                surfaceWidth,
                0,
            ) && EGL14.eglQuerySurface(
                display,
                encoderEglSurface,
                EGL14.EGL_HEIGHT,
                surfaceHeight,
                0,
            ),
        ) { "Could not read encoder surface dimensions" }
        this.width = surfaceWidth[0]
        this.height = surfaceHeight[0]
        makeCurrent()

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        inputTextureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTextureId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE,
        )

        program = createProgram()
        positionLocation = GLES20.glGetAttribLocation(program, "aPosition")
        textureLocation = GLES20.glGetAttribLocation(program, "aTexCoord")
        matrixLocation = GLES20.glGetUniformLocation(program, "uTexMatrix")
        invertedLocation = GLES20.glGetUniformLocation(program, "uInverted")

        inputTexture = SurfaceTexture(inputTextureId).apply {
            setDefaultBufferSize(width, height)
            setOnFrameAvailableListener({ renderFrame() }, handler)
        }
        inputSurface = Surface(inputTexture)
    }

    private fun renderFrame() {
        if (released) return
        try {
            makeCurrent()
            val texture = inputTexture ?: return
            texture.updateTexImage()

            GLES20.glViewport(0, 0, width, height)
            GLES20.glUseProgram(program)
            vertices.position(0)
            GLES20.glVertexAttribPointer(
                positionLocation,
                2,
                GLES20.GL_FLOAT,
                false,
                4 * java.lang.Float.BYTES,
                vertices,
            )
            GLES20.glEnableVertexAttribArray(positionLocation)
            vertices.position(2)
            GLES20.glVertexAttribPointer(
                textureLocation,
                2,
                GLES20.GL_FLOAT,
                false,
                4 * java.lang.Float.BYTES,
                vertices,
            )
            GLES20.glEnableVertexAttribArray(textureLocation)
            // Camera SurfaceTextures may advertise a preview rotation/mirror transform. The MP4
            // muxer already owns video orientation, so forwarding that transform would rotate the
            // pixels and then rotate them a second time during playback. Only convert between
            // SurfaceTexture's top-left convention and OpenGL's bottom-left convention here.
            GLES20.glUniformMatrix4fv(matrixLocation, 1, false, VIDEO_TEXTURE_MATRIX, 0)
            GLES20.glUniform1f(invertedLocation, if (inverted) 1f else 0f)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTextureId)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            EGLExt.eglPresentationTimeANDROID(display, encoderEglSurface, texture.timestamp)
            check(EGL14.eglSwapBuffers(display, encoderEglSurface)) {
                "Encoder surface rejected a filtered frame"
            }
        } catch (error: Throwable) {
            if (!renderWarningSent) {
                renderWarningSent = true
                onWarning("GPU inversion stopped: ${error.message ?: "rendering failed"}")
            }
        }
    }

    private fun makeCurrent() {
        check(
            EGL14.eglMakeCurrent(
                display,
                encoderEglSurface,
                encoderEglSurface,
                context,
            ),
        ) { "Could not activate EGL context" }
    }

    private fun createProgram(): Int {
        val vertexShader = compileShader(
            GLES20.GL_VERTEX_SHADER,
            """
                uniform mat4 uTexMatrix;
                attribute vec4 aPosition;
                attribute vec2 aTexCoord;
                varying vec2 vTexCoord;
                void main() {
                    gl_Position = aPosition;
                    vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
                }
            """.trimIndent(),
        )
        val fragmentShader = compileShader(
            GLES20.GL_FRAGMENT_SHADER,
            """
                #extension GL_OES_EGL_image_external : require
                precision mediump float;
                uniform samplerExternalOES uTexture;
                uniform float uInverted;
                varying vec2 vTexCoord;
                void main() {
                    vec4 color = texture2D(uTexture, vTexCoord);
                    color.rgb = mix(color.rgb, vec3(1.0) - color.rgb, uInverted);
                    gl_FragColor = color;
                }
            """.trimIndent(),
        )
        return GLES20.glCreateProgram().also { target ->
            GLES20.glAttachShader(target, vertexShader)
            GLES20.glAttachShader(target, fragmentShader)
            GLES20.glLinkProgram(target)
            val status = IntArray(1)
            GLES20.glGetProgramiv(target, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) {
                GLES20.glGetProgramInfoLog(target)
            }
            GLES20.glDeleteShader(vertexShader)
            GLES20.glDeleteShader(fragmentShader)
        }
    }

    private fun compileShader(type: Int, source: String): Int =
        GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) {
                GLES20.glGetShaderInfoLog(shader)
            }
        }

    private fun releaseGl() {
        inputTexture?.setOnFrameAvailableListener(null)
        runCatching { inputSurface?.release() }
        runCatching { inputTexture?.release() }
        inputSurface = null
        inputTexture = null
        if (display != EGL14.EGL_NO_DISPLAY) {
            if (inputTextureId != 0) {
                runCatching {
                    makeCurrent()
                    GLES20.glDeleteTextures(1, intArrayOf(inputTextureId), 0)
                }
            }
            if (program != 0) runCatching { GLES20.glDeleteProgram(program) }
            runCatching { EGL14.eglMakeCurrent(
                display,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            ) }
            if (encoderEglSurface != EGL14.EGL_NO_SURFACE) {
                runCatching { EGL14.eglDestroySurface(display, encoderEglSurface) }
            }
            if (context != EGL14.EGL_NO_CONTEXT) {
                runCatching { EGL14.eglDestroyContext(display, context) }
            }
            runCatching { EGL14.eglTerminate(display) }
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        encoderEglSurface = EGL14.EGL_NO_SURFACE
        inputTextureId = 0
        program = 0
    }

    private companion object {
        const val EGL_RECORDABLE_ANDROID = 0x3142
        val VIDEO_TEXTURE_MATRIX = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, -1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 1f, 0f, 1f,
        )
    }
}
