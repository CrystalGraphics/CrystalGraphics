package com.crystalgraphics.shadergraph;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * A GLSL type a shader-graph port can carry, plus the promotion rules the compiler emits casts for.
 *
 * <h3>Why this is an enum and {@code PortType} in the editor is an interface</h3>
 * <p>The editor's port types are open — a consumer registers whatever ids it likes, because a node graph
 * is a general-purpose editor and a dialogue graph's types are not a shader's. <b>GLSL's are not open.</b>
 * There is a fixed list, the compiler must emit a constructor for every promotion it permits, and a type
 * it cannot name is a type it cannot compile. An enum is the honest shape for that, and it is what makes
 * {@link #promote} total rather than a lookup that can fail at the worst moment.</p>
 *
 * <h3>Why this is not {@code CgMaterialProperty.Type}</h3>
 * <p>The two overlap and are deliberately separate, because they answer different questions.</p>
 *
 * <p>{@code CgMaterialProperty.Type} is a <b>property authoring</b> vocabulary — it carries two names,
 * the token an author writes in a {@code Properties} block and the GLSL type that token compiles to, and
 * it drags storage semantics behind it (UBO layout, sampler unit binding, range clamping). {@code COLOR}
 * compiles to {@code vec4} and {@code Range} to {@code float}: neither is a GLSL type, both are editor
 * affordances.</p>
 *
 * <p>This enum is <b>what flows along a wire</b>. It needs matrices and {@link #DYNAMIC}, which a uniform
 * never has, and promotion rules, which a uniform never needs — while a port typed {@code Range} would
 * be meaningless, since a Range <em>is</em> a float with metadata attached.</p>
 *
 * <p><b>They are bridged by the GLSL name, which both already carry</b>, so
 * {@code CgShaderType.parse(propertyType.getGlslName())} converts one way and {@link #glsl()} feeds
 * {@code CgMaterialProperty.Type.fromPropertyType} the other. {@code CgShaderTypeBridgeTest} pins that
 * every property type maps, so the two cannot drift apart silently when one gains a member.</p>
 *
 * <h3>{@link #DYNAMIC} — the reason node types are code, not only data</h3>
 * <p>Unity's {@code Add} accepts a float, a vec2, a vec3 or a vec4 and produces whichever is widest. A
 * port declared {@code DYNAMIC} resolves against what is actually connected to it, which is the
 * alternative to shipping four nodes per arithmetic operation. It never survives into emitted GLSL:
 * {@link CgGraphCompiler} resolves every dynamic port to a concrete type before a line is written, and a
 * dynamic port that resolves to nothing is a compile error rather than a guess.</p>
 */
public enum CgShaderType {

    /** Resolved from context, never emitted. See the class note. */
    DYNAMIC("", 0),

    BOOL("bool", 1),
    INT("int", 1),
    FLOAT("float", 1),
    VEC2("vec2", 2),
    VEC3("vec3", 3),
    VEC4("vec4", 4),
    MAT2("mat2", 0),
    MAT3("mat3", 0),
    MAT4("mat4", 0),
    SAMPLER2D("sampler2D", 0),
    SAMPLER2D_ARRAY("sampler2DArray", 0),
    SAMPLER3D("sampler3D", 0),
    SAMPLER_CUBE("samplerCube", 0);

    /** The GLSL keyword. Empty for {@link #DYNAMIC}, which has none by definition. */
    private final String glsl;

    /** Component count for the scalar/vector family, {@code 0} for everything else. This is the whole
     * of the widening order — a vector family is totally ordered by width, and nothing else is
     * ordered at all. */
    private final int components;

    CgShaderType(String glsl, int components) {
        this.glsl = glsl;
        this.components = components;
    }

    /** The GLSL keyword — {@code "vec3"}, {@code "sampler2D"}. */
    public String glsl() {
        return glsl;
    }

    public int components() {
        return components;
    }

    /** Whether this is a scalar or vector of floats, which is the only family with promotion rules. */
    public boolean isNumericVector() {
        return this == FLOAT || this == VEC2 || this == VEC3 || this == VEC4;
    }

    public boolean isSampler() {
        return this == SAMPLER2D || this == SAMPLER2D_ARRAY || this == SAMPLER3D || this == SAMPLER_CUBE;
    }

    public boolean isMatrix() {
        return this == MAT2 || this == MAT3 || this == MAT4;
    }

    /**
     * Parses a GLSL type name, case-insensitively.
     *
     * @return the type, or {@code null} when the name is not one this compiler can emit — which the
     *         caller should report against the node that declared it, not swallow
     */
    @Nullable
    public static CgShaderType parse(String name) {
        if (name == null) return null;
        String needle = name.trim();
        if (needle.equalsIgnoreCase("dynamic")) return DYNAMIC;
        for (CgShaderType type : values()) {
            if (type.glsl.equalsIgnoreCase(needle)) return type;
        }
        // GLSL spells it `bool`; a graph author reasonably writes `boolean`, and the .shader Properties
        // block already accepts that spelling — so accepting it here keeps one vocabulary rather than two.
        if (needle.equalsIgnoreCase("boolean")) return BOOL;
        return null;
    }

    // ── Promotion ───────────────────────────────────────────────────────────

    /**
     * Whether a value of {@code this} type can feed a port of {@code target} type.
     *
     * <p>GLSL's own rule, narrowed: <b>a scalar promotes into any vector, and nothing demotes.</b> A
     * float feeding a vec3 is {@code vec3(x)} and is what makes a shader graph usable; a vec3 feeding a
     * float would have to pick a component, and picking silently is how a graph starts lying about what
     * it computes. That belongs in an explicit Split node.</p>
     */
    public boolean canFeed(CgShaderType target) {
        if (this == target) return true;
        if (this == DYNAMIC || target == DYNAMIC) return true;
        return this == FLOAT && target.isNumericVector();
    }

    /**
     * The GLSL expression that adapts {@code expression} from this type to {@code target}.
     *
     * <p>Emitting the cast is the <b>compiler's</b> job, not the graph author's. The editor already
     * permits a float to feed a vec3, so if the compiler did not insert {@code vec3(...)} the graph
     * would look legal and fail in the driver — an error the user has no way to act on.</p>
     *
     * @throws IllegalArgumentException if the conversion is not one {@link #canFeed} permits, which is a
     *         compiler bug rather than a user error by the time it is reached
     */
    public String promote(String expression, CgShaderType target) {
        if (this == target) return expression;
        if (!canFeed(target)) {
            throw new IllegalArgumentException("Cannot convert " + this + " to " + target
                    + " — connect-time validation should have refused this edge");
        }
        // float -> vecN. GLSL's single-argument vector constructor splats, which is exactly the
        // intended meaning of wiring a scalar into a vector slot.
        return target.glsl + "(" + expression + ")";
    }

    /**
     * The type a {@code DYNAMIC} port takes given everything connected to it — the widest of them.
     *
     * <p>Widest rather than first-seen, because {@code Add(float, vec3)} must be a vec3: the narrow
     * side promotes and the wide side is untouched. First-seen would make the result depend on the
     * order the user happened to wire things, which is the kind of bug nobody can reproduce.</p>
     *
     * @return the resolved type, or {@code null} if there was nothing concrete to resolve from
     */
    @Nullable
    public static CgShaderType widest(Iterable<CgShaderType> candidates) {
        CgShaderType best = null;
        for (CgShaderType candidate : candidates) {
            if (candidate == null || candidate == DYNAMIC) continue;
            if (best == null) {
                best = candidate;
            } else if (candidate.isNumericVector() && best.isNumericVector()) {
                if (candidate.components > best.components) best = candidate;
            } else if (candidate != best) {
                // Two incompatible concrete types on one dynamic port — a matrix and a vector, say.
                // Not resolvable, and the caller reports it against the node rather than picking.
                return null;
            }
        }
        return best;
    }

    @Override
    public String toString() {
        return this == DYNAMIC ? "dynamic" : glsl;
    }

    /** For error messages that want a stable lowercase name. */
    public String displayName() {
        return toString().toLowerCase(Locale.ROOT);
    }

    /**
     * The token to write for this type in a {@code .shader} {@code Properties} block, or {@code null}
     * when it cannot be a property.
     *
     * <p><b>Not always {@link #glsl()}</b>, and the one exception is the reason this method exists:
     * GLSL spells it {@code bool} while the Properties block spells it {@code boolean}. Emitting the
     * GLSL name there produces a token the parser rejects — a generated shader that fails at parse
     * time, for a graph that is perfectly valid.</p>
     *
     * <p>Null for matrices, which have no property type today, and for {@link #DYNAMIC}, which is
     * resolved away long before anything is emitted.</p>
     */
    @Nullable
    public String propertyTypeName() {
        if (this == DYNAMIC || isMatrix()) return null;
        return this == BOOL ? "boolean" : glsl;
    }
}
