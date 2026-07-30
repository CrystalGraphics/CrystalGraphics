package com.crystalgraphics.platform.service;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.util.ResourceLocation;

/**
 * MC 1.7.10 UI sounds, through Minecraft's own {@code SoundHandler}.
 *
 * <p>A {@code soundId} is treated as a {@link ResourceLocation} string, so a caller asking for
 * {@code "button_click"} gets {@code minecraft:button_click} and one asking for {@code "mymod:beep"} gets
 * exactly that. The engine's own ids are deliberately vanilla-shaped —
 * {@code gui.button.press} is what a button asks for — so the common case needs no mapping table.</p>
 *
 * <p><b>Every failure is swallowed.</b> Sound is cosmetic: a malformed id, a sound the resource pack does
 * not define, or a sound engine that has not started yet must not propagate out of a widget's click
 * handler. {@code SoundHandler} itself is null during early startup, which is a real case — a UI can exist
 * before the sound engine does.</p>
 */
public final class SoundService1710 implements CgSoundService {

    @Override
    public void play(String soundId) {
        if (soundId == null || soundId.isEmpty()) return;
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.getSoundHandler() == null) return;
            mc.getSoundHandler().playSound(
                    PositionedSoundRecord.func_147674_a(new ResourceLocation(soundId), 1.0F));
        } catch (Throwable ignored) {
            // A bad id or an unstarted sound engine is not worth failing a click over.
        }
    }
}
