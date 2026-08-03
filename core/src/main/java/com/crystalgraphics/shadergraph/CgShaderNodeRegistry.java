package com.crystalgraphics.shadergraph;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The node types an editor can offer — id → {@link CgShaderNode}.
 *
 * <h3>An instance, not a static registry</h3>
 * <p>Unlike {@code CgMaterialRegistry} and friends, which are process-wide because their contents are
 * the engine's own, a node library belongs to an <em>editor</em>. Two graph editors in one process can
 * legitimately offer different node sets, and a global one would have them fighting over ids. Same
 * reasoning that made {@code NodeTypeRegistry} an instance on the CrystalGUI side.</p>
 *
 * <p>{@link #builtins()} is the standard set, and a consumer is free to add to it or ignore it.</p>
 */
public final class CgShaderNodeRegistry {

    private final Map<String, CgShaderNode> nodes = new LinkedHashMap<>();

    /**
     * Registers one or more nodes, in order — varargs so a whole subcategory can go on one call
     * ({@code register(ADD, MULTIPLY, SUBTRACT, DIVIDE) // math/basic}) instead of one chained
     * {@code .register(...)} per node, which is what {@link CgBuiltinShaderNodes#registerAll} does for
     * every subcategory it declares.
     *
     * @throws IllegalArgumentException on a duplicate id — a silent overwrite hides two consumers
     *         fighting over one name far more often than it is deliberate
     */
    public CgShaderNodeRegistry register(CgShaderNode... nodesToAdd) {
        for (CgShaderNode node : nodesToAdd) {
            CgShaderNode previous = nodes.putIfAbsent(node.id(), node);
            if (previous != null && previous != node) {
                throw new IllegalArgumentException("Shader node id already registered: " + node.id());
            }
        }
        return this;
    }

    @Nullable
    public CgShaderNode get(String id) {
        return nodes.get(id);
    }

    public boolean contains(String id) {
        return nodes.containsKey(id);
    }

    public Collection<CgShaderNode> all() {
        return Collections.unmodifiableCollection(nodes.values());
    }

    public int size() {
        return nodes.size();
    }

    /** A registry holding {@link CgBuiltinShaderNodes#registerAll the built-in set}. */
    public static CgShaderNodeRegistry builtins() {
        CgShaderNodeRegistry registry = new CgShaderNodeRegistry();
        CgBuiltinShaderNodes.registerAll(registry);
        return registry;
    }
}
