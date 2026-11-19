package com.crystalgraphics.api.paint;

import com.crystalgraphics.api.paint.brush.CgBrush;
import com.crystalgraphics.api.paint.gradient.CgGradient;
import com.crystalgraphics.api.paint.pattern.CgGrid;

/**
 * What fills a shape: a colour, a {@link CgGradient}, a {@link CgBrush} or a {@link CgGrid}. Every fill in the engine
 * takes one, on the 2D canvas and on a scene's quads alike, so a value made once paints anywhere.
 *
 * <pre>{@code
 * CgPaint flat = CgPaint.color(0xFF203040);
 * CgPaint glow = CgPaint.color(0xFFFFE080, 4f);                       // HDR: four times as bright
 * CgPaint sky  = CgGradient.linear(180f).stop(0f, 0xFF203040).stop(1f, 0xFF101820);
 * CgPaint icon = CgBrush.texture(atlas).region(0, 0, 16, 16);
 *
 * canvas.rect(x, y, w, h).fill(sky).submit();
 * }</pre>
 *
 * <ul>
 *   <li>Every paint is immutable and compared by value: keep it in a constant, share it across threads.</li>
 *   <li>Colours are straight {@code 0xAARRGGBB} in sRGB; an intensity above 1 brightens past white on an HDR target.</li>
 * </ul>
 */
public interface CgPaint {

    /** An opaque or translucent colour, straight {@code 0xAARRGGBB}. */
    static Solid color(int argb) {
        return new Solid(argb, 1f);
    }

    /** A colour times {@code intensity}, for a target that holds light above white. */
    static Solid color(int argb, float intensity) {
        return new Solid(argb, intensity);
    }

    /**
     * One colour everywhere.
     *
     * @param argb      straight {@code 0xAARRGGBB}, sRGB
     * @param intensity a multiplier on its colour, not its alpha: 1 for an ordinary colour
     */
    record Solid(int argb, float intensity) implements CgPaint {

        public Solid {
            if (!(intensity >= 0f) || Float.isInfinite(intensity)) {
                throw new IllegalArgumentException("intensity must be finite and non-negative: " + intensity);
            }
        }

        /** Whether it draws nothing: fully transparent. */
        public boolean isClear() {
            return (argb >>> 24) == 0;
        }
    }
}
