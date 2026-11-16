package com.crystalgraphics.world.actor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.crystalgraphics.animation.graph.CgAnimGraph;
import com.crystalgraphics.asset.kind.CgBodyKinds;
import com.crystalgraphics.asset.kind.CgLookKinds;
import com.crystalgraphics.asset.kind.CgSequenceKinds;
import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.asset.load.CgAssetMounts;
import com.crystalgraphics.asset.load.CgAssetRef;
import com.crystalgraphics.asset.load.CgAssetState;
import com.crystalgraphics.asset.load.CgVfxAssets;
import com.crystalgraphics.asset.registry.CgAssetRegistry;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.object.validate.CgIssue;
import com.crystalgraphics.object.validate.CgValidation;

/**
 * The {@code actor} kind: what draws an entity, as a file, registered at load the way {@link CgEntityActors#register}
 * registers it in Java. A Character Blueprint's defaults: the entities it binds, its prefab, the graph its body runs,
 * the body whose root motion moves the entity, and its reactions.
 *
 * <pre>{@code
 * # assets/mymod/actors/golem.yaml
 * _v: 1
 * binds: { entities: [mymod:golem] }               # or { local_player: true }; none: bound on demand
 * prefab: mymod:golem
 * anim_graph: mymod:golem_locomotion
 * root_motion: body
 * reactions:
 *   - { event: hurt, sequence: mymod:golem_flinch }
 * item_layers:                                     # a held item gives a linked layer its graph
 *   - { item: "#minecraft:swords", layer: upper_body, graph: mymod:golem_sword_arms }
 * outline: { render_custom_depth: true, custom_depth_stencil_value: 1 }   # the engine's outline round its body
 *                                                  # post: mymod:renderer/outline, a look of your own
 * }</pre>
 *
 * <p>The same in Java:</p>
 * <pre>{@code
 * CgEntityActors.register("mymod:golem", CgEntityActors.plain()).prefab("mymod:golem")
 *         .graph("mymod:golem_locomotion").rootMotion("body").react(CgReaction.of(CgEntityProperties.HURT, "mymod:golem_flinch"));
 * }</pre>
 *
 * <p>One with no {@code binds} draws nothing until code or {@code /cgc actor <name>} binds it:</p>
 * <pre>{@code
 * CgEntityActors.Registration hero = CgActorFiles.bindLocalPlayer("mymod:hero");   // the client's player, as mymod:hero
 * hero.close();                                                                    // its own model again
 * }</pre>
 *
 * <ul>
 *   <li>Every file is registered at pre-init on each side ({@code CgAssetMounts.mount}), before any entity enters; a
 *       {@code local_player} actor binds on a client, as its client starts.</li>
 *   <li>Fails at load, naming the file: a prefab, graph, rig, sequence or item layer's graph nobody declares, an unknown event, a rule
 *       naming a property no entity has, binds with both forms, an outline key beyond its custom depth, stencil and post, and an entity
 *       type two actors bind.</li>
 *   <li>Its references are soft: what it plays loads with the actors' prefabs, behind the loading screen.</li>
 * </ul>
 */
public final class CgActorFiles {

    private static final Logger LOGGER = LogManager.getLogger(CgActorFiles.class);
    /** The actors that bind the local player, bound when a client starts. */
    private static final List<Definition> LOCAL = new ArrayList<>();
    /** Every file that registered, by id: what {@link #bindLocalPlayer} binds. */
    private static final Map<String, Definition> DECLARED = new ConcurrentHashMap<>();

    private CgActorFiles() {
    }

