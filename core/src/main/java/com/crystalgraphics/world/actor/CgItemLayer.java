package com.crystalgraphics.world.actor;

import java.util.Objects;

/**
 * A held item giving one of an actor's linked anim layers its graph: Unreal's per-weapon linked anim layers
 * ({@code LinkAnimClassLayers} as a weapon is equipped). While the entity holds a matching item, in either hand, the
 * graph's layers play in place of the body's own; once it holds none, they are unlinked.
 *
 * <pre>{@code
 * CgEntityActors.register("mymod:knight", CgEntityActors.plain()).prefab("mymod:knight").graph("mymod:knight")
 *         .itemLayer(new CgItemLayer("#minecraft:swords", "upper_body", "mymod:sword_arms"))
 *         .itemLayer(new CgItemLayer("minecraft:bow", "upper_body", "mymod:bow_arms"))
 *         .itemLayer(new CgItemLayer("minecraft:shield", "off_arm", "mymod:shield_arm"));
 * }</pre>
 *
 * <ul>
 *   <li>{@code item} is an item id ({@code minecraft:bow}) or an item tag ({@code #minecraft:swords}); an id no mod
 *       registers matches nothing and is no error, since mods register items late.</li>
 *   <li>Per layer, the first entry whose item is in the main hand wins, else the first in the off hand.</li>
 *   <li>{@code graph} is an {@code anim_graph} implementing {@code layer}, bound to the body's mesh and its clips.</li>
 * </ul>
 *
 * @param item  an item id or {@code #} an item tag
 * @param layer the linked anim layer it fills
 * @param graph the {@code anim_graph} that implements it
 */
public record CgItemLayer(String item, String layer, String graph) {

    public CgItemLayer {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(layer, "layer");
        Objects.requireNonNull(graph, "graph");
        if (item.isEmpty() || item.equals("#")) throw new IllegalArgumentException("an item layer names an item or a #tag");
    }
}
