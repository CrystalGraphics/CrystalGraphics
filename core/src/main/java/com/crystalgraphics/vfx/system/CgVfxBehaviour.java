package com.crystalgraphics.vfx.system;

import com.crystalgraphics.vfx.CgVfxFrame;
import com.crystalgraphics.vfx.look.CgVfxEnding;
import com.crystalgraphics.vfx.look.CgVfxLook;

import java.util.List;

/**
 * A system's native behaviour: Java code a system instance carries as a component, the way a Blueprint drives a Niagara
 * component. The instance plays its definition's emitters either way; a behaviour adds what data cannot say (a beam's
 * homing, a charge timed to a key) and reads the instance's values as its parameters. A system with none is a burst.
 *
 * <pre>{@code
 * static final CgVfxBehaviour.Factory PULSE = instance -> new CgVfxBehaviour() {
 *     public void tick(CgVfxSystemInstance<?> in, float dt) {
 *         if (in.age() > in.get(LIFE)) in.request(CgVfxExecutionState.INACTIVE, CgVfxExecutionSource.INTERNAL);
 *     }
 * };
 * CgVfxSystemDefinition PULSE_SYSTEM = CgVfxSystemDefinition.of("mymod:pulse", LOOK).withBehaviour(PULSE);
 * }</pre>
 *
 * <p>Played from a file ({@code effect: mymod:pulse}), a behaviour is registered with its contract: the look it draws
 * and the parameters a file must declare, which it reads by name.</p>
 *
 * <pre>{@code
 * CgVfxSystemAssets.effect("mymod:pulse", PULSE, new CgVfxBehaviour.Contract(LOOK, List.of("life")));
 * }</pre>
 *
 * <ul>
 *   <li>One behaviour per instance: the factory runs once, in the instance's constructor, which has its values then.</li>
 *   <li>{@link #done} holds the instance alive past its emitters: a beam still flying with no particle left.</li>
 *   <li>A behaviour that implements {@code CgVfxCue.Live} or {@code CgVfxCue.Authoritative} hears its instance's cue.</li>
 * </ul>
 */
public interface CgVfxBehaviour {

    /** Each tick, before the instance's emitters book theirs. */
    void tick(CgVfxSystemInstance<?> instance, float dt);

    /** Draws what it draws itself this frame; its emitters' particles are the system's to draw. */
    default void submit(CgVfxSystemInstance<?> instance, CgVfxFrame frame) {
    }

    /** Its instance was asked to stop spawning ({@link CgVfxExecutionState#INACTIVE}): its own graceful end. */
    default void deactivated(CgVfxSystemInstance<?> instance) {
    }

    /** Its instance is ending by {@code ending} rather than its own way: heard before it deactivates. */
    default void ending(CgVfxSystemInstance<?> instance, CgVfxEnding ending) {
    }

    /**
     * The point its instance follows moved to {@code (x, y, z)}: true if it placed what it moves itself (a muzzle riding
     * the hands while what it fired stays in the world), false to move the instance's origin there.
     */
    default boolean followed(CgVfxSystemInstance<?> instance, double x, double y, double z) {
        return false;
    }

    /** Whether it lets its instance complete once the emitters have finished. */
    default boolean done(CgVfxSystemInstance<?> instance) {
        return true;
    }

    /** Makes an instance's behaviour, once, as it is played. */
    @FunctionalInterface
    interface Factory {
        CgVfxBehaviour make(CgVfxSystemInstance<?> instance);
    }

    /**
     * What a behaviour played from a file needs of it: the look it draws with, whose parameters a file's {@code user}
     * values set by name, and the names a file must declare. A file lacking one fails to compile, naming it.
     *
     * @param look     every parameter and kit the behaviour reads and draws
     * @param required the user parameter names a file must declare
     */
    record Contract(CgVfxLook look, List<String> required) {
        public Contract {
            if (look == null) throw new IllegalArgumentException("a contract needs a look");
            required = List.copyOf(required);
        }
    }
}
