package com.crystalgraphics.mc.legacy.platform.world;

import com.crystalgraphics.platform.service.CgWorldSound;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.ISound;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.util.ResourceLocation;
//? if >=1.9 {
import net.minecraft.util.SoundCategory;
//?}

/**
 * {@link CgWorldSound} through Minecraft's sound handler on Forge 1.8.9 to 1.12.2: a positional sound, attenuated
 * with distance, in the blocks category from 1.9 (1.8.9 has no categories on a sound). The id is a sounds.json key:
 * {@code minecraft:entity.generic.explode} from 1.9, {@code minecraft:random.explode} on 1.8.9. Client only.
 */
public final class WorldSoundLegacy implements CgWorldSound {

    @Override
    public void play(String sound, double x, double y, double z, float volume, float pitch) {
        if (sound == null || sound.isEmpty()) return;
        ResourceLocation id = new ResourceLocation(sound);
        //? if >=1.9 {
        ISound instance = new PositionedSoundRecord(id, SoundCategory.BLOCKS, volume, pitch, false, 0,
                ISound.AttenuationType.LINEAR, (float) x, (float) y, (float) z);
        //?} else {
        /*ISound instance = new PositionedSoundRecord(id, volume, pitch, (float) x, (float) y, (float) z);
        *///?}
        Minecraft.getMinecraft().getSoundHandler().playSound(instance);
    }
}
