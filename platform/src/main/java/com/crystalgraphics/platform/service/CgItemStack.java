package com.crystalgraphics.platform.service;

/**
 * One item stack as data: the same record on every Minecraft version, what a container's snapshot holds and what the
 * renderer draws an item from.
 *
 * <pre>{@code
 * CgItemStack stack = menu.slots().get(36).stack();
 * if (!stack.isEmpty()) label(stack.item() + " x" + stack.count());     // "minecraft:stone x16"
 * boolean worn = stack.maxDamage() > 0 && stack.damage() > 0;            // a durability bar shows
 * }</pre>
 *
 * <ul>
 *   <li>{@link #item} is the registry name, {@code namespace:path}; an empty stack has {@code ""}.</li>
 *   <li>{@link #damage} is how much durability is used, {@code 0} for an item with none; {@link #maxDamage} is {@code 0}
 *       for an item that does not wear.</li>
 *   <li>{@link #tag} summarises the stack's own data (its NBT, or its components), so a rename or an enchantment is a
 *       different stack. It is a hash: equal tags are not proof of equal data.</li>
 *   <li>{@link #host} is the host's own stack object, for the renderer and tooltips on the client thread. It is left
 *       out of equality, and never touched from another thread. Null on an empty stack or one built in a test.</li>
 * </ul>
 */
public record CgItemStack(String item, int count, int damage, int maxDamage, int tag, Object host) {

    /** Nothing in a slot. */
    public static final CgItemStack EMPTY = new CgItemStack("", 0, 0, 0, 0, null);

    /** Whether the slot holds nothing. */
    public boolean isEmpty() {
        return count <= 0 || item.isEmpty();
    }

    /** A stack of {@code count} of {@code item}, with no durability and no data: what a test builds. */
    public static CgItemStack of(String item, int count) {
        return new CgItemStack(item, count, 0, 0, 0, null);
    }

    /** Equal when the data is: the host's object is the live stack and differs by identity. */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CgItemStack other)) return false;
        if (isEmpty() && other.isEmpty()) return true;
        return count == other.count && damage == other.damage && maxDamage == other.maxDamage && tag == other.tag
                && item.equals(other.item);
    }

    @Override
    public int hashCode() {
        if (isEmpty()) return 0;
        int h = item.hashCode();
        h = 31 * h + count;
        h = 31 * h + damage;
        h = 31 * h + maxDamage;
        return 31 * h + tag;
    }
}
