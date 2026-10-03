package com.crystalgraphics.render.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Where a world draw sorts among every other, by name: Unity's sorting layers. A later layer draws after an earlier one
 * whatever their distances; within a layer, transparent draws and groups sort back to front, and a draw's
 * {@code order} breaks what is left (Unity's order in layer).
 *
 * <pre>{@code
 * public static final CgSortLayer PORTALS = CgSortLayer.after("mymod:portals", CgSortLayer.EFFECTS);
 * world.draw(mesh, material).at(x, y, z).layer(PORTALS).submit();
 * }</pre>
 *
 * <ul>
 *   <li>Define a layer once, in a static field: an id given twice throws. Defining one ranks the rest again, so a
 *       draw reads its layer's rank when it records, never when it is submitted; a layer defined while a stage
 *       records may sort beside its neighbour for that one frame.</li>
 *   <li>At most {@link #MAX} layers.</li>
 * </ul>
 */
public final class CgSortLayer {

    /** Layers there may be: the rank is 8 bits of the sort key. */
    public static final int MAX = 256;

    private static final List<CgSortLayer> ORDER = new ArrayList<>();

    /** Skies and anything every other draw goes over. */
    public static final CgSortLayer BACKGROUND = append("crystalgraphics:background");
    /** Where a draw sorts unless it names a layer. */
    public static final CgSortLayer DEFAULT = append("crystalgraphics:default");
    /** VFX effects, each one group (CgVfxEffect). */
    public static final CgSortLayer EFFECTS = append("crystalgraphics:effects");
    /** Over everything in the world. */
    public static final CgSortLayer OVERLAY = append("crystalgraphics:overlay");

    private final String id;
    private volatile int rank;

    private CgSortLayer(String id) {
        this.id = id;
    }

    /** A layer drawn just before {@code anchor}. */
    public static CgSortLayer before(String id, CgSortLayer anchor) {
        return insert(id, anchor, 0);
    }

    /** A layer drawn just after {@code anchor}. */
    public static CgSortLayer after(String id, CgSortLayer anchor) {
        return insert(id, anchor, 1);
    }

    public String id() {
        return id;
    }

    /** Its place among the layers, 0 first. */
    public int rank() {
        return rank;
    }

    @Override
    public String toString() {
        return id + "#" + rank;
    }

    private static CgSortLayer append(String id) {
        synchronized (ORDER) {
            CgSortLayer layer = new CgSortLayer(id);
            layer.rank = ORDER.size();
            ORDER.add(layer);
            return layer;
        }
    }

    private static CgSortLayer insert(String id, CgSortLayer anchor, int offset) {
        Objects.requireNonNull(anchor, "anchor");
        synchronized (ORDER) {
            if (ORDER.size() == MAX) throw new IllegalStateException("more than " + MAX + " sort layers: " + id);
            for (CgSortLayer layer : ORDER) {
                if (layer.id.equals(id)) throw new IllegalArgumentException("sort layer " + id + " defined twice");
            }
            CgSortLayer layer = new CgSortLayer(id);
            ORDER.add(ORDER.indexOf(anchor) + offset, layer);
            for (int i = 0; i < ORDER.size(); i++) ORDER.get(i).rank = i;
            return layer;
        }
    }
}
