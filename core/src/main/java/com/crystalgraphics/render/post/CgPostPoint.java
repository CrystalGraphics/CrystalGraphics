package com.crystalgraphics.render.post;

/**
 * Where a {@link CgPostEffect} records, around the post stack's one composite pass: Unreal's blendable locations and
 * HDRP's injection points. Within a point, effects record by {@link CgPostEffect#order()}.
 *
 * <p>The composite itself is the engine's and takes no effect, as Unreal's tonemapper takes none: a new screen-wide
 * look is a composite feature, asked of the engine.</p>
 */
public enum CgPostPoint {

    // Declared in the order they record: CgPostStack sorts effects by ordinal and walks each point's run in turn, so a
    // point out of order would have its effects skipped.

    /** After the world, before anything of the stack's: a pass that reads the scene (its colour or depth). */
    AFTER_WORLD,
    /** Effects that make the composite's inputs: bloom's chain, a flash's level. */
    BEFORE_COMPOSITE,
    /** Over the final picture, after the composite: an outline, a CRT filter. */
    AFTER_COMPOSITE
}
