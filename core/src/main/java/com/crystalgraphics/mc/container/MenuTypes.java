package com.crystalgraphics.mc.container;

import java.util.HashMap;
import java.util.Map;

/**
 * Every menu kind of the newest Minecraft (26.3), and whether {@link CgContainers.Menu#state} carries its values.
 * A kind not in the table (a mod's) is {@link Support#UNKNOWN}: its slots and actions work, its state is empty.
 *
 * <pre>{@code
 * MenuTypes.support("minecraft:anvil");          // STATE: state() has {cost}
 * MenuTypes.support("minecraft:loom");           // UNSUPPORTED: MenuTypes.reason(...) says what is missing
 * }</pre>
 *
 * <p>The state of each kind that has one (modern 1.14.3 and later; 1.8.9 to 1.12.2 and 1.7.10 carry the furnace's only):</p>
 *
 * <table>
 *   <caption>State by kind</caption>
 *   <tr><td>furnace, blast_furnace, smoker</td><td>{@code burn}, {@code cook}: 0-1000</td></tr>
 *   <tr><td>brewing_stand</td><td>{@code brew} ticks left (0-400), {@code fuel} (0-20)</td></tr>
 *   <tr><td>enchantment</td><td>{@code costs}, {@code clue}, {@code level}: int arrays of three; {@code seed}</td></tr>
 *   <tr><td>anvil</td><td>{@code cost}</td></tr>
 *   <tr><td>beacon</td><td>{@code levels}, {@code primary}, {@code secondary}: an effect's id or empty</td></tr>
 *   <tr><td>stonecutter</td><td>{@code selected} (-1 none), {@code count}</td></tr>
 *   <tr><td>lectern</td><td>{@code page}</td></tr>
 *   <tr><td>merchant</td><td>{@code level}, {@code xp}, {@code offers}: a list of {@code {a, b, result, uses, max}}, the stacks as {@link com.crystalgraphics.mc.item.CgItemStack#CODEC}</td></tr>
 * </table>
 */
public final class MenuTypes {

    public enum Support {
        /** Slots are all there is: nothing to extract. */
        PLAIN,
        /** {@link CgContainers.Menu#state} carries values (where the version can read them). */
        STATE,
        /** Values the menu has that state does not carry yet. */
        UNSUPPORTED,
        /** Not a vanilla kind. */
        UNKNOWN
    }

    private static final Map<String, Support> SUPPORT = new HashMap<>();
    private static final Map<String, String> REASON = new HashMap<>();

    static {
        plain("player", "generic_9x1", "generic_9x2", "generic_9x3", "generic_9x4", "generic_9x5", "generic_9x6",
                "generic_3x3", "hopper", "shulker_box", "crafting", "grindstone", "smithing", "cartography_table");
        state("furnace", "blast_furnace", "smoker", "brewing_stand", "enchantment", "anvil", "beacon", "stonecutter",
                "lectern", "merchant");
        unsupported("loom", "the selected pattern and the patterns on offer");
        unsupported("crafter_3x3", "the disabled slots and the powered flag");
    }

    private MenuTypes() {
    }

    private static void plain(String... names) {
        for (String n : names) SUPPORT.put("minecraft:" + n, Support.PLAIN);
    }

    private static void state(String... names) {
        for (String n : names) SUPPORT.put("minecraft:" + n, Support.STATE);
    }

    private static void unsupported(String name, String reason) {
        SUPPORT.put("minecraft:" + name, Support.UNSUPPORTED);
        REASON.put("minecraft:" + name, reason);
    }

    /** What state carries for {@code type}. */
    public static Support support(String type) {
        Support support = SUPPORT.get(type);
        return support == null ? Support.UNKNOWN : support;
    }

    /** What an {@link Support#UNSUPPORTED} kind has that state does not carry; empty for any other. */
    public static String reason(String type) {
        return REASON.getOrDefault(type, "");
    }

    /** Every kind in the table. */
    public static Iterable<String> all() {
        return SUPPORT.keySet();
    }
}
