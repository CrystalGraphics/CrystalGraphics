package com.crystalgraphics.mc.item;

import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecs;
import com.crystalgraphics.serialization.nbt.CgNbtCompound;
import com.crystalgraphics.serialization.nbt.CgNbtList;
import com.crystalgraphics.serialization.nbt.CgNbtTag;
import com.crystalgraphics.serialization.nbt.CgNbtValue;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * One item stack as a value: the item, how many, and <b>all</b> of its data, the same on every Minecraft version.
 * Nothing in it is the game's: it can cross threads, sit in a map, be compared, edited and sent, and turned into the
 * game's own stack only where you ask ({@link #toHost}).
 *
 * <pre>{@code
 * CgItemStack stack = CgItemStack.of(slot.getItem());                  // a copy of the game's stack
 * String label = stack.name() != null ? stack.name() : stack.item();   // "Excalibur" or "minecraft:iron_sword"
 * int sharp = stack.enchantments().getOrDefault("minecraft:sharpness", 0);
 * int charge = stack.customData().getInt("mymod:charge");              // your own NBT on the item
 *
 * CgItemStack better = stack.withName("Excalibur").withEnchantment("minecraft:sharpness", 5)
 *         .withCustomData(d -> d.put("mymod:charge", 3));              // edits answer a new stack
 * player.getInventory().setItem(0, better.toHost());                   // the game's, built from the data
 *
 * CgItemStack test = CgItemStack.of("minecraft:stone", 16);            // no game: a test, a tool, the harness
 * }</pre>
 *
 * <h2>The data</h2>
 * A stack's data is its <b>components</b>, as 1.21.5 and later keep them: a compound of {@code namespace:path} keys
 * ({@code minecraft:custom_name}, {@code minecraft:lore}, {@code minecraft:enchantments}, {@code minecraft:damage},
 * {@code minecraft:custom_data}, ...). Versions before 1.20.5 saved a stack as {@code {id, Count, tag}}; their tags
 * are projected into the same keys, so code written against components runs on every version:
 *
 * <table>
 * <caption>An older tag's key, as a component</caption>
 * <tr><td>{@code Damage}</td><td>{@code minecraft:damage}</td></tr>
 * <tr><td>{@code display.Name}, {@code display.Lore}</td><td>{@code minecraft:custom_name}, {@code minecraft:lore}</td></tr>
 * <tr><td>{@code Enchantments}, {@code StoredEnchantments}</td><td>{@code minecraft:enchantments}, {@code minecraft:stored_enchantments}</td></tr>
 * <tr><td>{@code Unbreakable}, {@code CustomModelData}, {@code RepairCost}</td><td>{@code minecraft:unbreakable}, {@code minecraft:custom_model_data}, {@code minecraft:repair_cost}</td></tr>
 * <tr><td>every other key</td><td>inside {@code minecraft:custom_data}</td></tr>
 * </table>
 *
 * <ul>
 *   <li><b>{@link #customData} is where a mod's own NBT lives on every version</b>: on 1.20.5 and later it is the
 *       {@code minecraft:custom_data} component, before that the rest of the item's {@code tag}.</li>
 *   <li>Components the game defines after 1.20.5 ({@code minecraft:food}, {@code minecraft:attribute_modifiers}) are
 *       there on the versions that have them, as the game wrote them, and read through {@link #component}. A version
 *       before 1.20.5 has nowhere to put one: {@link #toHost} throws, naming it.</li>
 *   <li>A name is a chat component, kept whole; {@link #name} is its text. Older versions' JSON text is projected into
 *       the same tag, and {@code §} codes before 1.13 stay in the text.</li>
 *   <li>Enchantments are {@code {id: level}}; before 1.13 the id is the game's number, as text ({@code "16"}).</li>
 *   <li>{@link #maxDamage} is the item's, not data: {@code 0} on a stack built in Java or by a codec, filled in by
 *       {@link #of(Object)}.</li>
 * </ul>
 *
 * <ul>
 *   <li><b>Equal when the item, count and data are</b>; the max damage is left out. Every empty stack is equal.</li>
 *   <li>{@link #stamp} is a hash of item and data, for noticing change; {@link CgItems#stampOf} is the game's own, taken
 *       without building a copy.</li>
 *   <li>It is a copy: editing the game's stack afterwards does not change it, and {@link #components} answers a copy.</li>
 * </ul>
 */
public final class CgItemStack {

    /** Nothing in a slot. */
    public static final CgItemStack EMPTY = new CgItemStack("", 0, 0, new CgNbtCompound());

    /** A stack as {@code {id, count, components}}, the modern shape: for a file, the wire or a data pack. */
    public static final CgCodec<CgItemStack> CODEC = CgCodecs.record(
            CgCodecs.field("id", CgCodecs.STRING, CgItemStack::item),
            CgCodecs.optionalField("count", CgCodecs.INT, CgItemStack::count, 1),
            CgCodecs.optionalField("components", CgNbtCompound.CODEC, CgItemStack::components, new CgNbtCompound()),
            (id, count, components) -> new CgItemStack(id, count, 0, components));

    private final String item;
    private final int count;
    private final int maxDamage;
    private final CgNbtCompound components;

    /**
     * @param item       the registry name, {@code namespace:path}; {@code ""} for an empty stack
     * @param count      how many; {@code 0} or less is empty
     * @param maxDamage  the item's durability, {@code 0} for one that does not wear or is not known
     * @param components the data, copied
     */
    public CgItemStack(String item, int count, int maxDamage, CgNbtCompound components) {
        this.item = item;
        this.count = count;
        this.maxDamage = maxDamage;
        this.components = components.copy();
    }

    /** A stack of {@code count} of {@code item} with no data: what a test builds. */
    public static CgItemStack of(String item, int count) {
        return new CgItemStack(item, count, 0, new CgNbtCompound());
    }

    /** A copy of the game's stack, on every version; {@link #EMPTY} for null. Throws outside Minecraft. */
    public static CgItemStack of(@Nullable Object hostStack) {
        return CgItems.of(hostStack);
    }

    /** The game's own stack, built from this one: an {@code ItemStack}, null for nothing on 1.7.10 to 1.10.2. */
    public <V> V toHost() {
        return CgItems.toHost(this);
    }

    // ---- the value ----

    /** The registry name, {@code namespace:path}; {@code ""} when empty. */
    public String item() {
        return item;
    }

    public int count() {
        return count;
    }

    /** The item's durability, {@code 0} when it has none or this stack was not read from the game. */
    public int maxDamage() {
        return maxDamage;
    }

    public boolean isEmpty() {
        return count <= 0 || item.isEmpty();
    }

    /** The data: a copy of the components, as the game's newest version keeps them. */
    public CgNbtCompound components() {
        return components.copy();
    }

    // ---- facets: each reads what it needs from the data and nothing else ----

    /** Durability used; {@code 0} for an item that does not wear. */
    public int damage() {
        return components.getInt(ItemShapes.DAMAGE);
    }

    /** Whether any durability is used: a bar shows. */
    public boolean isDamaged() {
        return maxDamage > 0 && damage() > 0;
    }

    /** The custom name's text, styling dropped, or null when the stack has none (the item's own name shows). */
    @Nullable
    public String name() {
        CgNbtTag name = components.get(ItemShapes.CUSTOM_NAME);
        return name == null ? null : TextComponents.plain(name);
    }

    /** The custom name as the chat component it is, or null. */
    @Nullable
    public CgNbtTag nameComponent() {
        CgNbtTag name = components.get(ItemShapes.CUSTOM_NAME);
        return name == null ? null : name.copy();
    }

    /** The lore's lines as text; empty when there is none. */
    public List<String> lore() {
        return ItemShapes.plainLines(components.getList(ItemShapes.LORE));
    }

    /** Enchantment id to level, in the stack's order; empty when there are none. Ids are {@code minecraft:sharpness}, or numbers before 1.13. */
    public Map<String, Integer> enchantments() {
        return levelsOf(components.getCompound(ItemShapes.ENCHANTMENTS));
    }

    /** The level of one enchantment, {@code 0} when absent. */
    public int enchantment(String id) {
        return enchantments().getOrDefault(id, 0);
    }

    /** The enchantments an enchanted book stores. */
    public Map<String, Integer> storedEnchantments() {
        return levelsOf(components.getCompound(ItemShapes.STORED_ENCHANTMENTS));
    }

    public boolean isEnchanted() {
        return !enchantments().isEmpty();
    }

    public boolean isUnbreakable() {
        return components.has(ItemShapes.UNBREAKABLE);
    }

    /** The custom model data number, {@code 0} when absent. A later version's is a compound: read it with {@link #component}. */
    public int customModelData() {
        return components.getInt(ItemShapes.CUSTOM_MODEL_DATA);
    }

    /** A mod's own data on the item, a copy: empty when there is none. See the class javadoc. */
    public CgNbtCompound customData() {
        return components.getCompound(ItemShapes.CUSTOM_DATA).copy();
    }

    public boolean has(String component) {
        return components.has(component);
    }

    /** One component by key, a copy, or null. */
    @Nullable
    public CgNbtTag component(String key) {
        CgNbtTag tag = components.get(key);
        return tag == null ? null : tag.copy();
    }

    // ---- edits: each answers a new stack ----

    public CgItemStack withItem(String item) {
        return new CgItemStack(item, count, maxDamage, components);
    }

    public CgItemStack withCount(int count) {
        return new CgItemStack(item, count, maxDamage, components);
    }

    public CgItemStack withMaxDamage(int maxDamage) {
        return new CgItemStack(item, count, maxDamage, components);
    }

    public CgItemStack withDamage(int damage) {
        return edit(c -> {
            if (damage == 0) c.remove(ItemShapes.DAMAGE); else c.put(ItemShapes.DAMAGE, damage);
        });
    }

    /** Names the stack with plain text. */
    public CgItemStack withName(String name) {
        return edit(c -> c.put(ItemShapes.CUSTOM_NAME, CgNbtValue.ofString(name)));
    }

    /** Names the stack with a chat component (a string, or a compound with {@code text}, {@code color}, {@code extra}). */
    public CgItemStack withName(CgNbtTag component) {
        return edit(c -> c.put(ItemShapes.CUSTOM_NAME, component.copy()));
    }

    /** Back to the item's own name. */
    public CgItemStack withoutName() {
        return without(ItemShapes.CUSTOM_NAME);
    }

    /** Sets the lore, one plain-text line each; none removes it. */
    public CgItemStack withLore(List<String> lines) {
        if (lines.isEmpty()) return without(ItemShapes.LORE);
        return edit(c -> {
            CgNbtList list = new CgNbtList();
            for (String line : lines) list.add(CgNbtValue.ofString(line));
            c.put(ItemShapes.LORE, list);
        });
    }

    /** Sets one enchantment's level; {@code 0} removes it. */
    public CgItemStack withEnchantment(String id, int level) {
        Map<String, Integer> levels = new LinkedHashMap<>(enchantments());
        if (level <= 0) levels.remove(id); else levels.put(id, level);
        return edit(c -> {
            if (levels.isEmpty()) {
                c.remove(ItemShapes.ENCHANTMENTS);
                return;
            }
            CgNbtCompound out = new CgNbtCompound();
            levels.forEach(out::put);
            c.put(ItemShapes.ENCHANTMENTS, out);
        });
    }

    public CgItemStack withUnbreakable(boolean unbreakable) {
        return edit(c -> {
            if (unbreakable) c.put(ItemShapes.UNBREAKABLE, new CgNbtCompound()); else c.remove(ItemShapes.UNBREAKABLE);
        });
    }

    /** Edits a copy of {@link #customData}; the stack answered holds the result, and an emptied one drops the component. */
    public CgItemStack withCustomData(Consumer<CgNbtCompound> edit) {
        CgNbtCompound data = customData();
        edit.accept(data);
        return data.isEmpty() ? without(ItemShapes.CUSTOM_DATA) : with(ItemShapes.CUSTOM_DATA, data);
    }

    /** Sets a component by key; the key is {@code namespace:path}. */
    public CgItemStack with(String component, CgNbtTag value) {
        return edit(c -> c.put(component, value.copy()));
    }

    public CgItemStack without(String component) {
        if (!components.has(component)) return this;
        return edit(c -> c.remove(component));
    }

    private CgItemStack edit(Consumer<CgNbtCompound> edit) {
        CgNbtCompound c = components.copy();
        edit.accept(c);
        return new CgItemStack(item, count, maxDamage, c);
    }

    // ---- comparing ----

    /** A hash of the item and its data, count left out: two stacks that stack together share it. */
    public int stamp() {
        return isEmpty() ? 0 : 31 * item.hashCode() + components.hashCode();
    }

    /** The same item with the same data, whatever the count: what vanilla's {@code isSameItemSameComponents} asks. */
    public boolean stacksWith(CgItemStack other) {
        if (isEmpty() || other.isEmpty()) return isEmpty() && other.isEmpty();
        return item.equals(other.item) && components.equals(other.components);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CgItemStack)) return false;
        CgItemStack other = (CgItemStack) o;
        if (isEmpty() || other.isEmpty()) return isEmpty() && other.isEmpty();
        return count == other.count && item.equals(other.item) && components.equals(other.components);
    }

    @Override
    public int hashCode() {
        return isEmpty() ? 0 : 31 * stamp() + count;
    }

    @Override
    public String toString() {
        if (isEmpty()) return "empty";
        return count + " x " + item + (components.isEmpty() ? "" : " " + components);
    }

    /** The levels of an enchantment compound, in either spelling: {@code {id: level}} or 1.20.5-1.21.4's {@code {levels:{id: level}}}. */
    static Map<String, Integer> levelsOf(CgNbtCompound ench) {
        if (ench.isEmpty()) return Collections.emptyMap();
        CgNbtCompound levels = ench.has("levels", CgNbtTag.Type.COMPOUND) ? ench.getCompound("levels") : ench;
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String id : levels.keys()) {
            if (levels.get(id) instanceof CgNbtValue && ((CgNbtValue) levels.get(id)).isNumber()) out.put(id, levels.getInt(id));
        }
        return Collections.unmodifiableMap(out);
    }
}
