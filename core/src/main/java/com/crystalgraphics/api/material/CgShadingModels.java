package com.crystalgraphics.api.material;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

/**
 * Every shading model by id: the engine's three, and what kits register. A material naming one that is not here fails
 * at load.
 *
 * <pre>{@code
 * CgShadingModels.register(new CgShadingModel("kakarot:cell_tone", "lit", "CELL_TONE", null));
 * CgShadingModels.registerKit("kakarot", shadingTree);   // a kit's declarations: { cell_tone: { base: lit, keyword: CELL_TONE } }
 * CgShadingModels.get("kakarot:cell_tone");
 * }</pre>
 *
 * <ul>
 *   <li>An id is registered once; a second registration of another model under it throws.</li>
 *   <li>A kit's ids are its namespace's: {@code registerKit("kakarot", ...)} registers {@code kakarot:<name>}.</li>
 * </ul>
 */
public final class CgShadingModels {

    /** The engine's own. */
    public static final CgShadingModel UNLIT = new CgShadingModel("unlit", "unlit", null, null);
    public static final CgShadingModel LIT = new CgShadingModel("lit", "lit", null, null);
    public static final CgShadingModel SUBSURFACE = new CgShadingModel("subsurface", "subsurface", null, null);

    private static final Map<String, CgShadingModel> BY_ID = new ConcurrentHashMap<>();

    static {
        BY_ID.put(UNLIT.id(), UNLIT);
        BY_ID.put(LIT.id(), LIT);
        BY_ID.put(SUBSURFACE.id(), SUBSURFACE);
    }

    private CgShadingModels() {
    }

    /**
     * Registers {@code model}.
     *
     * @throws IllegalArgumentException when another model holds its id, or its base is not one of the engine's
     */
    public static void register(CgShadingModel model) {
        if (!model.base().equals("unlit") && !model.base().equals("lit") && !model.base().equals("subsurface")) {
            throw new IllegalArgumentException(model.id() + " builds on '" + model.base()
                    + "': a shading model builds on unlit, lit or subsurface");
        }
        CgShadingModel had = BY_ID.putIfAbsent(model.id(), model);
        if (had != null && !had.equals(model)) {
            throw new IllegalArgumentException("shading model " + model.id() + " is registered already, as " + had);
        }
    }

    /**
     * Registers a kit's shading models from its declarations, a map of name to {@code base}, {@code keyword} and
     * {@code library}, each under {@code namespace}. Answers what it registered.
     */
    public static List<CgShadingModel> registerKit(String namespace, Map<?, ?> declarations) {
        List<CgShadingModel> made = new ArrayList<>();
        for (Map.Entry<?, ?> e : declarations.entrySet()) {
            if (!(e.getValue() instanceof Map)) {
                throw new IllegalArgumentException(namespace + ":" + e.getKey() + " is not a map of base, keyword, library");
            }
            Map<?, ?> m = (Map<?, ?>) e.getValue();
            Object base = m.get("base");
            CgShadingModel model = new CgShadingModel(namespace + ":" + e.getKey(), base == null ? "lit" : base.toString(),
                    m.get("keyword") == null ? null : m.get("keyword").toString(),
                    m.get("library") == null ? null : m.get("library").toString());
            register(model);
            made.add(model);
        }
        return made;
    }

    /** The model of {@code id}, null when none is registered. */
    @Nullable
    public static CgShadingModel get(String id) {
        return BY_ID.get(id);
    }
}
