package com.crystalgraphics.mc.item;

import com.crystalgraphics.serialization.CgCodecException;
import com.crystalgraphics.serialization.nbt.CgNbtCompound;
import com.crystalgraphics.serialization.nbt.CgNbtList;
import com.crystalgraphics.serialization.nbt.CgNbtTag;
import com.crystalgraphics.serialization.nbt.CgNbtValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Each era's saved stack, as the modern component shape and back. Pure on tags, so a unit test runs every era.
 *
 * <ul>
 *   <li>{@code COMPONENTS} (1.20.5 and later): {@code {id, count, components}}, taken as the game wrote it.</li>
 *   <li>{@code TAG} (1.13 to 1.20.4): {@code {id, Count, tag:{Damage, display, Enchantments, ...}}}, text as JSON.</li>
 *   <li>{@code LEGACY} (1.7.10 to 1.12.2): {@code {id, Count, Damage, tag}}, text as plain strings, enchantments by number.</li>
 * </ul>
 *
 * The old tag's keys map to a component each (the table is {@link CgItemStack}'s javadoc); what has none stays in
 * {@code minecraft:custom_data}, so every key survives a round trip.
 */
final class ItemShapes {

    static final String CUSTOM_NAME = "minecraft:custom_name";
    static final String LORE = "minecraft:lore";
    static final String ENCHANTMENTS = "minecraft:enchantments";
    static final String STORED_ENCHANTMENTS = "minecraft:stored_enchantments";
    static final String DAMAGE = "minecraft:damage";
    static final String UNBREAKABLE = "minecraft:unbreakable";
    static final String CUSTOM_MODEL_DATA = "minecraft:custom_model_data";
    static final String REPAIR_COST = "minecraft:repair_cost";
    static final String CUSTOM_DATA = "minecraft:custom_data";

    private ItemShapes() {
    }

    static CgItemStack fromSaved(CgNbtCompound saved, CgItems.Shape shape, int maxDamage) {
        String id = saved.getString("id");
        if (id.isEmpty() || id.equals("minecraft:air")) return CgItemStack.EMPTY;
        if (shape == CgItems.Shape.COMPONENTS) {
            return new CgItemStack(id, saved.getInt("count", 1), maxDamage, saved.getCompound("components"));
        }
        boolean legacy = shape == CgItems.Shape.LEGACY;
        CgNbtCompound tag = saved.getCompound("tag").copy();
        CgNbtCompound out = new CgNbtCompound();

        int damage = legacy ? saved.getInt("Damage") : tag.getInt("Damage");
        tag.remove("Damage");
        if (damage != 0) out.put(DAMAGE, damage);

        if (tag.has("display", CgNbtTag.Type.COMPOUND)) {
            CgNbtCompound display = tag.getCompound("display");
            if (display.has("Name", CgNbtTag.Type.STRING)) out.put(CUSTOM_NAME, text(display.getString("Name"), legacy));
            display.remove("Name");
            if (display.has("Lore", CgNbtTag.Type.LIST)) {
                CgNbtList lore = new CgNbtList();
                CgNbtList old = display.getList("Lore");
                for (int i = 0; i < old.size(); i++) lore.add(text(old.getString(i), legacy));
                out.put(LORE, lore);
            }
            display.remove("Lore");
            if (display.isEmpty()) tag.remove("display");
        }
        enchantments(tag, legacy ? "ench" : "Enchantments", ENCHANTMENTS, out, legacy);
        enchantments(tag, "StoredEnchantments", STORED_ENCHANTMENTS, out, legacy);
        if (tag.has("Unbreakable")) {
            if (tag.getBool("Unbreakable")) out.put(UNBREAKABLE, new CgNbtCompound());
            tag.remove("Unbreakable");
        }
        if (tag.has("CustomModelData")) {
            out.put(CUSTOM_MODEL_DATA, tag.getInt("CustomModelData"));
            tag.remove("CustomModelData");
        }
        if (tag.has("RepairCost")) {
            out.put(REPAIR_COST, tag.getInt("RepairCost"));
            tag.remove("RepairCost");
        }
        if (!tag.isEmpty()) out.put(CUSTOM_DATA, tag);
        return new CgItemStack(id, saved.getInt("Count", 1), maxDamage, out);
    }

