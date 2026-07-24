package com.digital.datamosh

import com.digital.datamosh.camera.CameraUiState
import com.digital.datamosh.camera.CaptureAction
import com.digital.datamosh.camera.CapturePhase
import com.digital.datamosh.camera.KeyframeGate
import com.digital.datamosh.camera.SampleDecision
import com.digital.datamosh.camera.SegmentMode
import com.digital.datamosh.camera.reduceCaptureState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureStateTest {
    @Test
    fun firstHoldIsClean_thenMoshStaysArmed() {
        var state = reduceCaptureState(CameraUiState(), CaptureAction.CameraReady)
        assertEquals(SegmentMode.CLEAN, state.nextMode)

        state = reduceCaptureState(state, CaptureAction.HoldStarted)
        assertEquals(SegmentMode.CLEAN, state.activeMode)

        state = reduceCaptureState(state, CaptureAction.HoldCommitted(900))
        assertEquals(CapturePhase.PAUSED, state.phase)
        assertEquals(SegmentMode.MOSH, state.nextMode)
        assertEquals(1, state.segmentCount)

        state = reduceCaptureState(state, CaptureAction.HoldStarted)
        state = reduceCaptureState(state, CaptureAction.HoldCommitted(700))
        assertEquals(SegmentMode.MOSH, state.nextMode)
        assertEquals(2, state.segmentCount)
        assertEquals(1_600, state.activeDurationMs)
    }

    @Test
    fun resetArmsExactlyOneCleanSegment() {
        var state = CameraUiState(
            phase = CapturePhase.PAUSED,
            nextMode = SegmentMode.MOSH,
            segmentCount = 1,
            cameraReady = true,
        )
        state = reduceCaptureState(state, CaptureAction.ResetNext)
        assertEquals(SegmentMode.CLEAN, state.nextMode)
        assertFalse(state.canReset)

        state = reduceCaptureState(state, CaptureAction.HoldStarted)
        state = reduceCaptureState(state, CaptureAction.HoldCommitted(500))
        assertEquals(SegmentMode.MOSH, state.nextMode)
    }

    @Test
    fun discardedHoldDoesNotAdvanceSequence() {
        var state = reduceCaptureState(CameraUiState(), CaptureAction.CameraReady)
        state = reduceCaptureState(state, CaptureAction.HoldStarted)
        state = reduceCaptureState(state, CaptureAction.HoldDiscarded)
        assertEquals(CapturePhase.READY, state.phase)
        assertEquals(SegmentMode.CLEAN, state.nextMode)
        assertEquals(0, state.segmentCount)
        assertTrue(state.warning != null)
    }

    @Test
    fun cleanGateWaitsForAndAcceptsBoundaryKeyframe() {
        val gate = KeyframeGate()
        gate.beginSegment(SegmentMode.CLEAN)
        assertEquals(SampleDecision.DROP, gate.decide(isKeyframe = false))
        assertEquals(SampleDecision.ACCEPT_AND_RESET_DECODER, gate.decide(isKeyframe = true))
        assertEquals(SampleDecision.ACCEPT, gate.decide(isKeyframe = false))
        assertEquals(SampleDecision.ACCEPT, gate.decide(isKeyframe = true))
    }

    @Test
    fun moshGateDropsBoundaryAndLaterKeyframes() {
        val gate = KeyframeGate()
        gate.beginSegment(SegmentMode.MOSH)
        assertEquals(SampleDecision.DROP, gate.decide(isKeyframe = false))
        assertEquals(SampleDecision.DROP, gate.decide(isKeyframe = true))
        assertEquals(SampleDecision.ACCEPT, gate.decide(isKeyframe = false))
        assertEquals(SampleDecision.DROP, gate.decide(isKeyframe = true))
    }

    @Test
    fun frameRateCanOnlyChangeBeforeFirstSegment() {
        val ready = CameraUiState(
            phase = CapturePhase.READY,
            cameraReady = true,
            supports60Fps = true,
        )
        assertTrue(ready.canChangeFps)
        assertFalse(ready.copy(phase = CapturePhase.HOLDING).canChangeFps)
        assertFalse(ready.copy(phase = CapturePhase.PAUSED, segmentCount = 1).canChangeFps)
    }
}
