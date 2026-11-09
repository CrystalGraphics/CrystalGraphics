package com.crystalgraphics.render.canvas;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialValues;

import java.util.Arrays;
import java.util.function.Consumer;

/**
 * A linear or radial gradient over a box, as CSS draws one: any number of stops, interpolated premultiplied, dithered,
 * optionally masked by elliptical corner radii. Set it up, then draw a quad over the box per window: up to eight stops
 * a draw (Skia's unrolled shape), each draw owning its window of the gradient line.
 *
 * <pre>{@code
 * CgCanvasGradient wash = new CgCanvasGradient(CgCanvasMaterials.SCREEN);    // once: it owns its material
 *
 * wash.size(w, h).angle(90f).clearStops().stop(0f, 0x003574F0).stop(1f, 0x803574F0);
 * for (int i = 0, n = wash.windows(); i < n; i++) {
 *     wash.window(i);                                                         // writes window i into its material
 *     canvas.quad(wash.material()).at(x, y).size(w, h).submit();
 * }
 *
 * glow.size(w, h).radial(w / 2, h, w / 2, h).radii(8, 8, 8, 8, 0, 0, 0, 0)  // a glow off the bottom, top corners round
 *         .clearStops().stop(0f, 0xFFFFE080).stop(1f, 0x00FFE080);
 * }</pre>
 *
 * <ul>
 *   <li>Stops ascend: a position below the one before is raised to it, as CSS does. Two at one position are a hard
 *       stop. Colours are straight ARGB; the quad's colour tints them.</li>
 *   <li>Positions are along the gradient line, 0 to 1: CSS's line for {@link #angle} (0 up, clockwise; 0 and 1 at the
 *       corners), or the ending shape for {@link #radial}. Before the first stop and past the last, the end colours.</li>
 *   <li>{@link #window} writes the material's values; a quad captures them as it is submitted, so set and submit each
 *       window in turn. One instance per drawer: its material is its own and holds the last window written.</li>
 *   <li>Premultiplied out: a {@link CgCanvasMaterials#SCREEN} gradient draws over the screen, a {@code WORLD} one is
 *       tested against the scene's depth.</li>
 * </ul>
 */
public final class CgCanvasGradient {

    /** Stops one draw evaluates. */
    public static final int STOPS_PER_DRAW = 8;
    static final String WITH_MASK = "WITH_MASK", RADIAL = "RADIAL";

    private static final String[] COLOR_PROPERTIES = {
            "_Color0", "_Color1", "_Color2", "_Color3", "_Color4", "_Color5", "_Color6", "_Color7"};

    private final CgMaterial material;
    private final Consumer<CgMaterialValues> writer = this::write;

    private float[] positions = new float[STOPS_PER_DRAW];
    private int[] colors = new int[STOPS_PER_DRAW];
    private int count;

    private float width, height;
    private boolean radial;
    private float angle;
    private float centreX, centreY, radiusX, radiusY;
    private final float[] radii = new float[8];

    private int first, end;
    private final float[] windowPositions = new float[STOPS_PER_DRAW];
    private final float[] axis = new float[2];

    public CgCanvasGradient(CgCanvasMaterials materials) {
        this.material = materials.newGradient();
    }

    /** What its quads draw under. */
    public CgMaterial material() {
        return material;
    }

    /** The box it covers, in the units its quads are drawn in. */
    public CgCanvasGradient size(float width, float height) {
        this.width = width;
        this.height = height;
        return this;
    }

    /** Linear, CSS's angle in degrees: 0 points up, 90 right, turning clockwise on a Y-down screen. */
    public CgCanvasGradient angle(float degrees) {
        this.radial = false;
        this.angle = degrees;
        return this;
    }

    /** Radial: 0 at {@code (centreX, centreY)}, 1 on the ellipse of radii {@code (radiusX, radiusY)}, all box-relative. */
    public CgCanvasGradient radial(float centreX, float centreY, float radiusX, float radiusY) {
        this.radial = true;
        this.centreX = centreX;
        this.centreY = centreY;
        this.radiusX = radiusX;
        this.radiusY = radiusY;
        return this;
    }