    private static CgNbtTag text(String old, boolean legacy) {
        return legacy ? CgNbtValue.ofString(old) : TextComponents.fromJson(old);
    }

    private static void enchantments(CgNbtCompound tag, String key, String component, CgNbtCompound out, boolean legacy) {
        if (!tag.has(key, CgNbtTag.Type.LIST)) return;
        CgNbtList list = tag.getList(key);
        CgNbtCompound levels = new CgNbtCompound();
        for (int i = 0; i < list.size(); i++) {
            CgNbtCompound e = list.getCompound(i);
            String id = legacy ? String.valueOf(e.getInt("id")) : e.getString("id");
            levels.put(id, e.getInt("lvl"));
        }
        tag.remove(key);
        out.put(component, levels);
    }

    static CgNbtCompound toSaved(CgItemStack stack, CgItems.Shape shape) {
        CgNbtCompound out = new CgNbtCompound();
        if (stack.isEmpty()) return out;
        CgNbtCompound components = stack.components();
        if (shape == CgItems.Shape.COMPONENTS) {
            out.put("id", stack.item()).put("count", stack.count());
            if (!components.isEmpty()) out.put("components", components);
            return out;
        }
        boolean legacy = shape == CgItems.Shape.LEGACY;
        CgNbtCompound tag = components.getCompound(CUSTOM_DATA).copy();
        out.put("id", stack.item()).putByte("Count", stack.count());
        if (legacy) out.putShort("Damage", stack.damage());
        else if (stack.damage() != 0) tag.put("Damage", stack.damage());

        for (String key : components.keys()) {
            switch (key) {
                case DAMAGE: case CUSTOM_DATA: break;
                case CUSTOM_NAME: display(tag).put("Name", oldText(components.get(key), legacy)); break;
                case LORE: {
                    CgNbtList lore = new CgNbtList();
                    for (CgNbtTag line : components.getList(key)) lore.add(oldText(line, legacy));
                    display(tag).put("Lore", lore);
                    break;
                }
                case ENCHANTMENTS: tag.put(legacy ? "ench" : "Enchantments", oldEnchantments(components.getCompound(key), legacy)); break;
                case STORED_ENCHANTMENTS: tag.put("StoredEnchantments", oldEnchantments(components.getCompound(key), legacy)); break;
                case UNBREAKABLE: tag.put("Unbreakable", true); break;
                case CUSTOM_MODEL_DATA: tag.put("CustomModelData", components.getInt(key)); break;
                case REPAIR_COST: tag.put("RepairCost", components.getInt(key)); break;
                default:
                    throw new CgCodecException("Component " + key + " has no form on a version before 1.20.5; keep it out of the stack or in minecraft:custom_data");
            }
        }
        if (!tag.isEmpty()) out.put("tag", tag);
        return out;
    }

    private static CgNbtCompound display(CgNbtCompound tag) {
        return tag.getOrCreateCompound("display");
    }

    private static CgNbtValue oldText(CgNbtTag component, boolean legacy) {
        return CgNbtValue.ofString(legacy ? TextComponents.plain(component) : TextComponents.toJson(component));
    }

    private static CgNbtList oldEnchantments(CgNbtCompound ench, boolean legacy) {
        CgNbtList list = new CgNbtList();
        for (Map.Entry<String, Integer> level : CgItemStack.levelsOf(ench).entrySet()) {
            CgNbtCompound e = new CgNbtCompound();
            if (legacy) {
                try {
                    e.putShort("id", Integer.parseInt(level.getKey()));
                } catch (NumberFormatException bad) {
                    throw new CgCodecException("Enchantment " + level.getKey() + " has no number: versions before 1.13 name them by number");
                }
            } else {
                e.put("id", level.getKey());
            }
            e.putShort("lvl", level.getValue());
            list.add(e);
        }
        return list;
    }

    /** The lines of a lore list as plain text. */
    static List<String> plainLines(CgNbtList lore) {
        List<String> out = new ArrayList<>(lore.size());
        for (int i = 0; i < lore.size(); i++) out.add(TextComponents.plain(lore.get(i)));
        return out;
    }
}
