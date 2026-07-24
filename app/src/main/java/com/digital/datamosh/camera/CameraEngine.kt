package com.digital.datamosh.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Range
import android.view.Surface
import android.view.TextureView
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

class CameraEngine(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onCameraReady(
            front: Boolean,
            torchAvailable: Boolean,
            supports60Fps: Boolean,
            activeFps: Int,
        )
        fun onSegmentFinished(durationMs: Long?)
        fun onSaved(uri: android.net.Uri)
        fun onWarning(message: String?)
        fun onError(message: String)
    }

    private val cameraManager = context.getSystemService(CameraManager::class.java)
    private val cameraThread = HandlerThread("DatamoshCamera").apply { start() }
    private val codecThread = HandlerThread("DatamoshCodec").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val codecHandler = Handler(codecThread.looper)

    private var layers: PreviewLayers? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var requestBuilder: CaptureRequest.Builder? = null
    private var encoder: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private var decoder: MediaCodec? = null
    private var decoderSurface: Surface? = null
    private var videoFormat: MediaFormat? = null
    private var writer: MuxWriter? = null
    private var audioEncoder: AudioEncoder? = null
    private var cameraId: String? = null
    private var usingFront = false
    private var torchAvailable = false
    private var torchEnabled = false
    private var targetFpsRange: Range<Int>? = null
    private var targetFps = 30
    private var supports60Fps = false
    private var sensorOrientation = 90
    private var prepared = false
    private var opening = false
    @Volatile private var lifecycleActive = true
    @Volatile private var released = false
    private var reopenAttempt = 0

    @Volatile private var recordingActive = false
    @Volatile private var activeMode = SegmentMode.CLEAN
    @Volatile private var segmentHadFrame = false
    private val keyframeGate = KeyframeGate()
    private var segmentStartedAtMs = 0L
    private var firstVideoPtsUs = Long.MIN_VALUE
    private var pausedVideoUs = 0L
    private var pauseStartedAtUs = 0L
    private val finishing = AtomicBoolean(false)
    private val finalized = AtomicBoolean(false)

    fun attach(previewLayers: PreviewLayers) {
        layers = previewLayers
        val available = previewLayers.rawPreview.isAvailable
        previewLayers.rawPreview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                prepareAndOpen()
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                closeCamera()
                return true
            }
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
        previewLayers.decodedPreview.surfaceTextureListener =
            object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    codecHandler.post { maybeCreateDecoder() }
                }
                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                    releaseDecoder()
                    return true
                }
                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
            }
        if (available) prepareAndOpen()
    }

    fun startSegment(mode: SegmentMode, deviceRotationDegrees: Int) {
        if (recordingActive || !prepared || finishing.get()) return
        try {
            if (writer == null) {
                val orientationHint =
                    (sensorOrientation - deviceRotationDegrees + 360) % 360
                writer = MuxWriter(context, orientationHint).also { mux ->
                    videoFormat?.let(mux::setVideoFormat)
                    audioEncoder = AudioEncoder(context, mux) {
                        listener.onWarning("Microphone stopped: ${it.message ?: "audio encoder error"}")
                    }.also { it.start() }
                }
            }
            activeMode = mode
            keyframeGate.beginSegment(mode)
            segmentHadFrame = false
            segmentStartedAtMs = SystemClock.elapsedRealtime()
            if (pauseStartedAtUs != 0L) {
                pausedVideoUs += System.nanoTime() / 1_000L - pauseStartedAtUs
                pauseStartedAtUs = 0L
            }
            recordingActive = true
            layers?.post { layers?.showDecoded(true) }
            audioEncoder?.setCapturing(true)
            resumeEncoderAndRequestSync()
        } catch (error: Throwable) {
            recordingActive = false
            listener.onError(error.message ?: "Unable to start recording")
        }
    }

    fun pauseSegment() {
        if (!recordingActive) return
        recordingActive = false
        audioEncoder?.setCapturing(false)
        suspendEncoder()
        pauseStartedAtUs = System.nanoTime() / 1_000L
        layers?.post {
            if (segmentHadFrame) {
                layers?.showLastFrameGuide()
            } else {
                layers?.showDecoded(false)
            }
        }
        val duration = SystemClock.elapsedRealtime() - segmentStartedAtMs
        listener.onSegmentFinished(if (segmentHadFrame) duration else null)
    }

    fun pauseForLifecycle() {
        lifecycleActive = false
        if (recordingActive) pauseSegment()
        closeCamera()
    }

    fun resumeForLifecycle() {
        if (released) return
        lifecycleActive = true
        reopenAttempt = 0
        if (prepared) {
            cameraHandler.post { openCamera() }
        } else {
            prepareAndOpen()
        }
    }

    fun finish() {
        if (writer == null || recordingActive || finishing.getAndSet(true)) return
        closeCamera()
        audioEncoder?.stop()
        audioEncoder = null
        runCatching { encoder?.signalEndOfInputStream() }
            .onFailure { codecHandler.post(::finalizeWriter) }
        codecHandler.postDelayed(::finalizeWriter, 2_000)
    }

    fun switchCamera() {
        if (recordingActive || finishing.get()) return
        usingFront = !usingFront
        val nextFps = if (targetFps == 60 && !cameraSupportsFps(usingFront, 60)) 30 else targetFps
        if (nextFps != targetFps) {
            listener.onWarning("60 fps is unavailable on this lens; switched to 30 fps")
            reconfigureVideoPipeline(nextFps)
        } else {
            closeCamera()
            cameraHandler.postDelayed({ openCamera() }, 180)
        }
    }

    fun setTargetFps(fps: Int): Boolean {
        if (fps !in setOf(30, 60) || recordingActive || writer != null || finishing.get()) {
            return false
        }
        if (fps == 60 && !cameraSupportsFps(usingFront, 60)) {
            listener.onWarning("60 fps is not exposed for this camera")
            return false
        }
        if (fps == targetFps) return true
        reconfigureVideoPipeline(fps)
        return true
    }

    fun setTorch(enabled: Boolean) {
        if (usingFront || !torchAvailable) return
        torchEnabled = enabled
        updateRepeatingRequest()
    }

    fun release(abandonRecording: Boolean) {
        released = true
        lifecycleActive = false
        recordingActive = false
        closeCamera()
        audioEncoder?.stop()
        audioEncoder = null
        releaseCodecs()
        if (abandonRecording) writer?.abandon()
        writer = null
        cameraThread.quitSafely()
        codecThread.quitSafely()
    }

    private fun prepareAndOpen() {
        codecHandler.post {
            if (!prepared) {
                try {
                    prepareVideoCodec()
                    prepared = true
                } catch (error: Throwable) {
                    listener.onError("H.264 pipeline unavailable: ${error.message}")
                    return@post
                }
            }
            if (lifecycleActive && !released) cameraHandler.post { openCamera() }
        }
    }

    private fun prepareVideoCodec() {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder = codec
        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

            override fun onOutputBufferAvailable(
                codec: MediaCodec,
                index: Int,
                info: MediaCodec.BufferInfo,
            ) {
                handleVideoOutput(codec, index, info)
            }

            override fun onError(codec: MediaCodec, error: MediaCodec.CodecException) {
                listener.onError("Video codec failed: ${error.diagnosticInfo}")
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                // Rotation is owned by PreviewLayers and the MP4 orientation hint. Leaving a
                // vendor-provided rotation key here can make only the decoded hold preview rotate.
                format.setInteger(MediaFormat.KEY_ROTATION, 0)
                videoFormat = format
                writer?.setVideoFormat(format)
                maybeCreateDecoder()
            }
        }, codecHandler)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 720).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, if (targetFps == 60) 14_000_000 else 8_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, targetFps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 600)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoderSurface = codec.createInputSurface()
        codec.start()
        suspendEncoder()
    }

    private fun handleVideoOutput(
        codec: MediaCodec,
        index: Int,
        info: MediaCodec.BufferInfo,
    ) {
        try {
            val output = codec.getOutputBuffer(index)
            val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
            val isKeyframe = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
            val isEos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0

            if (recordingActive && output != null && info.size > 0 && !isConfig) {
                val decision = keyframeGate.decide(isKeyframe)
                if (decision == SampleDecision.ACCEPT_AND_RESET_DECODER) {
                    runCatching { decoder?.flush() }
                }
                if (decision != SampleDecision.DROP) {
                    val data = ByteArray(info.size)
                    output.position(info.offset)
                    output.limit(info.offset + info.size)
                    output.get(data)
                    val pts = normalizedVideoPts(info.presentationTimeUs)
                    writer?.writeVideo(data, pts, info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv())
                    queueDecoder(data, pts, info.flags)
                    segmentHadFrame = true
                }
            }
            codec.releaseOutputBuffer(index, false)
            if (isEos && finishing.get()) codecHandler.post(::finalizeWriter)
        } catch (error: Throwable) {
            runCatching { codec.releaseOutputBuffer(index, false) }
            listener.onWarning("Damaged-stream preview stalled: ${error.message}")
        }
    }

    private fun normalizedVideoPts(sourcePtsUs: Long): Long {
        if (firstVideoPtsUs == Long.MIN_VALUE) firstVideoPtsUs = sourcePtsUs
        return (sourcePtsUs - firstVideoPtsUs - pausedVideoUs).coerceAtLeast(0)
    }

    private fun queueDecoder(data: ByteArray, pts: Long, flags: Int) {
        val target = decoder ?: return
        try {
            val index = target.dequeueInputBuffer(0)
            if (index < 0) {
                listener.onWarning("Decoder is catching up; the saved stream is unaffected")
                return
            }
            target.getInputBuffer(index)?.apply {
                clear()
                put(data)
            }
            target.queueInputBuffer(
                index,
                0,
                data.size,
                pts,
                flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv(),
            )
            drainDecoder(target)
        } catch (error: Throwable) {
            listener.onWarning("This device concealed the damaged frames")
        }
    }

    private fun drainDecoder(target: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = target.dequeueOutputBuffer(info, 0)
            if (index < 0) return
            target.releaseOutputBuffer(index, true)
        }
    }

    private fun maybeCreateDecoder() {
        if (decoder != null) return
        val format = videoFormat ?: return
        val texture = layers?.decodedPreview?.takeIf { it.isAvailable }?.surfaceTexture ?: return
        try {
            texture.setDefaultBufferSize(1280, 720)
            layers?.configureTransform(sensorOrientation, usingFront)
            val surface = Surface(texture)
            decoderSurface = surface
            decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, surface, null, 0)
                start()
            }
        } catch (error: Throwable) {
            releaseDecoder()
            listener.onWarning("Live damaged-stream preview is unavailable on this device")
        }
    }

    private fun resumeEncoderAndRequestSync() {
        val target = encoder ?: return
        runCatching {
            target.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_SUSPEND, 0)
            })
        }
        runCatching {
            target.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        }.onFailure {
            listener.onWarning("The encoder may recover early because sync-frame control is limited")
        }
    }

    private fun suspendEncoder() {
        runCatching {
            encoder?.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_SUSPEND, 1)
            })
        }.onFailure {
            listener.onWarning("This encoder cannot fully suspend between segments")
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (
            released || !lifecycleActive || camera != null || opening ||
            layers?.rawPreview?.isAvailable != true
        ) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            listener.onError("Camera permission is not granted")
            return
        }
        try {
            val facing = if (usingFront) {
                CameraCharacteristics.LENS_FACING_FRONT
            } else {
                CameraCharacteristics.LENS_FACING_BACK
            }
            val id = cameraManager.cameraIdList.firstOrNull {
                cameraManager.getCameraCharacteristics(it)
                    .get(CameraCharacteristics.LENS_FACING) == facing
            } ?: cameraManager.cameraIdList.first()
            cameraId = id
            val characteristics = cameraManager.getCameraCharacteristics(id)
            torchAvailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            sensorOrientation =
                characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            layers?.configureTransform(sensorOrientation, usingFront)
            targetFpsRange = characteristics
                .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.filter { it.lower <= targetFps && it.upper >= targetFps }
                ?.minByOrNull { (it.upper - it.lower) * 100 + it.upper }
            supports60Fps = characteristics
                .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.any { it.lower <= 60 && it.upper >= 60 } == true
            opening = true
            cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (released || !lifecycleActive) {
                        opening = false
                        device.close()
                        return
                    }
                    opening = false
                    reopenAttempt = 0
                    camera = device
                    createSession(device)
                }

                override fun onDisconnected(device: CameraDevice) {
                    opening = false
                    device.close()
                    camera = null
                    listener.onWarning("Camera disconnected")
                    scheduleReopen()
                }

                override fun onError(device: CameraDevice, error: Int) {
                    opening = false
                    device.close()
                    camera = null
                    listener.onError("Camera error $error")
                    scheduleReopen()
                }
            }, cameraHandler)
        } catch (error: Throwable) {
            opening = false
            listener.onError(error.message ?: "Unable to open camera")
            scheduleReopen()
        }
    }

    private fun scheduleReopen() {
        if (released || !lifecycleActive || reopenAttempt >= 4) return
        val delayMs = 350L shl reopenAttempt
        reopenAttempt++
        cameraHandler.postDelayed({
            if (!released && lifecycleActive && camera == null && !opening) openCamera()
        }, delayMs)
    }

    private fun createSession(device: CameraDevice) {
        val texture = layers?.rawPreview?.surfaceTexture ?: return
        texture.setDefaultBufferSize(1280, 720)
        layers?.configureTransform(sensorOrientation, usingFront)
        val previewSurface = Surface(texture)
        val codecSurface = encoderSurface ?: return
        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(previewSurface)
                addTarget(codecSurface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                targetFpsRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            }
            requestBuilder = builder
            @Suppress("DEPRECATION")
            device.createCaptureSession(
                listOf(previewSurface, codecSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(captureSession: CameraCaptureSession) {
                        session = captureSession
                        updateRepeatingRequest()
                        listener.onCameraReady(
                            usingFront,
                            torchAvailable,
                            supports60Fps,
                            targetFps,
                        )
                    }

                    override fun onConfigureFailed(captureSession: CameraCaptureSession) {
                        if (targetFps == 60) {
                            listener.onWarning("60 fps session failed; falling back to 30 fps")
                            reconfigureVideoPipeline(30)
                        } else {
                            listener.onError("Camera cannot stream to the H.264 encoder")
                        }
                    }
                },
                cameraHandler,
            )
        } catch (error: Throwable) {
            listener.onError(error.message ?: "Unable to configure camera")
        }
    }

    private fun updateRepeatingRequest() {
        cameraHandler.post {
            val builder = requestBuilder ?: return@post
            builder.set(
                CaptureRequest.FLASH_MODE,
                if (torchEnabled && torchAvailable && !usingFront) {
                    CaptureRequest.FLASH_MODE_TORCH
                } else {
                    CaptureRequest.FLASH_MODE_OFF
                },
            )
            runCatching { session?.setRepeatingRequest(builder.build(), null, cameraHandler) }
                .onFailure { listener.onWarning("Camera controls are temporarily unavailable") }
        }
    }

    private fun closeCamera() {
        cameraHandler.post {
            runCatching { session?.stopRepeating() }
            runCatching { session?.close() }
            runCatching { camera?.close() }
            session = null
            camera = null
            requestBuilder = null
            opening = false
        }
    }

    private fun reconfigureVideoPipeline(fps: Int) {
        targetFps = fps
        closeCamera()
        codecHandler.postDelayed({
            releaseCodecs()
            try {
                prepareVideoCodec()
                prepared = true
                if (lifecycleActive && !released) cameraHandler.post { openCamera() }
            } catch (error: Throwable) {
                releaseCodecs()
                if (fps == 60) {
                    listener.onWarning("The H.264 encoder rejected 60 fps; using 30 fps")
                    targetFps = 30
                    runCatching {
                        prepareVideoCodec()
                        prepared = true
                        if (lifecycleActive && !released) cameraHandler.post { openCamera() }
                    }.onFailure {
                        listener.onError("Could not restore 30 fps: ${it.message}")
                    }
                } else {
                    listener.onError("Could not configure $fps fps: ${error.message}")
                }
            }
        }, 260)
    }

    private fun cameraSupportsFps(front: Boolean, fps: Int): Boolean = runCatching {
        val facing = if (front) {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }
        val id = cameraManager.cameraIdList.firstOrNull {
            cameraManager.getCameraCharacteristics(it)
                .get(CameraCharacteristics.LENS_FACING) == facing
        } ?: return@runCatching false
        cameraManager.getCameraCharacteristics(id)
            .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.any { it.lower <= fps && it.upper >= fps } == true
    }.getOrDefault(false)

    private fun finalizeWriter() {
        if (!finalized.compareAndSet(false, true)) return
        val result = runCatching { writer?.finish() ?: error("No recording exists") }
        writer = null
        releaseCodecs()
        result.onSuccess(listener::onSaved)
            .onFailure { listener.onError("Could not finalize the video: ${it.message}") }
    }

    private fun releaseDecoder() {
        runCatching { decoder?.stop() }
        runCatching { decoder?.release() }
        runCatching { decoderSurface?.release() }
        decoder = null
        decoderSurface = null
    }

    private fun releaseCodecs() {
        releaseDecoder()
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        runCatching { encoderSurface?.release() }
        encoder = null
        encoderSurface = null
        prepared = false
    }
}
