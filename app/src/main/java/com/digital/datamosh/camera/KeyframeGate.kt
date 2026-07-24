package com.digital.datamosh.camera

internal enum class SampleDecision {
    DROP,
    ACCEPT,
    ACCEPT_AND_RESET_DECODER,
}

internal class KeyframeGate {
    private var mode = SegmentMode.CLEAN
    private var awaitingBoundaryKeyframe = false

    fun beginSegment(segmentMode: SegmentMode) {
        mode = segmentMode
        awaitingBoundaryKeyframe = true
    }

    fun decide(isKeyframe: Boolean): SampleDecision {
        if (awaitingBoundaryKeyframe) {
            if (!isKeyframe) return SampleDecision.DROP
            awaitingBoundaryKeyframe = false
            return if (mode == SegmentMode.MOSH) {
                SampleDecision.DROP
            } else {
                SampleDecision.ACCEPT_AND_RESET_DECODER
            }
        }
        return if (isKeyframe && mode == SegmentMode.MOSH) {
            SampleDecision.DROP
        } else {
            SampleDecision.ACCEPT
        }
    }
}
