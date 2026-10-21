package com.crystalgraphics.api.material;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import com.crystalgraphics.asset.codec.CgAssetYaml;

/**
 * Every shading model by id and by index: the engine's three, and what kits register. A material naming one that is
 * not here fails at load.
 *
 * <pre>{@code
 * CgShadingModels.register(new CgShadingModel("kakarot:cell_tone", 13, "lit", "CELL_TONE", null));
 * CgShadingModels.registerKit("kakarot", shadingTree);   // { cell_tone: { index: 13, base: lit, keyword: CELL_TONE } }
 * CgShadingModels.get("kakarot:cell_tone");
 * int bits = CgShadingModels.indexBits();                // 5 once an index of 16 is registered
 * }</pre>
 *
 * <ul>
 *   <li>An id is registered once, and an index too: a second model under either throws, naming both.</li>
 *   <li>An index runs 0 to {@link #MAX_INDEX}; a kit takes its game's own numbers, so a game's table of models
 *       ports as it is written.</li>
 *   <li>A kit's ids are its namespace's: {@code registerKit("kakarot", ...)} registers {@code kakarot:<name>}, and
 *       each declaration names its {@code index}.</li>
 * </ul>
 */
public final class CgShadingModels {

    /** The largest index: the mask target holds a byte a pixel. */
    public static final int MAX_INDEX = 255;

    /** The engine's own, at Unreal's numbers (DeferredShadingCommon.ush's SHADINGMODELID_*). */
    public static final CgShadingModel UNLIT = new CgShadingModel("unlit", 0, "unlit", null, null);
    public static final CgShadingModel LIT = new CgShadingModel("lit", 1, "lit", null, null);
    public static final CgShadingModel SUBSURFACE = new CgShadingModel("subsurface", 2, "subsurface", null, null);

    private static final Map<String, CgShadingModel> BY_ID = new ConcurrentHashMap<>();
    private static final Map<Integer, CgShadingModel> BY_INDEX = new ConcurrentHashMap<>();

    static {
        for (CgShadingModel m : new CgShadingModel[] {UNLIT, LIT, SUBSURFACE}) {
            BY_ID.put(m.id(), m);
            BY_INDEX.put(m.index(), m);
        }
    }

    private CgShadingModels() {
    }

    /**
     * Registers {@code model}.
     *
     * @throws IllegalArgumentException when another model holds its id or its index, its index is past
     *                                  {@link #MAX_INDEX}, or its base is not one of the engine's
     */
    public static synchronized void register(CgShadingModel model) {
        if (!model.base().equals("unlit") && !model.base().equals("lit") && !model.base().equals("subsurface")) {
            throw new IllegalArgumentException(model.id() + " builds on '" + model.base()
                    + "': a shading model builds on unlit, lit or subsurface");
        }
        if (model.index() < 0 || model.index() > MAX_INDEX) {
            throw new IllegalArgumentException(model.id() + "'s index " + model.index() + " is outside 0.." + MAX_INDEX);
        }
        CgShadingModel had = BY_ID.get(model.id());
        if (had != null) {
            if (had.equals(model)) return;
            throw new IllegalArgumentException("shading model " + model.id() + " is registered already, as " + had);
        }
        CgShadingModel at = BY_INDEX.get(model.index());
        if (at != null) {
            throw new IllegalArgumentException(model.id() + " takes index " + model.index() + ", which " + at.id() + " holds");
        }
        BY_ID.put(model.id(), model);
        BY_INDEX.put(model.index(), model);
    }

    /**
     * Registers a kit's shading models from its declarations, a map of name to {@code index}, {@code base},
     * {@code keyword} and {@code library}, each under {@code namespace}. Answers what it registered.
     */
    public static List<CgShadingModel> registerKit(String namespace, Map<?, ?> declarations) {
        List<CgShadingModel> made = new ArrayList<>();
        for (Map.Entry<?, ?> e : declarations.entrySet()) {
            String id = namespace + ":" + e.getKey();
            if (!(e.getValue() instanceof Map<?, ?> m)) {
                throw new IllegalArgumentException(id + " is not a map of index, base, keyword, library");
            }
            if (!(m.get("index") instanceof Number index)) {
                throw new IllegalArgumentException(id + " declares no index: the number its game gives it");
            }
            Object base = m.get("base");
            CgShadingModel model = new CgShadingModel(id, index.intValue(), base == null ? "lit" : base.toString(),
                    m.get("keyword") == null ? null : m.get("keyword").toString(),
                    m.get("library") == null ? null : m.get("library").toString());
            register(model);
            made.add(model);
        }
        return made;
    }

    /**
     * Registers a kit folder's shading models: its {@code shading.yaml}, or nothing when the kit ships none.
     *
     * <pre>{@code
     * // assets/kakarot_kit/shading.yaml:  _v: 1
     * //                                   shading: { cell_tone: { index: 13, base: lit, keyword: CELL_TONE } }
     * CgShadingModels.registerKit("kakarot_kit", Paths.get("assets/kakarot_kit"));   // kakarot_kit:cell_tone
     * }</pre>
     *
     * @throws IllegalArgumentException naming the file, as {@link #registerKit(String, Map)}, or if it cannot be read
     */
    public static List<CgShadingModel> registerKit(String namespace, Path folder) {
        Path file = folder.resolve("shading.yaml");
        if (!Files.isRegularFile(file)) return Collections.emptyList();
        Object tree;
        try {
            tree = CgAssetYaml.read(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalArgumentException(file + ": " + e.getMessage(), e);
        }
        if (!(tree instanceof Map<?, ?> root) || !(root.get("shading") instanceof Map<?, ?> declarations)) {
            throw new IllegalArgumentException(file + ": expected _v and a shading: map of name to its index and base");
        }
        try {
            return registerKit(namespace, declarations);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(file + ": " + e.getMessage(), e);
        }
    }

    /** The model of {@code id}, null when none is registered. */
    @Nullable
    public static CgShadingModel get(String id) {
        return BY_ID.get(id);
    }

    /** The model at {@code index}, null when none is registered. */
    @Nullable
    public static CgShadingModel at(int index) {
        return BY_INDEX.get(index);
    }

    /** The bits an index needs, sized from the highest registered: 2 for the engine's three, 5 for Kakarot's. */
    public static synchronized int indexBits() {
        int highest = 0;
        for (int index : BY_INDEX.keySet()) highest = Math.max(highest, index);
        return 32 - Integer.numberOfLeadingZeros(Math.max(highest, 1));
    }
}
