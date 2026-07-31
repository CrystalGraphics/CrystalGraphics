package com.crystalgraphics.shadergraph;

/**
 * The starter node set — small on purpose, and chosen so the demo is also a test.
 *
 * <h3>Why a handful and not fifty</h3>
 * <p>6.3.6 is the volume item and will eventually run to Unity's scale. These are the ones needed to
 * prove the <em>stack</em> works end to end: a graph that compiles, links, and draws something a person
 * can look at. Adding forty more before that is proven is forty more things to fix when the template
 * language turns out to need one more feature.</p>
 *
 * <p>Between them they exercise every mechanism the compiler has:</p>
 * <ul>
 *   <li>{@link #FLOAT} and the {@link #VECTOR4 vectors} — per-component input ports, so the
 *       unconnected-input-becomes-a-literal path is live and a component can be driven independently</li>
 *   <li>{@link #COLOR} — a value <b>property</b>, read by the body through {@code {@Value}}, for a
 *       constant nothing can be wired into</li>
 *   <li>{@link #ADD} and {@link #MULTIPLY} — <b>dynamic</b> ports, so widening and compiler-emitted casts
 *       are both live in any graph that uses them</li>
 *   <li>{@link #TIME} — an engine builtin from {@code cg_env.glsl}, which is what makes the result move
 *       and therefore obviously running rather than a static picture</li>
 *   <li>{@link #UV}, {@link #POSITION}, {@link #NORMAL} — vertex-domain nodes with a {@code forPreview}
 *       form, a {@code Space} property selecting a code variant, and the sphere preview geometry</li>
 * </ul>
 *
 * <p>Every one is a {@link CgTemplateShaderNode}, which is the point: the declarative path covers the
 * common case, and nothing here needed a Java implementation.</p>
 */
public final class CgBuiltinShaderNodes {

    private CgBuiltinShaderNodes() {
    }

    // ── The Space property, shared by every node that has a coordinate frame ──

    public static final String SPACE_ID = "Space";
    public static final String SPACE_OBJECT = "Object";
    public static final String SPACE_WORLD = "World";
    public static final String SPACE_VIEW = "View";

    /**
     * Which coordinate frame a spatial value is expressed in.
     *
     * <p>Object first, so it is the default — Unity's choice, and the one that makes a node's preview
     * legible: an object-space position on a unit sphere is exactly the ±1 range the thumbnail's colour
     * mapping expects, whereas a world position is wherever the model happens to sit.</p>
     *
     * <p>Tangent space is the one still missing. Unlike the other three it needs a per-vertex basis —
     * a tangent and bitangent alongside the normal — which this engine's vertex formats do not carry, so
     * it cannot be derived from what a node is handed. An option that silently emitted the wrong frame
     * would be worse than one that is absent.</p>
     */
    public static final CgShaderNodeProperty SPACE =
            CgShaderNodeProperty.of(SPACE_ID, "Space", SPACE_OBJECT, SPACE_WORLD, SPACE_VIEW);

    /**
     * A constant colour, held as a <b>property</b> rather than an input port.
     *
     * <p>Unity's Color node has no input, and that is the right shape: a colour you pick is not a value
     * something else could compute into. Giving it a port implied it could be driven, which meant an
     * editor sitting on a port that would vanish the moment anything was wired to it — and a text box
     * reading {@code vec4(1.0, 1.0, 1.0, 1.0)} as the way to choose a colour.</p>
     */
    public static final CgShaderNode COLOR = CgTemplateShaderNode.of("cg:input/basic/color")
            .label("Color")
            .out("Out", CgShaderType.VEC4)
            .property(CgShaderNodeProperty.value("Value", "", CgShaderType.VEC4, "vec4(1.0, 1.0, 1.0, 1.0)"))
            .noPreview()
            .body("{Out} = {@Value};")
            .build();

