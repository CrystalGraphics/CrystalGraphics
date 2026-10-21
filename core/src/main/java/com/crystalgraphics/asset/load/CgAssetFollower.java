package com.crystalgraphics.asset.load;

/**
 * A playing instance's hold on its definition: it keeps the one it started with until it adopts a newer one, at its
 * own next step. A {@code VALUE} swap is taken in place; a {@code STRUCTURAL} one restarts it at its age.
 *
 * <pre>{@code
 * CgAssetFollower<CgVfxSystemDefinition> def = new CgAssetFollower<>(NOVA);    // at play
 *
 * // each step, before it reads its definition:
 * switch (def.poll()) {
 *     case VALUE:   takeValues(def.definition()); break;         // the new values into the rows it holds
 *     case RESTART: restartAt(def.definition(), age); break;     // a module added
 *     default:
 * }
 * }</pre>
 *
 * <ul>
 *   <li>{@link #poll()} compares two ints when nothing swapped: no allocation.</li>
 *   <li>Several swaps between two steps adopt as one: a restart if any of them was structural.</li>
 *   <li>Made before its ref published anything, it adopts the first definition as a {@link Adoption#RESTART}.</li>
 * </ul>
 *
 * @param <D> the definition
 */
public final class CgAssetFollower<D> {

    /** What a step must do with the definition it now holds. */
    public enum Adoption {
        /** Nothing swapped. */
        NONE,
        /** Values changed: patch them in. */
        VALUE,
        /** The structure changed: restart on it at the same age. */
        RESTART
    }

    private final CgAssetRef<D> ref;
    private D definition;
    private D previous;
    private int generation;

    public CgAssetFollower(CgAssetRef<D> ref) {
        this.ref = ref;
        this.generation = ref.generation;
        this.definition = ref.loaded();
        this.previous = definition;
    }

    /** Takes the ref's latest definition if it swapped since the last poll; says how to adopt it. */
    public Adoption poll() {
        int g = ref.generation;
        if (g == generation) return Adoption.NONE;
        boolean structural = ref.structural > generation || definition == null;
        previous = definition;
        definition = ref.loaded();
        generation = g;
        return structural ? Adoption.RESTART : Adoption.VALUE;
    }

    /** The definition it holds. */
    public D definition() {
        return definition;
    }

    /** The definition it held before the last adoption: what a patch table diffs against. */
    public D previous() {
        return previous;
    }

    /** The generation it holds. */
    public int generation() {
        return generation;
    }

    public CgAssetRef<D> ref() {
        return ref;
    }
}
