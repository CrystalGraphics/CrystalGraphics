package com.crystalgraphics.sequence.clip;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;

import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecException;
import com.crystalgraphics.serialization.CgCodecs;
import com.crystalgraphics.serialization.format.CgFiles;

/**
 * A GeckoLib clip's markers as its notify sequence: each animation of a Bedrock {@code .animation.json} becomes a
 * {@code sequence} under the clip's id, which the asset registry registers as it scans and the ordinary sequence
 * compiler compiles. The file itself stays untouched, so GeckoLib and Blockbench still read it.
 *
 * <pre>{@code
 * // assets/rpgcore/animations/core/styles/default/melee.animation.json, animation melee_6:
 * //   "timeline":         { "0.1": "begin=charge;", "0.25": "notify=swing_trail;", "0.4": "end=charge;hit" }
 * //   "particle_effects": { "0.3": { "effect": "slash", "locator": "hand_r" } }
 * //   "sound_effects":    { "0.3": { "effect": "rpgcore:swing" } }
 * // is the sequence rpgcore:core/styles/default/melee/melee_6, which an animation section playing that clip takes as
 * // its notifies, and RPG-Core's own playback drives through CgClipNotifies.
 * }</pre>
 *
 * <table>
 *   <tr><th>Marker</th><th>In the sequence</th></tr>
 *   <tr><td>{@code notify=<sequence>;}</td><td>a {@code subsequence} section from the key, as long as the sequence</td></tr>
 *   <tr><td>{@code begin=<sequence>;} … {@code end=<sequence>;}</td><td>a looping {@code subsequence} over the span</td></tr>
 *   <tr><td>{@code particle_effects} {@code { effect, locator }}</td><td>a {@code system} spawnable at the caster's socket, activated at the key</td></tr>
 *   <tr><td>{@code sound_effects} {@code { effect }}</td><td>a {@code sound} section on the caster at the key</td></tr>
 *   <tr><td>any other instruction</td><td>an {@code rpg} event of that name, for the mod (RPG-Core's {@code hit})</td></tr>
 * </table>
 *
 * <ul>
 *   <li>An id with no namespace is in the clip file's.</li>
 *   <li>A {@code begin} pairs with the next {@code end} of the same id; an {@code end} with none open, a {@code begin}
 *       still open at the clip's end, or an id no asset holds fails, naming the file, the animation, the key's time
 *       and the id; so do a {@code notify=} sequence with no length, and a looping system on a particle key (Unreal
 *       spawns nothing for one: use {@code begin=} … {@code end=}).</li>
 *   <li>A key fires and forgets: when its clip stops, never killed, what a particle, sound or {@code notify=} key started
 *       plays out to its own end. A {@code begin}/{@code end} span ends with the clip.</li>
 *   <li>Its one role is {@code caster}: the role playing the clip, which the named sequences bind too.</li>
 * </ul>
 */
public final class CgClipMarkers {

    /** Where clip files sit in a namespace, and how each is named. */
    public static final String FOLDER = "animations/", SUFFIX = ".animation.json";
    /** Unreal's default tick resolution: a key a 24,000th of a second, Blockbench's times exactly at 24 frames. */
    static final int TICKS = 24000;

    /** What the translation asks of the assets a marker names. */
    public interface Known {
        /** Whether a {@code sequence} asset of {@code id} is registered. */
        boolean sequence(String id);

        /** Whether a {@code system} asset of {@code id} is registered. */
        boolean system(String id);

        /** Sequence {@code id}'s playback range in seconds; NaN where it has none (an open end). */
        double length(String id);
    }

    /** One animation of the file: its length and its marker channels, each keyed by time; the rest unread. */
    record Clip(float length, Map<String, Object> timeline, Map<String, Object> particles, Map<String, Object> sounds,
                Map<String, Object> bones) {
        static final CgCodec<Map<String, Object>> CHANNEL = CgCodecs.mapOf(CgCodecs.STRING, CgCodecs.PLAIN);
        static final CgCodec<Clip> CODEC = CgCodecs.record(
                CgCodecs.optionalField("animation_length", CgCodecs.FLOAT, Clip::length, Float.NaN),
                CgCodecs.optionalField("timeline", CHANNEL, Clip::timeline, Map.of()),
                CgCodecs.optionalField("particle_effects", CHANNEL, Clip::particles, Map.of()),
                CgCodecs.optionalField("sound_effects", CHANNEL, Clip::sounds, Map.of()),
                CgCodecs.optionalField("bones", CHANNEL, Clip::bones, Map.of()),
                Clip::new);
    }

    /** A Bedrock animation file: its animations by key, in file order. */
    record ClipFile(Map<String, Clip> animations) {
        static final CgCodec<ClipFile> CODEC = CgCodecs.record(
                CgCodecs.field("animations", CgCodecs.mapOf(CgCodecs.STRING, Clip.CODEC), ClipFile::animations),
                ClipFile::new);
    }

    private CgClipMarkers() {
    }

    /** Whether {@code rel}, a path under a namespace's folder, is a clip file. */
    public static boolean isClipFile(String rel) {
        return rel.startsWith(FOLDER) && rel.endsWith(SUFFIX);
    }

