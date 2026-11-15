package com.crystalgraphics.animation.blend;

import java.util.Map;
import java.util.function.Function;

import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.asset.tree.CgAssetException;

/**
 * A {@code blend_space} asset compiled: its samples' clips named by id, bound to a mesh's clips where the mesh is known.
 * A graph's blend space nodes bind it with the graph.
 *
 * <pre>{@code
 * CgBlendSpaceDefinition run = (CgBlendSpaceDefinition) load.definition();       // blend_spaces/mymod/hero_run.yaml
 * CgBlendSpace space = run.bind(id -> mesh.sequence(id));
 * }</pre>
 *
 * @param id   its asset id
 * @param tree its resolved tree
 */
public record CgBlendSpaceDefinition(String id, Map<?, ?> tree) {

    /**
     * Its blend space, each sample's clip from {@code clips}.
     *
     * @throws CgAssetException where a clip does not resolve, or the blend space cannot play, naming the asset
     */
    public CgBlendSpace bind(Function<String, CgAnimSequence> clips) {
        return CgBlendSpaceCodec.build(id, tree, clips);
    }
}
