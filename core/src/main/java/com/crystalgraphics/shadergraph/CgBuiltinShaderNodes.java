package com.crystalgraphics.shadergraph;

/**
 * The starter node set — five nodes, deliberately.
 *
 * <h3>Why five and not fifty</h3>
 * <p>6.3.6 is the volume item and will eventually run to Unity's scale. These five are the ones needed
 * to prove the <em>stack</em> works end to end: a graph that compiles, links, and draws something a
 * person can look at. Adding forty more before that is proven is forty more things to fix when the
 * template language turns out to need one more feature.</p>
 *
 * <p>They were also chosen to exercise every mechanism the compiler has, so the demo is a test:</p>
 * <ul>
 *   <li>{@link #COLOR} and {@link #FLOAT} — constants, and the unconnected-input-becomes-a-literal path</li>
 *   <li>{@link #ADD} and {@link #MULTIPLY} — <b>dynamic</b> ports, so widening and compiler-emitted casts
 *       are both live in any graph that uses them</li>
 *   <li>{@link #TIME} — an engine builtin from {@code cg_env.glsl}, which is what makes the result move
 *       and therefore obviously running rather than a static picture</li>
 * </ul>
 *
 * <p>Every one is a {@link CgTemplateShaderNode}, which is the point: the declarative path covers the
 * common case, and nothing here needed a Java implementation.</p>
 */
public final class CgBuiltinShaderNodes {

    private CgBuiltinShaderNodes() {
    }

    /** A constant colour. The simplest possible source of a {@code vec4}. */
    public static final CgShaderNode COLOR = CgTemplateShaderNode.of("cg:input/color")
            .label("Color")
            .in("Value", CgShaderType.VEC4, "vec4(1.0, 1.0, 1.0, 1.0)")
            .out("Out", CgShaderType.VEC4)
            .body("{Out} = {Value};")
            .build();

    /** A constant scalar. */
    public static final CgShaderNode FLOAT = CgTemplateShaderNode.of("cg:input/float")
            .label("Float")
            .in("Value", CgShaderType.FLOAT, "1.0")
            .out("Out", CgShaderType.FLOAT)
            .body("{Out} = {Value};")
            .build();

    /**
     * Seconds since start, from {@code cg_env.glsl}'s frame block.
     *
     * <p>{@code CG_TIME} is a macro over {@code cg_Time.y}, injected into every stage automatically —
     * so this node needs no include and works in both domains.</p>
     */
    public static final CgShaderNode TIME = CgTemplateShaderNode.of("cg:input/time")
            .label("Time")
            .out("Out", CgShaderType.FLOAT)
            .body("{Out} = CG_TIME;")
            .build();

    /**
     * Adds two values of any matching width.
     *
     * <p>Dynamic on every port, so {@code Add(float, vec3)} resolves to vec3 and the compiler emits
     * {@code vec3(...)} around the scalar. That is one node instead of four, and it is the reason
     * {@link CgShaderNode} is an interface rather than only data.</p>
     */
    public static final CgShaderNode ADD = CgTemplateShaderNode.of("cg:math/add")
            .label("Add")
            .in("A", CgShaderType.DYNAMIC, "0.0")
            .in("B", CgShaderType.DYNAMIC, "0.0")
            .out("Out", CgShaderType.DYNAMIC)
            .body("{Out} = {A} + {B};")
            .build();

    /** Multiplies two values of any matching width. @see #ADD */
    public static final CgShaderNode MULTIPLY = CgTemplateShaderNode.of("cg:math/multiply")
            .label("Multiply")
            .in("A", CgShaderType.DYNAMIC, "1.0")
            .in("B", CgShaderType.DYNAMIC, "1.0")
            .out("Out", CgShaderType.DYNAMIC)
            .body("{Out} = {A} * {B};")
            .build();

    /** Adds every built-in to {@code registry}, in menu order. */
    public static void registerAll(CgShaderNodeRegistry registry) {
        registry.register(COLOR)
                .register(FLOAT)
                .register(TIME)
                .register(ADD)
                .register(MULTIPLY);
    }
}