    /** The clip ids {@code json} holds, {@code rel} its path under the namespace: {@code <ns>:<path>/<key>} each. */
    public static List<String> ids(String namespace, String rel, String json) {
        String stem = rel.substring(FOLDER.length(), rel.length() - SUFFIX.length());
        List<String> ids = new ArrayList<>();
        for (String key : read(rel, json).animations().keySet()) ids.add(namespace + ":" + stem + "/" + key);
        return ids;
    }

    /** Clip {@code id}'s length in seconds, {@code json} its file's text: its {@code animation_length}, else its latest key. */
    public static double length(String id, String json) {
        Clip clip = read(id, json).animations().get(id.substring(id.lastIndexOf('/') + 1));
        return clip == null ? Double.NaN : Float.isNaN(clip.length()) ? latest(clip) : clip.length();
    }

    /**
     * The notify sequence of clip {@code id}, {@code json} its file's text and {@code file} what failures name.
     *
     * @throws CgAssetException naming the file, the animation, the key's time and the id, for an unknown id or an
     *                          unpaired {@code begin} or {@code end}
     */
    public static Map<String, Object> translate(String id, String file, String json, Known known) {
        int colon = id.indexOf(':'), slash = id.lastIndexOf('/');
        String key = id.substring(slash + 1);
        Clip clip = read(file, json).animations().get(key);
        if (clip == null) throw new CgAssetException(file + " holds no animation " + key);
        Translation t = new Translation(file, key, id.substring(0, colon), known);
        float length = Float.isNaN(clip.length()) ? latest(clip) : clip.length();
        t.length = ticks(length);
        for (Map.Entry<Float, Object> k : times(clip.timeline())) {
            for (Object e : entries(k.getValue())) {
                for (String instruction : String.valueOf(e).split(";")) t.instruction(instruction.trim(), k.getKey());
            }
        }
        for (Map.Entry<Float, Object> k : times(clip.particles())) {
            for (Object e : entries(k.getValue())) t.particle(effect(e), k.getKey());
        }
        for (Map.Entry<Float, Object> k : times(clip.sounds())) {
            for (Object e : entries(k.getValue())) t.sound(effect(e), k.getKey());
        }
        for (Map.Entry<String, Float> open : t.open.entrySet()) {
            throw t.fail(open.getValue(), open.getKey(), "begins and is still open at the clip's end, " + seconds(length));
        }
        return t.tree();
    }

    private static ClipFile read(String file, String json) {
        try {
            return CgFiles.JSON.fromText(ClipFile.CODEC, json);
        } catch (CgCodecException e) {
            throw new CgAssetException(file + " is not a Bedrock animation file: " + e.getMessage());
        }
    }

    /** With no {@code animation_length}, its latest key: a marker's or a bone channel's. */
    private static float latest(Clip clip) {
        float last = 0f;
        for (Map<String, Object> channel : List.of(clip.timeline(), clip.particles(), clip.sounds())) {
            for (Map.Entry<Float, Object> k : times(channel)) last = Math.max(last, k.getKey());
        }
        for (Object bone : clip.bones().values()) {
            if (!(bone instanceof Map<?, ?> b)) continue;
            for (Object channel : b.values()) {
                if (!(channel instanceof Map<?, ?> keys)) continue;
                for (Object time : keys.keySet()) {
                    Float t = time(String.valueOf(time));
                    if (t != null) last = Math.max(last, t);
                }
            }
        }
        return last;
    }

    /** {@code channel}'s keys by time, earliest first; a key that is no time is not a marker. */
    private static List<Map.Entry<Float, Object>> times(Map<String, Object> channel) {
        List<Map.Entry<Float, Object>> out = new ArrayList<>();
        for (Map.Entry<String, Object> k : channel.entrySet()) {
            Float t = time(k.getKey());
            if (t != null) out.add(Map.entry(t, k.getValue()));
        }
        out.sort(Map.Entry.comparingByKey());
        return out;
    }

