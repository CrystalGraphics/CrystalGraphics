package com.crystalgraphics.render.meshpass;

import javax.annotation.Nullable;

/**
 * What a processor may read of the primitive a batch belongs to: its flags and its passes' settings, as Unreal's mesh
 * pass processors read {@code FPrimitiveSceneProxy}.
 *
 * <pre>{@code
 * if (primitive.has(HEAT_SOURCE)) out.draw(batch, THERMAL);
 * CgCustomDepth mark = primitive.customDepth();     // null: not in custom depth
 * }</pre>
 */
public interface CgPrimitiveInfo {

    /** Whether its component carries {@code flag} ({@code component.flag(flag)}). */
    boolean has(CgPrimitiveFlag flag);

    /** The custom depth it is drawn into, or null for none. */
    @Nullable
    CgCustomDepth customDepth();

    /**
     * Whether it draws in the velocity pass this frame (Unreal's {@code PrimitiveHasVelocityForFrame}): it keeps a
     * previous transform, and it moved since last frame, deforms (skinned), or asked to ({@code writesVelocity}). A
     * material moving its vertices by time draws too, which the processor reads off the batch's material. A processor
     * reading it answers per frame, so the velocity pass's commands are not kept.
     */
    boolean hasVelocity();

    /**
     * Whether it keeps last frame's transform, so a velocity of its is known: false for a dynamic primitive, whose rows
     * are the frame's records and keep none. One that does not never draws in the velocity pass; with still solids asked
     * it draws there as still, so its texels count as the engine's (Unreal's dynamic elements, previous = current).
     */
    boolean keepsPrevious();

    /** Whether it grades what it draws by a look of its own: it draws again into that look's layer. */
    boolean graded();
}
