package com.digital.datamosh.camera

import android.net.Uri

enum class SegmentMode { CLEAN, MOSH }

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
    val targetFps: Int = 30,
    val supports60Fps: Boolean = false,
    val cameraReady: Boolean = false,
    val warning: String? = null,
    val error: String? = null,
    val savedUri: Uri? = null,
) {
    val isHolding: Boolean get() = phase == CapturePhase.HOLDING
    val canHold: Boolean get() = cameraReady && phase in setOf(CapturePhase.READY, CapturePhase.PAUSED)
    val canReset: Boolean get() = phase == CapturePhase.PAUSED && nextMode == SegmentMode.MOSH
    val canFinish: Boolean get() = phase == CapturePhase.PAUSED && segmentCount > 0
    val canChangeFps: Boolean
        get() = cameraReady && phase == CapturePhase.READY && segmentCount == 0
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
    )
}
