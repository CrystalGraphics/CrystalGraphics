package com.crystalgraphics.render.post.composite;

/**
 * How the composite writes the target, chosen each firing from what its active features need. We write in place onto
 * the host's 8-bit target, where engines ping-pong from HDR to LDR, so there are two forms.
 */
public enum CgCompositeForm {

    /**
     * {@code dst * (1 - a) + rgb}: no copy of the target, adding in its own encoding. Every look that adds or darkens
     * fits; the added term is dithered by stochastic rounding. {@code shaders/post/composite.shader}.
     */
    BLEND("crystalgraphics:shaders/post/composite.shader"),
    /**
     * Reads a copy of the target, composites in linear light and writes it back encoded and dithered: what a look that
     * brightens by multiplying or reads its neighbours needs, and the correct colour space. {@code composite_copy.shader}.
     */
    COPY("crystalgraphics:shaders/post/composite_copy.shader");

    final String shader;

    CgCompositeForm(String shader) {
        this.shader = shader;
    }
}