    /**
     * An {@code actor} file compiled.
     *
     * @param id          its asset id
     * @param localPlayer whether it draws the client's own player
     * @param entities    the entity types it draws; empty for the local player, and for one bound on demand
     * @param prefab      its prefab's id
     * @param graph       its body's {@code anim_graph}, or null
     * @param rig         its body's rig, or null
     * @param rootMotion  the prefab node whose montages move the entity, or null
     * @param keepVanillaModel whether the host's model draws too
 * @param reactions   its reactions, in order
     * @param itemLayers  its item layers, in order
     * @param outline     the custom stencil its body writes, outlined by the engine's outline; -1 for none
     * @param outlinePost the outline's look, a material on the outline shader; null for the engine's
     */
    public record Definition(String id, boolean localPlayer, List<String> entities, String prefab, @Nullable String graph,
                             @Nullable String rig, @Nullable String rootMotion, boolean keepVanillaModel,
                             List<CgReaction> reactions, List<CgItemLayer> itemLayers, int outline,
                             @Nullable String outlinePost, List<String> post) {

        /** {@code r} given everything this file says beyond its factory. */
        CgEntityActors.Registration apply(CgEntityActors.Registration r) {
            r.prefab(prefab);
            if (graph != null) r.graph(graph);
            if (rootMotion != null) r.rootMotion(rootMotion);
            if (keepVanillaModel) r.keepVanillaModel();
            for (CgReaction reaction : reactions) r.react(reaction);
            for (CgItemLayer layer : itemLayers) r.itemLayer(layer);
            if (outline >= 0) r.outline(outline, outlinePost);
            for (String material : post) r.post(material);
            return r;
        }
    }

    /** Sets it as the {@code actor} kind's compiler. {@code CgBuiltInCompilers.register} calls it. */
    public static void register() {
        CgAssetCompiler.register(CgBodyKinds.ACTOR, CgActorFiles::compile);
    }

