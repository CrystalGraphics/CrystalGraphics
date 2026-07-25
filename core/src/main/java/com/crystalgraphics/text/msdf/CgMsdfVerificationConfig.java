package com.crystalgraphics.text.msdf;

public record CgMsdfVerificationConfig(int referenceRenderPx, float reconstructionThreshold, float referenceThreshold,
                                       float maxMismatchRatio, boolean dumpPassingGlyphs) {

    public static final float DEFAULT_RECONSTRUCTION_THRESHOLD = 0.5f;
    public static final float DEFAULT_REFERENCE_THRESHOLD = 0.5f;
    public static final float DEFAULT_MAX_MISMATCH_RATIO = 0.02f;

    public CgMsdfVerificationConfig {
        if (referenceRenderPx <= 0) throw new IllegalArgumentException("referenceRenderPx must be > 0, got " + referenceRenderPx);
        if (reconstructionThreshold < 0.0f || reconstructionThreshold > 1.0f) throw new IllegalArgumentException("reconstructionThreshold must be in [0,1], got " + reconstructionThreshold);
        if (referenceThreshold < 0.0f || referenceThreshold > 1.0f) throw new IllegalArgumentException("referenceThreshold must be in [0,1], got " + referenceThreshold);
        if (maxMismatchRatio < 0.0f || maxMismatchRatio > 1.0f) throw new IllegalArgumentException("maxMismatchRatio must be in [0,1], got " + maxMismatchRatio);
    }
}
