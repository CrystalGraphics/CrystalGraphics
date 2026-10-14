package com.crystalgraphics.vfx;

import com.crystalgraphics.vfx.effect.CgVfxBurst;
import com.crystalgraphics.vfx.effect.air.CgVfxHeatHaze;
import com.crystalgraphics.vfx.effect.beam.CgEnergyWave;
import com.crystalgraphics.vfx.element.CgVfxBlast;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.net.CgVfxCue;

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

    /** Starts an effect of a look from a cue the server sent, reading its parameters and where it aims. */
    @FunctionalInterface
    public interface CueFactory {
        CgVfxEffect spawn(CgVfxLook look, CgVfxCue.Play cue);
    }

    public static final class Entry {
        private final String id;
        private final String name;
        private final String category;
        private final CgVfxLook look;
        private final Factory factory;
        private final CueFactory cueFactory;

        Entry(String id, String name, String category, CgVfxLook look, Factory factory, CueFactory cueFactory) {
            this.id = id;
            this.name = name;
            this.category = category;
            this.look = look;
            this.factory = factory;
            this.cueFactory = cueFactory;
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

        /**
         * The effect a cue plays: its cue factory's, else the point factory's, aimed at the cue's target, else
         * {@link #CUE_REACH} along its direction, else at its own point.
         */
        public CgVfxEffect spawn(CgVfxCue.Play cue) {
            if (cueFactory != null) return cueFactory.spawn(look, cue);
            double tx = cue.tx(), ty = cue.ty(), tz = cue.tz();
            if (!cue.hasTarget() && cue.hasDirection()) {
                tx = cue.x() + cue.dx() * CUE_REACH;
                ty = cue.y() + cue.dy() * CUE_REACH;
                tz = cue.z() + cue.dz() * CUE_REACH;
            }
            return spawn(look, cue.x(), cue.y(), cue.z(), tx, ty, tz);
        }

        @Override
        public String toString() {
            return id;
        }
    }

    /** How far along its direction a cue with no target aims a point factory's effect, in blocks. */
    public static final double CUE_REACH = 64;

    private static final List<Entry> ENTRIES = new CopyOnWriteArrayList<>();

    static {
        wave("crystalgraphics:kamehameha", "Kamehameha", CgEnergyWave.kamehameha());
        wave("crystalgraphics:final_flash", "Final Flash", CgEnergyWave.finalFlash());
        wave("crystalgraphics:galick_gun", "Galick Gun", CgEnergyWave.galickGun());
        wave("crystalgraphics:kamehameha_toon", "Kamehameha (toon)", CgEnergyWave.toon());
        register("crystalgraphics:explosion", "Explosion", "Bursts",
                CgVfxLook.builder(CgVfxBurst.SCHEMA).with(CgVfxBlast.EXPLOSION.kit()).build(),
                (look, x, y, z, tx, ty, tz) -> new CgVfxBurst(look, x, y, z));
        register("crystalgraphics:heat_haze", "Heat haze", "Air", CgVfxHeatHaze.standard(),
                (look, x, y, z, tx, ty, tz) -> new CgVfxHeatHaze(look, x, y, z));
    }

    private CgVfxRegistry() {
    }

    public static Entry register(String id, String name, String category, CgVfxLook look, Factory factory) {
        return add(new Entry(id, name, category, look, factory, null));
    }

    /**
     * What a cue plays here, under the cue's id. A tool lists it, and plays it at a point through a cue it makes there.
     *
     * <pre>{@code
     * static final CgVfxCue HIT = CgVfxCue.define("mymod:hit_spark").fields(LEVEL).build();   // both sides
     * CgVfxRegistry.register(HIT, "Hit spark", "Impacts", HitSpark.LOOK,                     // client
     *         (look, cue) -> new HitSpark(look, cue.x(), cue.y(), cue.z()).level(cue.getInt(LEVEL)));
     * }</pre>
     */
    public static Entry register(CgVfxCue cue, String name, String category, CgVfxLook look, CueFactory factory) {
        return add(new Entry(cue.id(), name, category, look, (l, x, y, z, tx, ty, tz) ->
                factory.spawn(l, cue.at(x, y, z).target(tx, ty, tz)), factory));
    }

    /** {@link #register(CgVfxCue, String, String, CgVfxLook, CueFactory)} named by its id, in category "Cues". */
    public static Entry register(CgVfxCue cue, CgVfxLook look, CueFactory factory) {
        return register(cue, cue.id(), "Cues", look, factory);
    }

    private static Entry add(Entry entry) {
        String id = entry.id;
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

    private static void wave(String id, String name, CgVfxLook look) {
        add(new Entry(id, name, "Beams", look, CgVfxRegistry::wave, CgEnergyWave::fromCue));
    }

    private static CgVfxEffect wave(CgVfxLook look, double x, double y, double z, double tx, double ty, double tz) {
        return new CgEnergyWave(look, x, y, z).aim((float) (tx - x), (float) (ty - y), (float) (tz - z)).target(tx, ty, tz);
    }
}