    static Definition compile(CgAssetData asset, CgAssetCompiler.Dependencies deps) {
        CgValidation v = asset.validate(CgValidation.Usecase.LOAD);
        if (!v.valid()) {
            for (CgIssue issue : v.issues()) {
                if (issue.severity() == CgIssue.Severity.ERROR) throw new CgAssetException(asset.id() + ": " + issue.path() + ": " + issue.message());
            }
        }
        Map<Object, Object> tree = asset.resolved();
        String id = asset.id();
        Map<?, ?> binds = tree.get("binds") instanceof Map<?, ?> m ? m : Map.of();
        boolean local = Boolean.TRUE.equals(binds.get("local_player"));
        List<String> entities = new ArrayList<>();
        if (binds.get("entities") instanceof List<?> list) for (Object e : list) entities.add(String.valueOf(e));
        if (local && !entities.isEmpty()) throw new CgAssetException(id + ": binds: local_player: true, or entities, and not both");
        List<CgReaction> reactions = new ArrayList<>();
        if (tree.get("reactions") instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                Map<?, ?> m = (Map<?, ?>) list.get(i);
                String at = id + ": reactions[" + i + "]";
                try {
                    Object slot = m.get("slot");
                    reactions.add(new CgReaction(CgReaction.event(String.valueOf(m.get("event"))), String.valueOf(m.get("sequence")),
                            slot == null ? CgAnimGraph.DEFAULT_SLOT : String.valueOf(slot),
                            m.get("when") == null ? null : CgReaction.When.read(at + ".when", m.get("when"))));
                } catch (IllegalArgumentException e) {
                    throw new CgAssetException(at + ": " + e.getMessage());
                }
            }
        }
        List<CgItemLayer> layers = new ArrayList<>();
        if (tree.get("item_layers") instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                Map<?, ?> m = (Map<?, ?>) list.get(i);
                try {
                    layers.add(new CgItemLayer(String.valueOf(m.get("item")), String.valueOf(m.get("layer")), String.valueOf(m.get("graph"))));
                } catch (IllegalArgumentException e) {
                    throw new CgAssetException(id + ": item_layers[" + i + "]: " + e.getMessage());
                }
            }
        }
        int outline = -1;
        String outlinePost = null;
        if (tree.get("outline") instanceof Map<?, ?> o) {
            for (String rigOnly : new String[] {"overlay", "colour", "strength", "slot_parameter", "slot_colours"}) {
                if (o.get(rigOnly) != null) {
                    throw new CgAssetException(id + ": outline." + rigOnly + ": an actor's outline is the engine's, drawn round its stencil");
                }
            }
            if (Boolean.TRUE.equals(o.get("render_custom_depth"))) {
                outline = o.get("custom_depth_stencil_value") instanceof Number n ? n.intValue() : 1;
                outlinePost = string(o.get("post"));
            }
        }
        return new Definition(id, local, List.copyOf(entities), String.valueOf(tree.get("prefab")), string(tree.get("anim_graph")),
                string(tree.get("rig")), string(tree.get("root_motion")), Boolean.TRUE.equals(tree.get("keep_vanilla_model")),
                List.copyOf(reactions), List.copyOf(layers), outline, outlinePost, strings(tree.get("post")));
    }

    @Nullable
    private static String string(@Nullable Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static List<String> strings(@Nullable Object o) {
        if (!(o instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>(list.size());
        for (Object e : list) out.add(String.valueOf(e));
        return List.copyOf(out);
    }

    /**
     * Every {@code actor} asset of {@code assets} compiled and registered now: its entity types' actors from here, a
     * local player's kept for {@link #bindLocalPlayers}. A file that fails is logged by name and registers nothing.
     */
    public static void declare(CgVfxAssets assets) {
        Map<String, String> boundBy = new HashMap<>();
        for (CgAssetRef<?> ref : CgAssetMounts.compileNow(assets, CgBodyKinds.ACTOR)) {
            if (ref.state() == CgAssetState.FAILED) {
                LOGGER.error("actor {} failed, so it draws nothing: {}", ref.id(), ref.failure());
                continue;
            }
            Definition d = (Definition) ref.loaded();
            try {
                check(assets.registry(), d);
                for (String type : d.entities()) {
                    String other = boundBy.putIfAbsent(type, d.id());
                    if (other != null) throw new CgAssetException(d.id() + ": " + type + " is bound by " + other + " already");
                    d.apply(CgEntityActors.register(type, CgEntityActors.plain()));
                }
                if (d.localPlayer()) {
                    synchronized (LOCAL) {
                        LOCAL.add(d);
                    }
                }
                DECLARED.put(d.id(), d);
            } catch (RuntimeException e) {
                LOGGER.error("actor {} failed, so it draws nothing: {}", d.id(), e.getMessage());
            }
        }
    }

    /** Binds each local-player actor a file declared, on a client, once. What a client start runs before its preload. */
    static void bindLocalPlayers() {
        synchronized (LOCAL) {
            for (Definition d : LOCAL) d.apply(CgEntityActors.bindLocalPlayer(CgEntityActors.plain()));
            LOCAL.clear();
        }
    }

    /**
     * The client's player drawn as the actor file {@code id} from now, following respawns and level changes; closing
     * the registration gives the host's model back. Client.
     *
     * @throws IllegalArgumentException where no file of that id registered
     * @throws IllegalStateException    where the local player has an actor bound already
     */
    public static CgEntityActors.Registration bindLocalPlayer(String id) {
        Definition d = DECLARED.get(id);
        if (d == null) throw new IllegalArgumentException("no actor file " + id);
        return d.apply(CgEntityActors.bindLocalPlayer(CgEntityActors.plain()));
    }

    /** Every actor file that registered, by id. */
    public static Map<String, Definition> declared() {
        return Collections.unmodifiableMap(DECLARED);
    }

    /** Each asset {@code d} names is declared by some file. */
    private static void check(CgAssetRegistry registry, Definition d) {
        exists(registry, d, CgBodyKinds.PREFAB, d.prefab());
        if (d.graph() != null) exists(registry, d, CgBodyKinds.ANIM_GRAPH, d.graph());
        if (d.rig() != null) exists(registry, d, CgBodyKinds.RIG, d.rig());
        for (CgReaction r : d.reactions()) exists(registry, d, CgSequenceKinds.SEQUENCE, r.sequence());
        for (CgItemLayer l : d.itemLayers()) exists(registry, d, CgBodyKinds.ANIM_GRAPH, l.graph());
        if (d.outlinePost() != null) exists(registry, d, CgLookKinds.MATERIAL, d.outlinePost());
        for (String m : d.post()) exists(registry, d, CgLookKinds.MATERIAL, m);
    }

    private static void exists(CgAssetRegistry registry, Definition d, CgType kind, String id) {
        try {
            registry.resolve(kind, id);
        } catch (CgAssetException e) {
            throw new CgAssetException(d.id() + ": no " + kind.id() + " " + id);
        }
    }
}
