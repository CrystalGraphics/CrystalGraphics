package com.crystalgraphics.api.material;

import java.util.Arrays;

/**
 * One draw's row of a material: its row properties' values, a vec4 a slot, starting at the material's constants. What
 * a renderer or a track writes per draw ({@link CgMaterialRoute#ROW}), so draws of one material differing in these
 * values stay one material and batch. The renderer copies it into the frame's rows ({@code CgPropertyRows}).
 *
 * <pre>{@code
 * CgMaterialRow row = new CgMaterialRow(definition);              // once a draw
 * int glow = definition.requireRow("_Glow", "track glow");        // at load: refused on a non-row property
 * row.set(glow, 2f, 1f, 0.5f, 1f);                                // each tick: this draw alone moves
 * row.copyTo(frameRows, at);                                      // where the draw's row starts, in vec4s
 * }</pre>
 *
 * <ul>
 *   <li>Writes by slot allocate nothing; resolve the slot once with {@code requireRow}.</li>
 *   <li>A slot past an array property's first is its next element.</li>
 * </ul>
 */
public final class CgMaterialRow {

    private final CgMaterialDefinition definition;
    private final float[] values;

    /** A row of {@code definition}, each slot its constant where it sets one, else zero. */
    public CgMaterialRow(CgMaterialDefinition definition) {
        this.definition = definition;
        this.values = new float[definition.rows().vec4s() * 4];
        for (String property : definition.rows().properties()) {
            float[] c = definition.constants().get(property);
            if (c != null) System.arraycopy(c, 0, values, definition.rows().slot(property) * 4, c.length);
        }
    }

    public CgMaterialDefinition definition() {
        return definition;
    }

    /** Sets the vec4 at {@code slot}. */
    public CgMaterialRow set(int slot, float x, float y, float z, float w) {
        int at = slot * 4;
        values[at] = x;
        values[at + 1] = y;
        values[at + 2] = z;
        values[at + 3] = w;
        return this;
    }

    /** Sets a number at {@code slot}: its vec4's x. */
    public CgMaterialRow set(int slot, float value) {
        values[slot * 4] = value;
        return this;
    }

    /** The vec4 at {@code slot}, component {@code c}. */
    public float get(int slot, int c) {
        return values[slot * 4 + c];
    }

    /** Copies the row into {@code rows} from vec4 {@code atVec4}. */
    public void copyTo(float[] rows, int atVec4) {
        System.arraycopy(values, 0, rows, atVec4 * 4, values.length);
    }

    /** Its length in vec4s. */
    public int vec4s() {
        return values.length / 4;
    }

    @Override
    public String toString() {
        return definition.id() + " row " + Arrays.toString(values);
    }
}
