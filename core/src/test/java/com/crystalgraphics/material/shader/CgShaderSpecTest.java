package com.crystalgraphics.material.shader;

import com.crystalgraphics.api.material.CgBlendMode;
import com.crystalgraphics.api.material.CgMaterialDomain;
import com.crystalgraphics.api.material.CgMaterialState;
import com.crystalgraphics.api.material.CgRenderPassVariant;
import com.crystalgraphics.api.material.CgRenderQueue;
import com.crystalgraphics.material.CgMaterialProperty;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.material.shader.syntax.CgShaderError;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/** What each domain reads a file into, and what it refuses. The programs it writes: {@link CgGlslWriterTest}. */
public class CgShaderSpecTest {

    static final String V2F = "struct v2f { vec2 uv0; };\n";

    static CgShaderSpec surface(String material, String body) {
        return CgShaderSpec.read("#domain surface\n" + (material.isEmpty() ? "" : "Material { " + material + " }\n")
                + V2F + "void surface(in v2f i, inout CgSurface s) { " + body + " }\n", "test:s.shader");
    }

    static CgShaderSpec global(String scope, String fragment) {
        return CgShaderSpec.read("#domain global\nMaterial { Queue Transparent }\n" + scope
                + "RenderState { Blend SRC_ALPHA ONE_MINUS_SRC_ALPHA  DepthWrite OFF }\nstruct v2f { vec3 n; };\n"
                + "void vertex(out v2f o) { o.n = cg_Normal; gl_Position = CG_MATRIX_MVP * vec4(cg_Position, 1.0); }\n"
                + "void fragment(in v2f i, out vec4 fragColor) { " + fragment + " }\n", "test:g.shader");
    }

    static CgShaderError refused(String source, String naming) {
        try {
            CgShaderSpec.read(source, "test:r.shader");
        } catch (CgShaderError e) {
            assertTrue(e.getMessage(), e.getMessage().contains(naming));
            return e;
        }
        throw new AssertionError("read: " + source);
    }

    // ── surface ──────────────────────────────────────────────────────────────

    @Test
    public void passesFollowTheOutputsNamed() {
        CgShaderSpec plain = surface("", "s.baseColor = vec3(i.uv0, 0.0);");
        assertEquals(CgMaterialDomain.SURFACE, plain.domain());
        assertEquals(1, plain.passes().size());

        CgShaderSpec all = surface("BlendMode Translucent", "s.opacity = 0.5; s.emissive = vec3(1.0); s.refraction = i.uv0;");
        assertEquals(3, all.passes().size());
        assertNotNull(all.pass(CgRenderPassVariant.EMISSIVE));
        assertNotNull(all.pass(CgRenderPassVariant.DISTORTION));
    }

    @Test
    public void depthModesComeFromTheBlendAndItsSettings() {
        CgShaderSpec masked = surface("BlendMode Masked  MaskClip 0.4", "s.opacityMask = i.uv0.x;");
        assertEquals(CgShaderSpec.DepthMode.CLIP, masked.depthMode());
        assertEquals("0.4", masked.maskClip());
        assertTrue(masked.pass(CgRenderPassVariant.FORWARD).state().getDepth().write());

        CgShaderSpec smoke = surface("BlendMode Translucent  DepthPrepass On", "s.opacity = 0.5; s.opacityMask = i.uv0.x;");
        assertEquals(CgShaderSpec.DepthMode.PREPASS, smoke.depthMode());
        assertFalse(smoke.pass(CgRenderPassVariant.FORWARD).state().getDepth().write());

        assertEquals(CgShaderSpec.DepthMode.NONE, surface("", "s.baseColor = vec3(1.0);").depthMode());
    }

    @Test
    public void settingsReachTheSpec() {
        assertTrue(surface("BlendMode Additive  TranslucencyPass Separate", "s.baseColor = vec3(1.0);").separateTranslucency());
        assertTrue(surface("ShadingModel Unlit", "s.baseColor = vec3(1.0);").unlit());
        assertEquals(CgShaderSpec.Lighting.SURFACE,
                surface("BlendMode Translucent  TranslucencyLighting Surface", "s.opacity = 0.5; s.normal = vec3(0, 0, 1);")
                        .lighting());
    }