    @Nullable
    private static Float time(String key) {
        try {
            return Float.parseFloat(key.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** A key's value: one entry, or a list of them, as Bedrock writes either. */
    private static List<?> entries(@Nullable Object value) {
        return value instanceof List<?> l ? l : value == null ? List.of() : List.of(value);
    }

    /** An effect key's {@code effect} and {@code locator}. */
    private static Map<?, ?> effect(Object e) {
        return e instanceof Map<?, ?> m ? m : Map.of();
    }

    private static int ticks(float seconds) {
        return Math.round(seconds * TICKS);
    }

    private static String seconds(float s) {
        return String.format(Locale.ROOT, "%.4gs", s);
    }

    /** One animation's translation: its tracks and spawnables, its open spans. */
    private static final class Translation {
        final String file, animation, namespace;
        final Known known;
        int length;
        final List<Object> tracks = new ArrayList<>(), spawnables = new ArrayList<>(), events = new ArrayList<>();
        /** An open {@code begin} by its id: when it began. */
        final Map<String, Float> open = new LinkedHashMap<>();
        int subsequences, particles, sounds;

        Translation(String file, String animation, String namespace, Known known) {
            this.file = file;
            this.animation = animation;
            this.namespace = namespace;
            this.known = known;
        }

        void instruction(String instruction, float at) {
            if (instruction.isEmpty()) return;
            int eq = instruction.indexOf('=');
            String name = eq < 0 ? instruction : instruction.substring(0, eq).trim();
            String value = eq < 0 ? null : instruction.substring(eq + 1).trim();
            switch (name) {
                case "notify" -> {
                    // As long as the sequence, as Sequencer sizes a new subsequence section to its playback range
                    String id = sequence(value, at);
                    double seconds = known.length(id);
                    if (!(seconds > 0)) throw fail(at, id, "has no length to play for");
                    subsequence(id, at, ticks(at) + (int) Math.round(seconds * TICKS), false);
                }
                case "begin" -> {
                    String id = sequence(value, at);
                    if (open.containsKey(id)) throw fail(at, id, "begins again while its begin at " + seconds(open.get(id)) + " is open");
                    open.put(id, at);
                }
                case "end" -> {
                    String id = qualify(value, at);
                    Float began = open.remove(id);
                    if (began == null) throw fail(at, id, "ends with no begin open");
                    subsequence(id, began, ticks(at), true);
                }
                default -> events.add(List.of(ticks(at), instruction));
            }
        }

        void particle(Map<?, ?> e, float at) {
            String system = qualify(e.get("effect") == null ? null : String.valueOf(e.get("effect")), at);
            if (!known.system(system)) throw fail(at, system, "names no system");
            String spawnable = "p" + ++particles;
            Map<String, Object> place = new LinkedHashMap<>();
            place.put("role", "caster");
            if (e.get("locator") != null && !String.valueOf(e.get("locator")).isEmpty()) place.put("socket", String.valueOf(e.get("locator")));
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("id", spawnable);
            s.put("name", seconds(at));   // its key's time, which a load failure names
            s.put("at", place);
            s.put("system", system);
            spawnables.add(s);
            tracks.add(track("ps" + particles, "system", Map.of("spawnable", spawnable),
                    keyed(section(0, null, "keys", List.of(List.of(ticks(at), "activate"))))));
        }

        void sound(Map<?, ?> e, float at) {
            String sound = qualify(e.get("effect") == null ? null : String.valueOf(e.get("effect")), at);
            Map<String, Object> section = section(ticks(at), null, "sound", sound);
            section.put("play_until_finished", true);   // fired and forgotten: it outlives an interrupted clip
            tracks.add(track("sn" + ++sounds, "sound", Map.of("role", "caster"), section));
        }

        void subsequence(String id, float from, int to, boolean loop) {
            Map<String, Object> s = section(ticks(from), to < 0 ? null : to, "sequence", id);
            if (loop) s.put("loop", true);
            tracks.add(track("ss" + ++subsequences, "subsequence", null, s));
        }

        String sequence(@Nullable String value, float at) {
            String id = qualify(value, at);
            if (!known.sequence(id)) throw fail(at, id, "names no sequence");
            return id;
        }

        String qualify(@Nullable String value, float at) {
            if (value == null || value.isEmpty()) throw fail(at, "", "names no id");
            return value.indexOf(':') < 0 ? namespace + ":" + value : value;
        }

        CgAssetException fail(float at, String id, String why) {
            return new CgAssetException(file + ": animation " + animation + " at " + seconds(at) + ": " + id + " " + why);
        }

        /** A key's system: left running at its section's end, so its clip's stop lets it play out. */
        private static Map<String, Object> keyed(Map<String, Object> section) {
            section.put("end", "none");
            return section;
        }

        private static Map<String, Object> section(int from, @Nullable Integer to, String key, Object value) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("id", "a");
            List<Object> range = new ArrayList<>();
            range.add(from);
            range.add(to);
            s.put("range", range);
            s.put(key, value);
            return s;
        }

        private static Map<String, Object> track(String id, String kind, @Nullable Map<String, Object> binding, Map<String, Object> section) {
            // Ids are unique across the sequence, tracks and sections alike
            section.put("id", id + "_a");
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("id", id);
            t.put("kind", kind);
            if (binding != null) t.put("binding", binding);
            t.put("sections", List.of(section));
            return t;
        }

        Map<String, Object> tree() {
            if (!events.isEmpty()) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("id", "mod_a");
                s.put("keys", events);
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("id", "mod");
                t.put("kind", "event");
                t.put("side", "rpg");
                t.put("binding", Map.of("role", "caster"));
                t.put("sections", List.of(s));
                tracks.add(t);
            }
            Map<String, Object> tree = new LinkedHashMap<>();
            tree.put("_v", 1);
            tree.put("tick_resolution", TICKS);
            tree.put("display_rate", 24);
            tree.put("range", List.of(0, Math.max(length, 1)));
            tree.put("roles", List.of(Map.of("id", "r1", "name", "caster", "kind", "caster")));
            if (!spawnables.isEmpty()) tree.put("spawnables", spawnables);
            tree.put("tracks", tracks);
            return tree;
        }
    }
}
