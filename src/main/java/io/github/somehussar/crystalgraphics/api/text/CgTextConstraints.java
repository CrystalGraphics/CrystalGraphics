package io.github.somehussar.crystalgraphics.api.text;

import lombok.Value;

/**
 * Immutable layout constraints for the text layout pipeline.
 *
 * <p>Specifies optional maximum width and/or height bounds that the layout engine
 * respects when breaking lines and truncating paragraphs. A constraint value of
 * {@code 0.0f} (or negative) means "unbounded" on that axis.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * // Wrap text at 200 logical pixels, no height limit
 * CgTextConstraints constraints = CgTextConstraints.maxWidth(200.0f);
 *
 * // Fully bounded box
 * CgTextConstraints constraints = CgTextConstraints.bounded(200.0f, 100.0f);
 *
 * // No constraints at all
 * CgTextConstraints constraints = CgTextConstraints.UNBOUNDED;
 * }</pre>
 *
 * <h3>Public Text API</h3>
 * <p>This type lives in {@code api/text} because it is a <strong>public domain
 * concept</strong> — callers use it to specify layout bounds before any internal
 * pipeline machinery runs. It has no dependencies on internal text or GL types.</p>
 *
 * @see CgTextLayout
 */
@Value
public final class CgTextConstraints {

    /** Maximum width in logical layout pixels. {@code 0.0f} = unbounded. */
    float maxWidth;

    /** Maximum height in logical layout pixels. {@code 0.0f} = unbounded. */
    float maxHeight;

    /** Shared unbounded instance — no width or height constraint. */
    public static final CgTextConstraints UNBOUNDED = new CgTextConstraints(0.0f, 0.0f);

    public CgTextConstraints(float maxWidth, float maxHeight) {
        this.maxWidth = maxWidth;
        this.maxHeight = maxHeight;
    }

    /** Creates constraints with a maximum width only (no height limit). */
    public static CgTextConstraints maxWidth(float maxWidth) {
        return new CgTextConstraints(maxWidth, 0.0f);
    }

    /** Creates constraints with a maximum height only (no width limit). */
    public static CgTextConstraints maxHeight(float maxHeight) {
        return new CgTextConstraints(0.0f, maxHeight);
    }

    /** Creates constraints bounded on both axes. */
    public static CgTextConstraints bounded(float maxWidth, float maxHeight) {
        return new CgTextConstraints(maxWidth, maxHeight);
    }
}
