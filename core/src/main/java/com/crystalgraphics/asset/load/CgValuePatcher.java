package com.crystalgraphics.asset.load;

/**
 * Writes a {@code VALUE} swap into what a playing instance holds: the values that differ between two compiles of one
 * definition, into its parameter rows. What a kind's patch table implements; a {@link CgAssetFollower} that answered
 * {@code VALUE} calls it at the instance's next step.
 *
 * <pre>{@code
 * CgValuePatcher<SystemDef, Rows> patcher = (from, to, rows) -> to.patchTable().apply(rows);
 * if (follower.poll() == CgAssetFollower.Adoption.VALUE) patcher.patch(follower.previous(), follower.definition(), rows);
 * }</pre>
 *
 * <ul>
 *   <li>Runs on the frame thread in the instance's step: no allocation.</li>
 *   <li>{@code from} and {@code to} share a layout: a swap that changes one is {@code STRUCTURAL}.</li>
 * </ul>
 *
 * @param <D> the definition
 * @param <T> what the instance holds its values in
 */
@FunctionalInterface
public interface CgValuePatcher<D, T> {

    void patch(D from, D to, T target);
}
