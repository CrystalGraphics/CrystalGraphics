package com.crystalgraphics.render.post.composite;

/**
 * A look the post stack's composite can lay over the target, each a shader keyword: the variant drawn is the set active
 * this firing, and with none the composite draws nothing. A look the blend form cannot draw puts the firing in the copy
 * form ({@link CgCompositeForm}).
 */
public enum CgCompositeFeature {

    /** The bloom chain over the target ({@link CgPostComposite#bloom}). */
    BLOOM("BLOOM", true),
    /** The picture brightened by an exposure ({@link CgPostComposite#flash}): brightening by multiplying needs the copy. */
    FLASH("FLASH", false),
    /** The corners darkened ({@link CgPostComposite#vignette}): a multiply, so the copy until dual-source blending. */
    VIGNETTE("VIGNETTE", false),
    /** Red and blue split from a focus ({@link CgPostComposite#chromatic}): it reads neighbours. */
    CHROMATIC("CHROMATIC", false),
    /** An impact frame ({@link CgPostComposite#impact}): it reads the picture. */
    IMPACT("IMPACT", false);

    final String keyword;
    final boolean blend;

    CgCompositeFeature(String keyword, boolean blend) {
        this.keyword = keyword;
        this.blend = blend;
    }

    /** The shader keyword it compiles in. */
    public String keyword() {
        return keyword;
    }

    /** Whether the blend form draws it; else a firing with it is drawn in the copy form. */
    public boolean blend() {
        return blend;
    }
}
