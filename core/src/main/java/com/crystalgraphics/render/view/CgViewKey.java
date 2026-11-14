package com.crystalgraphics.render.view;

/**
 * A value one stage of a view publishes for a later stage of the same view this frame ({@link CgView#publish}): a mask,
 * a count, a texture a pass of yours made. Compared by identity: one constant per value.
 *
 * <pre>{@code
 * static final CgViewKey<CgGraphTexture> MASK = new CgViewKey<>("mymod:mask");
 *
 * CgRenderStage.SURFACES.register(frame -> frame.view().publish(MASK, drawMask(frame)));
 * CgRenderStage.TRANSLUCENT.register(frame -> {
 *     CgGraphTexture mask = frame.view().read(MASK);       // null where nothing published it this frame
 *     if (mask != null) composite(frame, mask);
 * });
 * }</pre>
 */
public final class CgViewKey<T> {

    private final String name;

    public CgViewKey(String name) {
        this.name = name;
    }

    @Override
    public String toString() {
        return name;
    }
}
