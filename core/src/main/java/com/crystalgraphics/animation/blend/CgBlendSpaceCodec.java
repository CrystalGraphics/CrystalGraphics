package com.crystalgraphics.animation.blend;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.asset.kind.CgBodyKinds;
import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.asset.tree.CgAssetException;

/**
 * A {@code blend_spaces/} file and a {@link CgBlendSpace}, each way: {@link #compile} reads a resolved asset into a
 * {@link CgBlendSpaceDefinition}; {@link #toTree} writes a blend space so that compiling and binding it gives the same.
 *
 * <pre>{@code
 * # blend_spaces/mymod/hero_run.yaml
 * _v: 1
 * axes: [ { name: speed, min: 0, max: 8 } ]
 * samples:
 *   - { clip: mymod:models/hero.glb/idle, at: [0] }
 *   - { clip: mymod:models/hero.glb/jog, at: [4] }
 *   - { clip: mymod:models/hero.glb/sprint, at: [8] }
 * weight_speed: 5
 * }</pre>
 *
 * <ul>
 *   <li>{@code at} has one value per axis.</li>
 *   <li>{@code grid} is the editor's cooked grid: per grid point, index {@code y·(nx+1)+x}, its {@code [sample, weight]}
 *       pairs. Given, it is played as is; else the grid or triangulation is baked at load.</li>
 *   <li>{@code sync: true} ({@code bShouldMatchSyncPhases}) is not played and fails at load.</li>
 * </ul>
 */
public final class CgBlendSpaceCodec {

    /** The kind's compiler: the file checked by a bind against a stand-in clip. */
    public static final CgAssetCompiler<CgBlendSpaceDefinition> COMPILER = CgBlendSpaceCodec::compile;

    private static final CgAnimSequence STAND_IN = CgAnimSequence.builder("stand-in", 30, 2).build();

    private CgBlendSpaceCodec() {
    }

    public static void register() {
        CgAssetCompiler.register(CgBodyKinds.BLEND_SPACE, COMPILER);
    }

    /**
     * @throws CgAssetException a blend space that cannot play, naming the asset and the key
     */
    public static CgBlendSpaceDefinition compile(CgAssetData asset, CgAssetCompiler.Dependencies deps) {
        CgBlendSpaceDefinition d = new CgBlendSpaceDefinition(asset.id(), asset.resolved());
        d.bind(id -> STAND_IN);
        return d;
    }

    static CgBlendSpace build(String id, Map<?, ?> tree, Function<String, CgAnimSequence> clips) {
        if (Boolean.TRUE.equals(tree.get("sync"))) throw new CgAssetException(id + ": sync: true (matching sync phases) is not played");
        if (!(tree.get("axes") instanceof List<?> axes) || axes.isEmpty() || axes.size() > 2) {
            throw new CgAssetException(id + ": axes: one or two");
        }
        if (!(tree.get("samples") instanceof List<?> samples) || samples.isEmpty()) throw new CgAssetException(id + ": samples: none");
        CgBlendSpace.Builder b = new CgBlendSpace.Builder(id);
        try {
            for (int i = 0; i < axes.size(); i++) b.axis(axis(id + ": axes[" + i + "]", (Map<?, ?>) axes.get(i)));
            for (int i = 0; i < samples.size(); i++) {
                String at = id + ": samples[" + i + "]";
                Map<?, ?> s = (Map<?, ?>) samples.get(i);
                if (!(s.get("clip") instanceof String clipId)) throw new CgAssetException(at + ".clip: none");
                if (!(s.get("at") instanceof List<?> place) || place.size() != axes.size()) {
                    throw new CgAssetException(at + ".at: " + axes.size() + " values, one an axis");
                }
                CgAnimSequence clip = clips.apply(clipId);
                if (clip == null) throw new CgAssetException(at + ".clip: " + clipId + " names no clip of this mesh");
                b.sample(clip, clipId, number(at, place.get(0)), place.size() > 1 ? number(at, place.get(1)) : 0f,
                        s.get("rate_scale") == null ? 1f : number(at + ".rate_scale", s.get("rate_scale")));
            }
            if (tree.get("weight_speed") != null) b.weightSpeed(number(id + ": weight_speed", tree.get("weight_speed")));
            if (Boolean.FALSE.equals(tree.get("weight_ease"))) b.weightEase(false);
            if (Boolean.TRUE.equals(tree.get("grid_interpolation"))) b.gridInterpolation(true);
            if (Boolean.TRUE.equals(tree.get("legacy_length"))) b.legacyLength(true);
            if (tree.get("scale_axis") != null) {
                b.scaleAxis(CgBlendSpace.ScaleAxis.valueOf(String.valueOf(tree.get("scale_axis")).toUpperCase(Locale.ROOT)));
            }
            if (tree.get("grid") instanceof List<?> grid) b.cookedGrid(grid(id + ": grid", grid));
            return b.build();
        } catch (IllegalArgumentException e) {
            // The builder's own checks name the blend space
            throw new CgAssetException(e.getMessage());
        } catch (ClassCastException e) {
            throw new CgAssetException(id + ": a value of the wrong shape: " + e.getMessage());
        }
    }

