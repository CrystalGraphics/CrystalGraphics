package com.crystalgraphics.asset.curve;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.crystalgraphics.asset.kind.CgLookKinds;
import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.asset.load.CgPatchTable;
import com.crystalgraphics.asset.load.CgPatchable;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.object.CgPropertyPath;

/**
 * A curve asset compiled ({@code curves/}: Unreal's {@code UCurveFloat}, {@code UCurveVector},
 * {@code UCurveLinearColor}): one, three or four {@link CgRichCurve}s by its type, and its patch table, one entry per
 * key value. The reference compiler for every kind's.
 *
 * <pre>{@code
 * CgCurveDefinition.register();                                   // at init: the curve kind compiles to these
 * CgCurveDefinition fade = (CgCurveDefinition) handle.definition();
 * float a = fade.eval(0, age);                                    // channel 0 at a time
 * fade.eval(age, rgba);                                           // every channel into rgba
 * }</pre>
 *
 * <ul>
 *   <li>Compiles a curve given by id ({@code curves: [mymod:fade]}) only through its own asset: a curve asset's
 *       channels are inline.</li>
 *   <li>{@code adjust} (a colour curve's hue, saturation and brightness) is not applied yet.</li>
 * </ul>
 */
public final class CgCurveDefinition implements CgPatchable {

    /** The curve kind's compiler. */
    public static final CgAssetCompiler<CgCurveDefinition> COMPILER = (asset, deps) -> compile(asset);

    /** Its type: {@code float}, {@code vector} or {@code colour}. */
    public enum Type { FLOAT, VECTOR, COLOUR }

    private final String id;
    private final Type type;
    private final CgRichCurve[] curves;
    private final CgPatchTable patches;
    /** Per slot: the curve, then the key. */
    private final int[] slotCurve, slotKey;

    private CgCurveDefinition(String id, Type type, CgRichCurve[] curves) {
        this.id = id;
        this.type = type;
        this.curves = curves;
        int n = 0;
        for (CgRichCurve c : curves) n += c.size();
        slotCurve = new int[n];
        slotKey = new int[n];
        CgPatchTable.Builder b = CgPatchTable.builder();
        int slot = 0;
        for (int c = 0; c < curves.length; c++) {
            for (int k = 0; k < curves[c].size(); k++, slot++) {
                slotCurve[slot] = c;
                slotKey[slot] = k;
                b.add(CgPropertyPath.of(id).key("curves").index(c).key("keys").index(k).index(1), slot, CgPatchTable.Effect.DERIVED);
            }
        }
        patches = b.build();
    }

    /** Registers {@link #COMPILER} as the curve kind's. */
    public static void register() {
        CgAssetCompiler.register(CgLookKinds.CURVE_KIND, COMPILER);
    }

    /** {@code asset}'s definition: its resolved {@code type} and {@code curves}. */
    public static CgCurveDefinition compile(CgAssetData asset) {
        Map<Object, Object> tree = asset.resolved();
        Type type = Type.valueOf(String.valueOf(tree.get("type")).toUpperCase(Locale.ROOT));
        List<?> list = (List<?>) tree.get("curves");
        int want = type == Type.FLOAT ? 1 : type == Type.VECTOR ? 3 : 4;
        if (list == null || list.size() != want) {
            throw new IllegalArgumentException(asset.id() + ": a " + type.name().toLowerCase(Locale.ROOT)
                    + " curve holds " + want + " curves, not " + (list == null ? 0 : list.size()));
        }
        CgRichCurve[] curves = new CgRichCurve[want];
        for (int i = 0; i < want; i++) {
            Object c = list.get(i);
            if (c instanceof String ref) throw new IllegalArgumentException(asset.id() + ": curves[" + i + "] names " + ref + "; a curve asset's channels are inline");
            curves[i] = CgRichCurve.decode(c);
        }
        return new CgCurveDefinition(asset.id(), type, curves);
    }

    public String id() {
        return id;
    }

    public Type type() {
        return type;
    }

    /** How many channels: 1, 3 or 4. */
    public int channels() {
        return curves.length;
    }

    public CgRichCurve curve(int channel) {
        return curves[channel];
    }

    /** Channel {@code channel} at {@code time}. */
    public float eval(int channel, float time) {
        return curves[channel].eval(time);
    }

    /** Every channel at {@code time} into {@code out}. */
    public void eval(float time, float[] out) {
        for (int i = 0; i < curves.length; i++) out[i] = curves[i].eval(time);
    }

    @Override
    public CgPatchTable patches() {
        return patches;
    }

    @Override
    public void patch(CgPatchTable.Entry entry, double value) {
        curves[slotCurve[entry.slot()]].setValue(slotKey[entry.slot()], (float) value);
    }
}
