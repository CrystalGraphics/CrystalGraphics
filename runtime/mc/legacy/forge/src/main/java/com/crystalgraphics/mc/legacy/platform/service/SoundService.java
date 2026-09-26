package com.crystalgraphics.mc.legacy.platform.service;

import com.crystalgraphics.platform.service.CgSoundService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.util.ResourceLocation;
//? if >=1.9 {
import net.minecraft.util.SoundEvent;
//?}

/**
 * UI sounds on Forge 1.8–1.12.2. {@code soundId} is a resource location: {@code minecraft:gui.button.press}
 * on 1.8, {@code minecraft:ui.button.click} from 1.9, where sounds became registered {@code SoundEvent}s.
 * An unknown id or an unstarted sound engine plays nothing.
 */
public final class SoundService implements CgSoundService {

    @Override
    public void play(String soundId) {
        if (soundId == null || soundId.isEmpty()) return;
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.getSoundHandler() == null) return;
            //? if >=1.9 {
            SoundEvent event = SoundEvent.REGISTRY.getObject(new ResourceLocation(soundId));
            if (event == null) return;
            mc.getSoundHandler().playSound(PositionedSoundRecord.getMasterRecord(event, 1.0F));
            //?} else {
            /*mc.getSoundHandler().playSound(PositionedSoundRecord.create(new ResourceLocation(soundId), 1.0F));
            *///?}
        } catch (Throwable ignored) {
            // A bad id is not worth failing a click over.
        }
    }
}
