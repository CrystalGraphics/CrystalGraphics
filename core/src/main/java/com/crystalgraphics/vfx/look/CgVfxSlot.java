package com.crystalgraphics.vfx.look;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * A place an effect draws: its body along a path, its head on a sphere, its sparks as particles. An effect declares
 * its slots as constants and draws each through {@code CgVfxFrame}; a layer says which slots it draws on
 * ({@link CgVfxLayer.Builder#on}). The kind says what geometry the shader is given, and what each draw's customs hold.
 *
 * <pre>{@code
 * public static final CgVfxSlot BODY = CgVfxSlot.tube("mymod:bolt/body");
 * public static final CgVfxSlot HEAD = CgVfxSlot.mesh("mymod:bolt/head");
 *
 * frame.mesh(this, HEAD, x, y, z, transform, halfWidth, halfLength, intensity, 0f);   // every layer on HEAD
 * }</pre>
 *
 * <ul>
 *   <li>A slot is its kind and its name: two declarations of the same are the same slot. An emitter's particles are
 *       the slot {@code particles(emitter.name())} ({@code CgVfxEmitter.slot()}).</li>
 *   <li>A shader drawing on several kinds declares {@code #pragma cg_feature FX_ON_TUBE} and the like: the kind's
 *       keyword is set on each material ({@link Kind#keyword()}).</li>
 * </ul>
 */
public final class CgVfxSlot {

    /**
     * What a slot's draws are. Every kind's {@code CG_OBJECT_CUSTOM3} is the draw header ({@code fx_draw.glsl}: its row,
     * the effect's age, its seed, its presence); the first three customs are the kind's own.
     */
    public enum Kind {
        /**
         * Chunked tube draws along a path ({@code fx_tube.glsl}): {@code CUSTOM0} the path's row, the chunk's first
         * ring, the layer's size and 0; {@code CUSTOM1} the chunk's centre less the effect's origin and its share of the
         * draw.
         */
        TUBE,
        /**
         * A unit sphere placed by a transform: {@code CUSTOM0} the layer's size, 0, the effect's age and its seed;
         * {@code CUSTOM1} the four numbers the effect passes (its documented meaning per slot: a half-width, a
         * half-length, an intensity, a progress).
         */
        MESH,
        /** Stateless ribbons a shader places in a unit cube placed by a transform: the customs of {@link #MESH}. */
        RIBBONS,
        /**
         * Ribbons riding a path: {@code CUSTOM0} the path's row, 0, the layer's size, 0; {@code CUSTOM1} the draw's centre
         * less the effect's origin and an intensity.
         */
        PATH_RIBBONS,
        /** One camera-facing quad: the customs of {@link #MESH}, the four numbers the particle's own. */
        BILLBOARD,
        /** An emitter's particles ({@code #pragma cg_use particle}): {@code CgVfxFrame.particles} has the customs. */
        PARTICLES;

        /** The keyword a shader declares to draw on this kind: {@code FX_ON_TUBE}, {@code FX_ON_MESH} and so on. */
        public String keyword() {
            return "FX_ON_" + name();
        }
    }

    private static final Map<String, CgVfxSlot> INTERNED = new HashMap<>();
    private static final List<CgVfxSlot> BY_ID = new ArrayList<>();

    private final String name;
    private final Kind kind;
    final int id;

    private CgVfxSlot(String name, Kind kind, int id) {
        this.name = name;
        this.kind = kind;
        this.id = id;
    }

    public static CgVfxSlot of(Kind kind, String name) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(name, "name");
        synchronized (INTERNED) {
            String key = kind + ":" + name;
            CgVfxSlot slot = INTERNED.get(key);
            if (slot == null) {
                slot = new CgVfxSlot(name, kind, BY_ID.size());
                INTERNED.put(key, slot);
                BY_ID.add(slot);
            }
            return slot;
        }
    }

    public static CgVfxSlot tube(String name) {
        return of(Kind.TUBE, name);
    }

    public static CgVfxSlot mesh(String name) {
        return of(Kind.MESH, name);
    }

    public static CgVfxSlot ribbons(String name) {
        return of(Kind.RIBBONS, name);
    }

    public static CgVfxSlot pathRibbons(String name) {
        return of(Kind.PATH_RIBBONS, name);
    }

    public static CgVfxSlot billboard(String name) {
        return of(Kind.BILLBOARD, name);
    }

    /** The particles of the emitter named {@code emitter}. */
    public static CgVfxSlot particles(String emitter) {
        return of(Kind.PARTICLES, emitter);
    }

    public String name() {
        return name;
    }

    public Kind kind() {
        return kind;
    }

    /** How many slots exist: a look's per-slot table is this long. */
    static int count() {
        synchronized (INTERNED) {
            return BY_ID.size();
        }
    }

    @Override
    public String toString() {
        return kind.name().toLowerCase(Locale.ROOT) + " " + name;
    }
}
