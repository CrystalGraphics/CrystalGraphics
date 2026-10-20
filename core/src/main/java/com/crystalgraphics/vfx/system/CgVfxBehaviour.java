package com.crystalgraphics.vfx.system;

import com.crystalgraphics.vfx.CgVfxFrame;

/**
 * A system's native behaviour: Java code a system instance carries as a component, the way a Blueprint drives a Niagara
 * component. The instance plays its definition's emitters either way; a behaviour adds what data cannot say (a beam's
 * homing, a charge timed to a key) and reads the instance's values as its parameters. A system with none is a burst.
 *
 * <pre>{@code
 * static final CgVfxBehaviour.Factory PULSE = instance -> new CgVfxBehaviour() {
 *     public void tick(CgVfxSystemInstance in, float dt) {
 *         if (in.age() > in.get(LIFE)) in.request(CgVfxExecutionState.INACTIVE, CgVfxExecutionSource.INTERNAL);
 *     }
 * };
 * CgVfxSystemDefinition PULSE_SYSTEM = CgVfxSystemDefinition.of("mymod:pulse", LOOK).withBehaviour(PULSE);
 * }</pre>
 *
 * <ul>
 *   <li>One behaviour per instance: the factory runs once, in the instance's constructor.</li>
 *   <li>{@link #done} holds the instance alive past its emitters: a beam still flying with no particle left.</li>
 * </ul>
 */
public interface CgVfxBehaviour {

    /** Each tick, before the instance's emitters book theirs. */
    void tick(CgVfxSystemInstance instance, float dt);

    /** Draws what it draws itself this frame; its emitters' particles are the system's to draw. */
    default void submit(CgVfxSystemInstance instance, CgVfxFrame frame) {
    }

    /** Its instance was asked to stop spawning ({@link CgVfxExecutionState#INACTIVE}): its own graceful end. */
    default void deactivated(CgVfxSystemInstance instance) {
    }

    /** Whether it lets its instance complete once the emitters have finished. */
    default boolean done(CgVfxSystemInstance instance) {
        return true;
    }

    /** Makes an instance's behaviour, once, as it is played. */
    @FunctionalInterface
    interface Factory {
        CgVfxBehaviour make(CgVfxSystemInstance instance);
    }
}
