package com.crystalgraphics.mc.legacy.platform.world;

import javax.annotation.Nullable;

import com.crystalgraphics.serialization.nbt.CgNbt;
import com.crystalgraphics.serialization.nbt.CgNbtCompound;
import com.crystalgraphics.world.CgEntitySaves;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;

/**
 * {@link CgEntitySaves} on Forge 1.8.9 to 1.12.2: an entity's compound under the key {@code crystalgraphics} of Forge's entity data,
 * which Forge saves with the entity. Server thread.
 */
public final class EntitySavesLegacy implements CgEntitySaves.Host {

    public static final EntitySavesLegacy INSTANCE = new EntitySavesLegacy();
    private static final String KEY = "crystalgraphics";

    private EntitySavesLegacy() {
    }

    @Override
    @Nullable
    public CgNbtCompound read(Object entity) {
        NBTBase saved = ((Entity) entity).getEntityData().getTag(KEY);
        return saved instanceof NBTTagCompound ? CgNbt.of(saved) : null;
    }

    @Override
    public void write(Object entity, CgNbtCompound data) {
        ((Entity) entity).getEntityData().setTag(KEY, (NBTBase) CgNbt.toHost(data));
    }
}
