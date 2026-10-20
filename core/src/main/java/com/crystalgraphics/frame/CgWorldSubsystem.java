package com.crystalgraphics.frame;

/**
 * A system that lives and ends with a {@link CgWorld} and registers its tick functions into that world's schedule:
 * Unreal's {@code UWorldSubsystem}.
 *
 * <pre>{@code
 * public final class Cutscenes extends CgWorldSubsystem {
 *     private CgTickFunction evaluate;
 *
 *     @Override protected void initialize() {
 *         evaluate = world().schedule().add(CgTickGroup.SEQUENCES, "mymod:cutscenes", this::evaluate);
 *     }
 *
 *     @Override protected void deinitialize() {
 *         evaluate.close();
 *     }
 *
 *     private void evaluate(float dt) { ... }
 * }
 *
 * Cutscenes cutscenes = world.add(new Cutscenes());     // initialize() runs here
 * world.subsystem(Cutscenes.class);                     // the same instance, anywhere
 * }</pre>
 *
 * <ul>
 *   <li>A subsystem belongs to one world and is added once; nothing in it knows whether its world is the game's
 *       or an editor preview's.</li>
 *   <li>{@link #deinitialize} runs when the world closes, in reverse order of adding. Close the tick functions
 *       there if the world could outlive the subsystem.</li>
 * </ul>
 */
public abstract class CgWorldSubsystem {

    private CgWorld world;

    /** The world it belongs to. Valid from {@link #initialize}. */
    protected final CgWorld world() {
        return world;
    }

    /** Called once, as the world adds it: register tick functions into {@code world().schedule()}. */
    protected abstract void initialize();

    /** Called once, as the world closes. */
    protected void deinitialize() {
    }

    final void attach(CgWorld owner) {
        if (world != null) throw new IllegalStateException(getClass().getName() + " is already in a world");
        world = owner;
        initialize();
    }
}
