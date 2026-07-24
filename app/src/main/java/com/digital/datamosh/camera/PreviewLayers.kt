package com.digital.datamosh.camera

import android.content.Context
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout

class PreviewLayers(context: Context) : FrameLayout(context) {
    companion object {
        private const val ONION_SKIN_ALPHA = 0.30f
    }

    val rawPreview = TextureView(context)
    val decodedPreview = TextureView(context)
    private var sensorOrientation = 90
    private var mirrorDecoded = false
    private var bufferWidth = 1280f
    private var bufferHeight = 720f

    init {
        setBackgroundColor(Color.BLACK)
        addView(rawPreview, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(decodedPreview, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        decodedPreview.alpha = 0f
        decodedPreview.isOpaque = true
    }

    fun showDecoded(show: Boolean) {
        decodedPreview.animate()
            .alpha(if (show) 1f else 0f)
            .setDuration(90)
            .start()
    }

    fun showLastFrameGuide() {
        decodedPreview.animate()
            .alpha(ONION_SKIN_ALPHA)
            .setDuration(140)
            .start()
    }

    fun setRawInverted(inverted: Boolean) {
        post {
            if (inverted) {
                val matrix = ColorMatrix(
                    floatArrayOf(
                        -1f, 0f, 0f, 0f, 255f,
                        0f, -1f, 0f, 0f, 255f,
                        0f, 0f, -1f, 0f, 255f,
                        0f, 0f, 0f, 1f, 0f,
                    ),
                )
                rawPreview.setLayerType(
                    View.LAYER_TYPE_HARDWARE,
                    Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) },
                )
            } else {
                rawPreview.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            }
        }
    }

    fun configureTransform(
        sensorOrientation: Int,
        mirrorDecoded: Boolean,
        bufferWidth: Int,
        bufferHeight: Int,
    ) {
        this.sensorOrientation = sensorOrientation
        this.mirrorDecoded = mirrorDecoded
        this.bufferWidth = bufferWidth.toFloat()
        this.bufferHeight = bufferHeight.toFloat()
        post(::applyTransform)
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        applyTransform()
    }

    @Suppress("DEPRECATION")
    private fun applyTransform() {
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) return
        val windowManager = context.getSystemService(WindowManager::class.java)
        val displayRotation = windowManager.defaultDisplay.rotation
        val deviceDegrees = when (displayRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val rotation = (sensorOrientation - deviceDegrees + 360) % 360
        val matrix = centerCropMatrix(
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            bufferWidth = bufferWidth,
            bufferHeight = bufferHeight,
            rotation = rotation,
        )
        // Camera2's SurfaceTexture already carries the camera producer's sensor rotation.
        // Applying the decoder's explicit rotation here rotates the idle preview twice.
        rawPreview.setTransform(Matrix())
        if (mirrorDecoded) {
            matrix.postScale(-1f, 1f, viewWidth / 2f, viewHeight / 2f)
        }
        decodedPreview.setTransform(matrix)
    }

    private fun centerCropMatrix(
        viewWidth: Float,
        viewHeight: Float,
        bufferWidth: Float,
        bufferHeight: Float,
        rotation: Int,
    ): Matrix {
        val swapsAxes = rotation == 90 || rotation == 270
        val rotatedWidth = if (swapsAxes) bufferHeight else bufferWidth
        val rotatedHeight = if (swapsAxes) bufferWidth else bufferHeight
        val scale = maxOf(viewWidth / rotatedWidth, viewHeight / rotatedHeight)
        val left = (viewWidth - rotatedWidth * scale) / 2f
        val top = (viewHeight - rotatedHeight * scale) / 2f
        val source = floatArrayOf(
            0f, 0f,
            viewWidth, 0f,
            0f, viewHeight,
        )
        val destination = when (rotation) {
            90 -> floatArrayOf(
                left + bufferHeight * scale, top,
                left + bufferHeight * scale, top + bufferWidth * scale,
                left, top,
            )
            180 -> floatArrayOf(
                left + bufferWidth * scale, top + bufferHeight * scale,
                left, top + bufferHeight * scale,
                left + bufferWidth * scale, top,
            )
            270 -> floatArrayOf(
                left, top + bufferWidth * scale,
                left, top,
                left + bufferHeight * scale, top + bufferWidth * scale,
            )
            else -> floatArrayOf(
                left, top,
                left + bufferWidth * scale, top,
                left, top + bufferHeight * scale,
            )
        }
        return Matrix().apply {
            setPolyToPoly(source, 0, destination, 0, 3)
        }
    }
}