    @Test
    public void aLitModelIsLitByTheTemplateAfterTheWorldLights() {
        CgShaderSpec rock = surface("ShadingModel DefaultLit", "s.baseColor = vec3(0.5); s.metallic = 1.0; s.roughness = 0.3;");
        assertEquals(CgShaderSpec.Lighting.UNLIT, rock.lighting());
        assertEquals(CgShaderSpec.ColorSpace.LINEAR, rock.colorSpace());
        // A solid lit forward draws after the deferred apply, which would light it again
        assertEquals(CgRenderQueue.TRANSPARENT, rock.queue());
        assertTrue(rock.pass(CgRenderPassVariant.FORWARD).state().getDepth().write());
        assertTrue(rock.handedLights());
        assertTrue(rock.handedSun());
        assertTrue(rock.pass(CgRenderPassVariant.FORWARD).fragmentBody().contains("cg_default_lit(s, i."));

        CgShaderSpec leaf = surface("ShadingModel Subsurface", "s.subsurfaceColor = vec3(0.5, 1.0, 0.2); s.opacity = 0.3;");
        assertTrue(leaf.pass(CgRenderPassVariant.FORWARD).fragmentBody().contains(", true);"));

        // Every other model leaves the light to the world, as before
        assertEquals(CgShaderSpec.Lighting.LIT, surface("", "s.baseColor = vec3(1.0);").lighting());
    }

    @Test
    public void litOutputsAreRefusedWhereNothingReadsThem() {
        String v2f = "#domain surface\n";
        refused(v2f + V2F + "void surface(in v2f i, inout CgSurface s) { s.roughness = 0.2; }\n", "DefaultLit or Subsurface");
        refused(v2f + "Material { ShadingModel DefaultLit }\n" + V2F
                + "void surface(in v2f i, inout CgSurface s) { s.subsurfaceColor = vec3(1.0); }\n", "Subsurface");
        refused(v2f + "Material { ShadingModel DefaultLit  ColorSpace sRGB }\n" + V2F
                + "void surface(in v2f i, inout CgSurface s) { }\n", "linear");
        refused(v2f + "Material { BlendMode Translucent  ShadingModel DefaultLit  TranslucencyLighting Surface }\n" + V2F
                + "void surface(in v2f i, inout CgSurface s) { s.opacity = 0.5; }\n", "WorldLit's");
        refused(v2f + "Material { ShadingModel DefaultLit }\n" + V2F
                + "void surface(in v2f i, inout CgSurface s) { s.opacity = 0.5; }\n", "translucent");
    }

    private static final String LAYERS = "#domain surface\n"
            + "Layers {\n    Rock rock\n    Moss moss | wet_moss by slope {\n        Tint (\"Moss colour\", color) = (0.3, 0.5, 0.2, 1)\n"
            + "    }\n    Snow moss by slope {\n        Tint (\"Snow colour\", color) = (1, 1, 1, 1)\n    }\n}\n" + V2F
            + "CgSurface rock(in v2f i, CgSurface s) { s.baseColor = vec3(0.5); return s; }\n"
            + "CgSurface moss(in v2f i, CgSurface s) { s.baseColor = Tint.rgb; return s; }\n"
            + "CgSurface wet_moss(in v2f i, CgSurface s) { s.baseColor = Tint.rgb * 0.5; return s; }\n"
            + "CgSurface slope(in v2f i, CgSurface below, CgSurface top) { below.baseColor = mix(below.baseColor, top.baseColor, i.uv0.y); return below; }\n"
            + "void surface(in v2f i, inout CgSurface s) { s = cg_MaterialLayers(i, s); }\n";

