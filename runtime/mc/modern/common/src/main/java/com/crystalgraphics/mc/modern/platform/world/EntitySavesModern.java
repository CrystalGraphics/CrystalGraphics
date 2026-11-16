package com.crystalgraphics.mc.modern.platform.world;

import java.util.function.Function;

import javax.annotation.Nullable;

import com.crystalgraphics.serialization.nbt.CgNbt;
import com.crystalgraphics.serialization.nbt.CgNbtCompound;
import com.crystalgraphics.world.CgEntitySaves;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.Entity;

/**
 * {@link CgEntitySaves} on 1.13.2 to 26.3: an entity's compound under the key {@code crystalgraphics} of a compound the
 * game saves with the entity, which each loader names: Forge's and NeoForge's persistent data, the field Fabric's
 * {@code EntitySaveHook} adds and saves. Server thread.
 *
 * <pre>{@code
 * CgPlatform.provide(CgEntitySaves.HOST, new EntitySavesModern(Entity::getPersistentData));   // a loader's entry
 * }</pre>
 */
public final class EntitySavesModern implements CgEntitySaves.Host {

    /** The key it is kept under. */
    public static final String KEY = "crystalgraphics";

    private final Function<Entity, CompoundTag> root;

    /** {@code root}: the compound, saved with an entity, that the key lives in. */
    public EntitySavesModern(Function<Entity, CompoundTag> root) {
        this.root = root;
    }

    @Override
    @Nullable
    public CgNbtCompound read(Object entity) {
        Tag saved = root.apply((Entity) entity).get(KEY);
        return saved instanceof CompoundTag ? CgNbt.of(saved) : null;
    }

    @Override
    public void write(Object entity, CgNbtCompound data) {
        CgNbt.applyTo(root.apply((Entity) entity), CgNbt.compound().put(KEY, data));
    }
}