    /**
     * A single scalar — Unity's {@code Float}, which is a one-component vector and shaped like one.
     *
     * <p>It has an {@code X} <b>port</b>, not a property, so it behaves identically to {@link #VECTOR4}'s
     * components: type a number, or wire something in and the knob steps aside. A property here would
     * have made the one-component case the odd one out for no reason.</p>
     */
    public static final CgShaderNode FLOAT = CgTemplateShaderNode.of("cg:input/basic/float")
            .label("Float")
            .in("X", CgShaderType.FLOAT, "0.0")
            .out("Out", CgShaderType.FLOAT)
            .noPreview()
            .body("{Out} = {X};")
            .build();

    /**
     * Four scalars assembled into a vector — Unity's {@code Vector 4}, and the design this replaces a
     * single {@code vec4} text box with.
     *
     * <p><b>Each component is its own port.</b> That is the whole point: a component can be driven by
     * another node while the rest stay typed in, which one combined field cannot express — and it is why
     * unhooking that connection puts the knob back rather than losing what was there. The knobs are
     * ordinary unconnected-input editors, so none of this needs special support.</p>
     */
    public static final CgShaderNode VECTOR4 = CgTemplateShaderNode.of("cg:input/basic/vector4")
            .label("Vector 4")
            .in("X", CgShaderType.FLOAT, "0.0")
            .in("Y", CgShaderType.FLOAT, "0.0")
            .in("Z", CgShaderType.FLOAT, "0.0")
            .in("W", CgShaderType.FLOAT, "0.0")
            .out("Out", CgShaderType.VEC4)
            .noPreview()
            .body("{Out} = vec4({X}, {Y}, {Z}, {W});")
            .build();

    /** @see #VECTOR4 */
    public static final CgShaderNode VECTOR3 = CgTemplateShaderNode.of("cg:input/basic/vector3")
            .label("Vector 3")
            .in("X", CgShaderType.FLOAT, "0.0")
            .in("Y", CgShaderType.FLOAT, "0.0")
            .in("Z", CgShaderType.FLOAT, "0.0")
            .out("Out", CgShaderType.VEC3)
            .noPreview()
            .body("{Out} = vec3({X}, {Y}, {Z});")
            .build();

    /** @see #VECTOR4 */
    public static final CgShaderNode VECTOR2 = CgTemplateShaderNode.of("cg:input/basic/vector2")
            .label("Vector 2")
            .in("X", CgShaderType.FLOAT, "0.0")
            .in("Y", CgShaderType.FLOAT, "0.0")
            .out("Out", CgShaderType.VEC2)
            .noPreview()
            .body("{Out} = vec2({X}, {Y});")
            .build();

    /**
     * Seconds since start, from {@code cg_env.glsl}'s frame block.
     *
     * <p>{@code CG_TIME} is a macro over {@code cg_Time.y}, injected into every stage automatically —
     * so this node needs no include and works in both domains.</p>
     */
    public static final CgShaderNode TIME = CgTemplateShaderNode.of("cg:input/basic/time")
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

    /**
     * Texture coordinates.
     *
     * <p>The node every shader graph screenshot leads with, because its thumbnail is the one that makes
     * a preview system legible at a glance: a red-green gradient across the quad, so you can see
     * immediately which way U and V run.</p>
     *
     * <p>Four components to match Unity's {@code UV Out(4)} — the third and fourth are zero, and exist so
     * the node drops straight into anything expecting a vec4.</p>
     */
    public static final CgShaderNode UV = CgTemplateShaderNode.of("cg:input/geometry/uv")
            .label("UV")
            .out("Out", CgShaderType.VEC4)
            .domain(CgShaderDomain.VERTEX)
            .body("{Out} = vec4(cg_TexCoord0, 0.0, 0.0);")
            .previewBody("{Out} = vec4(i.uv, 0.0, 0.0);")
            .build();

