package com.crystalgraphics.mc.item;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgService;

/**
 * The game's creative tabs and what each shows, in its order: what a recipe viewer's item list or a gallery of items
 * is filled from.
 *
 * <pre>{@code
 * for (String tab : CgCreativeTabs.tabs()) log(tab);                    // minecraft:combat, minecraft:tools_and_utilities, ...
 * List<CgItemStack> combat = CgCreativeTabs.items("minecraft:combat");  // every stack the tab shows
 * }</pre>
 *
 * <ul>
 *   <li>Client thread, in a world: from 1.19.3 a tab's contents depend on the world's features and the player's
 *       permissions, and are rebuilt for them as the creative screen rebuilds them.</li>
 *   <li>A tab's id is its registry name from 1.20.1, {@code minecraft:<label>} before 1.19.3 (legacy Forge and 1.7.10
 *       included), {@code tab<index>} on 1.19.3-1.19.4, which name tabs neither way.</li>
 *   <li>Only tabs of items: not search, the hotbars or the survival inventory. An unknown id is no items.</li>
 *   <li>No host (a server, a test): no tabs.</li>
 * </ul>
 */
public final class CgCreativeTabs {

    /** What a host answers, one class per era, {@code CreativeTabs<Era>}. Client thread. */
    public interface Host {

        /** Every tab of items, in the creative screen's order. */
        List<String> tabs();

        /** The host's stacks a tab shows, in its order; empty for an unknown tab. */
        List<Object> items(String tab);

        Host NONE = new Host() {
            @Override public List<String> tabs() { return Collections.emptyList(); }
            @Override public List<Object> items(String tab) { return Collections.emptyList(); }
        };
    }

    /** The slot a client host fills. */
    public static final CgService<Host> HOST = CgService.of("crystalgraphics:creative_tabs", Host.NONE);

    private CgCreativeTabs() {
    }

    private static Host host() {
        return CgPlatform.get(HOST);
    }

    /** Every tab of items, in the creative screen's order. */
    public static List<String> tabs() {
        return host().tabs();
    }

    /** Every stack {@code tab} shows, in its order; empty for an unknown tab. */
    public static List<CgItemStack> items(String tab) {
        List<Object> host = host().items(tab);
        List<CgItemStack> out = new ArrayList<>(host.size());
        for (Object stack : host) out.add(CgItemStack.of(stack));
        return out;
    }
}
