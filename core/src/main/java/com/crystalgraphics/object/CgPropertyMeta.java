package com.crystalgraphics.object;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import lombok.Builder;
import lombok.Value;
import lombok.experimental.Accessors;

/**
 * What a property tells the editor and the systems that read it, beyond its type and flags: the format's words for
 * Unreal's property metadata ({@code UM::ToolTip}, {@code ClampMin}, {@code EditCondition}…), one to one.
 *
 * <p>Set through the {@link CgType} builder, never built by hand:</p>
 *
 * <pre>{@code
 * .property("quadratic", CgValueType.FLOAT).quantity(CgQuantity.RATE)
 *         .range(0, null)                                    // ClampMin 0
 *         .visibleWhen("use_quadratic", true)                // EditCondition + EditConditionHides
 *         .description("Velocity lost a second, as a share of its square.")
 * .property("count", CgValueType.INT).rolls(CgPropertyMeta.Rolls.LOOP).timeline(CgPropertyMeta.Timeline.KEY_VALUE)
 * }</pre>
 *
 * <ul>
 *   <li>A {@code null} bound is open on that side.</li>
 *   <li>{@link #by()}'s first entry is the default lookup a curve uses.</li>
 *   <li>{@link #replication()} is read only for {@link CgPropertyFlag#NETWORKED} properties; null takes the default
 *       by type (floats interpolate, positions are goals, the rest snap).</li>
 * </ul>
 */
@Value
@Builder(toBuilder = true)
@Accessors(fluent = true)
public class CgPropertyMeta {

    /** What an editor's timeline drags ({@code timeline:} in {@code modules.yaml}). */
    public enum Timeline { KEY_TIME, KEY_VALUE, LOOP_COUNT }

    /** When a range rolls ({@code rolls:}): per particle, per loop, per instance or per draw. */
    public enum Rolls { PARTICLE, LOOP, INSTANCE, DRAW }

    /** How a networked value reaches viewers. */
    public enum Replication { INTERPOLATE, GOAL, SNAP }

    /** No metadata at all. */
    public static final CgPropertyMeta NONE = builder().build();

    /** {@code ToolTip}. */
    String description;
    /** {@code DisplayName}; null shows the property's name. */
    String displayName;
    /** {@code Category}. */
    String category;
    /** {@code ClampMin}, {@code ClampMax}. */
    Double min;
    Double max;
    /** {@code UIMin}, {@code UIMax}. */
    Double uiMin;
    Double uiMax;
    /** {@code InlineEditConditionToggle}: the property this bool switches on. */
    String toggleFor;
    /** {@code EditCondition} with {@code EditConditionHides}: shown only while these properties hold these values. */
    @Builder.Default
    Map<String, Object> visibleWhen = Collections.emptyMap();
    Timeline timeline;
    /** The lookups a curve on it may use; the first is the default. */
    @Builder.Default
    List<String> by = Collections.emptyList();
    Rolls rolls;
    Replication replication;
}
