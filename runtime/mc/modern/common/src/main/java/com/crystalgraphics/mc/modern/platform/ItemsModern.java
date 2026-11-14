package com.crystalgraphics.mc.modern.platform;

import com.crystalgraphics.mc.item.CgItems;
import com.crystalgraphics.serialization.CgCodecException;
import com.crystalgraphics.serialization.nbt.CgNbt;
import com.crystalgraphics.serialization.nbt.CgNbtCompound;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
//? if >=1.20.6 {
/*import com.mojang.serialization.DynamicOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import java.util.function.Supplier;
*///?}

/**
 * {@link CgItems} on the modern tree, both sides.
 *
 * <ul>
 *   <li>From 1.20.6 a stack is {@code ItemStack.OPTIONAL_CODEC} run over NBT with the game's registries, which a
 *       component that names a registry entry (an enchantment) needs: the server's from its start, the client's through
 *       {@link #client}. Before it, {@code save}/{@code of} on a compound.</li>
 *   <li>The stamp is {@code hashItemAndComponents} from 1.20.6, the item and tag before.</li>
 * </ul>
 */
public final class ItemsModern implements CgItems.Host {

    public static final ItemsModern INSTANCE = new ItemsModern();

    //? if >=1.20.6 {
    /*private static volatile HolderLookup.Provider server;
    private static volatile Supplier<HolderLookup.Provider> client = () -> null;
    *///?}

    private ItemsModern() {
    }

    //? if >=1.20.6 {
    /*// The server's registries, from its start to its stop; null clears them.
    static void server(MinecraftServer value) {
        server = value == null ? null : value.registryAccess();
    }

    // Where a client reads the registries of the world it is in; set from the client install.
    static void client(Supplier<HolderLookup.Provider> access) {
        client = access;
    }

    private static DynamicOps<Tag> ops() {
        HolderLookup.Provider access = server != null ? server : client.get();
        if (access == null) throw new IllegalStateException("No registries yet: a stack's data is read in a world");
        return RegistryOps.create(NbtOps.INSTANCE, access);
    }
    *///?} else {
    static void server(MinecraftServer value) {
    }
    //?}

    @Override
    public boolean isStack(Object o) {
        return o instanceof ItemStack;
    }

    @Override
    public CgNbtCompound save(Object stack) {
        //? if >=1.20.6 {
        /*Tag tag = ItemStack.OPTIONAL_CODEC.encodeStart(ops(), (ItemStack) stack).result()
                .orElseThrow(() -> new CgCodecException("The game could not save " + stack));
        return CgNbt.of(tag);
        *///?} else {
        return CgNbt.of(((ItemStack) stack).save(new CompoundTag()));
        //?}
    }

    @Override
    public Object load(CgNbtCompound saved) {
        if (saved.isEmpty()) return ItemStack.EMPTY;
        //? if >=1.20.6 {
        /*Tag tag = CgNbt.toHost(saved);
        return ItemStack.OPTIONAL_CODEC.parse(ops(), tag).result()
                .orElseThrow(() -> new CgCodecException("The game could not read the stack " + saved));
        *///?} else {
        CompoundTag tag = CgNbt.toHost(saved);
        return ItemStack.of(tag);
        //?}
    }

    @Override
    public int maxDamage(Object stack) {
        return ((ItemStack) stack).getMaxDamage();
    }

    @Override
    public int stamp(Object stack) {
        ItemStack s = (ItemStack) stack;
        //? if >=1.20.6 {
        /*return ItemStack.hashItemAndComponents(s);
        *///?} else {
        return 31 * s.getItem().hashCode() + (s.getTag() == null ? 0 : s.getTag().hashCode());
        //?}
    }

    @Override
    public CgItems.Shape shape() {
        //? if >=1.20.6 {
        /*return CgItems.Shape.COMPONENTS;
        *///?} else {
        return CgItems.Shape.TAG;
        //?}
    }
}
