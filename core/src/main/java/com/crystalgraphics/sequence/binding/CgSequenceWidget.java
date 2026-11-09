package com.crystalgraphics.sequence.binding;

import com.crystalgraphics.sequence.CgSequenceTarget;
import com.crystalgraphics.sequence.definition.CgSequenceBinding;
import org.joml.Matrix4f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * A widget a sequence animates, as a UMG widget animation animates a {@code UWidget}: its render transform
 * ({@code translation}, {@code scale}, {@code shear}, {@code angle}), its {@code render_opacity}, and the colours a
 * brush and a border's content draw with ({@code colour_and_opacity}, {@code content_colour_and_opacity}). Its named
 * children are the parts a binding's {@code node} reaches. You draw it: read its values, or its {@link #pose}, each
 * frame.
 *
 * <pre>{@code
 * // the widgets, at their designer values; the root is the role the sequence binds
 * CgSequenceWidget number = new CgSequenceWidget();
 * CgSequenceWidget banner = new CgSequenceWidget().scale(1.5f, 1f).angle(2f);
 * CgSequenceWidget pop = new CgSequenceWidget().child("Pnl_Number", number).child("Img_Banner", banner);
 * CgSequencePlayer player = CgSequencer.widgets(CgWorld.client()).play(definition, Map.of("number", pop));
 *
 * // each frame, at a GUI stage: the banner's image, centred on (x, y), in the canvas's pixels at `unit` a widget unit
 * banner.pose(x, y, unit, pose);
 * canvas.quad(IMAGE).texture(0, bannerImage).at(-112, -28).size(224, 56).pose(pose).color(banner.argb()).submit();
 * }</pre>
 *
 * <pre>{@code
 * // a track binds a child by its name: { role: number, node: Img_Banner }, property: angle
 * player.jumpTo(0);   // replay a pooled player: the widget is written again from the start
 * }</pre>
 *
 * <ul>
 *   <li>Values a sequence never keys keep what you set here; a section's end puts back what it found.</li>
 *   <li>The angle is in degrees, clockwise on a y-down canvas; translation in widget units, as Slate's.</li>
 *   <li>Children are found by name only, never by layout: a parent's opacity is not multiplied into its children's
 *       {@link #argb}. Draw them in the order you want them stacked.</li>
 *   <li>Add children before a play binds them; a name a track binds that no child has fails the play.</li>
 * </ul>
 */
public final class CgSequenceWidget implements CgSequenceTarget {

    private static final int TRANSLATION = 0;
    private static final int SCALE = 1;
    private static final int SHEAR = 2;
    private static final int ANGLE = 3;
    private static final int RENDER_OPACITY = 4;
    private static final int COLOUR = 5;
    private static final int CONTENT_COLOUR = 6;

    // FWidgetTransform's defaults (WidgetTransform.h:39), UWidget's RenderOpacity (Widget.cpp:201), white brushes.
    private final float[][] slots = {
            {0f, 0f, 0f, 0f}, {1f, 1f, 0f, 0f}, {0f, 0f, 0f, 0f}, {0f, 0f, 0f, 0f}, {1f, 0f, 0f, 0f},
            {1f, 1f, 1f, 1f}, {1f, 1f, 1f, 1f}};
    private final List<String> names = new ArrayList<>(2);
    private final List<CgSequenceWidget> children = new ArrayList<>(2);

    /** Its designer translation, in widget units. */
    public CgSequenceWidget translation(float x, float y) {
        return put(TRANSLATION, x, y);
    }

    /** Its designer scale, 1 for none. */
    public CgSequenceWidget scale(float x, float y) {
        return put(SCALE, x, y);
    }

    /** Its designer shear, in degrees, each clamped to ±89 when posed. */
    public CgSequenceWidget shear(float x, float y) {
        return put(SHEAR, x, y);
    }

    /** Its designer angle, in degrees. */
    public CgSequenceWidget angle(float degrees) {
        return put(ANGLE, degrees, 0f);
    }

    /** Its designer render opacity, 0 to 1. */
    public CgSequenceWidget renderOpacity(float opacity) {
        return put(RENDER_OPACITY, opacity, 0f);
    }

    /** Its designer colour and opacity, ARGB. */
    public CgSequenceWidget colour(int argb) {
        return putColour(COLOUR, argb);
    }

    /** Its designer content colour and opacity, ARGB: a border's content. */
    public CgSequenceWidget contentColour(int argb) {
        return putColour(CONTENT_COLOUR, argb);
    }

    /** Adds {@code child} as the part a binding's {@code node: name} reaches. */
    public CgSequenceWidget child(String name, CgSequenceWidget child) {
        if (names.contains(name)) throw new IllegalArgumentException("a child is already named " + name);
        names.add(name);
        children.add(child);
        return this;
    }

    public float translationX() {
        return slots[TRANSLATION][0];
    }

    public float translationY() {
        return slots[TRANSLATION][1];
    }

    public float scaleX() {
        return slots[SCALE][0];
    }

    public float scaleY() {
        return slots[SCALE][1];
    }

    public float shearX() {
        return slots[SHEAR][0];
    }

    public float shearY() {
        return slots[SHEAR][1];
    }

    /** In degrees. */
    public float angle() {
        return slots[ANGLE][0];
    }

    public float renderOpacity() {
        return slots[RENDER_OPACITY][0];
    }

    /** Its colour and opacity times its render opacity, ARGB, each channel clamped to 0 to 1. */
    public int argb() {
        return argbOf(COLOUR);
    }

    /** Its content colour and opacity times its render opacity, ARGB. */
    public int contentArgb() {
        return argbOf(CONTENT_COLOUR);
    }

    /**
     * Its render transform about a pivot at canvas point ({@code pivotX}, {@code pivotY}), {@code unit} canvas pixels a
     * widget unit, into {@code out}: Slate's order, scale then shear then rotation then translation
     * ({@code FWidgetTransform::ToSlateRenderTransform}). Draw its quad around the origin, in widget units.
     */
    public Matrix4f pose(float pivotX, float pivotY, float unit, Matrix4f out) {
        float[] s = slots[SCALE];
        float[] sh = slots[SHEAR];
        out.identity().translate(pivotX, pivotY, 0f).scale(unit, unit, 1f)
           .translate(translationX(), translationY(), 0f).rotateZ((float) Math.toRadians(angle()));
        // TShear2::FromShearAngles: each factor the tangent of its angle; a point gains (y * x factor, x * y factor).
        float kx = shearFactor(sh[0]);
        float ky = shearFactor(sh[1]);
        if (kx != 0f || ky != 0f) {
            out.mul(1f, ky, 0f, 0f, kx, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f);
        }
        return out.scale(s[0], s[1], 1f);
    }

    @Override
    public int slot(String name) {
        return switch (name) {
            case "translation" -> TRANSLATION;
            case "scale" -> SCALE;
            case "shear" -> SHEAR;
            case "angle" -> ANGLE;
            case "render_opacity" -> RENDER_OPACITY;
            case "colour_and_opacity" -> COLOUR;
            case "content_colour_and_opacity" -> CONTENT_COLOUR;
            default -> -1;
        };
    }

    @Override
    public void set(int slot, float[] values, int mask) {
        float[] own = slots[slot];
        for (int i = 0; i < 4; i++) {
            if ((mask & 1 << i) != 0) own[i] = values[i];
        }
    }

    @Override
    public void get(int slot, float[] out) {
        System.arraycopy(slots[slot], 0, out, 0, 4);
    }

    @Nullable
    @Override
    public CgSequenceTarget part(CgSequenceBinding.Part part, String name) {
        if (part != CgSequenceBinding.Part.NODE) return null;
        int i = names.indexOf(name);
        return i < 0 ? null : children.get(i);
    }

    private CgSequenceWidget put(int slot, float x, float y) {
        slots[slot][0] = x;
        slots[slot][1] = y;
        return this;
    }

    private CgSequenceWidget putColour(int slot, int argb) {
        float[] c = slots[slot];
        c[0] = (argb >>> 16 & 0xFF) / 255f;
        c[1] = (argb >>> 8 & 0xFF) / 255f;
        c[2] = (argb & 0xFF) / 255f;
        c[3] = (argb >>> 24) / 255f;
        return this;
    }

    private int argbOf(int slot) {
        float[] c = slots[slot];
        return channel(c[3] * renderOpacity()) << 24 | channel(c[0]) << 16 | channel(c[1]) << 8 | channel(c[2]);
    }

    private static int channel(float value) {
        return Math.round(Math.max(0f, Math.min(1f, value)) * 255f);
    }

    private static float shearFactor(float degrees) {
        return degrees == 0f ? 0f : (float) Math.tan(Math.toRadians(Math.max(-89f, Math.min(89f, degrees))));
    }
}
