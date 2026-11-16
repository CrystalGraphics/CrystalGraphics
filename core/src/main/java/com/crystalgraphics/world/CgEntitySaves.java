package com.crystalgraphics.world;

import javax.annotation.Nullable;

import com.crystalgraphics.net.object.CgNetEntities;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgService;
import com.crystalgraphics.serialization.nbt.CgNbtCompound;

/**
 * What CrystalGraphics saves with an entity: one compound kept in the entity's own saved data, so it travels with the
 * entity through chunk unloads and dies with it. A persisted per-holder net type on entities saves here
 * ({@code CgNetType.Builder#persisted}), each type under its name. Unreal's {@code CPF_SaveGame} properties, saved with
 * their actor. Server.
 *
 * <pre>{@code
 * CgNbtCompound saved = CgEntitySaves.read(zombie);       // empty where it has none
 * saved.put("mymod:rage", rage);
 * CgEntitySaves.write(zombie, saved);                     // saved when the game saves the entity
 * }</pre>
 *
 * <p>A persisted type's change costs a flag: it is encoded once, as the game saves the entity (a chunk unloading, an
 * autosave), every type of that entity in one read and one write.</p>
 *
 * <ul>
 *   <li>Each era keeps it where its loader keeps a mod's entity data: Forge's and NeoForge's persistent data, legacy
 *       Forge's and 1.7.10's entity data, a field of the entity's on Fabric, saved and loaded with it.</li>
 *   <li>Never a player's: a player's objects are saved in the world's own file for them, by profile.</li>
 *   <li>Without a game (a test, a tool) nothing is kept: {@link #read} answers empty and {@link #write} does nothing.</li>
 * </ul>
 */
public final class CgEntitySaves {

    /** What a host does: hand over and take back an entity's compound, its game's tag converted at the seam. */
    public interface Host {
        /** {@code entity}'s compound, or null where it has none. */
        @Nullable
        CgNbtCompound read(Object entity);

        /** Replaces {@code entity}'s compound: what the game saves with it from now. */
        void write(Object entity, CgNbtCompound data);

        /**
         * Whether the host calls {@link CgEntitySaves#saving} as the game saves each entity, before its own data is
         * written: a change is then written once, at the save. Without, each change is written at the tick it happens.
         */
        boolean savesWhenSaved();

        Host NONE = new Host() {
            @Override public CgNbtCompound read(Object entity) { return null; }
            @Override public void write(Object entity, CgNbtCompound data) { }
            @Override public boolean savesWhenSaved() { return false; }
        };
    }

    /** The slot a host fills. */
    public static final CgService<Host> HOST = CgService.of("crystalgraphics:entity_saves", Host.NONE);

    private CgEntitySaves() {
    }

    /** {@code entity}'s compound, a copy to change and {@link #write}; empty where it has none. */
    public static CgNbtCompound read(Object entity) {
        CgNbtCompound saved = CgPlatform.get(HOST).read(entity);
        return saved != null ? saved : new CgNbtCompound();
    }

    /** {@code data} becomes what is saved with {@code entity}. */
    public static void write(Object entity, CgNbtCompound data) {
        CgPlatform.get(HOST).write(entity, data);
    }

    /**
     * A host, as the game starts saving {@code entity} and before its own data is written: what changed on it since its
     * last save is written now. A check and nothing more where nothing changed. Server thread.
     */
    public static void saving(Object entity) {
        CgNetEntities.saving(entity);
    }

    /** Whether the host writes at each entity's save ({@link Host#savesWhenSaved}). */
    public static boolean hooked() {
        return CgPlatform.get(HOST).savesWhenSaved();
    }
}
