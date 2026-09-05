package com.crystalgraphics.mc.platform.service;

import com.crystalgraphics.platform.service.CgSoundService;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;

/** UI sounds on MC 1.20.x. {@code soundId} is a resource location, e.g. {@code minecraft:ui.button.click}. */
public final class SoundService1201 implements CgSoundService {

    @Override
    public void play(String soundId) {
        if (soundId == null || soundId.isEmpty()) return;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.getSoundManager() == null) return;

            SoundEvent event = BuiltInRegistries.SOUND_EVENT.get(new ResourceLocation(soundId));
            if (event == null) return;

            mc.getSoundManager().play(SimpleSoundInstance.forUI(event, 1.0F));
        } catch (Throwable ignored) {
            // A bad id or an unstarted sound engine is not worth failing a click over.
        }
    }
}