    /**
     * Object-space vertex position.
     *
     * <p>{@link CgPreviewGeometry#SPHERE}, and this is the declaration that makes the whole propagation
     * mechanism earn its place: on a flat quad a position is one uniform colour, and every node
     * downstream of this one inherits the sphere so a chain of maths on a position stays readable.</p>
     */
    public static final CgShaderNode POSITION = CgTemplateShaderNode.of("cg:input/geometry/position")
            .label("Position")
            .out("Out", CgShaderType.VEC3)
            .domain(CgShaderDomain.VERTEX)
            .previewGeometry(CgPreviewGeometry.SPHERE)
            .property(SPACE)
            .body("{Out} = cg_Position;")
            .previewBody("{Out} = i.objectPos;")
            .bodyFor(SPACE_ID, SPACE_WORLD, "{Out} = (CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0)).xyz;")
            .bodyFor(SPACE_ID, SPACE_VIEW,
                    "{Out} = (cg_ViewMatrix * CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0)).xyz;")
            // A preview has no meaningful world transform — the thumbnail mesh sits at the origin, so
            // its world position IS its object position. Emitting the world form anyway would multiply
            // by whatever model matrix the preview pass happened to leave in the object buffer, which is
            // identity today and would silently stop being so the moment that changes.
            .previewBodyFor(SPACE_ID, SPACE_WORLD, "{Out} = i.objectPos;")
            // Same Z convention as the Normal node's preview — see the long note there.
            .previewBodyFor(SPACE_ID, SPACE_VIEW, "{Out} = vec3(i.objectPos.xy, -i.objectPos.z);")
            .build();

    /**
     * The surface normal, transformed out of object space.
     *
     * <p>Uses {@code CG_NORMAL_MATRIX} rather than the raw attribute — a normal under a non-uniform
     * scale does not survive the model matrix, which is exactly what that matrix exists to correct. The
     * preview form reads the varying the preview's own vertex stage already normalised.</p>
     */
    public static final CgShaderNode NORMAL = CgTemplateShaderNode.of("cg:input/geometry/normal")
            .label("Normal Vector")
            .out("Out", CgShaderType.VEC3)
            .domain(CgShaderDomain.VERTEX)
            .previewGeometry(CgPreviewGeometry.SPHERE)
            .property(SPACE)
            // Object space is the DEFAULT, matching Unity — and this node previously emitted the world
            // form unconditionally, so its thumbnail was a world normal labelled as nothing at all.
            .body("{Out} = cg_Normal;")
            .previewBody("{Out} = i.normal;")
            .bodyFor(SPACE_ID, SPACE_WORLD, "{Out} = CG_NORMAL_MATRIX * cg_Normal;")
            // mat3 of the view matrix, so only the ROTATION applies. A normal is a direction: translating
            // it is meaningless, and using the full mat4 would drag the camera's position into a unit
            // vector and denormalise it by however far the camera happens to be from the origin.
            .bodyFor(SPACE_ID, SPACE_VIEW, "{Out} = mat3(cg_ViewMatrix) * (CG_NORMAL_MATRIX * cg_Normal);")
            .previewBodyFor(SPACE_ID, SPACE_WORLD, "{Out} = i.normal;")
            // NOT mat3(cg_ViewMatrix) here, and the reason is worth stating.
            //
            // A preview is rendered by a camera sitting at the identity, so the literal view transform is
            // a no-op and View drew pixel-for-pixel the same picture as Object -- a dropdown that
            // visibly did nothing. Rotating the preview camera to make it differ is not available
            // either: the same matrix drives the geometry, so it would spin the sphere and change the
            // Object thumbnail too.
            //
            // What actually distinguishes the two is the Z convention: object Z points away from the
            // viewer, view Z toward it. Negating Z expresses exactly that relationship, and reproduces
            // Unity's view-space ball (blue everywhere, cyan and magenta quadrants) on the same geometry.
            // The REAL shader above still emits the true transform -- this is the thumbnail's camera
            // being a stand-in, not the semantics being faked.
            .previewBodyFor(SPACE_ID, SPACE_VIEW, "{Out} = vec3(i.normal.xy, -i.normal.z);")
            .build();

    /** Adds every built-in to {@code registry}, in menu order. */
    public static void registerAll(CgShaderNodeRegistry registry) {
        registry.register(COLOR)
                .register(FLOAT)
                .register(VECTOR2)
                .register(VECTOR3)
                .register(VECTOR4)
                .register(TIME)
                .register(UV)
                .register(POSITION)
                .register(NORMAL)
                .register(ADD)
                .register(MULTIPLY);
    }
}
