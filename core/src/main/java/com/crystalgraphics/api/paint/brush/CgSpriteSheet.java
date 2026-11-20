package com.crystalgraphics.api.paint.brush;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.crystalgraphics.api.texture.CgSampler;
import com.crystalgraphics.api.texture.CgTextures;
import com.crystalgraphics.util.io.CgIO;

/**
 * A sheet of named brushes in one JSON file: a theme's buttons, panels and icons, each a region of an image and its
 * nine-slice. A resource pack retheme ships the JSON and the PNG; nothing is registered in code.
 *
 * <pre>{@code
 * // assets/mymod/ui/sprites/theme.json
 * { "texture": "mymod:textures/gui/theme.png",
 *   "elements": {
 *     "button": { "sprite": [29, 1, 13, 13], "border": [1, 1, 11, 11] },
 *     "panel":  { "texture": "mymod:textures/gui/panel.png", "sprite": [0, 0, 32, 32], "border": [4, 4, 4, 4] },
 *     "dot":    { "sprite": [0, 0, 4, 4] }
 *   } }
 * }</pre>
 *
 * <pre>{@code
 * CgBrush button = CgBrush.sprite("mymod:theme", "button");      // the usual way
 * CgSpriteSheet theme = CgSpriteSheet.of("mymod:theme");          // or the sheet, to list or test its entries
 * for (String name : theme.names()) ...
 * }</pre>
 *
 * <ul>
 *   <li>{@code sprite} is {@code [x, y, width, height]} in the image's pixels; {@code border} is how far each slice
 *       reaches into the region, {@code [left, top, right, bottom]}, and makes the entry nine-sliced
 *       ({@code fillCenter: false} leaves its middle empty, {@code borderScale} scales its slices). {@code repeat}
 *       is {@code stretch}, {@code repeat}, {@code repeat-x}, {@code repeat-y}, {@code round} or {@code space}. An
 *       entry may name a {@code texture} of its own; {@code textureSize} is accepted and unused, since the image's own
 *       size is read.</li>
 *   <li>Sampled nearest and clamped: a sheet is pixel art, and a nine-slice middle one texel wide must stretch as
 *       that texel, not as a blend with its border.</li>
 *   <li>A missing sheet, a missing entry or a malformed field fails by name; nothing draws a placeholder.</li>
 *   <li>Sheets are read once and cached; any thread. A resource reload drops them ({@link #invalidateCache}), so an
 *       edited sheet is read again; a holder of a brush watches {@link #generation}.</li>
 * </ul>
 */
public final class CgSpriteSheet {

    // Threads: any; parsed sheets are immutable, filled by computeIfAbsent.
    private static final Map<String, CgSpriteSheet> SHEETS = new ConcurrentHashMap<>();
    /** Moves whenever the sheets are dropped. @see #generation */
    private static volatile int generation;

    private final String id;
    private final Map<String, CgBrush> brushes;

    private CgSpriteSheet(String id, Map<String, CgBrush> brushes) {
        this.id = id;
        this.brushes = brushes;
    }

    /** The sheet {@code id} ({@code "namespace:path"}), read on first use. */
    public static CgSpriteSheet of(String id) {
        return SHEETS.computeIfAbsent(id, CgSpriteSheet::read);
    }

    /** Entry {@code name}'s brush. */
    /** Every sheet dropped, so the next {@link #of} reads its file again: what a resource reload calls. */
    public static void invalidateCache() {
        SHEETS.clear();
        generation++;
    }

    /**
     * A counter that moves whenever the sheets are dropped, so a holder of a brush can tell it may be stale.
     *
     * <pre>{@code
     * if (seen != CgSpriteSheet.generation()) { seen = CgSpriteSheet.generation(); button = CgBrush.sprite(id, "button"); }
     * }</pre>
     */
    public static int generation() {
        return generation;
    }

    public CgBrush brush(String name) {
        CgBrush brush = brushes.get(name);
        if (brush == null) throw new IllegalArgumentException("sprite sheet " + id + " has no entry '" + name + "': " + brushes.keySet());
        return brush;
    }

    /** Its entries' names, in file order. */
    public Set<String> names() {
        return brushes.keySet();
    }

    public String id() {
        return id;
    }

    private static CgSpriteSheet read(String id) {
        int colon = id.indexOf(':');
        if (colon <= 0) throw new IllegalArgumentException("a sprite sheet id is namespace:path, not " + id);
        String path = id.substring(0, colon) + ":ui/sprites/" + id.substring(colon + 1) + ".json";
        JsonObject root;
        try (InputStream in = CgIO.openStream(path)) {
            if (in == null) throw new IllegalArgumentException("sprite sheet " + id + ": no file " + path);
            // The instance parse: the oldest Gson a host ships has no static parseReader.
            root = new JsonParser().parse(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException | IllegalStateException e) {
            throw new IllegalArgumentException("sprite sheet " + id + ": cannot read " + path + ": " + e.getMessage(), e);
        }
        String sheetTexture = root.has("texture") ? root.get("texture").getAsString() : null;
        JsonObject elements = root.has("elements") ? root.getAsJsonObject("elements") : null;
        if (elements == null) throw new IllegalArgumentException("sprite sheet " + id + ": no elements");
        Map<String, CgBrush> brushes = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : elements.entrySet()) {
            String at = id + "#" + e.getKey();
            JsonObject entry = e.getValue().getAsJsonObject();
            String texture = entry.has("texture") ? entry.get("texture").getAsString() : sheetTexture;
            if (texture == null) throw new IllegalArgumentException(at + ": no texture, on the entry or the sheet");
            // Texel for texel: a filtered one-texel centre would blend its border across the whole stretch
            CgBrush brush = CgBrush.texture(CgTextures.of(texture)).sampler(CgSampler.NEAREST_CLAMP);
            if (entry.has("sprite")) {
                int[] r = ints(at + ".sprite", entry.getAsJsonArray("sprite"));
                brush = brush.region(r[0], r[1], r[2], r[3]);
            }
            if (entry.has("border")) {
                int[] b = ints(at + ".border", entry.getAsJsonArray("border"));
                CgNineSlice slice = CgNineSlice.of(b[0], b[1], b[2], b[3]);
                if (entry.has("fillCenter") && !entry.get("fillCenter").getAsBoolean()) slice = slice.hollow();
                if (entry.has("borderScale")) slice = slice.scaled(entry.get("borderScale").getAsFloat());
                brush = brush.nineSlice(slice);
            }
            if (entry.has("repeat")) {
                CgTiling tiling = tiling(at + ".repeat", entry.get("repeat").getAsString());
                brush = brush.tiling(tiling).edges(tiling);
            }
            brushes.put(e.getKey(), brush);
        }
        return new CgSpriteSheet(id, Collections.unmodifiableMap(brushes));
    }

    private static CgTiling tiling(String at, String value) {
        for (CgTiling t : CgTiling.values()) {
            if (t.name().equalsIgnoreCase(value.replace('-', '_'))) return t;
        }
        throw new IllegalArgumentException(at + ": '" + value + "' is none of stretch, repeat, repeat-x, repeat-y, round, space");
    }

    private static int[] ints(String at, JsonArray array) {
        if (array.size() != 4) throw new IllegalArgumentException(at + ": four numbers, not " + array.size());
        int[] out = new int[4];
        for (int i = 0; i < 4; i++) out[i] = array.get(i).getAsInt();
        return out;
    }
}
