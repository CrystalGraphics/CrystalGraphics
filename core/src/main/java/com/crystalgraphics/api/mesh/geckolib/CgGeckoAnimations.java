package com.crystalgraphics.api.mesh.geckolib;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.asset.curve.CgRichCurve;
import com.crystalgraphics.serialization.CgCodecs;
import com.crystalgraphics.serialization.format.CgFiles;

/**
 * A GeckoLib {@code .animation.json} converted into keyed sequences for one model. Ported from GeckoLib 4.8's
 * {@code BakedAnimationsAdapter} and {@code EasingType} (MIT, branch {@code 1.20.1}): its keyframes, their easings and
 * arguments, Blockbench's {@code pre} and {@code post}, and the signs and units its processor applies, folded with
 * each bone's rest into the curves' values so a sample is the bone's local transform.
 */
final class CgGeckoAnimations {

    /** How far before its time GeckoLib puts a Blockbench key's {@code pre}, seconds. */
    private static final double PRE = 0.001;

    private CgGeckoAnimations() {
    }

    /** One key as GeckoLib reads it: its time in seconds, three values, and the easing of the segment arriving at it. */
    private record Key(double time, Object[] values, String easing, Double arg) {
    }

    static List<CgGeckoAnimation> read(CgGeckoModel model, String text, String file) {
        Map<?, ?> root = CgGeckoModel.map(CgFiles.JSON.fromText(CgCodecs.PLAIN, text), file);
        Object animations = root.get("animations");
        if (animations == null) return List.of();
        List<CgGeckoAnimation> out = new ArrayList<>();
        for (Map.Entry<?, ?> e : CgGeckoModel.map(animations, file + ": animations").entrySet()) {
            String name = String.valueOf(e.getKey());
            out.add(animation(model, name, CgGeckoModel.map(e.getValue(), file + ": " + name), file + ": animation " + name));
        }
        return Collections.unmodifiableList(out);
    }

