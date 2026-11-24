package com.crystalgraphics.mc.item;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgService;
import com.crystalgraphics.serialization.nbt.CgNbtCompound;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;

/**
 * The game's item stacks, copied into {@link CgItemStack} and built back, on every version. Most code calls
 * {@link CgItemStack#of(Object)} and {@link CgItemStack#toHost()}; this is where a host plugs in and where the cheap
 * questions that need no copy live.
 *
 * <pre>{@code
 * CgItemStack stack = CgItemStack.of(menu.getSlot(3).getItem());
 * int stamp = CgItems.stampOf(menu.getSlot(3).getItem());       // the game's own hash, no copy: notices a change
 * if (stamp != lastStamp) stack = CgItemStack.of(hostStack);    // copy only when it changed
 * }</pre>
 *
 * A host, from its install, both sides (the data of a stack needs no player):
 *
 * <pre>{@code
 * CgPlatform.provide(CgItems.HOST, new ItemsModern());
 * }</pre>
 *
 * An item by the game's raw id (what an entity's hand reports, {@code CgEntityProperties.MAIN_ITEM}), against an item
 * id or an item tag:
 *
 * <pre>{@code
 * String name = CgItems.name(itemId);                          // "minecraft:bow", or null for none
 * boolean sword = CgItems.matches(itemId, "#minecraft:swords"); // an item tag: every item in it
 * boolean bow = CgItems.matches(itemId, "minecraft:bow");       // one item
 * }</pre>
 */
public final class CgItems {

    /** How a version saves a stack; {@link ItemShapes} maps each to components. */
    public enum Shape {
        /** 1.20.5 and later: {@code {id, count, components}}. */
        COMPONENTS,
        /** 1.13 to 1.20.4: {@code {id, Count, tag}}, text as JSON, enchantments by name. */
        TAG,
        /** 1.7.10 to 1.12.2: {@code {id, Count, Damage, tag}}, text plain, enchantments by number. */
        LEGACY
    }

    /** What a host answers: its version's stack saved and loaded in its own shape, one class per era, {@code Items<Era>}. */
    public interface Host {

        /** Whether {@code o} is the game's item stack. */
        boolean isStack(Object o);

        /** The stack as the game saves it, with {@code id} always the registry name: {@link #shape()}'s form. */
        CgNbtCompound save(Object stack);

        /** A stack from {@link #save}'s form; the game's empty stack for an empty compound. */
        Object load(CgNbtCompound saved);

        /** The item's durability, {@code 0} for one that does not wear. */
        int maxDamage(Object stack);

        /** The most of the item one stack holds: {@code ItemStack.getMaxStackSize()}. */
        int maxStackSize(Object stack);

        /** The game's own hash of a stack's item and data, count left out, taken without a copy. */
        int stamp(Object stack);

        Shape shape();

        /** The registry name of the item the game numbers {@code itemId}, or null for none. */
        @Nullable
        String name(int itemId);

        /** Whether that item is in item tag {@code tag} ({@code minecraft:swords}); false where the version has no item tags. */
        boolean inTag(int itemId, String tag);

        /** The registry names of the items in item tag {@code tag}, in the tag's order; none where the version has no item tags. */
        default List<String> tagItems(String tag) {
            return Collections.emptyList();
        }

        Host NONE = new Host() {
            @Override public boolean isStack(Object o) { return false; }
            @Override public CgNbtCompound save(Object stack) { throw absent(); }
            @Override public Object load(CgNbtCompound saved) { throw absent(); }
            @Override public int maxDamage(Object stack) { throw absent(); }
            @Override public int maxStackSize(Object stack) { throw absent(); }
            @Override public int stamp(Object stack) { throw absent(); }
            @Override public Shape shape() { return Shape.COMPONENTS; }
            @Override public String name(int itemId) { return null; }
            @Override public boolean inTag(int itemId, String tag) { return false; }

            private IllegalStateException absent() {
                return new IllegalStateException("No game here: nothing provides crystalgraphics:items (a test, or a dedicated tool)");
            }
        };
    }

    /** The slot a host fills. */
    public static final CgService<Host> HOST = CgService.of("crystalgraphics:items", Host.NONE);

    private CgItems() {
    }

    private static Host host() {
        return CgPlatform.get(HOST);
    }

    /** The registry name of the item the game numbers {@code itemId} ({@code minecraft:bow}), or null for none or no game. */
    @Nullable
    public static String name(int itemId) {
        return itemId < 0 ? null : host().name(itemId);
    }

    /**
     * Whether the item the game numbers {@code itemId} is what {@code reference} names: an item id ({@code minecraft:bow})
     * or an item tag ({@code #minecraft:swords}, every item in it). An item no host knows matches nothing, as does a tag
     * on a version without item tags (before 1.13).
     */
    public static boolean matches(int itemId, String reference) {
        if (itemId < 0) return false;
        if (reference.startsWith("#")) return host().inTag(itemId, reference.substring(1));
        return reference.equals(host().name(itemId));
    }

    /** The items in item tag {@code tag} ({@code minecraft:planks}, a leading {@code #} allowed); none before 1.13 or with no game. */
    public static List<String> tagItems(String tag) {
        return host().tagItems(tag.startsWith("#") ? tag.substring(1) : tag);
    }

    /** A copy of the game's stack; {@link CgItemStack#EMPTY} for null or an empty one. */
    public static CgItemStack of(@Nullable Object hostStack) {
        if (hostStack == null) return CgItemStack.EMPTY;
        Host host = host();
        if (!host.isStack(hostStack)) {
            throw new IllegalArgumentException("Not the game's item stack: " + hostStack.getClass().getName());
        }
        return ItemShapes.fromSaved(host.save(hostStack), host.shape(), host.maxDamage(hostStack), host.maxStackSize(hostStack));
    }

    /** The game's own stack, built from {@code stack}; the game's empty stack (null before 1.11) for an empty one. */
    @SuppressWarnings("unchecked")
    public static <V> V toHost(CgItemStack stack) {
        Host host = host();
        return (V) host.load(ItemShapes.toSaved(stack, host.shape()));
    }

    /** How this version saves a stack. */
    public static Shape shape() {
        return host().shape();
    }

    /** The game's hash of the stack's item and data, with no copy; {@code 0} for null. Compare with an earlier call's, not with {@link CgItemStack#stamp}. */
    public static int stampOf(@Nullable Object hostStack) {
        return hostStack == null ? 0 : host().stamp(hostStack);
    }
}
