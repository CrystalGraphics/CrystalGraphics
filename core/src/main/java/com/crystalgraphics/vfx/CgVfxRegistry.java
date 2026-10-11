package com.crystalgraphics.vfx;

import com.crystalgraphics.vfx.effect.CgVfxBurst;
import com.crystalgraphics.vfx.effect.air.CgVfxHeatHaze;
import com.crystalgraphics.vfx.effect.beam.CgEnergyWave;
import com.crystalgraphics.vfx.element.CgVfxExplosion;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxSchema;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Every effect a tool can list, spawn and tweak by name: an id, a display name, the category a tool groups it under, a
 * look, and how to start one at a point aimed at a target. The engine's own effects are registered here; a mod adds
 * its own once at startup.
 *
 * <pre>{@code
 * CgVfxRegistry.register("mymod:hit_spark", "Hit spark", "Impacts", HitSpark.LOOK,
 *         (look, x, y, z, tx, ty, tz) -> new HitSpark(look, x, y, z).facing(tx - x, ty - y, tz - z));
 *
 * CgVfxRegistry.Entry entry = CgVfxRegistry.get("crystalgraphics:kamehameha");
 * CgVfxEffect effect = vfx.play(entry.spawn(entry.look(), x, y, z, tx, ty, tz));
 * }</pre>
 *
 * <ul>
 *   <li>An id is registered once; a second registration under it throws.</li>
 *   <li>The factory takes the look to play, so a tool can hand it a variant built from {@code look().toBuilder()}.</li>
 *   <li>The target is where a beam homes and blasts: one without a target never hits, and never blasts. An effect
 *       that only faces takes the direction to it; one with neither ignores it.</li>
 * </ul>
 */
public final class CgVfxRegistry {

    /** Starts an effect of a look at a world point, aimed at a world target. */
    @FunctionalInterface
    public interface Factory {
        CgVfxEffect spawn(CgVfxLook look, double x, double y, double z, double tx, double ty, double tz);
    }

    public static final class Entry {
        private final String id;
        private final String name;
        private final String category;
        private final CgVfxLook look;
        private final Factory factory;

        Entry(String id, String name, String category, CgVfxLook look, Factory factory) {
            this.id = id;
            this.name = name;
            this.category = category;
            this.look = look;
            this.factory = factory;
        }

        public String id() {
            return id;
        }

        public String name() {
            return name;
        }

        public String category() {
            return category;
        }

        public CgVfxLook look() {
            return look;
        }

        public CgVfxEffect spawn(CgVfxLook look, double x, double y, double z, double tx, double ty, double tz) {
            return factory.spawn(look, x, y, z, tx, ty, tz);
        }

        @Override
        public String toString() {
            return id;
        }
    }

    private static final List<Entry> ENTRIES = new CopyOnWriteArrayList<>();

    static {
        register("crystalgraphics:kamehameha", "Kamehameha", "Beams", CgEnergyWave.kamehameha(), CgVfxRegistry::wave);
        register("crystalgraphics:final_flash", "Final Flash", "Beams", CgEnergyWave.finalFlash(), CgVfxRegistry::wave);
        register("crystalgraphics:galick_gun", "Galick Gun", "Beams", CgEnergyWave.galickGun(), CgVfxRegistry::wave);
        CgVfxSchema schema = new CgVfxSchema();
        // Before the builder, which takes the schema's defaults: the kit declares its parameters on it.
        CgVfxExplosion blast = new CgVfxExplosion(schema, "blast");
        register("crystalgraphics:explosion", "Explosion", "Bursts", CgVfxLook.builder(schema).add(blast).build(),
                (look, x, y, z, tx, ty, tz) -> new CgVfxBurst(look, x, y, z));
        register("crystalgraphics:heat_haze", "Heat haze", "Air", CgVfxHeatHaze.standard(),
                (look, x, y, z, tx, ty, tz) -> new CgVfxHeatHaze(look, x, y, z));
    }

    private CgVfxRegistry() {
    }

    public static Entry register(String id, String name, String category, CgVfxLook look, Factory factory) {
        Entry entry = new Entry(id, name, category, look, factory);
        synchronized (ENTRIES) {
            if (get(id) != null) throw new IllegalArgumentException("VFX '" + id + "' is already registered");
            ENTRIES.add(entry);
        }
        return entry;
    }

    /** Every entry, in registration order. */
    public static List<Entry> entries() {
        return Collections.unmodifiableList(ENTRIES);
    }

    /** The entry under {@code id}, or null. */
    public static Entry get(String id) {
        for (Entry entry : ENTRIES) if (entry.id.equals(id)) return entry;
        return null;
    }

    private static CgVfxEffect wave(CgVfxLook look, double x, double y, double z, double tx, double ty, double tz) {
        return new CgEnergyWave(look, x, y, z).aim((float) (tx - x), (float) (ty - y), (float) (tz - z)).target(tx, ty, tz);
    }
}