    /** Corner radii per corner and axis, CSS order (TL, TR, BR, BL); all zero draws the box square. */
    public CgCanvasGradient radii(float rxTL, float ryTL, float rxTR, float ryTR,
                                  float rxBR, float ryBR, float rxBL, float ryBL) {
        radii[0] = rxTL; radii[1] = ryTL; radii[2] = rxTR; radii[3] = ryTR;
        radii[4] = rxBR; radii[5] = ryBR; radii[6] = rxBL; radii[7] = ryBL;
        return this;
    }

    public CgCanvasGradient clearStops() {
        count = 0;
        return this;
    }

    /** A stop at {@code position} along the line, straight ARGB. */
    public CgCanvasGradient stop(float position, int argb) {
        if (count == positions.length) {
            positions = Arrays.copyOf(positions, count * 2);
            colors = Arrays.copyOf(colors, count * 2);
        }
        float p = Math.max(0f, Math.min(1f, position));
        positions[count] = count > 0 ? Math.max(p, positions[count - 1]) : p;
        colors[count++] = argb;
        return this;
    }

    /**
     * CSS's gradient line at {@code degrees} over a {@code width x height} box, into {@code out}: the direction scaled by
     * the box over the line's length, so {@code t = 0.5 + dot(uv - 0.5, out)} is 0 and 1 at the corners (CSS Images 3
     * section 3.4.1). {@code (1, 0)} is 90 degrees, {@code (0, 1)} 180.
     */
    public static void axis(float degrees, float width, float height, float[] out) {
        double radians = Math.toRadians(degrees);
        float s = (float) Math.sin(radians), c = (float) Math.cos(radians);
        float length = Math.abs(width * s) + Math.abs(height * c);
        out[0] = length <= 0f ? 1f : width * s / length;
        out[1] = length <= 0f ? 0f : -height * c / length;
    }

    /** How many draws the stops take: one up to eight, then one more per seven. */
    public int windows() {
        if (count < 2) throw new IllegalStateException("a gradient needs at least two stops");
        return count <= STOPS_PER_DRAW ? 1 : 1 + (count - 2) / (STOPS_PER_DRAW - 1);
    }

    /** Writes window {@code index}'s stops and the gradient's shape into its material, for the next quad. */
    public CgCanvasGradient window(int index) {
        int last = count - 1;
        first = index * (STOPS_PER_DRAW - 1);
        end = Math.min(first + STOPS_PER_DRAW - 1, last);
        boolean rounded = false;
        for (float r : radii) rounded |= r > 0f;
        material.toggleKeyword(WITH_MASK, rounded);
        material.toggleKeyword(RADIAL, radial);
        material.applyProperties(writer);
        return this;
    }

    private void write(CgMaterialValues b) {
        for (int k = 0; k < STOPS_PER_DRAW; k++) {
            // Past this window's last stop the shader stops reading; repeat it rather than leave the last window's.
            int at = Math.min(first + k, end);
            int argb = colors[at];
            float a = ((argb >>> 24) & 0xFF) / 255f;
            b.vec4(COLOR_PROPERTIES[k], ((argb >>> 16) & 0xFF) / 255f * a, ((argb >>> 8) & 0xFF) / 255f * a,
                    (argb & 0xFF) / 255f * a, a);
            windowPositions[k] = positions[at];
        }
        b.vec4("_Positions0", windowPositions[0], windowPositions[1], windowPositions[2], windowPositions[3]);
        b.vec4("_Positions1", windowPositions[4], windowPositions[5], windowPositions[6], windowPositions[7]);
        b.set1f("_Count", end - first + 1);
        // A middle window owns [its first stop, its last): its neighbours own the rest, and the ends run on forever.
        b.vec2("_Window", first == 0 ? -1f : positions[first], end == count - 1 ? 2f : positions[end]);
        if (radial) {
            b.vec2("_Centre", centreX, centreY);
            // A zero radius is a point: every fragment is past it and takes the last stop, as CSS draws it.
            b.vec2("_Radii", Math.max(radiusX, 1e-4f), Math.max(radiusY, 1e-4f));
        } else {
            axis(angle, width, height, axis);
            b.vec2("_Axis", axis[0], axis[1]);
        }
        b.vec4("_CornerRadiusX", radii[0], radii[2], radii[4], radii[6]);
        b.vec4("_CornerRadiusY", radii[1], radii[3], radii[5], radii[7]);
        b.vec2("_BoxSize", width, height);
    }
}
