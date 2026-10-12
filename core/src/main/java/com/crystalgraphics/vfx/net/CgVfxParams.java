package com.crystalgraphics.vfx.net;

import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecs;
import com.crystalgraphics.serialization.CgDynamicOps;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Named values a cue carries to the effect it plays: numbers, colours and text. On the client each number and colour is
 * set onto the effect's look parameter of the same name, so an effect needs no code to take them (Niagara's user
 * parameters). Server-safe.
 *
 * <pre>{@code
 * EXPLOSION.at(level, x, y, z).params(CgVfxParams.of().set("radius", 6f).color("ki", 0xFF40A0FF)).send();
 *
 * // a factory reading one by hand
 * int level = (int) cue.params().get("level", 1f);
 * }</pre>
 *
 * <ul>
 *   <li>A name the effect's look does not declare is carried and ignored.</li>
 *   <li>Not shared after a send: the send encodes it at the call, so a later change is not sent.</li>
 * </ul>
 */
public final class CgVfxParams {

    /** Names, then each value's width (1, or 4 for a colour), then the values flattened; text by name. */
    public static final CgCodec<CgVfxParams> CODEC = new CgCodec<CgVfxParams>() {
        @Override
        public <T> T encode(CgDynamicOps<T> ops, CgVfxParams p) {
            List<String> names = new ArrayList<>(p.values.keySet());
            List<Float> flat = new ArrayList<>();
            List<Integer> widths = new ArrayList<>();
            for (float[] v : p.values.values()) {
                widths.add(v.length);
                for (float f : v) flat.add(f);
            }
            return CgCodecs.map(ops)
                    .optionalList("n", CgCodecs.STRING, names)
                    .optionalList("w", CgCodecs.INT, widths)
                    .optionalList("v", CgCodecs.FLOAT, flat)
                    .optionalList("tn", CgCodecs.STRING, new ArrayList<>(p.texts.keySet()))
                    .optionalList("tv", CgCodecs.STRING, new ArrayList<>(p.texts.values()))
                    .build();
        }

        @Override
        public <T> CgVfxParams decode(CgDynamicOps<T> ops, T input) {
            CgCodecs.MapCodecReader<T> in = CgCodecs.read(ops, input);
            List<String> names = in.optionalList("n", CgCodecs.STRING);
            List<Integer> widths = in.optionalList("w", CgCodecs.INT);
            List<Float> flat = in.optionalList("v", CgCodecs.FLOAT);
            CgVfxParams p = new CgVfxParams();
            for (int i = 0, at = 0; i < names.size() && i < widths.size(); i++) {
                int width = widths.get(i);
                if (width < 1 || at + width > flat.size()) break;
                float[] v = new float[width];
                for (int k = 0; k < width; k++) v[k] = flat.get(at++);
                p.values.put(names.get(i), v);
            }
            List<String> textNames = in.optionalList("tn", CgCodecs.STRING);
            List<String> texts = in.optionalList("tv", CgCodecs.STRING);
            for (int i = 0; i < textNames.size() && i < texts.size(); i++) p.texts.put(textNames.get(i), texts.get(i));
            return p;
        }
    };

    private final Map<String, float[]> values = new LinkedHashMap<>();
    private final Map<String, String> texts = new LinkedHashMap<>();

    public static CgVfxParams of() {
        return new CgVfxParams();
    }

    /** A copy to change, leaving this one as it is. */
    public CgVfxParams copy() {
        CgVfxParams out = new CgVfxParams();
        values.forEach((k, v) -> out.values.put(k, v.clone()));
        out.texts.putAll(texts);
        return out;
    }

    public CgVfxParams set(String name, float value) {
        values.put(name, new float[]{value});
        return this;
    }

    public CgVfxParams set(String name, float r, float g, float b, float a) {
        values.put(name, new float[]{r, g, b, a});
        return this;
    }

    /** A colour from {@code 0xAARRGGBB}. */
    public CgVfxParams color(String name, int argb) {
        return set(name, (argb >>> 16 & 0xFF) / 255f, (argb >>> 8 & 0xFF) / 255f, (argb & 0xFF) / 255f,
                (argb >>> 24) / 255f);
    }

    public CgVfxParams set(String name, String value) {
        texts.put(name, value);
        return this;
    }

    /** A number, or the first component of a colour; {@code fallback} when absent. */
    public float get(String name, float fallback) {
        float[] v = values.get(name);
        return v == null ? fallback : v[0];
    }

    /** A value's components (one for a number, four for a colour), or null. Not a copy: read it, never write it. */
    public float[] components(String name) {
        return values.get(name);
    }

    public String text(String name, String fallback) {
        String t = texts.get(name);
        return t == null ? fallback : t;
    }

    /** The names of its numbers and colours. */
    public Set<String> names() {
        return Collections.unmodifiableSet(values.keySet());
    }

    public boolean isEmpty() {
        return values.isEmpty() && texts.isEmpty();
    }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder("{");
        values.forEach((k, v) -> out.append(out.length() > 1 ? ", " : "").append(k).append('=')
                .append(v.length == 1 ? Float.toString(v[0]) : Arrays.toString(v)));
        texts.forEach((k, v) -> out.append(out.length() > 1 ? ", " : "").append(k).append("='").append(v).append('\''));
        return out.append('}').toString();
    }
}
