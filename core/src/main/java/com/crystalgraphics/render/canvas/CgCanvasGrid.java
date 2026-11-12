package com.crystalgraphics.render.canvas;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialValues;

import com.crystalgraphics.api.material.CgMaterialInstance;
import com.crystalgraphics.api.material.CgMaterialInstances;

/**
 * A ruled grid over a box in one draw, antialiased at any scale and any cell size: Ben Golus's "Pristine Grid"
 * (`canvas/grid.shader`), whose lines never thin below a pixel but fade, and dissolve to the grid's density once a
 * cell is under one. Set it up, then draw a quad over the box.
 *
 * <pre>{@code
 * CgCanvasGrid ruling = new CgCanvasGrid(CgCanvasMaterials.SCREEN);   // once: it owns its material
 *
 * ruling.size(w, h).cell(16f, 16f).line(1f).color(0x30FFFFFF).apply();
 * canvas.quad(ruling.material()).at(x, y).size(w, h).submit();
 *
 * ruling.size(w, h).cell(8f, 8f).line(1f).color(argb).radii(6, 6, 6, 6, 6, 6, 6, 6).apply();   // rounded
 * }</pre>
 *
 * <ul>
 *   <li>Cells and lines are in the units the quad is drawn in; a cell under half a unit is half a unit.</li>
 *   <li>{@link #apply} writes the material's values; a quad captures them as it is submitted. One instance per drawer.</li>
 *   <li>Lines are straight ARGB, tinted by the quad's colour; premultiplied out.</li>
 * </ul>
 */
public final class CgCanvasGrid {

    static final String WITH_MASK = "WITH_MASK";

    /** An instance with its mask and one without. */
    private final CgMaterialInstances instances;
    private CgMaterialInstance current;

    private float width, height, cellWidth = 16f, cellHeight = 16f, lineWidth = 1f;
    private int argb = 0xFFFFFFFF;
    private final float[] radii = new float[8];

    public CgCanvasGrid(CgCanvasMaterials materials) {
        this.instances = new CgMaterialInstances(materials.grid());
        this.current = instances.of(instances.material());
    }

    /** What its quad draws under: the instance {@link #apply} wrote. */
    public CgMaterialInstance material() {
        return current;
    }

    public CgCanvasGrid size(float width, float height) {
        this.width = width;
        this.height = height;
        return this;
    }

    public CgCanvasGrid cell(float width, float height) {
        this.cellWidth = Math.max(0.5f, width);
        this.cellHeight = Math.max(0.5f, height);
        return this;
    }

    public CgCanvasGrid line(float width) {
        this.lineWidth = Math.max(0f, width);
        return this;
    }

    public CgCanvasGrid color(int argb) {
        this.argb = argb;
        return this;
    }

    /** Corner radii per corner and axis, CSS order (TL, TR, BR, BL); all zero draws the box square. */
    public CgCanvasGrid radii(float rxTL, float ryTL, float rxTR, float ryTR,
                              float rxBR, float ryBR, float rxBL, float ryBL) {
        radii[0] = rxTL; radii[1] = ryTL; radii[2] = rxTR; radii[3] = ryTR;
        radii[4] = rxBR; radii[5] = ryBR; radii[6] = rxBL; radii[7] = ryBL;
        return this;
    }

    /** Writes it into its material, for the next quad. */
    public CgCanvasGrid apply() {
        boolean rounded = false;
        for (float r : radii) rounded |= r > 0f;
        current = instances.of(instances.material().keyword(WITH_MASK, rounded));
        write(current);
        return this;
    }

    private void write(CgMaterialValues b) {
        float a = ((argb >>> 24) & 0xFF) / 255f;
        b.vec4("_Color", ((argb >>> 16) & 0xFF) / 255f * a, ((argb >>> 8) & 0xFF) / 255f * a, (argb & 0xFF) / 255f * a, a);
        b.vec2("_Cell", cellWidth, cellHeight);
        b.vec2("_LineWidth", lineWidth, lineWidth);
        b.vec2("_BoxSize", width, height);
        b.vec4("_CornerRadiusX", radii[0], radii[2], radii[4], radii[6]);
        b.vec4("_CornerRadiusY", radii[1], radii[3], radii[5], radii[7]);
    }
}
