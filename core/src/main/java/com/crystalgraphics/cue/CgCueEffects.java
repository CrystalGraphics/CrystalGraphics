package com.crystalgraphics.cue;

import com.crystalgraphics.cue.CgCueDefinition.Effects;
import com.crystalgraphics.cue.CgCueDefinition.Write;

/**
 * What plays a cue's effects on a client: the manager routes and times, this draws and sounds. A dedicated server
 * installs none. The VFX engine's is {@code CgVfxCueEffects}.
 *
 * <pre>{@code
 * cues.effects(new CgCueEffects() {
 *     public void burst(CgCueDefinition cue, Effects block, CgCueParameters at, float age) { ... play and forget ... }
 *     public Lasting lasting(CgCueDefinition cue) { return new MyLasting(cue); }
 * });
 * }</pre>
 *
 * <ul>
 *   <li>Each entry's spawn condition is the implementation's to test, with {@link CgCueManager#spawns}.</li>
 *   <li>Nothing here may allocate per play once warm: a {@link Lasting} is pooled and reused.</li>
 * </ul>
 */
public interface CgCueEffects {

    /** Plays {@code block} once and forgets it: a burst, an event, a recurrence. {@code age} is how late it is. */
    void burst(CgCueDefinition cue, Effects block, CgCueParameters at, float age);

    /** A new holder of one looping play of {@code cue}'s effects; the manager pools it per definition. */
    Lasting lasting(CgCueDefinition cue);

    /** One looping play's effects: {@code AGameplayCueNotify_Looping}'s, reused across plays after {@link #reset}. */
    interface Lasting {
        /** {@code application} plays and {@code looping} starts, {@code age} seconds in. */
        void start(CgCueParameters at, float age);

        /** {@code write}'s value this frame, into every slot it reaches. {@code value} is the provider's four floats. */
        void write(Write write, float[] value);

        /** {@code removal} plays and {@code looping} stops, each effect ending as it does. */
        void remove(CgCueParameters at);

        /** Whether, once removed, nothing it played is still alive. */
        boolean finished();

        /** Ends everything it plays now. */
        void kill();

        /** Back to new for the next play: parameters to defaults, instances returned, nothing kept from this play. */
        void reset();
    }

    /** A dedicated server's: plays nothing, and is always finished. */
    CgCueEffects NONE = new CgCueEffects() {
        private final Lasting nothing = new Lasting() {
            @Override
            public void start(CgCueParameters at, float age) {
            }

            @Override
            public void write(Write write, float[] value) {
            }

            @Override
            public void remove(CgCueParameters at) {
            }

            @Override
            public boolean finished() {
                return true;
            }

            @Override
            public void kill() {
            }

            @Override
            public void reset() {
            }
        };

        @Override
        public void burst(CgCueDefinition cue, Effects block, CgCueParameters at, float age) {
        }

        @Override
        public Lasting lasting(CgCueDefinition cue) {
            return nothing;
        }
    };
}