    @Test
    public void aLayerStackIsItsSlotsOneOfsAndItsFunctionsOncePerSlot() {
        CgShaderSpec spec = CgShaderSpec.read(LAYERS, "test:layers.shader");
        assertTrue(spec.features().containsAll(List.of("LAYER_MOSS_MOSS", "LAYER_MOSS_WET_MOSS", "LAYER_MOSS_HIDDEN",
                "LAYER_SNOW_MOSS", "LAYER_SNOW_HIDDEN")));
        assertFalse("a base of one layer is no dimension", spec.features().contains("LAYER_ROCK_ROCK"));
        // One function, two slots, two values: each slot's own property
        CgMaterialProperty moss = null, snow = null;
        for (CgMaterialProperty p : spec.properties()) {
            if (p.getName().equals("Moss_Tint")) moss = p;
            if (p.getName().equals("Snow_Tint")) snow = p;
        }
        assertNotNull(moss);
        assertNotNull(snow);
        assertEquals("Moss: Moss colour", moss.getDisplayName());
        StringBuilder code = new StringBuilder();
        for (CgShaderPass.Code c : spec.pass(CgRenderPassVariant.FORWARD).code()) code.append(c.text()).append('\n');
        String glsl = code.toString();
        assertTrue(glsl, glsl.contains("#define Tint Moss_Tint") && glsl.contains("#define Tint Snow_Tint"));
        assertTrue(glsl, glsl.contains("_cg_layer_Moss_moss(") && glsl.contains("_cg_layer_Snow_moss("));
        // The stack before surface(), which calls it
        assertTrue(glsl, glsl.indexOf("CgSurface cg_MaterialLayers(") < glsl.indexOf(SpecReader.MATERIAL_SURFACE + "("));
    }

    @Test
    public void layerRefusals() {
        String v2f = "#domain surface\n";
        String fns = V2F + "CgSurface a(in v2f i, CgSurface s) { return s; }\n"
                + "CgSurface b(in v2f i, CgSurface below, CgSurface top) { return top; }\n";
        String calls = "void surface(in v2f i, inout CgSurface s) { s = cg_MaterialLayers(i, s); }\n";
        refused(v2f + "Layers {\n    Base a by b\n}\n" + fns + calls, "the base layer");
        refused(v2f + "Layers {\n    Base a\n    Top a\n}\n" + fns + calls, "needs 'by <blend>'");
        refused(v2f + "Layers {\n    Base a\n    Top lichen by b\n}\n" + fns + calls, "'lichen' is no function");
        refused(v2f + "Layers {\n    Base a\n}\n" + fns + "void surface(in v2f i, inout CgSurface s) { }\n", "never drawn");
    }

    @Test
    public void surfaceRefusals() {
        String body = "\nvoid surface(in v2f i, inout CgSurface s) { }\n";
        refused("#domain surface\n" + V2F + "void surface(in v2f i, inout CgSurface s) { s.opacity = 0.5; }\n", "translucent");
        refused("#domain surface\nMaterial { DepthPrepass On }\n" + V2F + body, "translucent");
        refused("#domain surface\nMaterial { MaskClip 0.3 }\n" + V2F + body, "MaskClip");
        refused("#domain surface\nMaterial { BlendMode Masked  MaskClip _Cut }\n" + V2F
                + "void surface(in v2f i, inout CgSurface s) { s.opacityMask = 1.0; }\n", "_Cut");
        refused("#domain surface\nstruct v2f { vec2 bend; };" + body, "bend");
        refused("#domain surface\n" + V2F + "void vertex(inout CgVertex v, out v2f o) { gl_Position = vec4(0.0); o.uv0 = v.uv0; }"
                + body, "gl_Position");
        refused("#domain surface\nKeywords { SHAPE (\"Shape\") = BOX }\n" + V2F + body, "one value");
        refused("#domain surface\n" + V2F + "void surface(in v2f i, inout CgSurface s) { s.baseColor = CG_QUAD_COLOR.rgb; }\n",
                "never by naming it");
        refused("#domain decal" + body, "decal");
    }

