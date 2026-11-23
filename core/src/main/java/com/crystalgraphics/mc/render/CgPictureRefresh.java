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
    /**
     * Each game tick while it is shown, {@link CgHostPictures#TICK_HZ} a second, all such pictures on the same frames:
     * what changes on a tick, as a tooltip's lines and most tooltip mods' frames do.
     */
    TICK,
    /** When what it draws changes (its kind's stamp) or {@link CgHostPicture#invalidate} is called. */
    ON_CHANGE,
    /** Once at each size it is shown at; {@link CgHostPicture#invalidate} draws it again. */
    ONCE
}
