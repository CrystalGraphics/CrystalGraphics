package com.crystalgraphics.render.post.composite;

/**
 * A look the post stack's composite can lay over the target, each a keyword of {@code shaders/post/composite.shader}:
 * the variant drawn is the set active this firing, and with none the composite draws nothing.
 */
public enum CgCompositeFeature {

    /** The bloom chain added over the target ({@link CgPostComposite#bloom}). */
    BLOOM("BLOOM");

    final String keyword;

    CgCompositeFeature(String keyword) {
        this.keyword = keyword;
    }

    /** The shader keyword it compiles in. */
    public String keyword() {
        return keyword;
    }
}