    @Test
    public void aRefusedSettingIsPlacedOnItsValue() {
        CgShaderError e = refused("#domain surface\nMaterial {\n    BlendMode Glass\n}\n" + V2F
                + "void surface(in v2f i, inout CgSurface s) { }\n", "'BlendMode Glass' is one of");
        assertEquals(3, e.line());
        assertEquals(15, e.column());
        assertTrue(e.getMessage(), e.getMessage().startsWith("test:r.shader:3:15: "));
    }

    // ── what a program names ─────────────────────────────────────────────────

    @Test
    public void aProgramNamingTheWorldsLightsIsHandedTheGrid() {
        CgShaderSpec lights = global("", "fragColor = vec4(CG_DRAW_POINT_LIGHT_COLOR(0), 1.0);");
        assertTrue(lights.handedLights());
        assertNotNull(lights.buffer("WORLD_LIGHTS"));
        assertNotNull(lights.buffer("WORLD_LIGHT_INDICES"));

        CgShaderSpec sun = global("", "fragColor = vec4(vec3(CG_DIRECTIONAL_SHADOW(i.n)), 1.0);");
        assertTrue(sun.handedSun());
        assertFalse(sun.handedLights());

        CgShaderSpec plain = global("", "fragColor = vec4(1.0);");
        assertFalse(plain.handedLights());
        assertTrue(plain.buffers().isEmpty());
    }

    @Test
    public void buffersAndTheStructsTheyName() {
        CgShaderSpec spec = global("struct Unused { vec4 a; };\nstruct Spark { vec4 positionLife; vec4 velocitySeed; };\n"
                + "Buffers {\n    SPARKS (\"Sparks\", Spark, readonly)\n    HEAT (\"Heat\", float, readonly)\n}\n",
                "fragColor = SPARKS(0u).positionLife * HEAT(0u);");
        assertEquals("Spark", spec.buffer("SPARKS").element());
        assertEquals(32, spec.buffer("SPARKS").stride());
        assertEquals(1, spec.buffer("HEAT").index());
        assertTrue(spec.bufferStructs().contains("struct Spark"));
        assertFalse(spec.bufferStructs().contains("Unused"));
    }

    @Test
    public void bufferRefusals() {
        String head = "#domain global\n", tail = "struct v2f { vec2 uv; };\nvoid vertex(out v2f o) { gl_Position = vec4(0.0); o.uv = vec2(0.0); }\n"
                + "void fragment(in v2f i, out vec4 c) { c = vec4(1.0); }\n";
        refused(head + "struct S { vec4 a; };\nBuffers { B (\"B\", S, readwrite) }\n" + tail, "only reads");
        refused(head + "Buffers { B (\"B\", vec3, readonly) }\n" + tail, "every tier holds");
        refused(head + "Buffers { a (\"B\", float, readonly) }\n" + tail, "cannot name");
        refused(head + "Buffers {\n A (\"A\", float, readonly)\n B (\"B\", float, readonly)\n C (\"C\", float, readonly)\n"
                + " D (\"D\", float, readonly)\n E (\"E\", float, readonly)\n}\n" + tail, "at most 4");
        refused(head + "Buffers {\n A (\"A\", vec4, readonly)\n B (\"B\", vec4, readonly)\n}\n"
                + tail.replace("c = vec4(1.0);", "c = vec4(CG_DRAW_POINT_LIGHT_POSITION(0), 1.0);"), "3 more");
        refused(head + tail.replace("void vertex(out v2f o) { gl_Position = vec4(0.0); o.uv = vec2(0.0); }\n", ""), "vertex");
    }

    // ── the emission merge ───────────────────────────────────────────────────

    @Test
    public void aGlowOnTheForwardBlendFoldsIntoIt() {
        CgShaderSpec added = surface("BlendMode Additive", "s.baseColor = vec3(1.0); s.emissive = vec3(2.0);");
        assertNotEquals(CgShaderSpec.EmissionMerge.NONE, added.emissionMerge());
        assertTrue(added.sceneFolds());

        CgShaderSpec modulate = surface("BlendMode Modulate", "s.baseColor = vec3(1.0); s.emissive = vec3(2.0);");
        assertFalse(modulate.sceneFolds());
    }

