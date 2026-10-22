package com.crystalgraphics.vfx.particle;

import java.util.List;

/**
 * An event its emitter raises: a {@code generate_event} entry, Cascade's {@code EventGenerator}. A handler whose
 * trigger and name match it receives it (Cascade's receiver match, {@code ParticleModules_Event.cpp:278, :338}).
 *
 * <pre>{@code
 * CgVfxEmitter.builder("rocks").generator(CgVfxEventGenerator.of("death", "big_First"))...   // raises named deaths
 * CgVfxEmitter.builder("debris").generator(CgVfxEventGenerator.of("collision", "").atFirstOnly())...
 * }</pre>
 *
 * <ul>
 *   <li>The name is matched exactly: an unnamed generator ({@code ""}) feeds only unnamed handlers.</li>
 *   <li>{@link #firstTimeOnly()} is read on collision events alone, as Cascade reads it.</li>
 * </ul>
 *
 * @param trigger       {@code spawn}, {@code death}, {@code collision}, {@code burst} or {@code location}
 * @param name          its name, {@code ""} for none
 * @param firstTimeOnly a collision event at each particle's first collision alone
 */
public record CgVfxEventGenerator(String trigger, String name, boolean firstTimeOnly) {

    /** The triggers a generator raises. */
    public static final List<String> TRIGGERS = List.of("spawn", "death", "collision", "burst", "location");

    public CgVfxEventGenerator {
        if (!TRIGGERS.contains(trigger)) {
            throw new IllegalArgumentException("an event generator's trigger " + trigger + " is not one of " + TRIGGERS);
        }
        name = name == null ? "" : name;
    }

    /** {@code trigger} events named {@code name}, every one. */
    public static CgVfxEventGenerator of(String trigger, String name) {
        return new CgVfxEventGenerator(trigger, name, false);
    }

    /** This, its collision events at each particle's first collision alone. */
    public CgVfxEventGenerator atFirstOnly() {
        return new CgVfxEventGenerator(trigger, name, true);
    }

    /** Whether a handler of {@code trigger} named {@code name} ({@code null} for none) receives it. */
    public boolean feeds(String trigger, String name) {
        return this.trigger.equals(trigger) && this.name.equals(name == null ? "" : name);
    }
}
