package com.crystalgraphics.api.paint.brush;

/**
 * How a brush whose picture has its own proportions ({@link CgBrush#svg}) fills a rect: CSS's {@code object-fit}.
 *
 * <pre>{@code
 * canvas.image(CgBrush.svg(logo), x, y, 200, 40);                       // CONTAIN: fitted, centred, proportions kept
 * canvas.image(CgBrush.svg(bar).fit(CgFit.FILL), x, y, 200, 8);         // stretched to the rect
 * }</pre>
 */
public enum CgFit {
    /** As large as fits, centred: SVG's default {@code preserveAspectRatio="xMidYMid meet"}. */
    CONTAIN,
    /** Stretched to the rect on each axis: {@code preserveAspectRatio="none"}. */
    FILL
}
