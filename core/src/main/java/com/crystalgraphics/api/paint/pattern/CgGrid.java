package com.crystalgraphics.api.paint.pattern;

import com.crystalgraphics.api.paint.CgPaint;

/**
 * A ruled grid or a checkerboard, antialiased at any scale and cell size: a graph editor's background, a transparency
 * checker, a measuring overlay. A value: kept and shared, compared by value.
 *
 * <pre>{@code
 * static final CgGrid RULED = CgGrid.lines(0x40FFFFFF).cell(16f).width(1f);
 * static final CgGrid CHECKER = CgGrid.checker(0xFFCCCCCC, 0xFF999999).cell(8f);
 *
 * canvas.rect(x, y, w, h).fill(RULED).submit();
 * scene.quad(4f, 4f).fill(RULED.cell(0.25f).width(0.02f)).at(x, y, z).spawn();   // in world units on a quad
 * }</pre>
 *
 * <ul>
 *   <li>Cell and line sizes are in the units the filled shape is drawn in: pixels on the canvas, blocks on a quad.</li>
 *   <li>Lines never draw thinner than a pixel; a thinner one fades instead, so its brightness holds as it shrinks, and
 *       cells smaller than a pixel dissolve to the grid's mean rather than shimmer (Ben Golus's pristine grid).</li>
 *   <li>A line wider than half a cell draws the gaps instead.</li>
 * </ul>
 *
 * @param lineColor   the lines' colour, or a checker's first cells', straight {@code 0xAARRGGBB}
 * @param secondColor a checker's other cells' colour; unused for lines
 * @param checker     alternate cells filled rather than ruled
 * @param cellWidth   a cell's width
 * @param cellHeight  a cell's height
 * @param lineWidth   a vertical line's width
 * @param lineHeight  a horizontal line's thickness
 */
public record CgGrid(int lineColor, int secondColor, boolean checker, float cellWidth, float cellHeight,
                     float lineWidth, float lineHeight) implements CgPaint {

    public CgGrid {
        positive(cellWidth, "cellWidth");
        positive(cellHeight, "cellHeight");
        nonNegative(lineWidth, "lineWidth");
        nonNegative(lineHeight, "lineHeight");
    }

    /** Lines in {@code argb} on 16-unit cells, one unit wide. */
    public static CgGrid lines(int argb) {
        return new CgGrid(argb, 0, false, 16f, 16f, 1f, 1f);
    }

    /** A checkerboard of {@code first} and {@code second} on 8-unit cells. */
    public static CgGrid checker(int first, int second) {
        return new CgGrid(first, second, true, 8f, 8f, 0f, 0f);
    }

    /** This grid with square cells {@code size} wide. */
    public CgGrid cell(float size) {
        return cell(size, size);
    }

    /** This grid with cells {@code width} by {@code height}. */
    public CgGrid cell(float width, float height) {
        return new CgGrid(lineColor, secondColor, checker, width, height, lineWidth, lineHeight);
    }

    /** This grid's lines {@code width} wide on both axes. */
    public CgGrid width(float width) {
        return width(width, width);
    }

    /** This grid's vertical lines {@code x} wide and horizontal ones {@code y} thick. */
    public CgGrid width(float x, float y) {
        return new CgGrid(lineColor, secondColor, checker, cellWidth, cellHeight, x, y);
    }

    private static void positive(float v, String name) {
        if (!(v > 0f) || Float.isInfinite(v)) throw new IllegalArgumentException(name + " must be positive: " + v);
    }

    private static void nonNegative(float v, String name) {
        if (!(v >= 0f) || Float.isInfinite(v)) throw new IllegalArgumentException(name + " must be finite and >= 0: " + v);
    }
}
