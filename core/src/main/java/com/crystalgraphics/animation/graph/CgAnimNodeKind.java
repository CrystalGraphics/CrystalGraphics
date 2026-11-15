package com.crystalgraphics.animation.graph;

import java.util.Map;

import javax.annotation.Nullable;

import com.crystalgraphics.animation.sync.CgSyncGroup;
import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.object.CgType;

/**
 * Reads one node kind's data form in an {@code anim_graph} file: how a node kind outside this package (a state machine,
 * inertialization, a mod's) joins the format. Registered once with {@link CgAnimGraphCodec#registerKind}; the node
 * writes itself back through {@link CgAnimNode#write}.
 *
 * <pre>{@code
 * CgAnimGraphCodec.registerKind("hold", (c, at, node) ->
 *         Hold.of(c.pose(c.string(at, node, "source")), c.variable(at + ".frozen", c.string(at, node, "frozen"), CgAnimVariable.Type.BOOL)));
 * }</pre>
 *
 * <ul>
 *   <li>Read the nodes it reads through {@link Context#pose}: each name once, as every node's.</li>
 *   <li>Fail by throwing {@code CgAssetException} with {@code at} in its message: the file and the node.</li>
 *   <li>Its declaration (the schema's) is the kind's {@code CgType} in {@code CgBodyKinds.ANIM_NODES}.</li>
 * </ul>
 */
@FunctionalInterface
public interface CgAnimNodeKind {

    /** Node {@code node}, at {@code at} ({@code <file>: nodes.<name>}), read into a node. */
    CgAnimNode read(Context c, String at, Map<?, ?> node);

    /** What a kind reads with: the file's other nodes, its variables and its sections. */
    interface Context {

        /** Node {@code name}, read as a pose: each name is read once. */
        CgAnimNode pose(String name);

        /**
         * Variable {@code name}, of {@code type}.
         *
         * Fails with {@code CgAssetException} where the graph declares none, or one of another type.
         */
        <T> CgAnimVariable<T> variable(String at, String name, CgAnimVariable.Type type);

        /**
         * Variable {@code name}, of whatever type it is declared.
         *
         * Fails with {@code CgAssetException} where the graph declares none.
         */
        CgAnimVariable<?> variable(String at, String name);

        /** Section {@code key}'s entry {@code name} ({@code machines.locomotion}), or null. */
        Map<?, ?> section(String key, String name);

        /** {@code m.key} as a string, failing at {@code at} when it is not one. */
        String string(String at, Map<?, ?> m, String key);

        /** {@code o} as a number, failing at {@code at} when it is not one. */
        float number(String at, Object o);

        /** The mesh's clip {@code id}, failing at {@code at} where the mesh has none. */
        CgAnimSequence clip(String at, String id);

        /**
         * The compiled asset {@code id} of {@code kind} the node reads (a blend space): resolved when the graph compiles,
         * kept with it for every bind. Fails at {@code at} where it does not resolve.
         */
        Object definition(String at, CgType kind, String id);

        /** The node's {@code sync_group} and {@code group_role}, or null where it names no group. */
        @Nullable
        CgSyncGroup syncGroup(String at, Map<?, ?> m);
    }
}