    private static CgBlendSpace.Axis axis(String at, Map<?, ?> a) {
        CgBlendSpace.Smoothing smoothing = CgBlendSpace.Smoothing.NONE;
        if (a.get("smoothing") instanceof Map<?, ?> s) {
            smoothing = new CgBlendSpace.Smoothing(
                    s.get("time") == null ? 0f : number(at + ".smoothing.time", s.get("time")),
                    s.get("type") == null ? CgBlendFilter.Type.SPRING_DAMPER
                            : CgBlendFilter.Type.valueOf(String.valueOf(s.get("type")).toUpperCase(Locale.ROOT)),
                    s.get("damping") == null ? 1f : number(at + ".smoothing.damping", s.get("damping")),
                    s.get("max_speed") == null ? 0f : number(at + ".smoothing.max_speed", s.get("max_speed")));
        }
        return new CgBlendSpace.Axis(a.get("name") == null ? "" : String.valueOf(a.get("name")),
                a.get("min") == null ? 0f : number(at + ".min", a.get("min")),
                a.get("max") == null ? 100f : number(at + ".max", a.get("max")),
                a.get("grid") instanceof Number n ? n.intValue() : 4, smoothing, Boolean.TRUE.equals(a.get("wrap")));
    }

    private static float[][][] grid(String at, List<?> grid) {
        float[][][] out = new float[grid.size()][][];
        for (int p = 0; p < out.length; p++) {
            List<?> point = (List<?>) grid.get(p);
            out[p] = new float[point.size()][];
            for (int k = 0; k < point.size(); k++) {
                List<?> pair = (List<?>) point.get(k);
                if (pair.size() != 2) throw new CgAssetException(at + "[" + p + "][" + k + "]: [sample, weight]");
                out[p][k] = new float[] {number(at, pair.get(0)), number(at, pair.get(1))};
            }
        }
        return out;
    }

    private static float number(String at, Object o) {
        if (!(o instanceof Number n)) throw new CgAssetException(at + ": " + o + " is not a number");
        return n.floatValue();
    }

    /** {@code space}'s data form: compiled and bound to the same clips, it is the same blend space. */
    public static Map<String, Object> toTree(CgBlendSpace space) {
        Map<String, Object> tree = new LinkedHashMap<>();
        List<Object> axes = new ArrayList<>();
        for (CgBlendSpace.Axis a : space.axes()) {
            Map<String, Object> m = new LinkedHashMap<>();
            if (!a.name().isEmpty()) m.put("name", a.name());
            if (a.min() != 0f) m.put("min", (double) a.min());
            if (a.max() != 100f) m.put("max", (double) a.max());
            if (a.grid() != 4) m.put("grid", a.grid());
            CgBlendSpace.Smoothing s = a.smoothing();
            if (!s.equals(CgBlendSpace.Smoothing.NONE)) {
                Map<String, Object> sm = new LinkedHashMap<>();
                if (s.time() != 0f) sm.put("time", (double) s.time());
                if (s.type() != CgBlendFilter.Type.SPRING_DAMPER) sm.put("type", s.type().name().toLowerCase(Locale.ROOT));
                if (s.damping() != 1f) sm.put("damping", (double) s.damping());
                if (s.maxSpeed() != 0f) sm.put("max_speed", (double) s.maxSpeed());
                m.put("smoothing", sm);
            }
            if (a.wrap()) m.put("wrap", true);
            axes.add(m);
        }
        tree.put("axes", axes);
        List<Object> samples = new ArrayList<>();
        for (CgBlendSpace.Sample s : space.samples()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("clip", s.clipId());
            m.put("at", space.axes().size() == 1 ? List.of((double) s.x()) : List.of((double) s.x(), (double) s.y()));
            if (s.rateScale() != 1f) m.put("rate_scale", (double) s.rateScale());
            samples.add(m);
        }
        tree.put("samples", samples);
        if (space.weightSpeed() != 0f) tree.put("weight_speed", (double) space.weightSpeed());
        if (!space.weightEase()) tree.put("weight_ease", false);
        if (space.gridInterpolation() && space.cookedGrid() == null) tree.put("grid_interpolation", true);
        if (space.legacyLength()) tree.put("legacy_length", true);
        if (space.scaleAxis() != CgBlendSpace.ScaleAxis.NONE) tree.put("scale_axis", space.scaleAxis().name().toLowerCase(Locale.ROOT));
        if (space.cookedGrid() != null) {
            tree.put("grid_interpolation", true);
            List<Object> grid = new ArrayList<>();
            for (float[][] point : space.cookedGrid()) {
                List<Object> pairs = new ArrayList<>();
                for (float[] pair : point) pairs.add(List.of((int) pair[0], (double) pair[1]));
                grid.add(pairs);
            }
            tree.put("grid", grid);
        }
        return tree;
    }
}
