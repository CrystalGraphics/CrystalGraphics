package com.crystalgraphics.material.shader;

import com.crystalgraphics.api.material.CgRenderPassVariant;
import com.crystalgraphics.api.state.CgCullState;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.material.shader.syntax.CgCodeScan;
import com.github.bsideup.jabel.Desugar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One pass a material draws in: its kind, its render state, what its vertex stage hands its fragment stage, the code
 * it compiles, and its two entry points' bodies. Made by a domain reader; written to GLSL by {@link CgGlslWriter}.
 *
 * <pre>{@code
 * CgShaderPass forward = spec.pass(CgRenderPassVariant.FORWARD);
 * CgRenderState state = forward.state();
 * for (CgVarying v : forward.varyings()) { ... }      // the v2f
 * boolean glows = forward.namesGlow();                 // decided once, when it was read
 * }</pre>
 *
 * <ul>
 *   <li>{@link #vertexBody()} is the body of {@code void vertex(out v2f o)}, {@link #fragmentBody()} of
 *       {@code void fragment(in v2f i, out vec4 <output>)}.</li>
 *   <li>Each {@link Code} chunk compiles into the stages it names: a helper only {@code surface()} reaches is the
 *       fragment stage's alone. {@link #directives()} are preprocessor lines every stage takes first.</li>
 *   <li>A codeless Emissive pass draws its Forward pass's code ({@link #copiesForward()}).</li>
 * </ul>
 */
public final class CgShaderPass {

    /** The stages a {@link Code} chunk compiles into. */
    public static final int VERTEX = 1, FRAGMENT = 2, BOTH = 3;

    /** A piece of file-scope GLSL and the stages it compiles into. */
    @Desugar
    public record Code(String text, int stages) {
    }

    private final CgRenderPassVariant variant;
    private final String name;
    private final CgRenderState state;
    private final List<CgVarying> varyings;
    private final List<String> directives;
    private final List<Code> code;
    private final String vertexBody, fragmentBody, output;
    private final boolean copiesForward;

    private final boolean clips, namesGlow, namesSurfaceNormal, namesSurfaceEmissive, namesVolumeDepth, readsEmission,
            namesEmissivePass, namesClip, simpleBody;
    private final Set<String> vertexNames;
    private Set<String> identifiers;

    CgShaderPass(CgRenderPassVariant variant, String name, CgRenderState state, List<CgVarying> varyings,
                 List<String> directives, List<Code> code, String vertexBody, String fragmentBody, String output,
                 boolean copiesForward) {
        this.variant = variant;
        this.name = name;
        this.state = state;
        this.varyings = Collections.unmodifiableList(new ArrayList<>(varyings));
        this.directives = Collections.unmodifiableList(new ArrayList<>(directives));
        this.code = Collections.unmodifiableList(new ArrayList<>(code));
        this.vertexBody = vertexBody;
        this.fragmentBody = fragmentBody;
        this.output = output;
        this.copiesForward = copiesForward;
        CgCodeScan fragment = CgCodeScan.of(fragmentBody), vertex = CgCodeScan.of(vertexBody);
        clips = fragment.names("discard") || fragment.names("cg_Clip");
        namesGlow = fragment.names("CG_GLOW");
        namesSurfaceNormal = fragment.names("CG_SURFACE_NORMAL");
        namesSurfaceEmissive = fragment.names("CG_SURFACE_EMISSIVE");
        namesVolumeDepth = fragment.names("CG_VOLUME_DEPTH");
        readsEmission = fragment.names("CG_EMISSION");
        boolean inCode = false, clipInCode = false;
        for (String d : directives) {
            CgCodeScan s = CgCodeScan.of(d);
            inCode |= s.names("CG_EMISSIVE_PASS");
            clipInCode |= s.names("cg_Clip");
        }
        for (Code c : code) {
            CgCodeScan s = CgCodeScan.of(c.text());
            inCode |= s.names("CG_EMISSIVE_PASS");
            clipInCode |= s.names("cg_Clip");
        }
        namesEmissivePass = inCode || fragment.names("CG_EMISSIVE_PASS") || vertex.names("CG_EMISSIVE_PASS");
        namesClip = clipInCode || fragment.names("cg_Clip");
        vertexNames = vertex.identifiers();
        boolean props = false;
        for (String id : vertexNames) {
            props |= id.length() >= 3 && id.charAt(0) == '_' && id.charAt(1) >= 'A' && id.charAt(1) <= 'Z';
        }
        boolean animated = vertex.namesPrefix("cg_Time") || vertex.namesPrefix("CG_OBJECT_CUSTOM0")
                || vertex.namesPrefix("CG_OBJECT_CUSTOM1") || vertex.namesPrefix("CG_OBJECT_CUSTOM2")
                || vertex.namesPrefix("CG_OBJECT_CUSTOM3");
        simpleBody = !props && !animated && !clips && !vertexNames.contains(SurfaceDomain.MATERIAL_VERTEX)
                && !vertexNames.contains("cg_material_vertex");
    }

    /** {@code of} under {@code state}: its code and what it names are the same, only a culling vertex changes. */
    private CgShaderPass(CgShaderPass of, CgRenderState state) {
        this.variant = of.variant;
        this.name = of.name;
        this.state = state;
        this.varyings = of.varyings;
        this.directives = of.directives;
        this.code = of.code;
        this.vertexBody = of.vertexBody;
        this.fragmentBody = of.fragmentBody;
        this.output = of.output;
        this.copiesForward = of.copiesForward;
        this.clips = of.clips;
        this.namesGlow = of.namesGlow;
        this.namesSurfaceNormal = of.namesSurfaceNormal;
        this.namesSurfaceEmissive = of.namesSurfaceEmissive;
        this.namesVolumeDepth = of.namesVolumeDepth;
        this.readsEmission = of.readsEmission;
        this.namesEmissivePass = of.namesEmissivePass;
        this.namesClip = of.namesClip;
        this.vertexNames = of.vertexNames;
        this.identifiers = of.identifiers;
        this.simpleBody = of.simpleBody;
    }

    /** This pass under another render state, its code the same. */
    CgShaderPass withState(CgRenderState state) {
        return state == this.state ? this : new CgShaderPass(this, state);
    }

    public CgRenderPassVariant variant() {
        return variant;
    }

    /** {@code Pass0}, {@code Pass1}, ... for a Forward pass (a post step's own name); {@code Emissive}, {@code Distortion}. */
    public String name() {
        return name;
    }

    public CgRenderState state() {
        return state;
    }

    public List<CgVarying> varyings() {
        return varyings;
    }

    /** Preprocessor lines every stage takes before the engine's environment: includes, defines. */
    public List<String> directives() {
        return directives;
    }

    public List<Code> code() {
        return code;
    }

    public String vertexBody() {
        return vertexBody;
    }

    public String fragmentBody() {
        return fragmentBody;
    }

    /** The name of the fragment's {@code out vec4}. */
    public String output() {
        return output;
    }

    /** An Emissive pass with no code of its own: its Forward pass's, drawn into the glow. */
    public boolean copiesForward() {
        return copiesForward;
    }

    /** Whether its fragment cuts pixels away: a {@code discard} or a {@code cg_Clip}. */
    public boolean clips() {
        return clips;
    }

    /** Whether its fragment names its glow, {@code CG_GLOW(rgb)}. */
    public boolean namesGlow() {
        return namesGlow;
    }

    /** Whether its fragment names the normal it is lit by, {@code CG_SURFACE_NORMAL(n)}. */
    public boolean namesSurfaceNormal() {
        return namesSurfaceNormal;
    }

    /** Whether its fragment names an emissive added after lighting, {@code CG_SURFACE_EMISSIVE(rgb)}. */
    public boolean namesSurfaceEmissive() {
        return namesSurfaceEmissive;
    }

    /** Whether its fragment names the depth it marched to, {@code CG_VOLUME_DEPTH(eye)}. */
    public boolean namesVolumeDepth() {
        return namesVolumeDepth;
    }

    /** Whether its fragment applies {@code CG_EMISSION} itself. */
    public boolean readsEmission() {
        return readsEmission;
    }

    /** Whether any of its code tells an Emissive pass apart by {@code CG_EMISSIVE_PASS}. */
    public boolean namesEmissivePass() {
        return namesEmissivePass;
    }

    /** Whether its fragment or code names {@code cg_Clip}. */
    public boolean namesClip() {
        return namesClip;
    }

    /**
     * Whether a depth pass may draw it with the minimal transform: its vertex reads no property, time or custom slot,
     * runs no material {@code vertex()} or particle factory; its fragment cuts nothing; it culls.
     */
    public boolean simpleVertex() {
        return simpleBody && state.getCull() != CgCullState.NONE;
    }

    /** Whether its vertex body names {@code identifier}. */
    public boolean vertexNames(String identifier) {
        return vertexNames.contains(identifier);
    }

    /** Every identifier its code and bodies name. */
    Set<String> identifiers() {
        Set<String> ids = identifiers;
        if (ids != null) return ids;
        ids = new LinkedHashSet<>();
        for (String d : directives) CgCodeScan.of(d).identifiers(ids);
        for (Code c : code) CgCodeScan.of(c.text()).identifiers(ids);
        CgCodeScan.of(vertexBody).identifiers(ids);
        CgCodeScan.of(fragmentBody).identifiers(ids);
        return identifiers = ids;
    }

    @Override
    public String toString() {
        return variant.lightModeName() + " pass " + name;
    }
}
