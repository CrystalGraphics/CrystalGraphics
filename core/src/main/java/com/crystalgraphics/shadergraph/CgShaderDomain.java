package com.crystalgraphics.shadergraph;

/**
 * Which shader stage a node's code can live in.
 *
 * <h3>Why a node declares this rather than the compiler inferring it</h3>
 * <p>Most of it <em>is</em> inferable — a node reachable only from the vertex output belongs in the
 * vertex stage — and {@link CgGraphCompiler} does infer exactly that. What cannot be inferred is the
 * cases where GLSL itself forbids a stage: {@code fwidth}, {@code dFdx}, {@code discard} and
 * {@code gl_FragCoord} are fragment-only, and a vertex shader containing one does not compile.</p>
 *
 * <p>This engine has already paid for that once. {@code sdf.glsl}'s {@code sdf_coverage} uses
 * {@code fwidth}; the material compiler hoists every material-scope {@code #include} into <b>both</b>
 * stages; NVIDIA accepted it and AMD correctly refused, which made the whole UI gallery unlaunchable on
 * that hardware. A node graph would reproduce that failure at scale, and the fix is the same shape:
 * declare the constraint where it is known rather than discovering it at the driver.</p>
 */
public enum CgShaderDomain {

    /** Runs in either stage — the common case, and the default. */
    ANY,

    /** Vertex stage only: it reads vertex attributes, or writes position. */
    VERTEX,

    /** Fragment stage only: it uses a derivative, discards, or reads a fragment builtin. */
    FRAGMENT;

    /** Whether a node of this domain may be emitted into {@code stage}. */
    public boolean allows(CgShaderDomain stage) {
        return this == ANY || this == stage;
    }

    /**
     * The domain a value must be in to feed a node in {@code this} domain, when the two differ.
     *
     * <p>Vertex data can reach the fragment stage — that is what a varying is for. Fragment data cannot
     * reach the vertex stage at all: the vertex shader has already run. That asymmetry is the whole
     * reason the split has to be validated rather than resolved.</p>
     */
    public boolean canReceiveFrom(CgShaderDomain upstream) {
        if (upstream == ANY || this == ANY || upstream == this) return true;
        // VERTEX -> FRAGMENT is legal, via a varying. FRAGMENT -> VERTEX is not, ever.
        return upstream == VERTEX && this == FRAGMENT;
    }
}
