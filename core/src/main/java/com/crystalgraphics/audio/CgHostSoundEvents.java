package com.crystalgraphics.audio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import javax.annotation.Nullable;

import com.crystalgraphics.mc.CgAssetReloader;
import com.crystalgraphics.mc.CgReloadListener;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgResourceService;
import com.crystalgraphics.serialization.format.CgJsonOps;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The host's sound events, read from its {@code sounds.json} as Minecraft merges them: every namespace's copy in
 * every pack, the lowest pack first, an event marked {@code replace} dropping what the packs below gave it. What
 * turns {@code minecraft:entity.generic.explode} into a file our mixer plays; the host's own sound engine is never
 * called.
 *
 * <pre>{@code
 * CgHostSoundEvents.Variant v = CgHostSoundEvents.current().pick("minecraft:entity.generic.explode");
 * CgSound boom = audio.sounds().load(v.path(), v.stream());     // minecraft:sounds/random/explode1.ogg
 * }</pre>
 *
 * Usually through the engine, which picks, loads and places in one call:
 *
 * <pre>{@code
 * long id = audio.playEvent("minecraft:entity.generic.explode", x, y, z, 4f, 1f);
 * }</pre>
 *
 * <ul>
 *   <li>Built at first use and dropped on a resource reload, so a pack change is heard at the next play.</li>
 *   <li>Frame thread: its pick's random is unsynchronised.</li>
 *   <li>An unknown event fails, naming it: a typo never plays as silence.</li>
 * </ul>
 */
public final class CgHostSoundEvents {

    /**
     * One sound an event may play: its file as {@code namespace:sounds/<name>.ogg}, Minecraft's volume, pitch and
     * weight, whether it streams, and its attenuation distance in blocks.
     */
    public record Variant(String path, float volume, float pitch, int weight, boolean stream, int distance) {
    }

    /** A sound entry as written: a file, or another event to pick from. */
    private record Entry(String name, float volume, float pitch, int weight, boolean stream, int distance, boolean event) {
    }

    private static final int MAX_EVENT_DEPTH = 8;
    private static volatile CgHostSoundEvents current;

    static {
        CgAssetReloader.addListener(() -> current = null);
    }

    private final Map<String, List<Entry>> events = new HashMap<>();
    private final Map<String, String> subtitles = new HashMap<>();
    /** Each event flattened at its first pick: every file it can reach, with its chance as a running sum. */
    private final Map<String, Flat> flat = new HashMap<>();
    private final SplittableRandom random = new SplittableRandom();

    /** The host's table, read at first use after a reload. */
    public static CgHostSoundEvents current() {
        CgHostSoundEvents c = current;
        if (c == null) current = c = read(CgPlatform.resources());
        return c;
    }

    /** Every namespace's {@code sounds.json} in {@code resources}, lowest pack first; none where it is null. */
    public static CgHostSoundEvents read(@Nullable CgResourceService resources) {
        CgHostSoundEvents table = new CgHostSoundEvents();
        if (resources == null) return table;
        for (String namespace : resources.namespaces()) {
            for (InputStream in : resources.openStack(namespace, "sounds.json")) {
                try (InputStream s = in) {
                    table.merge(namespace, text(s));
                } catch (IOException e) {
                    throw new IllegalStateException("Could not read " + namespace + ":sounds.json", e);
                }
            }
        }
        return table;
    }

