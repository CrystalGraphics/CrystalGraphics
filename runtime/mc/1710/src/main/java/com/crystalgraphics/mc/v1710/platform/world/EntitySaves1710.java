package com.crystalgraphics.mc.v1710.platform.world;

import javax.annotation.Nullable;

import com.crystalgraphics.serialization.nbt.CgNbt;
import com.crystalgraphics.serialization.nbt.CgNbtCompound;
import com.crystalgraphics.world.CgEntitySaves;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;

/**
 * {@link CgEntitySaves} on 1.7.10: an entity's compound under the key {@code crystalgraphics} of Forge's entity data,
 * which Forge saves with the entity. Server thread.
 */
public final class EntitySaves1710 implements CgEntitySaves.Host {

    public static final EntitySaves1710 INSTANCE = new EntitySaves1710();
    private static final String KEY = "crystalgraphics";

    private EntitySaves1710() {
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
