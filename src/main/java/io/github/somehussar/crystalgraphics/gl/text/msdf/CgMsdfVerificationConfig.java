package io.github.somehussar.crystalgraphics.gl.text.msdf;

public final class CgMsdfVerificationConfig {

    public static final float DEFAULT_RECONSTRUCTION_THRESHOLD = 0.5f;
    public static final float DEFAULT_REFERENCE_THRESHOLD = 0.5f;
    public static final float DEFAULT_MAX_MISMATCH_RATIO = 0.02f;

    private final int referenceRenderPx;
    private final float reconstructionThreshold;
    private final float referenceThreshold;
    private final float maxMismatchRatio;
    private final boolean dumpPassingGlyphs;

    public CgMsdfVerificationConfig(int referenceRenderPx,
                                    float reconstructionThreshold,
                                    float referenceThreshold,
                                    float maxMismatchRatio,
                                    boolean dumpPassingGlyphs) {
        if (referenceRenderPx <= 0) {
            throw new IllegalArgumentException("referenceRenderPx must be > 0, got " + referenceRenderPx);
        }
        if (reconstructionThreshold < 0.0f || reconstructionThreshold > 1.0f) {
            throw new IllegalArgumentException("reconstructionThreshold must be in [0,1], got " + reconstructionThreshold);
        }
        if (referenceThreshold < 0.0f || referenceThreshold > 1.0f) {
            throw new IllegalArgumentException("referenceThreshold must be in [0,1], got " + referenceThreshold);
        }
        if (maxMismatchRatio < 0.0f || maxMismatchRatio > 1.0f) {
            throw new IllegalArgumentException("maxMismatchRatio must be in [0,1], got " + maxMismatchRatio);
        }
        this.referenceRenderPx = referenceRenderPx;
        this.reconstructionThreshold = reconstructionThreshold;
        this.referenceThreshold = referenceThreshold;
        this.maxMismatchRatio = maxMismatchRatio;
        this.dumpPassingGlyphs = dumpPassingGlyphs;
    }

    public int getReferenceRenderPx() {
        return referenceRenderPx;
    }

    public float getReconstructionThreshold() {
        return reconstructionThreshold;
    }

    public float getReferenceThreshold() {
        return referenceThreshold;
    }

    public float getMaxMismatchRatio() {
        return maxMismatchRatio;
    }

    public boolean isDumpPassingGlyphs() {
        return dumpPassingGlyphs;
    }
}