    private static CgGeckoAnimation animation(CgGeckoModel model, String name, Map<?, ?> a, String where) {
        CgGeckoAnimation.Loop loop = loop(a.get("loop"), where);
        Map<?, ?> bones = a.get("bones") == null ? Map.of() : CgGeckoModel.map(a.get("bones"), where + ": bones");
        List<Object[]> tracks = new ArrayList<>();
        double last = 0;
        for (Map.Entry<?, ?> e : bones.entrySet()) {
            String boneName = String.valueOf(e.getKey());
            int bone = model.bone(boneName);
            Map<?, ?> channels = CgGeckoModel.map(e.getValue(), where + ": bone " + boneName);
            String at = where + ": bone " + boneName;
            List<Key> position = keys(channels.get("position"), at + ": position");
            List<Key> rotation = keys(channels.get("rotation"), at + ": rotation");
            List<Key> scale = keys(channels.get("scale"), at + ": scale");
            for (List<Key> k : List.of(position, rotation, scale)) if (!k.isEmpty()) last = Math.max(last, k.get(k.size() - 1).time);
            // GeckoLib animates only the bones its model has (AnimationProcessor's bone registry)
            if (bone < 0) continue;
            int parent = model.parent(bone);
            CgRichCurve[] c = new CgRichCurve[9];
            for (int axis = 0; axis < 3; axis++) {
                float rest = model.pivot(bone, axis) - (parent < 0 ? 0f : model.pivot(parent, axis));
                // translateMatrixToBone: (-x, y, z) / 16, on top of the bone's place from its parent
                c[axis] = curve(position, axis, axis == 0 ? -1 / 16.0 : 1 / 16.0, rest, at + ": position");
                // the adapter's (-x, -y, z) in radians, added to the rest angles (AnimationProcessor)
                c[3 + axis] = curve(rotation, axis, (axis == 2 ? 1 : -1) * Math.PI / 180, model.restEuler(bone, axis), at + ": rotation");
                c[6 + axis] = curve(scale, axis, 1, scale.isEmpty() ? 1 : 0, at + ": scale");
            }
            tracks.add(new Object[]{boneName, c});
        }
        double length = a.get("animation_length") instanceof Number n ? n.doubleValue() : last;
        CgAnimSequence.KeyedBuilder sequence = CgAnimSequence.keyed(name, (float) length);
        for (Object[] t : tracks) {
            CgRichCurve[] c = (CgRichCurve[]) t[1];
            sequence.track((String) t[0], c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8]);
        }
        return new CgGeckoAnimation(name, sequence.build(), loop);
    }

    /** {@code Animation.LoopType.fromJson}; a name it does not register fails rather than playing once. */
    private static CgGeckoAnimation.Loop loop(Object loop, String where) {
        if (loop == null) return CgGeckoAnimation.Loop.PLAY_ONCE;
        if (loop instanceof Boolean b) return b ? CgGeckoAnimation.Loop.LOOP : CgGeckoAnimation.Loop.PLAY_ONCE;
        switch (String.valueOf(loop)) {
            case "true": case "loop": return CgGeckoAnimation.Loop.LOOP;
            case "false": case "play_once": return CgGeckoAnimation.Loop.PLAY_ONCE;
            case "hold_on_last_frame": return CgGeckoAnimation.Loop.HOLD_ON_LAST_FRAME;
            default: throw new IllegalArgumentException(where + ": loop " + loop + " is not GeckoLib's");
        }
    }

    /** {@code getKeyframes}: a channel's keys in file order, each with the easing of the segment arriving at it. */
    private static List<Key> keys(Object element, String where) {
        List<Key> out = new ArrayList<>();
        if (element == null) return out;
        if (element instanceof Number || element instanceof String) {
            out.add(new Key(0, new Object[]{element, element, element}, null, null));
            return out;
        }
        if (element instanceof List<?> vector) {
            out.add(new Key(0, vector(vector, where), null, null));
            return out;
        }
        Map<?, ?> obj = CgGeckoModel.map(element, where);
        if (obj.containsKey("vector")) {
            out.add(key(0, obj, where));
            return out;
        }
        for (Map.Entry<?, ?> e : obj.entrySet()) {
            String stamp = String.valueOf(e.getKey());
            double time = timestamp(stamp);
            if (time == 0 && !out.isEmpty()) throw new IllegalArgumentException(where + ": two keys at 0 (" + stamp + ")");
            String at = where + " at " + stamp;
            if (e.getValue() instanceof Map<?, ?> k && !k.containsKey("vector")) {
                bedrock(time, k, out, at);
            } else if (e.getValue() instanceof Map<?, ?> k) {
                out.add(key(time, k, at));
            } else {
                out.add(new Key(time, vector(CgGeckoModel.list(e.getValue(), at), at), null, null));
            }
        }
        // In time order, as Blockbench keeps them and RPG-Core's own reader plays them; GeckoLib walks a file's
        // order, which plays keys written out of order with negative lengths. A stable sort keeps a pre before its post.
        out.sort((x, y) -> Double.compare(x.time, y.time));
        return out;
    }

    /** A key written as {@code { vector, easing, easingArgs }}. */
    private static Key key(double time, Map<?, ?> k, String where) {
        Object[] values = vector(CgGeckoModel.list(k.get("vector"), where + ": vector"), where);
        String easing = k.get("easing") instanceof String s ? s : null;
        Double arg = null;
        if (k.get("easingArgs") instanceof List<?> args && !args.isEmpty()) arg = CgGeckoModel.num(args.get(0), where + ": easingArgs");
        return new Key(time, values, easing, arg);
    }

    /** {@code addBedrockKeyframes}: a {@code pre} just before its time, arrived at linearly, then its {@code post} eased by {@code lerp_mode}. */
    private static void bedrock(double time, Map<?, ?> k, List<Key> out, String where) {
        boolean added = false;
        if (k.containsKey("pre")) {
            added = true;
            out.add(new Key(time == 0 ? time : time - PRE, extract(k.get("pre"), where + ": pre"), null, null));
        }
        if (k.containsKey("post")) {
            String lerp = k.get("lerp_mode") instanceof String s ? s : null;
            out.add(new Key(time, extract(k.get("post"), where + ": post"), lerp, null));
            return;
        }
        if (!added) throw new IllegalArgumentException(where + ": a key with no vector, pre or post");
    }

    /** {@code extractBedrockKeyframe}: an array, or an object's {@code vector}, {@code pre} or {@code post}. */
    private static Object[] extract(Object value, String where) {
        if (value instanceof List<?> l) return vector(l, where);
        Map<?, ?> obj = CgGeckoModel.map(value, where);
        Object inner = obj.containsKey("vector") ? obj.get("vector") : obj.containsKey("pre") ? obj.get("pre") : obj.get("post");
        return vector(CgGeckoModel.list(inner, where), where);
    }

    private static Object[] vector(List<?> v, String where) {
        if (v.size() < 3) throw new IllegalArgumentException(where + ": three values, not " + v);
        return new Object[]{v.get(0), v.get(1), v.get(2)};
    }

    /** {@code readTimestamp}: a number, else 0. */
    private static double timestamp(String stamp) {
        try {
            return Double.parseDouble(stamp);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * One axis as a curve, each value {@code value * scale + offset}: each key's interpolation the easing of the segment
     * after it, which GeckoLib keeps on the key that segment arrives at. No keys: the offset, held.
     */
    private static CgRichCurve curve(List<Key> keys, int axis, double scale, double offset, String where) {
        List<Object> out = new ArrayList<>();
        if (keys.isEmpty()) {
            out.add(List.of(0.0, offset, "linear"));
        } else {
            for (int i = 0; i < keys.size(); i++) {
                Key k = keys.get(i);
                double v = value(k.values[axis], where + " at " + k.time) * scale + offset;
                List<Object> key = new ArrayList<>(List.of(k.time, v));
                if (i + 1 < keys.size()) {
                    Key next = keys.get(i + 1);
                    String interp = interp(next.easing, where + " at " + next.time);
                    key.add(interp);
                    if (next.arg != null && takesArg(interp)) key.add(next.arg);
                } else {
                    key.add("linear");
                }
                out.add(key);
            }
        }
        return CgRichCurve.decode(Map.of("keys", out));
    }

    /** A value GeckoLib parses to a constant: a number, or a string that is one. Molang beyond that fails. */
    private static double value(Object v, String where) {
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(where + ": molang '" + s + "' is not played, only a constant");
            }
        }
        throw new IllegalArgumentException(where + ": a value, not " + v);
    }

    /**
     * GeckoLib's easing name to the format's: by what it computes, so {@code easeInQuint}, which is {@code pow(4)},
     * is {@code quart_in}, and its expo is {@code expo_unclamped}. Absent is linear; an unknown name fails.
     */
    private static String interp(String easing, String where) {
        if (easing == null) return "linear";
        String e = easing.toLowerCase(Locale.ROOT);
        switch (e) {
            case "linear": case "none": return "linear";
            case "step": return "steps";
            case "catmullrom": return "catmull_rom";
            case "easeinquint": return "quart_in";
            default:
        }
        String mode = e.startsWith("easeinout") ? "_in_out" : e.startsWith("easein") ? "_in" : e.startsWith("easeout") ? "_out" : null;
        if (mode != null) {
            String family = e.substring(mode.equals("_in_out") ? 9 : mode.equals("_in") ? 6 : 7);
            switch (family) {
                case "sine": return "sin" + mode;
                case "expo": return "expo_unclamped" + mode;
                case "quad": case "cubic": case "quart": case "quint": case "circ": case "back": case "elastic": case "bounce":
                    return family + mode;
                default:
            }
        }
        throw new IllegalArgumentException(where + ": easing '" + easing + "' is not GeckoLib's");
    }

    /** The easings whose first argument GeckoLib reads. */
    private static boolean takesArg(String interp) {
        return interp.equals("steps") || interp.startsWith("back_") || interp.startsWith("elastic_") || interp.startsWith("bounce_");
    }
}
