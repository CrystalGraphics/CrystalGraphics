package com.crystalgraphics.mc.render;

/**
 * When a {@link CgHostPicture} Minecraft draws is drawn again. Unreal's {@code EThumbnailRenderFrequency}.
 *
 * <pre>{@code
 * CgCustomPicture.of("mymod:radar").refresh(CgPictureRefresh.REALTIME).renderer(Radar::draw);
 * }</pre>
 */
public enum CgPictureRefresh {
    /** Every frame it was shown in the last: an animation, the player, a clock. Vanilla redraws its own so. */
    REALTIME,
    /** When what it draws changes (its kind's stamp) or {@link CgHostPicture#invalidate} is called. */
    ON_CHANGE,
    /** Once at each size it is shown at; {@link CgHostPicture#invalidate} draws it again. */
    ONCE
}