    // ── post, ui ─────────────────────────────────────────────────────────────

    private static String post(String... steps) {
        StringBuilder s = new StringBuilder("#domain post\nProperties {\n    _Amount (\"Amount\", float) = 1\n}\n");
        for (String step : steps) {
            int space = step.indexOf(' ');
            String name = space < 0 ? step : step.substring(0, space), settings = space < 0 ? "" : step.substring(space + 1);
            s.append("Step ").append(name).append(" {\n    ").append(settings).append("\n")
                    .append("    void surface(in CgScreen i, inout CgPostSurface s) { s.color = vec3(1.0); }\n}\n");
        }
        return s.toString();
    }

    @Test
    public void eachStepButTheLastDrawsIntoItsTarget() {
        CgShaderSpec spec = CgShaderSpec.read(post("down Target RGBA16_FLOAT  Scale 0.5",
                "blur Target RG11B10_UFLOAT  Scale 0.25", "over"), "test:p.shader");
        assertEquals(CgMaterialDomain.POST, spec.domain());
        List<CgShaderSpec.PostTarget> targets = spec.postTargets();
        assertEquals(2, targets.size());
        assertEquals(0.5f, targets.get(0).scale(), 0f);
        assertEquals(CgFormat.RG11B10_UFLOAT, targets.get(1).format());
        assertTrue(CgShaderSpec.read(post("over"), "test:p.shader").postTargets().isEmpty());
    }

    @Test
    public void postRefusals() {
        for (String scale : new String[]{"0", "1.5", "half"}) {
            refused(post("down Target RGBA16_FLOAT  Scale " + scale, "over"), "Scale");
        }
        refused(post("down", "over"), "down");
        refused(post("down Target RGBA16_FLOAT", "over Target RGBA16_FLOAT"), "over");
    }

    @Test
    public void aUiFileReadsItsPrimitive() {
        CgShaderSpec ui = CgShaderSpec.read("#domain ui\nMaterial { Primitive Quad }\n"
                + "void surface(in CgUiInput i, inout CgUiSurface s) { }\n", "test:u.shader");
        assertEquals(CgMaterialDomain.UI, ui.domain());
        assertFalse(ui.passes().isEmpty());
    }

    // ── under a material state ───────────────────────────────────────────────

    @Test
    public void aStateSetsTheForwardBlendAndItsClip() {
        CgShaderSpec opaque = surface("", "s.baseColor = vec3(1.0);");
        CgShaderSpec alpha = opaque.withState(CgMaterialState.builder().blend(CgBlendMode.ALPHA).build(), "test");
        assertEquals(CgBlendMode.ALPHA.state(), alpha.pass(CgRenderPassVariant.FORWARD).state().getBlend());

        CgShaderSpec masked = surface("BlendMode Masked", "s.opacityMask = i.uv0.x;");
        CgShaderSpec clipped = masked.withState(CgMaterialState.builder().blend(CgBlendMode.ALPHA).clip().build(), "test");
        assertEquals(CgShaderSpec.DepthMode.CLIP, clipped.depthMode());
        assertTrue(clipped.pass(CgRenderPassVariant.FORWARD).state().getDepth().write());

        assertEquals("a blend without clip unmasks it", CgShaderSpec.DepthMode.NONE, masked.withState(
                CgMaterialState.builder().blend(CgBlendMode.ALPHA).depthWrite(false).build(), "test").depthMode());
    }

    @Test
    public void aStateThePrepassOwnsIsRefused() {
        CgShaderSpec smoke = surface("BlendMode Translucent  DepthPrepass On", "s.opacity = 0.5; s.opacityMask = i.uv0.x;");
        assertThrows(CgShaderError.class,
                () -> smoke.withState(CgMaterialState.builder().blend(CgBlendMode.ALPHA).clip().build(), "test"));
        assertThrows(CgShaderError.class,
                () -> smoke.withState(CgMaterialState.builder().depthWrite(true).build(), "test"));
    }
}
