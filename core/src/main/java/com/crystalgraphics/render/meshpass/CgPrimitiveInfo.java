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
     * Whether it draws in the velocity pass this frame: it moved since last frame, it is skinned, or it asked to
     * ({@code writesVelocity}). A processor reading it answers per frame, so the velocity pass's commands are not kept.
     */
    boolean hasVelocity();

    /** Whether it grades what it draws by a look of its own: it draws again into that look's layer. */
    boolean graded();
}
