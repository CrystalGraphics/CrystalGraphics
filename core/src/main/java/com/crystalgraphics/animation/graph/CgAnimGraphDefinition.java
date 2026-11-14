package com.crystalgraphics.animation.graph;

import java.util.Map;
import java.util.function.Function;

import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.api.animation.CgSkeleton;
import com.crystalgraphics.asset.tree.CgAssetException;

/**
 * An {@code anim_graph} asset compiled: a graph whose clips are named by id, checked at load, bound to a skeleton and
 * its clips where the mesh is known. Unreal's anim blueprint, before it is used on a skeleton.
 *
 * <pre>{@code
 * CgAnimGraphDefinition hero = (CgAnimGraphDefinition) load.definition();      // anim_graphs/mymod/hero.yaml
 * CgAnimGraph graph = hero.graph(mesh.skeleton(), id -> mesh.sequence(id));     // once per mesh; keep it
 * body.anim().graph(graph);
 * }</pre>
 *
 * <ul>
 *   <li>Bind once per skeleton and keep the graph: each call builds its nodes anew.</li>
 *   <li>A clip the resolver does not answer fails the bind, naming the node and the id.</li>
 * </ul>
 *
 * @param id   its asset id
 * @param tree its resolved tree, as the codec reads it
 */
public record CgAnimGraphDefinition(String id, Map<?, ?> tree) {

    /**
     * Its graph on {@code skeleton}, each player's clip from {@code clips} by the id the file names.
     *
     * @throws CgAssetException where a clip does not resolve, or the graph cannot run, naming the node
     */
    public CgAnimGraph graph(CgSkeleton skeleton, Function<String, CgAnimSequence> clips) {
        return CgAnimGraphCodec.build(id, tree, skeleton, clips);
    }
}
