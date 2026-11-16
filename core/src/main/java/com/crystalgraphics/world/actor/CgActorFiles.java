package com.crystalgraphics.world.actor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.crystalgraphics.animation.graph.CgAnimGraph;
import com.crystalgraphics.asset.kind.CgBodyKinds;
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
 * binds: { entities: [mymod:golem] }               # or { local_player: true }
 * prefab: mymod:golem
 * anim_graph: mymod:golem_locomotion
 * root_motion: body
 * reactions:
 *   - { event: hurt, sequence: mymod:golem_flinch }
 * }</pre>
 *
 * <p>The same in Java:</p>
 * <pre>{@code
 * CgEntityActors.register("mymod:golem", CgEntityActors.plain()).prefab("mymod:golem")
 *         .graph("mymod:golem_locomotion").rootMotion("body").react(CgReaction.of(CgEntityProperties.HURT, "mymod:golem_flinch"));
 * }</pre>
 *
 * <ul>
 *   <li>Every file is registered at pre-init on each side ({@code CgAssetMounts.mount}), before any entity enters; a
 *       {@code local_player} actor binds on a client, as its client starts.</li>
 *   <li>Fails at load, naming the file: a prefab, graph, rig or sequence nobody declares, an unknown event, a rule
 *       naming a property no entity has, binds with neither or both forms, and an entity type two actors bind.</li>
 *   <li>Its references are soft: what it plays loads with the actors' prefabs, behind the loading screen.</li>
 * </ul>
 */
public final class CgActorFiles {

    private static final Logger LOGGER = LogManager.getLogger(CgActorFiles.class);
    /** The actors that bind the local player, bound when a client starts. */
    private static final List<Definition> LOCAL = new ArrayList<>();

    private CgActorFiles() {
    }

    /**
     * An {@code actor} file compiled.
     *
     * @param id          its asset id
     * @param localPlayer whether it draws the client's own player
     * @param entities    the entity types it draws, empty for the local player
     * @param prefab      its prefab's id
     * @param graph       its body's {@code anim_graph}, or null
     * @param rig         its body's rig, or null
     * @param rootMotion  the prefab node whose montages move the entity, or null
     * @param keepVanillaModel whether the host's model draws too
     * @param reactions   its reactions, in order
     */
    public record Definition(String id, boolean localPlayer, List<String> entities, String prefab, @Nullable String graph,
                             @Nullable String rig, @Nullable String rootMotion, boolean keepVanillaModel,
                             List<CgReaction> reactions) {

        /** {@code r} given everything this file says beyond its factory. */
        CgEntityActors.Registration apply(CgEntityActors.Registration r) {
            r.prefab(prefab);
            if (graph != null) r.graph(graph);
            if (rootMotion != null) r.rootMotion(rootMotion);
            if (keepVanillaModel) r.keepVanillaModel();
            for (CgReaction reaction : reactions) r.react(reaction);
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
        Map<?, ?> binds = (Map<?, ?>) tree.get("binds");
        boolean local = Boolean.TRUE.equals(binds.get("local_player"));
        List<String> entities = new ArrayList<>();
        if (binds.get("entities") instanceof List<?> list) for (Object e : list) entities.add(String.valueOf(e));
        if (local == !entities.isEmpty()) throw new CgAssetException(id + ": binds: local_player: true, or entities, and not both");
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
        return new Definition(id, local, List.copyOf(entities), String.valueOf(tree.get("prefab")), string(tree.get("anim_graph")),
                string(tree.get("rig")), string(tree.get("root_motion")), Boolean.TRUE.equals(tree.get("keep_vanilla_model")),
                List.copyOf(reactions));
    }

    @Nullable
    private static String string(@Nullable Object o) {
        return o == null ? null : String.valueOf(o);
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

    /** Each asset {@code d} names is declared by some file. */
    private static void check(CgAssetRegistry registry, Definition d) {
        exists(registry, d, CgBodyKinds.PREFAB, d.prefab());
        if (d.graph() != null) exists(registry, d, CgBodyKinds.ANIM_GRAPH, d.graph());
        if (d.rig() != null) exists(registry, d, CgBodyKinds.RIG, d.rig());
        for (CgReaction r : d.reactions()) exists(registry, d, CgSequenceKinds.SEQUENCE, r.sequence());
    }

    private static void exists(CgAssetRegistry registry, Definition d, CgType kind, String id) {
        try {
            registry.resolve(kind, id);
        } catch (CgAssetException e) {
            throw new CgAssetException(d.id() + ": no " + kind.id() + " " + id);
        }
    }
}