    private static String text(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /** One pack's {@code sounds.json} of {@code namespace}, over what lower packs gave. */
    public CgHostSoundEvents merge(String namespace, String json) {
        JsonElement root = CgJsonOps.fromText(json);
        if (!root.isJsonObject()) throw new IllegalArgumentException(namespace + ":sounds.json is not an object");
        for (Map.Entry<String, JsonElement> e : root.getAsJsonObject().entrySet()) {
            String id = namespace + ":" + e.getKey();
            JsonObject event = e.getValue().getAsJsonObject();
            flat.clear();
            List<Entry> list = events.computeIfAbsent(id, k -> new ArrayList<>());
            if (event.has("replace") && event.get("replace").getAsBoolean()) list.clear();
            if (event.has("subtitle")) subtitles.put(id, event.get("subtitle").getAsString());
            if (!event.has("sounds")) continue;
            for (JsonElement s : event.getAsJsonArray("sounds")) list.add(entry(namespace, s));
        }
        return this;
    }

    private static Entry entry(String namespace, JsonElement s) {
        if (s.isJsonPrimitive()) return new Entry(qualify(namespace, s.getAsString()), 1f, 1f, 1, false, 16, false);
        JsonObject o = s.getAsJsonObject();
        return new Entry(qualify(namespace, o.get("name").getAsString()),
                o.has("volume") ? o.get("volume").getAsFloat() : 1f,
                o.has("pitch") ? o.get("pitch").getAsFloat() : 1f,
                o.has("weight") ? o.get("weight").getAsInt() : 1,
                o.has("stream") && o.get("stream").getAsBoolean(),
                o.has("attenuation_distance") ? o.get("attenuation_distance").getAsInt() : 16,
                o.has("type") && "event".equals(o.get("type").getAsString()));
    }

    /** A name without a namespace is in the file's own. */
    private static String qualify(String namespace, String name) {
        return name.indexOf(':') >= 0 ? name : namespace + ":" + name;
    }

    /** Whether {@code event} is in the table. */
    public boolean has(String event) {
        return events.containsKey(event);
    }

    /** {@code event}'s subtitle key, or null. */
    @Nullable
    public String subtitle(String event) {
        return subtitles.get(event);
    }

    /** The events it holds. */
    public Iterable<String> events() {
        return Collections.unmodifiableSet(events.keySet());
    }

    /** A variant of {@code event}, rolled by weight, an event reference followed to its file. Allocates nothing once picked before. */
    public Variant pick(String event) {
        Flat f = flat.get(event);
        if (f == null) {
            List<Variant> variants = new ArrayList<>();
            List<Double> chances = new ArrayList<>();
            flatten(event, 1f, 1f, 1.0, 0, variants, chances);
            f = new Flat(variants.toArray(new Variant[0]), new double[chances.size()]);
            double sum = 0;
            for (int k = 0; k < f.cumulative.length; k++) f.cumulative[k] = sum += chances.get(k);
            flat.put(event, f);
        }
        double roll = random.nextDouble() * f.cumulative[f.cumulative.length - 1];
        for (int k = 0; k < f.cumulative.length; k++) if (roll < f.cumulative[k]) return f.variants[k];
        return f.variants[f.variants.length - 1];
    }

    /** {@code event}'s files at {@code chance}, each entry its share of the event's weight; a reference recursed. */
    private void flatten(String event, float volume, float pitch, double chance, int depth, List<Variant> variants,
            List<Double> chances) {
        List<Entry> list = events.get(event);
        if (list == null || list.isEmpty()) throw new IllegalArgumentException("No sound event " + event);
        if (depth > MAX_EVENT_DEPTH) throw new IllegalStateException(event + " refers to itself through other events");
        int total = 0;
        for (Entry e : list) total += Math.max(e.weight(), 0);
        for (Entry e : list) {
            double share = total > 0 ? chance * Math.max(e.weight(), 0) / total : chance / list.size();
            if (e.event()) {
                flatten(e.name(), volume * e.volume(), pitch * e.pitch(), share, depth + 1, variants, chances);
                continue;
            }
            int colon = e.name().indexOf(':');
            String path = e.name().substring(0, colon) + ":sounds/" + e.name().substring(colon + 1) + ".ogg";
            variants.add(new Variant(path, volume * e.volume(), pitch * e.pitch(), e.weight(), e.stream(), e.distance()));
            chances.add(share);
        }
    }

    private record Flat(Variant[] variants, double[] cumulative) {
    }
}
