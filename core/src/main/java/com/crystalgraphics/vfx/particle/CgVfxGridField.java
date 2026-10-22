package com.crystalgraphics.vfx.particle;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.object.value.CgQuantity;
import com.crystalgraphics.object.value.CgUnits;
import com.crystalgraphics.util.io.CgIO;

/**
 * A {@code fields/} asset of kind {@code grid}, compiled: Unreal's {@code VectorFieldStatic}, its vectors in the host's
 * units and axes over its box, which a {@code vector_field} module places and scales. Its data is Unreal's FGA text:
 * the grid's size, its box's two corners, then a vector a texel, x fastest.
 *
 * <pre>{@code
 * // fields/swirl.yaml: kind: grid, size: [16, 16, 16], bounds: [[-50, -50, 0], [50, 50, 100]], data: { path: mymod:fields/swirl.fga }
 * CgVfxGridField grid = (CgVfxGridField) deps.definition(CgFieldKinds.FIELD, "mymod:swirl");
 * new CgVfxModule.VectorField(grid.field(), CgBox.of(grid.half()[0], grid.half()[1], grid.half()[2]), grid.intensity(), 1f);
 * }</pre>
 *
 * <ul>
 *   <li>Its axes move through the asset's units as its vectors do: a field of Unreal's is turned onto the host's y up.</li>
 *   <li>A {@code graph} field compiles to its resolved asset, as a kind with no compiler does.</li>
 * </ul>
 *
 * @param field     its vectors, not tiling: a module tiles it
 * @param centre    its box's centre, in blocks
 * @param half      its box's half extents
 * @param intensity what its vectors are scaled by ({@code Intensity})
 */
public record CgVfxGridField(CgVfxField field, float[] centre, float[] half, float intensity) {

    /** The field kind's compiler. */
    public static final CgAssetCompiler<Object> COMPILER = (asset, deps) -> compile(asset, deps.units());

    private static Object compile(CgAssetData asset, CgUnits units) {
        Map<Object, Object> tree = asset.resolved();
        if (!"grid".equals(tree.get("kind"))) return asset;
        if (!(tree.get("data") instanceof Map<?, ?> data) || !(data.get("path") instanceof String path)) {
            throw new IllegalArgumentException(asset.id() + ": a grid field names its data, { path }");
        }
        String text = CgIO.loadSource(path);
        if (text == null) throw new IllegalArgumentException(asset.id() + ": its data " + path + " is missing");
        float[] numbers = numbers(asset.id(), text);
        int[] size = {(int) numbers[0], (int) numbers[1], (int) numbers[2]};
        if (numbers.length != 9 + 3 * size[0] * size[1] * size[2]) {
            throw new IllegalArgumentException(asset.id() + ": " + path + " holds " + (numbers.length - 9) + " numbers for a "
                    + size[0] + "x" + size[1] + "x" + size[2] + " grid");
        }
        List<?> bounds = tree.get("bounds") instanceof List<?> b ? b
                : List.of(List.of(numbers[3], numbers[4], numbers[5]), List.of(numbers[6], numbers[7], numbers[8]));
        float[] lo = host(units, bounds.get(0)), hi = host(units, bounds.get(1));
        float[] centre = new float[3], half = new float[3];
        for (int a = 0; a < 3; a++) {
            centre[a] = (lo[a] + hi[a]) / 2f;
            half[a] = Math.abs(hi[a] - lo[a]) / 2f;
        }
        int[] axes = units.axes();
        int[] hostSize = new int[3];
        for (int a = 0; a < 3; a++) hostSize[a] = size[Math.abs(axes[a]) - 1];
        int[] at = new int[3];
        CgVfxField field = CgVfxField.of(hostSize[0], hostSize[1], hostSize[2], false, (x, y, z, out) -> {
            int[] h = {x, y, z};
            for (int a = 0; a < 3; a++) {
                int from = Math.abs(axes[a]) - 1;
                at[from] = axes[a] > 0 ? h[a] : size[from] - 1 - h[a];
            }
            int i = 9 + 3 * ((at[2] * size[1] + at[1]) * size[0] + at[0]);
            List<?> v = (List<?>) units.toHost(List.of(numbers[i], numbers[i + 1], numbers[i + 2]), CgQuantity.SPEED);
            for (int c = 0; c < 3; c++) out[c] = ((Number) v.get(c)).floatValue();
        });
        float intensity = tree.get("intensity") instanceof Number n ? n.floatValue() : 1f;
        return new CgVfxGridField(field, centre, half, intensity);
    }

    private static float[] host(CgUnits units, Object corner) {
        List<?> v = (List<?>) units.toHost(corner, CgQuantity.POSITION);
        return new float[]{((Number) v.get(0)).floatValue(), ((Number) v.get(1)).floatValue(), ((Number) v.get(2)).floatValue()};
    }

    /** FGA's comma-separated numbers. */
    private static float[] numbers(String id, String text) {
        List<Float> out = new ArrayList<>();
        for (String s : text.split("[,\\s]+")) {
            if (s.isEmpty()) continue;
            try {
                out.add(Float.parseFloat(s));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(id + ": its data holds '" + s + "', not a number");
            }
        }
        float[] a = new float[out.size()];
        for (int i = 0; i < a.length; i++) a[i] = out.get(i);
        if (a.length < 9) throw new IllegalArgumentException(id + ": its data has no FGA header");
        return a;
    }
}
