package com.crystalgraphics.world.actor;

import java.util.Map;

import javax.annotation.Nullable;

import com.crystalgraphics.animation.CgAnimInstance;
import com.crystalgraphics.animation.graph.CgAnimGraph;
import com.crystalgraphics.animation.graph.CgAnimVariable;
import com.crystalgraphics.net.object.CgNetObject;
import com.crystalgraphics.tag.CgTagCounts;

/**
 * Sets what an actor's graph binds from its entity, every frame before the graph runs: each {@code entity:} variable from
 * the entity's property object on this side, each {@code tags:} variable from its owned tags. Unreal's gameplay tag
 * property map, whose owner sets the properties it names. One per animation instance; allocates only when the
 * instance's graph changes.
 */
final class EntityFeed implements CgAnimInstance.Update {

    private final CgEntity entity;
    @Nullable
    private CgAnimGraph bound;
    private CgAnimVariable<?>[] targets = new CgAnimVariable<?>[0], tagTargets = new CgAnimVariable<?>[0];
    private CgAnimGraph.EntityBinding[] sources = new CgAnimGraph.EntityBinding[0];
    /** Per binding, the entity's object of its type, last found on this side. */
    private CgNetObject[] objects = new CgNetObject[0];
    private CgAnimGraph.TagBinding[] tagSources = new CgAnimGraph.TagBinding[0];

    EntityFeed(CgEntity entity) {
        this.entity = entity;
    }

    @Override
    public void update(CgAnimInstance anim, float seconds) {
        CgAnimGraph graph = anim.graph().graph();
        if (graph != bound) bind(graph);
        for (int i = 0; i < targets.length; i++) {
            CgNetObject o = objects[i];
            if (o == null || o.isRemoved()) objects[i] = o = entity.properties(sources[i].type());
            set(anim, targets[i], o == null ? sources[i].field().fallbackNumber() : o.getNumber(sources[i].field()));
        }
        CgTagCounts tags = entity.tags();
        for (int i = 0; i < tagTargets.length; i++) {
            CgAnimGraph.TagBinding b = tagSources[i];
            set(anim, tagTargets[i], b.as().value(tags.count(b.tag())));
        }
    }

    @SuppressWarnings("unchecked")
    private static void set(CgAnimInstance anim, CgAnimVariable<?> t, float v) {
        switch (t.type()) {
            case FLOAT -> anim.set((CgAnimVariable<Float>) t, v);
            case BOOL -> anim.set((CgAnimVariable<Boolean>) t, v != 0f);
            case INT -> anim.set((CgAnimVariable<Integer>) t, (int) v);
        }
    }

    private void bind(CgAnimGraph graph) {
        bound = graph;
        Map<String, CgAnimGraph.EntityBinding> byVariable = graph.entity();
        int n = byVariable.size();
        targets = new CgAnimVariable<?>[n];
        sources = new CgAnimGraph.EntityBinding[n];
        objects = new CgNetObject[n];
        int i = 0;
        for (Map.Entry<String, CgAnimGraph.EntityBinding> e : byVariable.entrySet()) {
            targets[i] = graph.variables().get(graph.variable(e.getKey()));
            sources[i++] = e.getValue();
        }
        Map<String, CgAnimGraph.TagBinding> byTag = graph.tags();
        tagTargets = new CgAnimVariable<?>[byTag.size()];
        tagSources = new CgAnimGraph.TagBinding[byTag.size()];
        i = 0;
        for (Map.Entry<String, CgAnimGraph.TagBinding> e : byTag.entrySet()) {
            tagTargets[i] = graph.variables().get(graph.variable(e.getKey()));
            tagSources[i++] = e.getValue();
        }
    }
}
