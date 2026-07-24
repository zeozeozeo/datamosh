package com.digital.datamosh.camera

import android.net.Uri

enum class SegmentMode { CLEAN, MOSH }

enum class VideoResolution(
    val width: Int,
    val height: Int,
    val label: String,
) {
    HD_720P(1280, 720, "720p"),
    FULL_HD_1080P(1920, 1080, "1080p"),
}

enum class VideoCodec(
    val mimeType: String,
    val label: String,
) {
    AVC("video/avc", "H.264"),
    HEVC("video/hevc", "H.265 / HEVC"),
}

enum class FilterMode { REGULAR, INVERTED }

data class RecordingConfiguration(
    val resolution: VideoResolution = VideoResolution.HD_720P,
    val fps: Int = 30,
    val codec: VideoCodec = VideoCodec.AVC,
) {
    val bitRate: Int
        get() = when (resolution) {
            VideoResolution.HD_720P -> if (fps == 60) 14_000_000 else 8_000_000
            VideoResolution.FULL_HD_1080P -> if (fps == 60) 20_000_000 else 12_000_000
        }
}

internal fun videoOrientationHint(
    sensorOrientation: Int,
    deviceOrientation: Int,
    frontFacing: Boolean,
): Int = if (frontFacing) {
    (sensorOrientation + deviceOrientation) % 360
} else {
    (sensorOrientation - deviceOrientation + 360) % 360
}

internal fun clockwiseDeviceOrientation(sensorDegrees: Int): Int =
    (360 - sensorDegrees) % 360

enum class CapturePhase {
    PREPARING,
    READY,
    HOLDING,
    PAUSED,
    FINALIZING,
    ERROR,
}

data class CameraUiState(
    val phase: CapturePhase = CapturePhase.PREPARING,
    val nextMode: SegmentMode = SegmentMode.CLEAN,
    val activeMode: SegmentMode? = null,
    val activeDurationMs: Long = 0,
    val segmentCount: Int = 0,
    val usingFrontCamera: Boolean = false,
    val torchEnabled: Boolean = false,
    val torchAvailable: Boolean = false,
    val recordingConfiguration: RecordingConfiguration = RecordingConfiguration(),
    val availableConfigurations: Set<RecordingConfiguration> = emptySet(),
    val filterMode: FilterMode = FilterMode.REGULAR,
    val invertedFilterAvailable: Boolean = false,
    val cameraReady: Boolean = false,
    val warning: String? = null,
    val error: String? = null,
    val savedUri: Uri? = null,
) {
    val isHolding: Boolean get() = phase == CapturePhase.HOLDING
    val canHold: Boolean get() = cameraReady && phase in setOf(CapturePhase.READY, CapturePhase.PAUSED)
    val canReset: Boolean get() = phase == CapturePhase.PAUSED && nextMode == SegmentMode.MOSH
    val canFinish: Boolean get() = phase == CapturePhase.PAUSED && segmentCount > 0
    val canChangeRecordingSettings: Boolean
        get() = cameraReady && phase == CapturePhase.READY && segmentCount == 0
    val targetFps: Int get() = recordingConfiguration.fps
}

sealed interface CaptureAction {
    data object CameraReady : CaptureAction
    data class CameraFailed(val message: String) : CaptureAction
    data object HoldStarted : CaptureAction
    data class HoldCommitted(val durationMs: Long) : CaptureAction
    data object HoldDiscarded : CaptureAction
    data object ResetNext : CaptureAction
    data object FinalizeStarted : CaptureAction
    data class Finalized(val uri: Uri) : CaptureAction
    data class Warning(val message: String?) : CaptureAction
    data object NewTake : CaptureAction
}

fun reduceCaptureState(state: CameraUiState, action: CaptureAction): CameraUiState = when (action) {
    CaptureAction.CameraReady -> state.copy(
        phase = if (state.segmentCount == 0) CapturePhase.READY else CapturePhase.PAUSED,
        cameraReady = true,
        error = null,
    )

    is CaptureAction.CameraFailed -> state.copy(
        phase = CapturePhase.ERROR,
        cameraReady = false,
        error = action.message,
        activeMode = null,
    )

    CaptureAction.HoldStarted -> if (state.canHold) {
        state.copy(phase = CapturePhase.HOLDING, activeMode = state.nextMode, warning = null)
    } else {
        state
    }

    is CaptureAction.HoldCommitted -> if (state.phase == CapturePhase.HOLDING) {
        state.copy(
            phase = CapturePhase.PAUSED,
            nextMode = SegmentMode.MOSH,
            activeMode = null,
            activeDurationMs = state.activeDurationMs + action.durationMs,
            segmentCount = state.segmentCount + 1,
        )
    } else {
        state
    }

    CaptureAction.HoldDiscarded -> if (state.phase == CapturePhase.HOLDING) {
        state.copy(
            phase = if (state.segmentCount == 0) CapturePhase.READY else CapturePhase.PAUSED,
            activeMode = null,
            warning = "Hold a little longer to capture a segment",
        )
    } else {
        state
    }

    CaptureAction.ResetNext -> if (state.canReset) {
        state.copy(nextMode = SegmentMode.CLEAN, warning = null)
    } else {
        state
    }

    CaptureAction.FinalizeStarted -> if (state.canFinish) {
        state.copy(phase = CapturePhase.FINALIZING)
    } else {
        state
    }

    is CaptureAction.Finalized -> state.copy(
        phase = CapturePhase.READY,
        activeMode = null,
        savedUri = action.uri,
    )

    is CaptureAction.Warning -> state.copy(warning = action.message)

    CaptureAction.NewTake -> CameraUiState(
        phase = if (state.cameraReady) CapturePhase.READY else CapturePhase.PREPARING,
        cameraReady = state.cameraReady,
        usingFrontCamera = state.usingFrontCamera,
        torchAvailable = state.torchAvailable,
        recordingConfiguration = state.recordingConfiguration,
        availableConfigurations = state.availableConfigurations,
        filterMode = state.filterMode,
        invertedFilterAvailable = state.invertedFilterAvailable,
    )
}
