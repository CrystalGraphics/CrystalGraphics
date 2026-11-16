package com.crystalgraphics.animation.graph;

import java.util.Map;
import java.util.Objects;

import javax.annotation.Nullable;

import com.crystalgraphics.animation.CgAnimInstance;
import com.crystalgraphics.net.object.CgNetObject;
import com.crystalgraphics.tag.CgTagCounts;
import com.crystalgraphics.world.CgEntityBinding;
import com.crystalgraphics.world.CgEntityField;
import com.crystalgraphics.world.CgEntityFields;
import com.crystalgraphics.world.CgEntityNetField;
import com.crystalgraphics.world.CgEntityState;

/**
 * Sets a graph's entity-bound variables every frame, before it runs: each {@code entity:} binding from the entity's
 * derived field or stored networked field, each {@code tags:} binding from its owned tags. Unreal's gameplay tag
 * property map, whose owner sets the properties it names. An entity actor gives one to each skeletal mesh it owns;
 * anything else animating an entity feeds its own instance.
 *
 * <pre>{@code
 * body.anim().onUpdate(CgEntityFeed.of(entity));                          // an actor's entity: everything
 * body.anim().onUpdate(CgEntityFeed.of(CgEntityFields.of(state, tags)));  // a bare state: derived fields and tags
 * }</pre>
 *
 * <ul>
 *   <li>One per instance: it caches what its graph binds, and allocates only when the instance's graph changes.</li>
 *   <li>A stored field reads its fallback until the entity has an object of its type on this side.</li>
 * </ul>
 */
public final class CgEntityFeed implements CgAnimInstance.Update {

    private final CgEntityFields.Source source;
    @Nullable
    private CgAnimGraph bound;
    private CgAnimVariable<?>[] targets = new CgAnimVariable<?>[0], tagTargets = new CgAnimVariable<?>[0];
    /** Per binding: a derived field, or null where {@link #nets} holds a stored one. */
    private CgEntityField[] fields = new CgEntityField[0];
    private CgEntityNetField[] nets = new CgEntityNetField[0];
    /** Per stored binding, the object last found on this side. */
    private CgNetObject[] objects = new CgNetObject[0];
    private CgAnimGraph.TagBinding[] tagSources = new CgAnimGraph.TagBinding[0];

    private CgEntityFeed(CgEntityFields.Source source) {
        this.source = source;
    }

    /** A feed reading {@code source}: an actor's entity, or {@link CgEntityFields#of(CgEntityState, CgTagCounts)}. */
    public static CgEntityFeed of(CgEntityFields.Source source) {
        return new CgEntityFeed(Objects.requireNonNull(source, "source"));
    }

    @Override
    public void update(CgAnimInstance anim, float seconds) {
        CgAnimGraph graph = anim.graph().graph();
        if (graph != bound) bind(graph);
        CgEntityState state = source.state();
        Object entity = source.entity();
        for (int i = 0; i < targets.length; i++) {
            float value;
            if (fields[i] != null) {
                value = fields[i].read(state, entity);
            } else {
                CgNetObject o = objects[i];
                if (o == null || o.isRemoved()) objects[i] = o = nets[i].find(source);
                value = nets[i].read(o);
            }
            set(anim, targets[i], value);
        }
        CgTagCounts tags = source.tags();
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
        Map<String, CgEntityBinding> entity = graph.entity();
        int n = entity.size();
        targets = new CgAnimVariable<?>[n];
        fields = new CgEntityField[n];
        nets = new CgEntityNetField[n];
        objects = new CgNetObject[n];
        int i = 0;
        for (Map.Entry<String, CgEntityBinding> e : entity.entrySet()) {
            targets[i] = graph.variables().get(graph.variable(e.getKey()));
            if (e.getValue() instanceof CgEntityField f) fields[i] = f;
            else nets[i] = (CgEntityNetField) e.getValue();
            i++;
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
