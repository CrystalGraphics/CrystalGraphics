package com.crystalgraphics.api.paint.effect;

/**
 * A box's shadow, as CSS's {@code box-shadow} draws one: the box's shape offset, grown by a spread, blurred, in a colour;
 * outside the box, or inset inside it. Drawn analytically: no layer, no blur pass.
 *
 * <pre>{@code
 * static final CgShadow LIFT = CgShadow.of(0, 2, 8, 0x60000000);
 * static final CgShadow WELL = CgShadow.of(0, 1, 3, 0x80000000).inset();
 *
 * canvas.rect(x, y, w, h).radius(6).fill(card).shadow(LIFT).submit();
 * }</pre>
 *
 * <ul>
 *   <li>{@code blur} is CSS's blur radius, twice the Gaussian's sigma; 0 is a hard shadow.</li>
 *   <li>A negative spread shrinks the shape before blurring.</li>
 *   <li>An outer shadow draws only outside the box, an inset one only inside it, as CSS clips them.</li>
 * </ul>
 *
 * @param dx     offset right, in the box's units
 * @param dy     offset down
 * @param blur   CSS's blur radius; 0 for a hard edge
 * @param spread how much larger the shadow's shape is than the box's
 * @param argb   its colour, straight {@code 0xAARRGGBB}
 * @param inside inside the box rather than outside: an inset shadow
 */
public record CgShadow(float dx, float dy, float blur, float spread, int argb, boolean inside) {

    public CgShadow {
        if (!Float.isFinite(dx) || !Float.isFinite(dy) || !Float.isFinite(spread)) {
            throw new IllegalArgumentException("offset and spread must be finite: " + dx + ", " + dy + ", " + spread);
        }
        if (!(blur >= 0f) || Float.isInfinite(blur)) throw new IllegalArgumentException("blur must be >= 0: " + blur);
    }

    /** An outer shadow offset by ({@code dx}, {@code dy}), blurred by {@code blur}, no spread. */
    public static CgShadow of(float dx, float dy, float blur, int argb) {
        return new CgShadow(dx, dy, blur, 0f, argb, false);
    }

    /** An outer shadow with a spread. */
    public static CgShadow of(float dx, float dy, float blur, float spread, int argb) {
        return new CgShadow(dx, dy, blur, spread, argb, false);
    }

    /** This shadow inside the box. */
    public CgShadow inset() {
        return new CgShadow(dx, dy, blur, spread, argb, true);
    }

    /** The Gaussian's sigma for its blur: CSS's blur radius is two sigma. */
    public float sigma() {
        return blur * 0.5f;
    }
}
