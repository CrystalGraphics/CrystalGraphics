package com.crystalgraphics.api.vector.svg;

import javax.annotation.Nullable;

import com.crystalgraphics.api.vector.CgStroke;
import com.crystalgraphics.api.vector.CgVectorPath;

/**
 * One drawn part of an SVG document, as its own path: to animate or restyle a part rather than draw the whole
 * ({@link CgSvg#shapes()}, {@link CgSvg#shape(String)}). In document units, its transforms applied.
 *
 * <pre>{@code
 * CgSvgShape tick = CgSvg.of("mymod:ui/icons/check.svg").shape("tick");
 * canvas.stroke(tick.path(), tick.stroke().trim(0f, progress), 0xFF4CAF50);   // drawn on as progress runs
 * }</pre>
 *
 * <pre>{@code
 * for (CgSvgShape s : doc.shapes()) {                                          // every part, one restyled
 *     int argb = "badge".equals(s.id()) ? alert : s.fill().argb();
 *     if (s.fill() != null) canvas.fill(s.path(), argb);
 * }
 * }</pre>
 *
 * <ul>
 *   <li>A gradient's paint is its representative colour here; the document draws it in full.</li>
 *   <li>A stroke drawn with a gradient is its outline, filled: a shape with that fill and no stroke.</li>
 *   <li>Curves arrive flattened, as the document draws them.</li>
 * </ul>
 *
 * @param id     the element's {@code id}; null when it has none
 * @param path   its contours, with its fill rule
 * @param fill   how it is filled; null when it is not
 * @param stroke its stroke's width and cap; null when it is not stroked
 * @param strokePaint how its stroke is painted; null when it is not stroked
 */
public record CgSvgShape(@Nullable String id, CgVectorPath path, @Nullable Paint fill, @Nullable CgStroke stroke,
                         @Nullable Paint strokePaint) {

    /**
     * A part's paint: {@code argb} its colour, {@code currentColor} when the document left it to the draw's colour,
     * {@code gradient} when it is a gradient {@code argb} stands for.
     */
    public record Paint(int argb, boolean currentColor, boolean gradient) {
    }
}
