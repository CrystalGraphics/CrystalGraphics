package com.crystalgraphics.sequence.condition;

import javax.annotation.Nullable;

import com.crystalgraphics.sequence.CgSequenceTarget;
import com.crystalgraphics.sequence.definition.CgSequenceCondition;

/**
 * A condition kind's reader: whether a leaf of its kind holds for the role it names. Unreal's
 * {@code UMovieSceneCondition::EvaluateConditionInternal}, one class a kind; registered in {@link CgConditionKinds}.
 *
 * <pre>{@code
 * // condition: { kind: mymod:weather, is: [rain, storm] }
 * CgConditionKinds.register("mymod:weather", (leaf, role) -> leaf.anyValue() ? !weather.isClear() : leaf.is().contains(weather.name()));
 * }</pre>
 *
 * <ul>
 *   <li>Read presentation alone: it runs on a client, once, as play binds its section, and its answer holds for the play.</li>
 *   <li>{@code role} is null where the leaf names none, or its role was skipped.</li>
 * </ul>
 */
@FunctionalInterface
public interface CgConditionKind {

    boolean holds(CgSequenceCondition.Leaf leaf, @Nullable CgSequenceTarget role);
}
