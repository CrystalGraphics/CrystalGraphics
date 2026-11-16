package com.crystalgraphics.mc.modern.platform.world;

import net.minecraft.nbt.CompoundTag;

/**
 * An entity carrying the compound {@link EntitySavesModern} keeps its key in, where the loader has no persistent data
 * of its own: Fabric's {@code EntitySaveHook} adds it to every entity and saves its key with the entity.
 */
public interface SavedEntity {

    /** The compound, made empty the first time it is asked for. */
    CompoundTag crystalgraphics$saved();
}
