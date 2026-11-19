package com.crystalgraphics.api.paint.gradient;

/**
 * What a {@link CgGradient} paints past its first and last stop: Skia's tile modes, CSS's plain and repeating
 * gradients.
 *
 * <pre>{@code
 * CgGradient.linear(90f).stop(0f, a).stop(1f, b);                             // PAD: the end colours hold
 * CgGradient.linear(45f).repeat().stop(0f, a).stop(0.1f, b);                  // stripes every tenth of the line
 * CgGradient.radial(0.5f, 0.5f, 0.2f, 0.2f).reflect().stop(0f, a).stop(1f, b); // rings that fold back
 * }</pre>
 */
public enum CgSpread {
    /** The end colours continue: CSS's {@code linear-gradient}, Skia's clamp. */
    PAD,
    /** The span between the first and last stop repeats: CSS's {@code repeating-linear-gradient}. */
    REPEAT,
    /** The span repeats, every other copy reversed: Skia's mirror. */
    REFLECT
}
