package com.crystalgraphics.mc.modern.platform.service;

import com.crystalgraphics.mc.modern.platform.ResourceIds;
import com.crystalgraphics.platform.service.CgSoundService;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
//? if >=1.19.3 {
import net.minecraft.core.registries.BuiltInRegistries;
//?} else {
/*import net.minecraft.core.Registry;
*///?}
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;

/** UI sounds on MC 1.20.x. {@code soundId} is a resource location, e.g. {@code minecraft:ui.button.click}. */
public final class SoundService implements CgSoundService {

    @Override
    public void play(String soundId) {
        if (soundId == null || soundId.isEmpty()) return;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.getSoundManager() == null) return;

            // 1.21.2 made `get` answer an Optional holder; `getValue` is the nullable lookup.
            // 1.19.3 moved the static registries to BuiltInRegistries.
            //? if >=1.21.2 {
            /*SoundEvent event = BuiltInRegistries.SOUND_EVENT.getValue(ResourceIds.parse(soundId));
            *///?} elif >=1.19.3 {
            SoundEvent event = BuiltInRegistries.SOUND_EVENT.get(ResourceIds.parse(soundId));
            //?} else {
            /*SoundEvent event = Registry.SOUND_EVENT.get(ResourceIds.parse(soundId));
            *///?}
            if (event == null) return;

            mc.getSoundManager().play(SimpleSoundInstance.forUI(event, 1.0F));
        } catch (Throwable ignored) {
            // A bad id or an unstarted sound engine is not worth failing a click over.
        }
    }
}
