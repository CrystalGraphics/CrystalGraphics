package com.crystalgraphics.shadergraph;

/**
 * The node set — starting small on purpose (see below), now growing toward Unity's Math category per
 * {@code docs/research/UNITY_SHADER_GRAPH_NODES.md}.
 *
 * <h3>Why a handful and not fifty, originally</h3>
 * <p>6.3.6 is the volume item and will eventually run to Unity's scale. The original set below was
 * chosen to prove the <em>stack</em> works end to end: a graph that compiles, links, and draws something
 * a person can look at. Adding forty more before that was proven would have been forty more things to
 * fix when the template language turned out to need one more feature.</p>
 *
 * <p>It was — the finding recorded on {@link #SPLIT} above is what closed that question — so the volume
 * pass has since started. {@link #SUBTRACT} through {@link #SIGN} are the first batch: Unity's Math ▸
 * Basic/Advanced/Round/Range nodes with no property, no domain and no conditional port, i.e. every one
 * that needed nothing from the template language {@link #ADD}/{@link #MULTIPLY} did not already prove.
 * Each is one GLSL builtin (or, for {@link #SATURATE}, one call into {@code math.glsl}) behind a
 * {@code DYNAMIC} in/out pair — no node in this batch needed a Java implementation of its own.</p>
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
 *   <li>{@link #SPLIT} — the first node with more than one output, proving 6.3's multi-output path</li>
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
    public static final CgShaderNode COLOR = CgTemplateShaderNode.of("cg:Input/Basic/color")
            .label("Color")
            .out("Out", CgShaderType.VEC4)
            .property(CgShaderNodeProperty.value("Value", "", CgShaderType.VEC4, "vec4(0.0, 0.0, 0.0, 1.0)"))
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
    public static final CgShaderNode FLOAT = CgTemplateShaderNode.of("cg:Input/Basic/float")
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
    public static final CgShaderNode VECTOR4 = CgTemplateShaderNode.of("cg:Input/Basic/vector4")
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
    public static final CgShaderNode VECTOR3 = CgTemplateShaderNode.of("cg:Input/Basic/vector3")
            .label("Vector 3")
            .in("X", CgShaderType.FLOAT, "0.0")
            .in("Y", CgShaderType.FLOAT, "0.0")
            .in("Z", CgShaderType.FLOAT, "0.0")
            .out("Out", CgShaderType.VEC3)
            .noPreview()
            .body("{Out} = vec3({X}, {Y}, {Z});")
            .build();

    /** @see #VECTOR4 */
    public static final CgShaderNode VECTOR2 = CgTemplateShaderNode.of("cg:Input/Basic/vector2")
            .label("Vector 2")
            .in("X", CgShaderType.FLOAT, "0.0")
            .in("Y", CgShaderType.FLOAT, "0.0")
            .out("Out", CgShaderType.VEC2)
            .noPreview()
            .body("{Out} = vec2({X}, {Y});")
            .build();

    /**
     * Seconds since start, from {@code cg_env.glsl}'s frame block — Unity's {@code Time} node, minus
     * the two outputs this engine has no data for.
     *
     * <p>{@code CG_TIME} is a macro over {@code cg_Time.y}, injected into every stage automatically —
     * so this node needs no include and works in both domains. {@code Sine Time}/{@code Cosine Time}
     * are honest derivations of it.</p>
     *
     * <p><b>{@code Delta Time}/{@code Smooth Delta} are deliberately absent</b> rather than wired to
     * something that looks plausible: {@code cg_Time} is {@code (t/20, t, t*2, t*3)} — four functions
     * of elapsed time, none of them a per-frame delta — and nothing in {@code cg_env.glsl}'s frame
     * block carries one. Faking it (e.g. a derivative of {@code CG_TIME}) would be a value with no
     * relationship to the real frame time, which is worse than a node not offering it at all.</p>
     *
     * <p>No preview, matching Unity: five numbers reading themselves above a swatch that just shows
     * "the current brightness" is the constant's own problem repeated, not a picture worth drawing.</p>
     */
    public static final CgShaderNode TIME = CgTemplateShaderNode.of("cg:Input/Basic/time")
            .label("Time")
            // Unity spells these "Sine Time"/"Cosine Time" — a space CgShaderPort's id cannot carry,
            // since it is substituted straight into a GLSL variable name (see variableName()). No
            // separate port label exists to spell it differently on screen either; camelCase is the
            // honest compromise until CgShaderPort grows one.
            .out("Time", CgShaderType.FLOAT)
            .out("SineTime", CgShaderType.FLOAT)
            .out("CosineTime", CgShaderType.FLOAT)
            .noPreview()
            // The one node in the library that reads a value with nothing in the graph having changed —
            // see CgShaderNode.isAnimated() for what this makes CgPreviewEmitter/CgPreviewRenderer do
            // with it downstream. Never set this on a node other than the actual source of the variance.
            .animated()
            .body("{Time} = CG_TIME;\n{SineTime} = sin(CG_TIME);\n{CosineTime} = cos(CG_TIME);")
            .build();

    /**
     * Adds two values of any matching width.
     *
     * <p>Dynamic on every port, so {@code Add(float, vec3)} resolves to vec3 and the compiler emits
     * {@code vec3(...)} around the scalar. That is one node instead of four, and it is the reason
     * {@link CgShaderNode} is an interface rather than only data.</p>
     */
    public static final CgShaderNode ADD = CgTemplateShaderNode.of("cg:Math/Basic/add")
            .label("Add")
            .in("A", CgShaderType.DYNAMIC, "0.0")
            .in("B", CgShaderType.DYNAMIC, "0.0")
            .out("Out", CgShaderType.DYNAMIC)
            .body("{Out} = {A} + {B};")
            .build();

    /**
     * Multiplies two values of any matching width. @see #ADD
     *
     * <p><b>{@code A} defaults to {@code 0.0}, {@code B} to {@code 1.0} — Unity's own asymmetric pair,
     * not two copies of the same "safe" number.</b> Both were tried first: {@code 0.0}/{@code 0.0}
     * previews black when nothing is wired but zeroes the whole result the instant only one side gets
     * wired, since the OTHER side is still 0. {@code 1.0}/{@code 1.0} fixes that (an unwired side is a
     * true no-op) but then a completely untouched node previews white — a value that reads as "set"
     * when nothing has been touched at all. The asymmetric pair gets both right at once, with no
     * preview-only special case anywhere: nothing wired is {@code 0 * 1 = 0} (black); wiring only
     * {@code A} is {@code A * 1 = A} (A's own value, untouched); wiring only {@code B} is
     * {@code 0 * B = 0} (still black, which is the deliberate nudge to also set {@code A} — Unity's
     * Multiply wants BOTH sides given a real value, not one wired input silently standing in for the
     * whole node).</p>
     */
    public static final CgShaderNode MULTIPLY = CgTemplateShaderNode.of("cg:Math/Basic/multiply")
            .label("Multiply")
            .in("A", CgShaderType.DYNAMIC, "0.0")
            .in("B", CgShaderType.DYNAMIC, "1.0")
            .out("Out", CgShaderType.DYNAMIC)
            .body("{Out} = {A} * {B};")
            .build();

    /**
     * Subtracts two values of any matching width. @see #ADD
     *
     * <p>Both default to {@code 0.0}, unlike {@link #MULTIPLY}'s asymmetric pair — subtraction has no
     * equivalent "safe" non-zero default the way multiplication's identity is {@code 1}, so nothing
     * wired is {@code 0 - 0 = 0} and wiring only one side passes that side through untouched either
     * way ({@code A - 0} or {@code 0 - B}), both honest.</p>
     */
    public static final CgShaderNode SUBTRACT = CgTemplateShaderNode.of("cg:Math/Basic/subtract")
            .label("Subtract")
            .in("A", CgShaderType.DYNAMIC, "0.0")
            .in("B", CgShaderType.DYNAMIC, "0.0")
            .out("Out", CgShaderType.DYNAMIC)
            .body("{Out} = {A} - {B};")
            .build();

    /**
     * Divides two values of any matching width. @see #ADD
     *
     * <p>{@code B} defaults to {@code 1.0}, not {@code 0.0} — GLSL does not raise on a divide by zero,
     * it silently produces {@code inf}/{@code NaN}, and an untouched node should preview a real number
     * rather than a value that poisons everything computed from it. {@code A} stays {@code 0.0} for the
     * same reason {@link #ADD}'s does: an unwired numerator is nothing to divide.</p>
     */
    public static final CgShaderNode DIVIDE = CgTemplateShaderNode.of("cg:Math/Basic/divide")
            .label("Divide")
            .in("A", CgShaderType.DYNAMIC, "0.0")
            .in("B", CgShaderType.DYNAMIC, "1.0")
            .out("Out", CgShaderType.DYNAMIC)
            .body("{Out} = {A} / {B};")
            .build();

    /**
     * Raises {@code A} to the {@code B} power. @see #ADD
     *
     * <p>Plain {@code pow}, not {@code positive_pow}/{@code safe_pow} from {@code math.glsl} — Unity's
     * own Power node is the raw GLSL/HLSL builtin with the same negative-base-and-fractional-exponent
     * {@code NaN} behaviour, and silently substituting a "safer" function would make this node compute
     * something Unity's own graph does not for the same inputs.</p>
     */
    public static final CgShaderNode POWER = CgTemplateShaderNode.of("cg:Math/Basic/power")
            .label("Power")
            .in("A", CgShaderType.DYNAMIC, "1.0")
            .in("B", CgShaderType.DYNAMIC, "2.0")
            .out("Out", CgShaderType.DYNAMIC)
            .body("{Out} = pow({A}, {B});")
            .build();

    /**
     * The square root of a value of any width — GLSL's {@code sqrt} is already {@code genType}, so one
     * template covers float through vec4 with no {@code {type:}} cast needed anywhere.
     */
    public static final CgShaderNode SQUARE_ROOT = CgTemplateShaderNode.of("cg:Math/Basic/square-root")
            .label("Square Root")
            .in("A", CgShaderType.DYNAMIC, "0.0")
            .out("Out", CgShaderType.DYNAMIC)
            .body("{Out} = sqrt({A});")
            .build();

    /** The absolute value of a value of any width. */
    public static final CgShaderNode ABSOLUTE = CgTemplateShaderNode.of("cg:Math/Advanced/absolute")
            .label("Absolute")
            .in("A", CgShaderType.DYNAMIC, "0.0")
            .out("Out", CgShaderType.DYNAMIC)
            .body("{Out} = abs({A});")
            .build();

    /**
     * Flips the sign of a value of any width — Unity's {@code Negate} is {@code Out = -1 * In}, which
     * is exactly what unary minus already does component-wise in GLSL.
     */
    public static final CgShaderNode NEGATE = CgTemplateShaderNode.of("cg:Math/Advanced/negate")
            .label("Negate")
            .in("A", CgShaderType.DYNAMIC, "0.0")
            .out("Out", CgShaderType.DYNAMIC)
            .body("{Out} = -{A};")
            .build();

    /**
     * {@code 1 - A}, component-wise — Unity's {@code One Minus}, the complement of a value already in
     * the {@code 0..1} range (most commonly paired with {@link #SATURATE} upstream).
     *
     * <p>Defaults to {@code 1.0} rather than {@code 0.0}: an untouched node then previews {@code 0}
     * (its own honest identity, {@code 1 - 1 = 0}) instead of {@code 1}, which would read as "this node
     * does nothing" the instant nothing is wired to it.</p>
     */
    public static final CgShaderNode ONE_MINUS = CgTemplateShaderNode.of("cg:Math/Range/one-minus")
            .label("One Minus")
            .in("A", CgShaderType.DYNAMIC, "1.0")
            .out("Out", CgShaderType.DYNAMIC)
            .body("{Out} = 1.0 - {A};")
            .build();

    /** The component-wise minimum of two values of any matching width. */
    public static final CgShaderNode MINIMUM = CgTemplateShaderNode.of("cg:Math/Range/minimum")
            .label("Minimum")
            .in("A", CgShaderType.DYNAMIC, "0.0")
            .in("B", CgShaderType.DYNAMIC, "0.0")
            .out("Out", CgShaderType.DYNAMIC)
            .body("{Out} = min({A}, {B});")
            .build();

    /** The component-wise maximum of two values of any matching width. */
    public static final CgShaderNode MAXIMUM = CgTemplateShaderNode.of("cg:Math/Range/maximum")
            .label("Maximum")
            .in("A", CgShaderType.DYNAMIC, "0.0")
            .in("B", CgShaderType.DYNAMIC, "0.0")
            .out("Out", CgShaderType.DYNAMIC)
            .body("{Out} = max({A}, {B});")
            .build();

    /**
     * Clamps {@code In} between {@code Min} and {@code Max}, all three of any matching width.
     *
     * <p>All three ports are {@code DYNAMIC} and resolve together, matching Unity's own Clamp — but an
     * unconnected {@code Min}/{@code Max} still emits as a bare float literal even when {@code In}
     * resolves to a vector, and that is correct rather than a missing cast: GLSL's {@code clamp}
     * overload set includes {@code clamp(vecN, float, float)}, broadcasting the scalar bound across
     * every component, so {@code clamp(node_x_Out, 0.0, 1.0)} on a {@code vec3} is legal as written.</p>
     */
    public static final CgShaderNode CLAMP = CgTemplateShaderNode.of("cg:Math/Range/clamp")
            .label("Clamp")
            .in("In", CgShaderType.DYNAMIC, "0.0")
            .in("Min", CgShaderType.DYNAMIC, "0.0")
            .in("Max", CgShaderType.DYNAMIC, "1.0")
            .out("Out", CgShaderType.DYNAMIC)
            .body("{Out} = clamp({In}, {Min}, {Max});")
            .build();

    /**
     * Clamps a value of any width to {@code 0..1} — {@code math.glsl}'s own {@code saturate}, which is
     * what every other consumer of that helper already calls, rather than a second, node-local
     * {@code clamp(x, 0.0, 1.0)} spelling of the identical operation.
     */
    public static final CgShaderNode SATURATE = CgTemplateShaderNode.of("cg:Math/Range/saturate")
            .label("Saturate")
            .in("A", CgShaderType.DYNAMIC, "0.0")
            .out("Out", CgShaderType.DYNAMIC)
            .include("crystalgraphics:shaders/lib/math.glsl")
            .body("{Out} = saturate({A});")
            .build();

    /** Rounds down to the nearest integer, component-wise, for a value of any width. */
    public static final CgShaderNode FLOOR = CgTemplateShaderNode.of("cg:Math/Round/floor")
            .label("Floor")
            .in("A", CgShaderType.DYNAMIC, "0.0")
            .out("Out", CgShaderType.DYNAMIC)
            .body("{Out} = floor({A});")
            .build();

    /** Rounds up to the nearest integer, component-wise, for a value of any width. */
    public static final CgShaderNode CEILING = CgTemplateShaderNode.of("cg:Math/Round/ceiling")
            .label("Ceiling")
            .in("A", CgShaderType.DYNAMIC, "0.0")
            .out("Out", CgShaderType.DYNAMIC)
            .body("{Out} = ceil({A});")
            .build();

    /** Rounds to the nearest integer, component-wise, for a value of any width. */
    public static final CgShaderNode ROUND = CgTemplateShaderNode.of("cg:Math/Round/round")
            .label("Round")
            .in("A", CgShaderType.DYNAMIC, "0.0")
            .out("Out", CgShaderType.DYNAMIC)
            .body("{Out} = round({A});")
            .build();

    /** {@code -1}, {@code 0} or {@code 1} per component, matching the sign of the input. */
    public static final CgShaderNode SIGN = CgTemplateShaderNode.of("cg:Math/Round/sign")
            .label("Sign")
            .in("A", CgShaderType.DYNAMIC, "0.0")
            .out("Out", CgShaderType.DYNAMIC)
            .body("{Out} = sign({A});")
            .build();

    // ── Math ▸ Advanced (completing the subcategory) ─────────────────────────

    /** {@code e^A} — Unity's Exponential minus its Base dropdown (Base 2 / Base e): this is always the
     * natural-log form, the same simplification {@link #LOG} makes for the same reason. */
    public static final CgShaderNode EXPONENTIAL = CgTemplateShaderNode.of("cg:Math/Advanced/exponential")
            .label("Exponential")
            .in("A", CgShaderType.DYNAMIC, "0.0")
            .out("Out", CgShaderType.DYNAMIC)
            .body("{Out} = exp({A});")
            .build();

    /** The magnitude of a vector of any width — {@code DYNAMIC} in, fixed {@code FLOAT} out, the same
     * shape {@link #DOT_PRODUCT} uses: the input's width is resolved from context, but a length is
     * always one number regardless of it. */
    public static final CgShaderNode LENGTH = CgTemplateShaderNode.of("cg:Math/Advanced/length")
            .label("Length")
            .in("A", CgShaderType.DYNAMIC, "0.0")
            .out("Out", CgShaderType.FLOAT)
            .body("{Out} = length({A});")
            .build();

    /** Natural log — Unity's Log minus its Base dropdown (Base 2 / Base 10 / Base e), same
     * simplification as {@link #EXPONENTIAL}. Defaults to {@code 1.0}: {@code log(0)} is {@code -inf}. */
    public static final CgShaderNode LOG = CgTemplateShaderNode.of("cg:Math/Advanced/log")
            .label("Log")
            .in("A", CgShaderType.DYNAMIC, "1.0")
            .out("Out", CgShaderType.DYNAMIC)
            .body("{Out} = log({A});")
            .build();

    /** {@code A} wrapped into {@code [0, B)} — GLSL's {@code mod}, component-wise for any width. */
    public static final CgShaderNode MODULO = CgTemplateShaderNode.of("cg:Math/Advanced/modulo")
            .label("Modulo")
            .in("A", CgShaderType.DYNAMIC, "0.5")
            .in("B", CgShaderType.DYNAMIC, "1.0")
            .out("Out", CgShaderType.DYNAMIC)
            .body("{Out} = mod({A}, {B});")
            .build();

    /** Scales a vector to unit length, direction unchanged. Defaults to {@code 1.0} rather than
     * {@code 0.0} — {@code normalize} of a zero-length vector is undefined (a {@code 0/0} per
     * component), so the one default that cannot silently produce {@code NaN} is a non-zero one. */
    public static final CgShaderNode NORMALIZE = CgTemplateShaderNode.of("cg:Math/Advanced/normalize")
            .label("Normalize")
            .in("A", CgShaderType.DYNAMIC, "1.0")
            .out("Out", CgShaderType.DYNAMIC)
            .body("{Out} = normalize({A});")
            .build();

    /** Quantises {@code In} to {@code Steps} discrete levels — Unity's formula, {@code floor(In *
     * Steps) / Steps}, kept as one expression rather than an intermediate variable since the template
     * language has nowhere to put one. */
    public static final CgShaderNode POSTERIZE = CgTemplateShaderNode.of("cg:Math/Advanced/posterize")
            .label("Posterize")
            .in("In", CgShaderType.DYNAMIC, "1.0")
            .in("Steps", CgShaderType.DYNAMIC, "4.0")
            .out("Out", CgShaderType.DYNAMIC)
            .body("{Out} = floor({In} * {Steps}) / {Steps};")
            .build();

    /** {@code 1 / A}. Defaults to {@code 1.0}, not {@code 0.0} — same reasoning as {@link #DIVIDE}'s
     * {@code B}: an untouched node should preview a real number, not {@code inf}. */
    public static final CgShaderNode RECIPROCAL = CgTemplateShaderNode.of("cg:Math/Advanced/reciprocal")
            .label("Reciprocal")
            .in("A", CgShaderType.DYNAMIC, "1.0")
            .out("Out", CgShaderType.DYNAMIC)
            .body("{Out} = 1.0 / {A};")
            .build();

    /** {@code 1 / sqrt(A)} — GLSL's {@code inversesqrt}, one call rather than two. Same {@code 1.0}
     * default as {@link #RECIPROCAL} and for the same reason. */
    public static final CgShaderNode RECIPROCAL_SQUARE_ROOT =
            CgTemplateShaderNode.of("cg:Math/Advanced/reciprocal-square-root")
                    .label("Reciprocal Square Root")
                    .in("A", CgShaderType.DYNAMIC, "1.0")
                    .out("Out", CgShaderType.DYNAMIC)
                    .body("{Out} = inversesqrt({A});")
                    .build();

    // ── Math ▸ Interpolation (all three, completing the subcategory) ─────────

    /**
     * The inverse of {@link #LERP}: given a value between {@code A} and {@code B}, returns where in
     * that range it sits, as a fraction. <b>Deliberately not clamped</b> — {@code T} outside the
     * {@code [A,B]} range extrapolates to a fraction outside {@code [0,1]} rather than saturating,
     * matching Unity's own Inverse Lerp; a caller that wants it clamped chains {@link #SATURATE}.
     */
    public static final CgShaderNode INVERSE_LERP = CgTemplateShaderNode.of("cg:Math/Interpolation/inverse-lerp")
            .label("Inverse Lerp")
            .in("A", CgShaderType.DYNAMIC, "0.0")
            .in("B", CgShaderType.DYNAMIC, "1.0")
            .in("T", CgShaderType.DYNAMIC, "0.5")
            .out("Out", CgShaderType.DYNAMIC)
            .body("{Out} = ({T} - {A}) / ({B} - {A});")
            .build();

    /**
     * Linearly interpolates between {@code A} and {@code B} by {@code T} — GLSL's {@code mix}, which
     * already accepts {@code T} either matching {@code A}/{@code B}'s width or as a lone float
     * (broadcast across every component), so an unconnected {@code T} stays a bare scalar literal
     * exactly like {@link #CLAMP}'s {@code Min}/{@code Max} do, and still compiles once {@code A}/
     * {@code B} widen.
     */
    public static final CgShaderNode LERP = CgTemplateShaderNode.of("cg:Math/Interpolation/lerp")
            .label("Lerp")
            .in("A", CgShaderType.DYNAMIC, "0.0")
            .in("B", CgShaderType.DYNAMIC, "1.0")
            .in("T", CgShaderType.DYNAMIC, "0.5")
            .out("Out", CgShaderType.DYNAMIC)
            .body("{Out} = mix({A}, {B}, {T});")
            .build();

    /** GLSL's own {@code smoothstep} — a Hermite-smoothed {@link #INVERSE_LERP}, clamped to
     * {@code [0,1]} and eased at both ends rather than linear. */
    public static final CgShaderNode SMOOTHSTEP = CgTemplateShaderNode.of("cg:Math/Interpolation/smoothstep")
            .label("Smoothstep")
            .in("Edge1", CgShaderType.DYNAMIC, "0.0")
            .in("Edge2", CgShaderType.DYNAMIC, "1.0")
            .in("In", CgShaderType.DYNAMIC, "0.5")
            .out("Out", CgShaderType.DYNAMIC)
            .body("{Out} = smoothstep({Edge1}, {Edge2}, {In});")
            .build();

    // ── Math ▸ Range (completing the subcategory) ─────────────────────────────

    /** The fractional part of {@code In} — GLSL's {@code fract}. */
    public static final CgShaderNode FRACTION = CgTemplateShaderNode.of("cg:Math/Range/fraction")
            .label("Fraction")
            .in("In", CgShaderType.DYNAMIC, "0.5")
            .out("Out", CgShaderType.DYNAMIC)
            .body("{Out} = fract({In});")
            .build();

    /**
     * Remaps {@code In} from {@code [InMin,InMax]} to {@code [OutMin,OutMax]} — {@code math.glsl}'s
     * {@code remap}, whose four overloads each require <b>all five parameters the same width</b> (no
     * scalar-broadcast overload the way GLSL's own {@code clamp}/{@code mix} have). An unconnected
     * bound is still a bare float literal, the same as {@link #CLAMP}'s {@code Min}/{@code Max} — but
     * here that literal has to be cast up explicitly via {@code {type:In}(...)}, or {@code
     * remap(vec3, float, float, float, float)} matches no overload at all and the shader fails to
     * compile the instant {@code In} widens past a float.
     */
    public static final CgShaderNode REMAP = CgTemplateShaderNode.of("cg:Math/Range/remap")
            .label("Remap")
            .in("In", CgShaderType.DYNAMIC, "0.5")
            .in("InMin", CgShaderType.DYNAMIC, "0.0")
            .in("InMax", CgShaderType.DYNAMIC, "1.0")
            .in("OutMin", CgShaderType.DYNAMIC, "0.0")
            .in("OutMax", CgShaderType.DYNAMIC, "1.0")
            .out("Out", CgShaderType.DYNAMIC)
            .include("crystalgraphics:shaders/lib/math.glsl")
            .body("{Out} = remap({In}, {type:In}({InMin}), {type:In}({InMax}), "
                    + "{type:In}({OutMin}), {type:In}({OutMax}));")
            .build();

    /**
     * A pseudo-random float in {@code [Min,Max]}, deterministic from {@code Seed} — Unity's Random
     * Range. {@code Seed}/{@code Min}/{@code Max} are fixed types, not {@code DYNAMIC}: a hash needs a
     * concrete {@code vec2} to hash and the result is always one float, so there is no width to
     * resolve from context the way every other node in this batch has one.
     */
    public static final CgShaderNode RANDOM_RANGE = CgTemplateShaderNode.of("cg:Math/Range/random-range")
            .label("Random Range")
            .in("Seed", CgShaderType.VEC2, "vec2(0.0, 0.0)")
            .in("Min", CgShaderType.FLOAT, "0.0")
            .in("Max", CgShaderType.FLOAT, "1.0")
            .out("Out", CgShaderType.FLOAT)
            .include("crystalgraphics:shaders/lib/noise.glsl")
            .body("{Out} = mix({Min}, {Max}, hash12({Seed}));")
            .build();

    // ── Math ▸ Round (completing the subcategory) ─────────────────────────────

    /** {@code 0} where {@code In < Edge}, {@code 1} where {@code In >= Edge} — GLSL's {@code step}. */
    public static final CgShaderNode STEP = CgTemplateShaderNode.of("cg:Math/Round/step")
            .label("Step")
            .in("Edge", CgShaderType.DYNAMIC, "0.5")
            .in("In", CgShaderType.DYNAMIC, "1.0")
            .out("Out", CgShaderType.DYNAMIC)
            .body("{Out} = step({Edge}, {In});")
            .build();

    /** Discards the fractional part, toward zero — GLSL's {@code trunc}, distinct from {@link #FLOOR}
     * only for a negative input ({@code trunc(-1.5) = -1.0}, {@code floor(-1.5) = -2.0}). */
    public static final CgShaderNode TRUNCATE = CgTemplateShaderNode.of("cg:Math/Round/truncate")
            .label("Truncate")
            .in("A", CgShaderType.DYNAMIC, "0.0")
            .out("Out", CgShaderType.DYNAMIC)
            .body("{Out} = trunc({A});")
            .build();

    // ── Math ▸ Trigonometry (all twelve, completing the subcategory) ─────────

    public static final CgShaderNode SINE = CgTemplateShaderNode.of("cg:Math/Trigonometry/sine")
            .label("Sine")
            .in("In", CgShaderType.DYNAMIC, "0.0")
            .out("Out", CgShaderType.DYNAMIC)
            .body("{Out} = sin({In});")
            .build();

    public static final CgShaderNode COSINE = CgTemplateShaderNode.of("cg:Math/Trigonometry/cosine")
            .label("Cosine")
            .in("In", CgShaderType.DYNAMIC, "0.0")
            .out("Out", CgShaderType.DYNAMIC)
            .body("{Out} = cos({In});")
            .build();

    public static final CgShaderNode TANGENT = CgTemplateShaderNode.of("cg:Math/Trigonometry/tangent")
            .label("Tangent")
            .in("In", CgShaderType.DYNAMIC, "0.0")
            .out("Out", CgShaderType.DYNAMIC)
            .body("{Out} = tan({In});")
            .build();

    /** Inverse sine, in radians. {@code In} outside {@code [-1,1]} is out of domain (GLSL yields
     * undefined results, not an error) — the same contract GLSL's own {@code asin} carries. */
    public static final CgShaderNode ARCSINE = CgTemplateShaderNode.of("cg:Math/Trigonometry/arcsine")
            .label("Arcsine")
            .in("In", CgShaderType.DYNAMIC, "0.0")
            .out("Out", CgShaderType.DYNAMIC)
            .body("{Out} = asin({In});")
            .build();

    /** Inverse cosine, in radians. Same domain note as {@link #ARCSINE}. */
    public static final CgShaderNode ARCCOSINE = CgTemplateShaderNode.of("cg:Math/Trigonometry/arccosine")
            .label("Arccosine")
            .in("In", CgShaderType.DYNAMIC, "0.0")
            .out("Out", CgShaderType.DYNAMIC)
            .body("{Out} = acos({In});")
            .build();

    /** Inverse tangent, in radians, in {@code (-pi/2, pi/2)} — the one-argument form. See {@link
     * #ARCTANGENT2} for the two-argument, full-circle version. */
    public static final CgShaderNode ARCTANGENT = CgTemplateShaderNode.of("cg:Math/Trigonometry/arctangent")
            .label("Arctangent")
            .in("In", CgShaderType.DYNAMIC, "0.0")
            .out("Out", CgShaderType.DYNAMIC)
            .body("{Out} = atan({In});")
            .build();

    /** Two-argument {@code atan(A, B)} — the full {@code (-pi, pi]} range {@link #ARCTANGENT} cannot
     * reach, since it alone cannot tell which quadrant the original {@code (x,y)} came from. */
    public static final CgShaderNode ARCTANGENT2 = CgTemplateShaderNode.of("cg:Math/Trigonometry/arctangent2")
            .label("Arctangent2")
            .in("A", CgShaderType.DYNAMIC, "0.0")
            .in("B", CgShaderType.DYNAMIC, "1.0")
            .out("Out", CgShaderType.DYNAMIC)
            .body("{Out} = atan({A}, {B});")
            .build();

    public static final CgShaderNode HYPERBOLIC_SINE =
            CgTemplateShaderNode.of("cg:Math/Trigonometry/hyperbolic-sine")
                    .label("Hyperbolic Sine")
                    .in("In", CgShaderType.DYNAMIC, "0.0")
                    .out("Out", CgShaderType.DYNAMIC)
                    .body("{Out} = sinh({In});")
                    .build();

    public static final CgShaderNode HYPERBOLIC_COSINE =
            CgTemplateShaderNode.of("cg:Math/Trigonometry/hyperbolic-cosine")
                    .label("Hyperbolic Cosine")
                    .in("In", CgShaderType.DYNAMIC, "0.0")
                    .out("Out", CgShaderType.DYNAMIC)
                    .body("{Out} = cosh({In});")
                    .build();

    public static final CgShaderNode HYPERBOLIC_TANGENT =
            CgTemplateShaderNode.of("cg:Math/Trigonometry/hyperbolic-tangent")
                    .label("Hyperbolic Tangent")
                    .in("In", CgShaderType.DYNAMIC, "0.0")
                    .out("Out", CgShaderType.DYNAMIC)
                    .body("{Out} = tanh({In});")
                    .build();

    /** GLSL's {@code radians()} builtin is already {@code genType} — no need for {@code math.glsl}'s
     * float-only {@code deg_to_rad}, and no {@code {type:}} cast either. */
    public static final CgShaderNode DEGREES_TO_RADIANS =
            CgTemplateShaderNode.of("cg:Math/Trigonometry/degrees-to-radians")
                    .label("Degrees to Radians")
                    .in("In", CgShaderType.DYNAMIC, "0.0")
                    .out("Out", CgShaderType.DYNAMIC)
                    .body("{Out} = radians({In});")
                    .build();

    /** @see #DEGREES_TO_RADIANS — same reasoning, GLSL's {@code degrees()} builtin. */
    public static final CgShaderNode RADIANS_TO_DEGREES =
            CgTemplateShaderNode.of("cg:Math/Trigonometry/radians-to-degrees")
                    .label("Radians to Degrees")
                    .in("In", CgShaderType.DYNAMIC, "0.0")
                    .out("Out", CgShaderType.DYNAMIC)
                    .body("{Out} = degrees({In});")
                    .build();

    // ── Math ▸ Vector (starting the subcategory — two of ten) ─────────────────

    /** The scalar dot product of two vectors of any matching width — fixed {@code FLOAT} output, same
     * shape as {@link #LENGTH}. GLSL's {@code dot} is defined for {@code float} too ({@code dot(x,y) =
     * x*y}), so one template covers the whole width range with no separate scalar case. */
    public static final CgShaderNode DOT_PRODUCT = CgTemplateShaderNode.of("cg:Math/Vector/dot-product")
            .label("Dot Product")
            .in("A", CgShaderType.DYNAMIC, "0.0")
            .in("B", CgShaderType.DYNAMIC, "0.0")
            .out("Out", CgShaderType.FLOAT)
            .body("{Out} = dot({A}, {B});")
            .build();

    /** The cross product of two {@code vec3}s — fixed, not {@code DYNAMIC}: {@code cross} is defined
     * only for three components, so there is no width to resolve from context the way {@link
     * #DOT_PRODUCT} has. Defaults to the X and Y basis vectors, so an untouched node previews a
     * genuine, non-degenerate result ({@code X × Y = Z}) rather than the zero vector two unwired
     * defaults would otherwise both collapse to. */
    public static final CgShaderNode CROSS_PRODUCT = CgTemplateShaderNode.of("cg:Math/Vector/cross-product")
            .label("Cross Product")
            .in("A", CgShaderType.VEC3, "vec3(1.0, 0.0, 0.0)")
            .in("B", CgShaderType.VEC3, "vec3(0.0, 1.0, 0.0)")
            .out("Out", CgShaderType.VEC3)
            .body("{Out} = cross({A}, {B});")
            .build();

    // ── Math ▸ Derivative (all three) ─────────────────────────────────────────
    //
    // FRAGMENT-only, all three — dFdx/dFdy are refused outright in a vertex shader, the same GLSL rule
    // sdf.glsl's own fwidth-guard documents. Declaring the domain here is what stops a node graph from
    // reproducing that failure by construction: CgGraphCompiler refuses to place one in the vertex
    // stage rather than emitting GLSL a vertex shader will not compile.

    public static final CgShaderNode DDX = CgTemplateShaderNode.of("cg:Math/Derivative/ddx")
            .label("DDX")
            .in("In", CgShaderType.DYNAMIC, "0.0")
            .out("Out", CgShaderType.DYNAMIC)
            .domain(CgShaderDomain.FRAGMENT)
            .body("{Out} = dFdx({In});")
            .build();

    public static final CgShaderNode DDY = CgTemplateShaderNode.of("cg:Math/Derivative/ddy")
            .label("DDY")
            .in("In", CgShaderType.DYNAMIC, "0.0")
            .out("Out", CgShaderType.DYNAMIC)
            .domain(CgShaderDomain.FRAGMENT)
            .body("{Out} = dFdy({In});")
            .build();

    /** {@code |dFdx| + |dFdy|} — Unity's combined screen-space derivative magnitude. */
    public static final CgShaderNode DDXY = CgTemplateShaderNode.of("cg:Math/Derivative/ddxy")
            .label("DDXY")
            .in("In", CgShaderType.DYNAMIC, "0.0")
            .out("Out", CgShaderType.DYNAMIC)
            .domain(CgShaderDomain.FRAGMENT)
            .body("{Out} = abs(dFdx({In})) + abs(dFdy({In}));")
            .build();

    // ── Math ▸ Matrix (all four, simplified to mat4 only) ─────────────────────
    //
    // Unity's four Matrix nodes each carry a Dimension property (2x2/3x3/4x4) this batch does not
    // reproduce — every port here is a fixed mat4, matching this codebase's established policy of
    // dropping a property rather than adding one where a single concrete form covers the common case
    // (see EXPONENTIAL/LOG's own Base-dropdown simplification). A caller needing mat2/mat3 still has
    // the type (CgShaderType.MAT2/MAT3) and math.glsl's own functions; it just has no NODE for it yet.
    //
    // These are also this library's first consumers of a matrix TYPE at all — CgShaderType.MAT2/3/4
    // existed with nothing wired through them. One consequence worth knowing: ShaderGraphBridge's
    // widgetKindFor has no case for a matrix type, so an unconnected matrix INPUT port gets no inline
    // editor (same gap Texture/Sampler/Gradient already have) — every port below must be wired rather
    // than typed in, which is why each still declares an identity-matrix literal default (the value an
    // unconnected port compiles to, never something a user is expected to edit by hand).

    /** GLSL's own column-major {@code m[i]} indexing, not Unity's row-based Matrix Split — see the
     * class-level note above. Naming the ports {@code Col0}..{@code Col3} states plainly which
     * convention this is, rather than a {@code Row0} that would be quietly wrong. */
    public static final CgShaderNode MATRIX_SPLIT = CgTemplateShaderNode.of("cg:Math/Matrix/split")
            .label("Matrix Split")
            .in("In", CgShaderType.MAT4, "mat4(1.0)")
            .out("Col0", CgShaderType.VEC4)
            .out("Col1", CgShaderType.VEC4)
            .out("Col2", CgShaderType.VEC4)
            .out("Col3", CgShaderType.VEC4)
            .noPreview()
            .body("{Col0} = {In}[0];\n{Col1} = {In}[1];\n{Col2} = {In}[2];\n{Col3} = {In}[3];")
            .build();

    /** The inverse of {@link #MATRIX_SPLIT} — GLSL's {@code mat4(c0,c1,c2,c3)} constructor already
     * takes four column vectors directly, so this needed no Java implementation either. */
    public static final CgShaderNode MATRIX_CONSTRUCTION = CgTemplateShaderNode.of("cg:Math/Matrix/construction")
            .label("Matrix Construction")
            .in("Col0", CgShaderType.VEC4, "vec4(1.0, 0.0, 0.0, 0.0)")
            .in("Col1", CgShaderType.VEC4, "vec4(0.0, 1.0, 0.0, 0.0)")
            .in("Col2", CgShaderType.VEC4, "vec4(0.0, 0.0, 1.0, 0.0)")
            .in("Col3", CgShaderType.VEC4, "vec4(0.0, 0.0, 0.0, 1.0)")
            .out("Out", CgShaderType.MAT4)
            .noPreview()
            .body("{Out} = mat4({Col0}, {Col1}, {Col2}, {Col3});")
            .build();

    public static final CgShaderNode MATRIX_TRANSPOSE = CgTemplateShaderNode.of("cg:Math/Matrix/transpose")
            .label("Matrix Transpose")
            .in("In", CgShaderType.MAT4, "mat4(1.0)")
            .out("Out", CgShaderType.MAT4)
            .noPreview()
            .body("{Out} = transpose({In});")
            .build();

    public static final CgShaderNode MATRIX_DETERMINANT = CgTemplateShaderNode.of("cg:Math/Matrix/determinant")
            .label("Matrix Determinant")
            .in("In", CgShaderType.MAT4, "mat4(1.0)")
            .out("Out", CgShaderType.FLOAT)
            .noPreview()
            .body("{Out} = determinant({In});")
            .build();

    // ── Math ▸ Vector (the remaining eight, completing the subcategory) ───────

    /** The scalar distance between two vectors of any matching width — fixed {@code FLOAT} out, same
     * shape as {@link #LENGTH}/{@link #DOT_PRODUCT}. */
    public static final CgShaderNode DISTANCE = CgTemplateShaderNode.of("cg:Math/Vector/distance")
            .label("Distance")
            .in("A", CgShaderType.DYNAMIC, "0.0")
            .in("B", CgShaderType.DYNAMIC, "0.0")
            .out("Out", CgShaderType.FLOAT)
            .body("{Out} = distance({A}, {B});")
            .build();

    /** {@code I} reflected about {@code N} — GLSL's {@code reflect}, {@code DYNAMIC} on both ports
     * since it is defined component-wise for any matching width. */
    public static final CgShaderNode REFLECTION = CgTemplateShaderNode.of("cg:Math/Vector/reflection")
            .label("Reflection")
            .in("I", CgShaderType.DYNAMIC, "0.0")
            .in("N", CgShaderType.DYNAMIC, "1.0")
            .out("Out", CgShaderType.DYNAMIC)
            .body("{Out} = reflect({I}, {N});")
            .build();

    /** Schlick's approximation — {@code vector.glsl}'s own {@code fresnel}, the exact function this
     * node exists to expose. Fixed {@code VEC3} ports, not {@code DYNAMIC}: a Fresnel term is only
     * meaningful for a real surface normal and view direction, both inherently three-component. */
    public static final CgShaderNode FRESNEL_EFFECT = CgTemplateShaderNode.of("cg:Math/Vector/fresnel-effect")
            .label("Fresnel Effect")
            // BOUND TO REAL GEOMETRY, not to a literal — the same mechanism every UV-consuming node
            // already uses, and the same failure it was introduced to kill.
            //
            // Both were `vec3(0.0, 0.0, 1.0)`. A Fresnel term is `(1 - dot(N, V))^p`, so with N and V
            // both fixed at the same constant it evaluates to zero at EVERY pixel: the node previewed
            // as a solid black square, and a graph that used it without wiring anything got a constant.
            // Unity binds these two slots to the surface normal and the view direction and shows a space
            // chooser rather than a value field, for exactly this reason.
            .inWithImplicitDefault("Normal", CgShaderType.VEC3,
                    () -> CgBuiltinShaderNodes.NORMAL, "Out")
            .inWithImplicitDefault("ViewDir", CgShaderType.VEC3,
                    () -> CgBuiltinShaderNodes.VIEW_DIRECTION, "Out")
            .in("Power", CgShaderType.FLOAT, "1.0")
            .out("Out", CgShaderType.FLOAT)
            .include("crystalgraphics:shaders/lib/vector.glsl")
            .body("{Out} = fresnel({Normal}, {ViewDir}, {Power});")
            .build();

    /** The component of {@code A} lying along {@code B} — {@code vector.glsl}'s {@code project_onto}.
     * Fixed {@code VEC3}: the function itself is, and a projection is meaningless without a genuine
     * direction to project onto. */
    public static final CgShaderNode PROJECTION = CgTemplateShaderNode.of("cg:Math/Vector/projection")
            .label("Projection")
            .in("A", CgShaderType.VEC3, "vec3(1.0, 0.0, 0.0)")
            .in("B", CgShaderType.VEC3, "vec3(0.0, 1.0, 0.0)")
            .out("Out", CgShaderType.VEC3)
            .include("crystalgraphics:shaders/lib/vector.glsl")
            .body("{Out} = project_onto({A}, {B});")
            .build();

    /** {@code A} minus {@link #PROJECTION} — the component of {@code A} perpendicular to {@code B},
     * {@code vector.glsl}'s {@code reject_from}. Same fixed {@code VEC3} reasoning as Projection. */
    public static final CgShaderNode REJECTION = CgTemplateShaderNode.of("cg:Math/Vector/rejection")
            .label("Rejection")
            .in("A", CgShaderType.VEC3, "vec3(1.0, 0.0, 0.0)")
            .in("B", CgShaderType.VEC3, "vec3(0.0, 1.0, 0.0)")
            .out("Out", CgShaderType.VEC3)
            .include("crystalgraphics:shaders/lib/vector.glsl")
            .body("{Out} = reject_from({A}, {B});")
            .build();

    /**
     * Rotates {@code In} around {@code Axis} by {@code Rotation} — Rodrigues' formula, {@code
     * vector.glsl}'s own {@code rotate_axis} (whose doc comment there already names this as the node
     * it exists for).
     *
     * <p>{@code Rotation} is <b>radians</b>, not Unity's degrees or turns — this node was not among the
     * fifteen verified in detail, so rather than guess at which unit Unity's own field uses, this
     * states plainly what {@code rotate_axis} itself takes. A caller wanting degrees chains {@link
     * #DEGREES_TO_RADIANS} first.</p>
     */
    public static final CgShaderNode ROTATE_ABOUT_AXIS = CgTemplateShaderNode.of("cg:Math/Vector/rotate-about-axis")
            .label("Rotate About Axis")
            .in("In", CgShaderType.VEC3, "vec3(1.0, 0.0, 0.0)")
            .in("Axis", CgShaderType.VEC3, "vec3(0.0, 1.0, 0.0)")
            .in("Rotation", CgShaderType.FLOAT, "0.0")
            .out("Out", CgShaderType.VEC3)
            .include("crystalgraphics:shaders/lib/vector.glsl")
            .body("{Out} = rotate_axis({In}, {Axis}, {Rotation});")
            .build();

    /**
     * A soft-edged sphere mask: {@code 1} inside {@code Radius} of {@code Center}, fading to {@code 0}
     * over a band {@code Hardness} controls, {@code 1} fully sharp.
     *
     * <p>Not a single library call — Unity's own formula, reproduced directly rather than through a new
     * {@code math.glsl} helper this is the only caller of. {@code max(..., 1e-4)} guards the divide when
     * {@code Hardness} is authored at exactly {@code 1.0}.</p>
     */
    public static final CgShaderNode SPHERE_MASK = CgTemplateShaderNode.of("cg:Math/Vector/sphere-mask")
            .label("Sphere Mask")
            .in("Coords", CgShaderType.VEC3, "vec3(0.0, 0.0, 0.0)")
            .in("Center", CgShaderType.VEC3, "vec3(0.0, 0.0, 0.0)")
            .in("Radius", CgShaderType.FLOAT, "0.1")
            .in("Hardness", CgShaderType.FLOAT, "0.8")
            .out("Out", CgShaderType.FLOAT)
            .include("crystalgraphics:shaders/lib/math.glsl")
            .body("{Out} = 1.0 - saturate((distance({Coords}, {Center}) - {Radius}) "
                    + "/ max(1.0 - {Hardness}, 1e-4));")
            .build();

    /**
     * Converts a position from object space into {@link #SPACE}. Simplified from Unity's own
     * Transform node, which crosses a {@code From} space, a {@code To} space AND a {@code Type}
     * (Position/Direction/Normal) — a combinatorial control surface this batch does not reproduce.
     * {@code From} is fixed at Object and {@code Type} at Position, mirroring exactly what {@link
     * #POSITION}'s own {@code SPACE} property already does; only {@code To} is offered, via the same
     * shared property, rather than introducing a second one.
     */
    public static final CgShaderNode TRANSFORM = CgTemplateShaderNode.of("cg:Math/Vector/transform")
            .label("Transform")
            .in("In", CgShaderType.VEC3, "vec3(0.0, 0.0, 0.0)")
            .out("Out", CgShaderType.VEC3)
            .property(SPACE)
            .body("{Out} = {In};")
            .bodyFor(SPACE_ID, SPACE_WORLD, "{Out} = (CG_OBJECT_TO_WORLD * vec4({In}, 1.0)).xyz;")
            .bodyFor(SPACE_ID, SPACE_VIEW, "{Out} = (cg_ViewMatrix * CG_OBJECT_TO_WORLD * vec4({In}, 1.0)).xyz;")
            // THE PREVIEW FORMS, which this node had none of — so its thumbnail fell through to the real
            // bodies above and every option drew the same picture.
            //
            // Not a wrong picture, which is what made it quieter than the inversion Position and Normal
            // Vector each carried, but wrong in a way that reads as the node being broken: pick World,
            // nothing changes; pick View, nothing changes. And it was true by ACCIDENT rather than by
            // design — the preview pass leaves an identity model matrix and an identity view matrix, so
            // both transforms above happen to be no-ops there. Position's own note says not to depend on
            // that, and this was depending on it.
            //
            // World is the same value, stated rather than inherited from a coincidence.
            .previewBodyFor(SPACE_ID, SPACE_WORLD, "{Out} = {In};")
            // ...and View flips Z, which is the SAME stand-in Position and Normal Vector use, for the same
            // reason: a preview camera sits at the identity, so nothing else distinguishes the two spaces
            // and the Z convention is what the option actually means. All three nodes share this property,
            // so all three have to express it identically — a Transform whose View disagreed with the
            // Position wired into it would be the more confusing of the two answers.
            .previewBodyFor(SPACE_ID, SPACE_VIEW, "{Out} = vec3(({In}).xy, -({In}).z);")
            .build();

    // ── Math ▸ Wave (one of two — see the class doc for why Noise Sine Wave is deferred) ─────────────

    /** A rising ramp from {@code -1} to {@code 1} with period {@code 1}, resetting at each integer —
     * the standard sawtooth. */
    public static final CgShaderNode SAWTOOTH_WAVE = CgTemplateShaderNode.of("cg:Math/Wave/sawtooth-wave")
            .label("Sawtooth Wave")
            .in("In", CgShaderType.DYNAMIC, "0.0")
            .out("Out", CgShaderType.DYNAMIC)
            .body("{Out} = (fract({In}) - 0.5) * 2.0;")
            .build();

    // ── Channel ▸ Combine, Flip (Swizzle deferred — see below) ────────────────

    /** Assembles four scalars into a {@code vec4} — the exact inverse of {@link #SPLIT}. {@code A}
     * defaults to {@code 1.0}, not {@code 0.0}: an untouched Combine should preview an opaque colour,
     * the same reasoning {@code Color}'s own default carries. */
    public static final CgShaderNode COMBINE = CgTemplateShaderNode.of("cg:Channel/combine")
            .label("Combine")
            .in("R", CgShaderType.FLOAT, "0.0")
            .in("G", CgShaderType.FLOAT, "0.0")
            .in("B", CgShaderType.FLOAT, "0.0")
            .in("A", CgShaderType.FLOAT, "1.0")
            .out("Out", CgShaderType.VEC4)
            .body("{Out} = vec4({R}, {G}, {B}, {A});")
            .build();

    /** Inverts whichever channels their own toggle selects — {@code channel' = toggle ? 1-channel :
     * channel}, one ternary per component. Fixed {@code VEC4}/{@code BOOL}, not {@code DYNAMIC}: a
     * per-channel toggle is meaningless without knowing which four channels there are. */
    public static final CgShaderNode FLIP = CgTemplateShaderNode.of("cg:Channel/flip")
            .label("Flip")
            .in("In", CgShaderType.VEC4, "vec4(0.0, 0.0, 0.0, 0.0)")
            .in("RFlip", CgShaderType.BOOL, "false")
            .in("GFlip", CgShaderType.BOOL, "false")
            .in("BFlip", CgShaderType.BOOL, "false")
            .in("AFlip", CgShaderType.BOOL, "false")
            .out("Out", CgShaderType.VEC4)
            .noPreview()
            .body("{Out} = vec4({RFlip} ? 1.0 - {In}.r : {In}.r, {GFlip} ? 1.0 - {In}.g : {In}.g, "
                    + "{BFlip} ? 1.0 - {In}.b : {In}.b, {AFlip} ? 1.0 - {In}.a : {In}.a);")
            .build();

    // ── UV ▸ direct stdlib calls (Rotate, Tiling and Offset, Polar Coordinates) ───────────────────

    /** {@code uv.glsl}'s own {@code rotate_uv} — {@code Rotation} is radians, same simplification
     * {@link #ROTATE_ABOUT_AXIS} already documents (Unity's own Unit dropdown not reproduced). */
    public static final CgShaderNode UV_ROTATE = CgTemplateShaderNode.of("cg:UV/rotate")
            .label("Rotate")
            .inWithImplicitDefault("UV", CgShaderType.VEC2, () -> CgBuiltinShaderNodes.UV, "Out")
            .in("Center", CgShaderType.VEC2, "vec2(0.5, 0.5)")
            .in("Rotation", CgShaderType.FLOAT, "0.0")
            .out("Out", CgShaderType.VEC2)
            .include("crystalgraphics:shaders/lib/uv.glsl")
            .body("{Out} = rotate_uv({UV}, {Rotation}, {Center});")
            .build();

    /** {@code uv.glsl}'s {@code tile_uv}. */
    public static final CgShaderNode TILING_AND_OFFSET = CgTemplateShaderNode.of("cg:UV/tiling-and-offset")
            .label("Tiling and Offset")
            .inWithImplicitDefault("UV", CgShaderType.VEC2, () -> CgBuiltinShaderNodes.UV, "Out")
            .in("Tiling", CgShaderType.VEC2, "vec2(1.0, 1.0)")
            .in("Offset", CgShaderType.VEC2, "vec2(0.0, 0.0)")
            .out("Out", CgShaderType.VEC2)
            .include("crystalgraphics:shaders/lib/uv.glsl")
            .body("{Out} = tile_uv({UV}, {Tiling}, {Offset});")
            .build();

    /**
     * {@code uv.glsl}'s {@code polar_coordinates_uv} — Unity's own node formula, not the generic
     * {@code cartesian_to_polar_uv} helper beside it.
     *
     * <p>It went through that helper first, and the preview said so: the helper wraps theta into
     * {@code [0,1]} (right for re-tiling a UV into polar space, wrong here), which made the green
     * channel positive everywhere and produced a uniformly green/yellow thumbnail with the seam on the
     * wrong axis. Unity's angle is <b>signed</b>, so half the field clamps green to zero and reads red —
     * the red/green split down the middle is the node's whole visual signature. See that function's own
     * note for both differences.</p>
     */
    public static final CgShaderNode POLAR_COORDINATES = CgTemplateShaderNode.of("cg:UV/polar-coordinates")
            .label("Polar Coordinates")
            .inWithImplicitDefault("UV", CgShaderType.VEC2, () -> CgBuiltinShaderNodes.UV, "Out")
            .in("Center", CgShaderType.VEC2, "vec2(0.5, 0.5)")
            .in("RadialScale", CgShaderType.FLOAT, "1.0")
            .in("LengthScale", CgShaderType.FLOAT, "1.0")
            .out("Out", CgShaderType.VEC2)
            .include("crystalgraphics:shaders/lib/uv.glsl")
            .body("{Out} = polar_coordinates_uv({UV}, {Center}, {RadialScale}, {LengthScale});")
            .build();

    // ── UV ▸ standard distortion formulas (new to uv.glsl this batch) ────────────────────────────

    public static final CgShaderNode TWIRL = CgTemplateShaderNode.of("cg:UV/twirl")
            .label("Twirl")
            .inWithImplicitDefault("UV", CgShaderType.VEC2, () -> CgBuiltinShaderNodes.UV, "Out")
            .in("Center", CgShaderType.VEC2, "vec2(0.5, 0.5)")
            .in("Strength", CgShaderType.FLOAT, "1.0")
            .in("Offset", CgShaderType.VEC2, "vec2(0.0, 0.0)")
            .out("Out", CgShaderType.VEC2)
            .include("crystalgraphics:shaders/lib/uv.glsl")
            .body("{Out} = twirl_uv({UV}, {Center}, {Strength}, {Offset});")
            .build();

    public static final CgShaderNode RADIAL_SHEAR = CgTemplateShaderNode.of("cg:UV/radial-shear")
            .label("Radial Shear")
            .inWithImplicitDefault("UV", CgShaderType.VEC2, () -> CgBuiltinShaderNodes.UV, "Out")
            .in("Center", CgShaderType.VEC2, "vec2(0.5, 0.5)")
            .in("Strength", CgShaderType.VEC2, "vec2(1.0, 1.0)")
            .in("Offset", CgShaderType.VEC2, "vec2(0.0, 0.0)")
            .out("Out", CgShaderType.VEC2)
            .include("crystalgraphics:shaders/lib/uv.glsl")
            .body("{Out} = radial_shear_uv({UV}, {Center}, {Strength}, {Offset});")
            .build();

    public static final CgShaderNode SPHERIZE = CgTemplateShaderNode.of("cg:UV/spherize")
            .label("Spherize")
            .inWithImplicitDefault("UV", CgShaderType.VEC2, () -> CgBuiltinShaderNodes.UV, "Out")
            .in("Center", CgShaderType.VEC2, "vec2(0.5, 0.5)")
            .in("Strength", CgShaderType.FLOAT, "1.0")
            .in("Offset", CgShaderType.VEC2, "vec2(0.0, 0.0)")
            .out("Out", CgShaderType.VEC2)
            .include("crystalgraphics:shaders/lib/uv.glsl")
            .body("{Out} = spherize_uv({UV}, {Center}, {Strength}, {Offset});")
            .build();

    // ── Utility ▸ Logic (And, Or, Not, Nand, Comparison, Branch) ──────────────────────────────────
    //
    // All fixed BOOL/FLOAT, not DYNAMIC: GLSL's &&/||/comparison operators are scalar-bool-only (no
    // bvecN short-circuit form), and generalising Comparison to a vector would need lessThan()/any()/
    // all() reducing a bvec back to one bool — a real mechanism this batch does not add. noPreview()
    // on all four boolean-output nodes: a bool has no color-swatch reading the way a vec4 does.

    public static final CgShaderNode AND = CgTemplateShaderNode.of("cg:Utility/Logic/and")
            .label("And")
            .in("A", CgShaderType.BOOL, "true")
            .in("B", CgShaderType.BOOL, "true")
            .out("Out", CgShaderType.BOOL)
            .noPreview()
            .body("{Out} = {A} && {B};")
            .build();

    public static final CgShaderNode OR = CgTemplateShaderNode.of("cg:Utility/Logic/or")
            .label("Or")
            .in("A", CgShaderType.BOOL, "false")
            .in("B", CgShaderType.BOOL, "false")
            .out("Out", CgShaderType.BOOL)
            .noPreview()
            .body("{Out} = {A} || {B};")
            .build();

    public static final CgShaderNode NOT = CgTemplateShaderNode.of("cg:Utility/Logic/not")
            .label("Not")
            .in("In", CgShaderType.BOOL, "false")
            .out("Out", CgShaderType.BOOL)
            .noPreview()
            .body("{Out} = !{In};")
            .build();

    /** {@code !(A && B)} — the one Logic node this batch adds beyond Unity's own And/Or/Not/Comparison
     * quartet, since it costs nothing once {@link #AND} exists. */
    public static final CgShaderNode NAND = CgTemplateShaderNode.of("cg:Utility/Logic/nand")
            .label("Nand")
            .in("A", CgShaderType.BOOL, "true")
            .in("B", CgShaderType.BOOL, "true")
            .out("Out", CgShaderType.BOOL)
            .noPreview()
            .body("{Out} = !({A} && {B});")
            .build();

    /** {@code A} {@code op} {@code B}, {@code op} chosen by the {@code Condition} property — fixed
     * {@code FLOAT} ports, not {@code DYNAMIC}, per the class-scope note above. */
    public static final CgShaderNode COMPARISON = CgTemplateShaderNode.of("cg:Utility/Logic/comparison")
            .label("Comparison")
            .in("A", CgShaderType.FLOAT, "0.0")
            .in("B", CgShaderType.FLOAT, "0.0")
            .out("Out", CgShaderType.BOOL)
            .noPreview()
            .property(CgShaderNodeProperty.of("Condition", "Condition",
                    "Equal", "NotEqual", "Less", "LessOrEqual", "Greater", "GreaterOrEqual"))
            .body("{Out} = {A} == {B};")
            .bodyFor("Condition", "NotEqual", "{Out} = {A} != {B};")
            .bodyFor("Condition", "Less", "{Out} = {A} < {B};")
            .bodyFor("Condition", "LessOrEqual", "{Out} = {A} <= {B};")
            .bodyFor("Condition", "Greater", "{Out} = {A} > {B};")
            .bodyFor("Condition", "GreaterOrEqual", "{Out} = {A} >= {B};")
            .build();

    /** {@code Predicate ? True : False} — GLSL's ternary already handles any matching {@code DYNAMIC}
     * width on both branches, so this needed no per-width special casing. */
    public static final CgShaderNode BRANCH = CgTemplateShaderNode.of("cg:Utility/branch")
            .label("Branch")
            .in("Predicate", CgShaderType.BOOL, "true")
            .in("True", CgShaderType.DYNAMIC, "1.0")
            .in("False", CgShaderType.DYNAMIC, "0.0")
            .out("Out", CgShaderType.DYNAMIC)
            .body("{Out} = {Predicate} ? {True} : {False};")
            .build();

    // ── Procedural ▸ Pattern, Noise, Shape (six of nine — Voronoi and the two Polygon shapes need a
    // genuinely new stdlib function each, deferred rather than approximated) ────────────────────────

    /** {@code mix} between two colours by a two-frequency checkerboard parity test — {@code mod(...,
     * 2.0)} rather than a bitwise parity check, since GLSL's {@code int}/bitwise ops on a value derived
     * from {@code floor()} of a float coordinate are the less portable choice of the two. */
    public static final CgShaderNode CHECKERBOARD = CgTemplateShaderNode.of("cg:Procedural/Pattern/checkerboard")
            .label("Checkerboard")
            .inWithImplicitDefault("UV", CgShaderType.VEC2, () -> CgBuiltinShaderNodes.UV, "Out")
            .in("ColorA", CgShaderType.VEC4, "vec4(1.0, 1.0, 1.0, 1.0)")
            .in("ColorB", CgShaderType.VEC4, "vec4(0.0, 0.0, 0.0, 1.0)")
            .in("Frequency", CgShaderType.VEC2, "vec2(2.0, 2.0)")
            .out("Out", CgShaderType.VEC4)
            .body("{Out} = mix({ColorA}, {ColorB}, "
                    + "mod(floor({UV}.x * {Frequency}.x) + floor({UV}.y * {Frequency}.y), 2.0));")
            .build();

    /** {@code noise.glsl}'s single-octave {@code value_noise}. */
    public static final CgShaderNode SIMPLE_NOISE = CgTemplateShaderNode.of("cg:Procedural/Noise/simple-noise")
            .label("Simple Noise")
            .inWithImplicitDefault("UV", CgShaderType.VEC2, () -> CgBuiltinShaderNodes.UV, "Out")
            .in("Scale", CgShaderType.FLOAT, "10.0")
            .out("Out", CgShaderType.FLOAT)
            .include("crystalgraphics:shaders/lib/noise.glsl")
            .body("{Out} = value_noise({UV} * {Scale});")
            .build();

    /**
     * {@code noise.glsl}'s {@code fbm4} — four-octave value noise, <b>not</b> true Perlin/gradient
     * noise. Unity's own Gradient Noise is a real gradient-vector implementation; this library has no
     * such function yet, and {@code fbm4} is the closest existing stand-in rather than a new one built
     * to match. Visually similar (both are smooth, band-limited noise), mathematically different —
     * documented rather than presented as the genuine article, the same honesty this batch's other
     * simplifications ({@link #EXPONENTIAL}, {@link #TRANSFORM}) already carry.
     */
    public static final CgShaderNode GRADIENT_NOISE = CgTemplateShaderNode.of("cg:Procedural/Noise/gradient-noise")
            .label("Gradient Noise")
            .inWithImplicitDefault("UV", CgShaderType.VEC2, () -> CgBuiltinShaderNodes.UV, "Out")
            .in("Scale", CgShaderType.FLOAT, "10.0")
            .out("Out", CgShaderType.FLOAT)
            .include("crystalgraphics:shaders/lib/noise.glsl")
            .body("{Out} = fbm4({UV} * {Scale});")
            .build();

    /**
     * An antialiased ellipse mask — {@code FRAGMENT}-only, like every shape in this batch, since {@code
     * sdf_coverage} is {@code fwidth}-based. Not {@code sdf.glsl}'s own {@code sdf_rounded_box} (that
     * is a true rounded-RECTANGLE distance field): {@code length(p/radius) - 1} is the standard cheap
     * ellipse approximation — its gradient is not unit magnitude off-axis, so the antialiasing band
     * is not perfectly uniform around the rim, a real but minor cost of not adding a true elliptical
     * SDF function for this one shape.
     */
    public static final CgShaderNode ELLIPSE = CgTemplateShaderNode.of("cg:Procedural/Shape/ellipse")
            .label("Ellipse")
            .inWithImplicitDefault("UV", CgShaderType.VEC2, () -> CgBuiltinShaderNodes.UV, "Out")
            .in("Center", CgShaderType.VEC2, "vec2(0.5, 0.5)")
            .in("Radius", CgShaderType.VEC2, "vec2(0.25, 0.25)")
            .out("Out", CgShaderType.FLOAT)
            .domain(CgShaderDomain.FRAGMENT)
            .include("crystalgraphics:shaders/lib/sdf.glsl")
            .body("{Out} = sdf_coverage(length(({UV} - {Center}) / {Radius}) - 1.0);")
            .build();

    /** An antialiased rectangle mask — {@code sdf.glsl}'s {@code sdf_rounded_box} at radius {@code 0}.
     * {@code Size} is the box's HALF-extent, the same convention {@code sdf_rounded_box} itself uses. */
    public static final CgShaderNode RECTANGLE = CgTemplateShaderNode.of("cg:Procedural/Shape/rectangle")
            .label("Rectangle")
            .inWithImplicitDefault("UV", CgShaderType.VEC2, () -> CgBuiltinShaderNodes.UV, "Out")
            .in("Center", CgShaderType.VEC2, "vec2(0.5, 0.5)")
            .in("Size", CgShaderType.VEC2, "vec2(0.25, 0.25)")
            .out("Out", CgShaderType.FLOAT)
            .domain(CgShaderDomain.FRAGMENT)
            .include("crystalgraphics:shaders/lib/sdf.glsl")
            .body("{Out} = sdf_coverage(sdf_rounded_box({UV} - {Center}, {Size}, 0.0));")
            .build();

    /** @see #RECTANGLE — same shape, plus {@code sdf_rounded_box}'s own uniform corner radius. */
    public static final CgShaderNode ROUNDED_RECTANGLE =
            CgTemplateShaderNode.of("cg:Procedural/Shape/rounded-rectangle")
                    .label("Rounded Rectangle")
                    .inWithImplicitDefault("UV", CgShaderType.VEC2, () -> CgBuiltinShaderNodes.UV, "Out")
                    .in("Center", CgShaderType.VEC2, "vec2(0.5, 0.5)")
                    .in("Size", CgShaderType.VEC2, "vec2(0.25, 0.25)")
                    .in("Radius", CgShaderType.FLOAT, "0.1")
                    .out("Out", CgShaderType.FLOAT)
                    .domain(CgShaderDomain.FRAGMENT)
                    .include("crystalgraphics:shaders/lib/sdf.glsl")
                    .body("{Out} = sdf_coverage(sdf_rounded_box({UV} - {Center}, {Size}, {Radius}));")
                    .build();

    /**
     * Breaks a vec4 into its four channels — Unity's {@code Split}, and the first node in this library
     * with more than one output.
     *
     * <p>The input stays a fixed {@code vec4} rather than {@code DYNAMIC}: swizzling {@code .r}/{@code .g}
     * on a {@code float} or {@code vec2} is either meaningless or GLSL refuses it, and a node whose
     * legality depends on what happens to be wired in is worse than one that asks for the widest type and
     * lets a narrower value promote up to it — the same promotion every dynamic node already relies on,
     * just resolved by the compiler's cast rather than by this node branching on width.</p>
     *
     * <p>Four independently-typed, fixed-{@code float} outputs is the reason this node exists as the
     * proof for 6.3's multi-output work: nothing before it ever exercised {@link CgShaderNode#outputs()}
     * returning more than one port, so this is what proved the compiler, the preview emitter and the
     * editor's port list all generalise past one — none of them needed to change.</p>
     */
    public static final CgShaderNode SPLIT = CgTemplateShaderNode.of("cg:Channel/split")
            .label("Split")
            // NOT a colour, despite being a vec4 — see inNoInlineEditor's own doc. Unity's Split shows
            // a plain unconnected-input box, not a colour wheel popup, and rightly so: R/G/B/A here are
            // four unrelated channels, not a value anyone picks off a hue ring.
            .inNoInlineEditor("In", CgShaderType.VEC4, "vec4(0.0, 0.0, 0.0, 0.0)")
            .out("R", CgShaderType.FLOAT)
            .out("G", CgShaderType.FLOAT)
            .out("B", CgShaderType.FLOAT)
            .out("A", CgShaderType.FLOAT)
            // No single picture represents four independent scalars — Unity's own Split has none either.
            .noPreview()
            .body("{R} = {In}.r;\n{G} = {In}.g;\n{B} = {In}.b;\n{A} = {In}.a;")
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
    public static final CgShaderNode UV = CgTemplateShaderNode.of("cg:Input/Geometry/uv")
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
    public static final CgShaderNode POSITION = CgTemplateShaderNode.of("cg:Input/Geometry/position")
            .label("Position")
            .out("Out", CgShaderType.VEC3)
            .domain(CgShaderDomain.VERTEX)
            .previewGeometry(CgPreviewGeometry.SPHERE)
            .property(SPACE)
            .body("{Out} = cg_Position;")
            // NEGATED Z, and it is a HANDEDNESS conversion rather than a fudge.
            //
            // Unity's object-space ball shows the hemisphere at z < 0, because Unity is left-handed and
            // its +Z points away from the viewer. This engine is right-handed, so the camera sees the
            // z > 0 hemisphere and a raw coordinate previews blue. The difference between the two
            // conventions is a MIRROR, and there is no camera placement that expresses one: swapping the
            // projection's near/far flips winding along with depth and cancels itself, and a 180 degree
            // rotation turns the mesh around so +X points left. Both were tried; the arithmetic is
            // recorded in CgPreviewRenderer.applyCamera.
            //
            // So the mirror is applied to the VALUE, which is the one place it costs nothing. The real
            // shader bodies below are untouched and emit the true transforms.
            .previewBody("{Out} = vec3(i.objectPos.xy, -i.objectPos.z);")
            .bodyFor(SPACE_ID, SPACE_WORLD, "{Out} = (CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0)).xyz;")
            .bodyFor(SPACE_ID, SPACE_VIEW,
                    "{Out} = (cg_ViewMatrix * CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0)).xyz;")
            // A preview has no meaningful world transform — the thumbnail mesh sits at the origin, so
            // its world position IS its object position. Emitting the world form anyway would multiply
            // by whatever model matrix the preview pass happened to leave in the object buffer, which is
            // identity today and would silently stop being so the moment that changes.
            .previewBodyFor(SPACE_ID, SPACE_WORLD, "{Out} = vec3(i.objectPos.xy, -i.objectPos.z);")
            // ...and View is the RAW attribute, which under the mirror above IS the other convention --
            // the blue ball with cyan and magenta quadrants, exactly Unity's view-space thumbnail.
            .previewBodyFor(SPACE_ID, SPACE_VIEW, "{Out} = i.objectPos;")
            .build();

    /**
     * The surface normal, transformed out of object space.
     *
     * <p>Uses {@code CG_NORMAL_MATRIX} rather than the raw attribute — a normal under a non-uniform
     * scale does not survive the model matrix, which is exactly what that matrix exists to correct. The
     * preview form reads the varying the preview's own vertex stage already normalised.</p>
     */
    public static final CgShaderNode NORMAL = CgTemplateShaderNode.of("cg:Input/Geometry/normal")
            .label("Normal Vector")
            .out("Out", CgShaderType.VEC3)
            .domain(CgShaderDomain.VERTEX)
            .previewGeometry(CgPreviewGeometry.SPHERE)
            .property(SPACE)
            // Object space is the DEFAULT, matching Unity — and this node previously emitted the world
            // form unconditionally, so its thumbnail was a world normal labelled as nothing at all.
            .body("{Out} = cg_Normal;")
            // THE SAME HANDEDNESS CONVERSION Position carries one node up -- see the long note there.
            //
            // THE TWO NODES MUST AGREE, and that is the check worth keeping: the preview mesh is a UNIT
            // sphere centred on the origin, so its normal at any point IS its object position. Position
            // and Normal Vector therefore have to draw the same picture in object space, and a
            // side-by-side where they do not is a bug in one of them by construction. They have been
            // mirror images of each other twice, in both directions.
            .previewBody("{Out} = vec3(i.normal.xy, -i.normal.z);")
            .bodyFor(SPACE_ID, SPACE_WORLD, "{Out} = CG_NORMAL_MATRIX * cg_Normal;")
            // mat3 of the view matrix, so only the ROTATION applies. A normal is a direction: translating
            // it is meaningless, and using the full mat4 would drag the camera's position into a unit
            // vector and denormalise it by however far the camera happens to be from the origin.
            .bodyFor(SPACE_ID, SPACE_VIEW, "{Out} = mat3(cg_ViewMatrix) * (CG_NORMAL_MATRIX * cg_Normal);")
            .previewBodyFor(SPACE_ID, SPACE_WORLD, "{Out} = vec3(i.normal.xy, -i.normal.z);")
            // ...and View is the RAW attribute, which is the blue ball with cyan and magenta quadrants.
            //
            // NOT mat3(cg_ViewMatrix). A preview is rendered by a camera at the identity, so the literal
            // view transform is a no-op and View would draw pixel-for-pixel the same picture as Object —
            // a dropdown that visibly does nothing. Rotating the preview camera to force a difference is
            // not available either: the same matrix drives the geometry, so it would spin the sphere and
            // change the Object thumbnail too.
            //
            // What distinguishes the two is the Z convention, and it is the thumbnail's stand-in camera
            // being expressed rather than the semantics being faked — the REAL shader above emits the
            // true transform and is untouched.
            // ...and View is the RAW attribute, the blue ball, for the same reason Position's is.
            .previewBodyFor(SPACE_ID, SPACE_VIEW, "{Out} = i.normal;")
            .build();

    /**
     * The direction from the surface to the camera — Unity's {@code View Direction}, and the second half
     * of what a Fresnel term needs.
     *
     * <p>Added because {@link #FRESNEL_EFFECT} had nothing to bind its {@code ViewDir} slot to and was
     * making do with a constant. It is a perfectly ordinary node in its own right, and the last of
     * Unity's Input ▸ Geometry set that this engine can express — Tangent and Bitangent need a per-vertex
     * basis the vertex formats do not carry, which is the same reason {@link #SPACE} offers no tangent
     * option.</p>
     *
     * <h3>Normalised, and pointing AT the camera</h3>
     * <p>Both are Unity's convention and both matter: {@code dot(N, V)} is only a cosine if V is unit, and
     * a Fresnel term is {@code 1 - dot(N, V)} rather than {@code 1 + dot(N, V)} because V points the same
     * way N does when you are looking straight at a surface. The opposite sign is the single most common
     * way to get a rim light inside-out.</p>
     */
    public static final CgShaderNode VIEW_DIRECTION =
            CgTemplateShaderNode.of("cg:Input/Geometry/view-direction")
            .label("View Direction")
            .out("Out", CgShaderType.VEC3)
            .domain(CgShaderDomain.VERTEX)
            .previewGeometry(CgPreviewGeometry.SPHERE)
            .property(SPACE)
            // Object space needs the camera brought INTO object space, which is the one direction that
            // costs an inverse. Correct rather than cheap: the alternative is to compute in world space
            // and lie about the label.
            .body("{Out} = normalize((inverse(CG_OBJECT_TO_WORLD) "
                    + "* vec4(CG_CAMERA_WORLD_POS, 1.0)).xyz - cg_Position);")
            .bodyFor(SPACE_ID, SPACE_WORLD, "{Out} = normalize(CG_CAMERA_WORLD_POS "
                    + "- (CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0)).xyz);")
            // In view space the camera IS the origin, so the direction to it is just the negated position
            // — no camera term at all, and no inverse.
            .bodyFor(SPACE_ID, SPACE_VIEW,
                    "{Out} = normalize(-(cg_ViewMatrix * CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0)).xyz);")
            // A CONSTANT in every preview, because the preview camera is ORTHOGRAPHIC: every ray through
            // it is parallel, so the view direction genuinely does not vary across the thumbnail. Deriving
            // it per pixel would be a picture of a perspective camera that is not there.
            //
            // Negative Z, which is the same flipped convention the Position and Normal preview bodies
            // carry — and it has to be, because this is the vector those get dotted against. Get it
            // backwards and a Fresnel preview comes out inside-out: bright in the middle, dark at the rim.
            .previewBody("{Out} = vec3(0.0, 0.0, -1.0);")
            .build();

    /** Adds every built-in to {@code registry}, in menu order. */
    public static void registerAll(CgShaderNodeRegistry registry) {
        registry.register(COLOR, FLOAT, VECTOR2, VECTOR3, VECTOR4) // input/basic
                .register(TIME, UV, POSITION, NORMAL, VIEW_DIRECTION) // input/time + input/geometry
                .register(ADD, MULTIPLY, SUBTRACT, DIVIDE, POWER, SQUARE_ROOT)               // math/basic
                .register(ABSOLUTE, NEGATE, EXPONENTIAL, LENGTH, LOG, MODULO, NORMALIZE,
                        POSTERIZE, RECIPROCAL, RECIPROCAL_SQUARE_ROOT)                        // math/advanced
                .register(INVERSE_LERP, LERP, SMOOTHSTEP)                                    // math/interpolation
                .register(ONE_MINUS, MINIMUM, MAXIMUM, CLAMP, SATURATE, FRACTION, REMAP,
                        RANDOM_RANGE)                                                        // math/range
                .register(FLOOR, CEILING, ROUND, SIGN, STEP, TRUNCATE)                       // math/round
                .register(SINE, COSINE, TANGENT, ARCSINE, ARCCOSINE, ARCTANGENT, ARCTANGENT2,
                        HYPERBOLIC_SINE, HYPERBOLIC_COSINE, HYPERBOLIC_TANGENT,
                        DEGREES_TO_RADIANS, RADIANS_TO_DEGREES)                               // math/trigonometry
                .register(DDX, DDY, DDXY)                                                    // math/derivative
                .register(MATRIX_SPLIT, MATRIX_CONSTRUCTION, MATRIX_TRANSPOSE,
                        MATRIX_DETERMINANT)                                                  // math/matrix
                .register(DOT_PRODUCT, CROSS_PRODUCT, DISTANCE, REFLECTION, FRESNEL_EFFECT,
                        PROJECTION, REJECTION, ROTATE_ABOUT_AXIS, SPHERE_MASK, TRANSFORM)     // math/vector
                .register(SAWTOOTH_WAVE)                                                     // math/wave
                .register(SPLIT, COMBINE, FLIP)                                              // channel
                .register(UV_ROTATE, TILING_AND_OFFSET, POLAR_COORDINATES, TWIRL, RADIAL_SHEAR,
                        SPHERIZE)                                                             // uv
                .register(AND, OR, NOT, NAND, COMPARISON, BRANCH)                            // utility/logic
                .register(CHECKERBOARD, SIMPLE_NOISE, GRADIENT_NOISE, ELLIPSE, RECTANGLE,
                        ROUNDED_RECTANGLE);                                                   // procedural
    }
}
