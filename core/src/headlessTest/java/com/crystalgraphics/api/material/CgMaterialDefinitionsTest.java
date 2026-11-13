package com.crystalgraphics.api.material;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.BeforeClass;
import org.junit.Test;

import com.crystalgraphics.api.texture.CgSampler;
import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.asset.kind.CgLookKinds;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.material.shader.CgShaderSpec;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Materials compile from assets: names checked, the parent chain merged, value-only children sharing programs. */
public class CgMaterialDefinitionsTest {

    private static final String FX = "test:shaders/fx.shader";
    private static final String SOURCE = String.join("\n",
            "#domain surface",
            "Material { BlendMode Translucent  PerDrawValues On }",
            "Keywords { SOFT (\"Soft\")  GLOW (\"Glow\") }",
            "Properties {",
            "    _Color (\"Colour\", color) = (1, 1, 1, 1)",
            "    _Bands (\"Bands\", color[3]) = (1, 1, 1, 1), (0, 1, 1, 1), (0, 0.5, 0.9, 1)",
            "    _Speed (\"Speed\", float) = 1.0",
            "    _MainTex (\"Main\", sampler2D) = \"white\"",
            "}",
            "void surface(in v2f i, inout CgSurface s) {",
            "    vec4 c = _Color * _Bands(1) * _Speed;",
            "    s.baseColor = c.rgb;",
            "    s.opacity = c.a;",
            "}");

    private static final String POST = "test:shaders/post.shader", DECAL = "test:shaders/decal.shader";
    /** A decal's archetype: no renderer draws #domain decal yet, so it is a masked surface this stub calls a decal. */
    private static final String DECAL_SOURCE = String.join("\n",
            "#domain surface",
            "Material { BlendMode Masked  MaskClip _Clip }",
            "Keywords { CELL_TONE (\"Cell tone\") }",
            "Properties {",
            "    _Clip (\"Clip\", float) = 0.5",
            "}",
            "void surface(in v2f i, inout CgSurface s) { s.opacityMask = _Clip; }");

    private static final String MESH_SPECK = "test:shaders/mesh_speck.shader", SPRITE = "test:shaders/sprite.shader";

    /** A particle material naming no usage keyword: its factory decides how it is placed. */
    private static String particle(String factory) {
        return String.join("\n",
                "#domain surface",
                "Material { BlendMode Translucent  ShadingModel Unlit  VertexFactory " + factory + " }",
                "void surface(in v2f i, inout CgSurface s) { s.baseColor = cg_Particle.color.rgb; }");
    }

    static final CgMaterialArchetypes ARCHETYPES = new CgMaterialArchetypes() {
        @Override
        public CgShaderSpec parse(String path) {
            if (path.equals(MESH_SPECK)) return CgShaderSpec.read(particle("MeshParticles"), path);
            if (path.equals(SPRITE)) return CgShaderSpec.read(particle("Sprites"), path);
            if (path.equals(FX) || path.equals(POST)) return CgShaderSpec.read(SOURCE, path);
            if (path.equals(DECAL)) return CgShaderSpec.read(DECAL_SOURCE, path);
            throw new IllegalArgumentException(path + " is not a shader");
        }

        @Override
        public CgMaterialDomain domain(String path) {
            return path.equals(POST) ? CgMaterialDomain.POST
                    : path.equals(DECAL) ? CgMaterialDomain.DECAL : CgMaterialDomain.SURFACE;
        }
    };

    @BeforeClass
    public static void kinds() {
        CgAssetKinds.register();
    }

    private static Map<String, Object> tree(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("_v", 1);
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static CgAssetData asset(String id, Map<String, Object> tree, CgAssetData parent) {
        return CgAssetData.of(id, CgLookKinds.MATERIAL, tree, parent);
    }

    private static CgAssetData root() {
        return asset("test:glow", tree("shader", FX, "state", map("blend", "additive"), "keywords", Arrays.asList("SOFT"),
                "values", map("_Speed", 2.0, "_Color", Arrays.asList(1.0, 0.5, 0.25, 1.0)),
                "textures", map("_MainTex", map("texture", "test:noise"))), null);
    }

    @Test
    public void aParticleProgramWithNoUsageKeywordIsDrawnByEachFactory() {
        // A #type spatial one drawn as sprites once read vertex data the quads have none of, and drew nothing
        CgMaterialDefinitions.compile(asset("test:speck", tree("shader", MESH_SPECK, "usage",
                Arrays.asList("sprites", "mesh_particles", "ribbons")), null), ARCHETYPES);
        assertTrue(ARCHETYPES.parse(MESH_SPECK).implicitFeatures().contains("FX_ON_SPRITES"));
        CgMaterialDefinitions.compile(asset("test:sprite", tree("shader", SPRITE, "usage", Arrays.asList("sprites", "ribbons")), null), ARCHETYPES);
    }

    @Test
    public void aRootCompilesItsKeyConstantsTexturesAndRows() {
        CgMaterialDefinition d = CgMaterialDefinitions.compile(root(), ARCHETYPES);
        assertEquals(FX, d.permutation().shader());
        assertEquals(Arrays.asList("SOFT"), d.permutation().keywords());
        assertEquals("additive", d.permutation().blend());
        assertArrayEquals(new float[] {2f}, d.constants().get("_Speed"), 0f);
        assertArrayEquals(new float[] {1f, 0.5f, 0.25f, 1f}, d.constants().get("_Color"), 0f);
        assertEquals("test:noise", d.textures().get("_MainTex").texture());
        assertEquals(0, d.rows().slot("_Color"));
        assertEquals(1, d.rows().slot("_Bands"));
        assertEquals(4, d.rows().slot("_Speed"));
        assertEquals("every rows shader's exposure", 5, d.rows().slot("_cg_Exposure"));
        assertEquals(6, d.rows().vec4s());
        assertEquals(-1, d.rows().slot("_MainTex"));
    }

    @Test
    public void noBlendKeepsTheArchetypesOwnAndASetOneOverrides() {
        CgMaterialDefinition own = CgMaterialDefinitions.compile(asset("test:own", tree("shader", FX), null), ARCHETYPES);
        assertEquals(null, own.permutation().blend());
        assertEquals(null, own.state().blend());
        CgMaterialDefinition child = CgMaterialDefinitions.compile(asset("test:own_fast", tree("parent", "test:own",
                "values", map("_Speed", 3.0)), asset("test:own", tree("shader", FX), null)), ARCHETYPES);
        assertSame(own.permutation(), child.permutation());
        CgMaterialDefinition opaque = CgMaterialDefinitions.compile(asset("test:opaque", tree("shader", FX,
                "state", map("blend", "opaque")), null), ARCHETYPES);
        assertEquals(CgBlendMode.OPAQUE, opaque.state().blend());
    }

    @Test
    public void anInstanceChangingOnlyValuesSharesItsParentsPrograms() {
        CgAssetData parent = root();
        CgMaterialDefinition p = CgMaterialDefinitions.compile(parent, ARCHETYPES);
        CgMaterialDefinition c = CgMaterialDefinitions.compile(asset("test:glow_red", tree("parent", "test:glow",
                "values", map("_Speed", 5.0)), parent), ARCHETYPES);
        assertSame(p.permutation(), c.permutation());
        assertTrue(c.sharesProgramsWith(p));
        assertEquals("test:glow", c.parent());
        assertArrayEquals(new float[] {5f}, c.constants().get("_Speed"), 0f);
        assertArrayEquals(new float[] {1f, 0.5f, 0.25f, 1f}, c.constants().get("_Color"), 0f);

        CgMaterialDefinition k = CgMaterialDefinitions.compile(asset("test:glow_lit", tree("parent", "test:glow",
                "keywords", Arrays.asList("GLOW", "SOFT")), parent), ARCHETYPES);
        assertNotSame(p.permutation(), k.permutation());
        CgMaterialDefinition b = CgMaterialDefinitions.compile(asset("test:glow_blend", tree("parent", "test:glow",
                "state", map("blend", "translucent")), parent), ARCHETYPES);
        assertNotSame(p.permutation(), b.permutation());
        CgMaterialDefinition s = CgMaterialDefinitions.compile(asset("test:glow_sided", tree("parent", "test:glow",
                "state", map("two_sided", true)), parent), ARCHETYPES);
        assertSame("two-sided is pipeline state, not a program", p.permutation(), s.permutation());
    }

    @Test
    public void namesAreChecked() {
        fails("_Sped", asset("test:a", tree("shader", FX, "values", map("_Sped", 1.0)), null));
        fails("_MainTex", asset("test:b", tree("shader", FX, "values", map("_MainTex", 1.0)), null));
        fails("_Speed", asset("test:c", tree("shader", FX, "textures", map("_Speed", map("texture", "t:x"))), null));
        fails("WIND", asset("test:d", tree("shader", FX, "keywords", Arrays.asList("WIND")), null));
        fails("_Speed", asset("test:e", tree("shader", FX, "values", map("_Speed", Arrays.asList(1.0, 2.0))), null));
        fails("test:shaders/missing.shader", asset("test:f", tree("shader", "test:shaders/missing.shader"), null));
    }

    @Test
    public void depthTestOffIsATranslucentMaterialsOwnPermutation() {
        CgMaterialDefinition spark = CgMaterialDefinitions.compile(asset("test:spark", tree("shader", FX,
                "state", map("blend", "additive", "depth_test", false)), null), ARCHETYPES);
        assertFalse(spark.permutation().depthTest());
        assertFalse(spark.state().depthTested());
        CgMaterialDefinition tested = CgMaterialDefinitions.compile(asset("test:tested", tree("shader", FX,
                "state", map("blend", "additive")), null), ARCHETYPES);
        assertNotSame("its programs are its own", tested.permutation(), spark.permutation());
        fails("depth_test", asset("test:solid", tree("shader", FX, "state", map("blend", "opaque", "depth_test", false)), null));
        fails("depth_test", asset("test:masked", tree("shader", FX, "state", map("blend", "masked", "depth_test", false)), null));
    }

    private static void fails(String naming, CgAssetData asset) {
        try {
            CgMaterialDefinitions.compile(asset, ARCHETYPES);
            fail(asset.id() + " compiled");
        } catch (CgAssetException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(naming) && e.getMessage().contains(asset.id()));
        }
    }

    @Test
    public void aChildMayNotMoveAPostMaterial() {
        CgAssetData post = asset("test:post", tree("shader", POST, "post", map("priority", 1)), null);
        CgMaterialDefinitions.compile(post, ARCHETYPES);
        fails("post", asset("test:post_child", tree("parent", "test:post", "post", map("priority", 2)), post));
    }

    @Test
    public void aKeyedChannelMovesOneDrawsValueWithoutANewMaterial() {
        CgMaterialDefinition d = CgMaterialDefinitions.compile(root(), ARCHETYPES);
        int color = d.requireRow("_Color", "track colour");
        CgMaterialRow a = new CgMaterialRow(d), b = new CgMaterialRow(d);
        a.set(color, 0f, 1f, 0f, 1f);
        assertEquals(1f, a.get(color, 1), 0f);
        assertEquals("the other draw keeps the material's value", 0.5f, b.get(color, 1), 0f);
        assertSame(a.definition(), b.definition());
        float[] frame = new float[4 * (a.vec4s() + b.vec4s())];
        a.copyTo(frame, 0);
        b.copyTo(frame, a.vec4s());
        assertEquals(1f, frame[color * 4 + 1], 0f);
        assertEquals(0.5f, frame[(a.vec4s() + color) * 4 + 1], 0f);
        assertEquals(2f, a.get(d.rows().slot("_Speed"), 0), 0f);
    }

    @Test
    public void routesAndRefusals() {
        CgMaterialDefinition d = CgMaterialDefinitions.compile(asset("test:bound", tree("shader", FX,
                "values", map("_Color", map("bind", "user.tint"), "_Speed", map("bind", "global.skill.speed"))), null),
                ARCHETYPES);
        assertEquals(CgMaterialRoute.ROW, d.route("_Color"));
        assertEquals(CgMaterialRoute.GLOBAL, d.route("_Speed"));
        assertEquals(CgMaterialRoute.CONSTANT, d.route("_Bands"));
        try {
            d.requireRow("_MainTex", "track tex");
            fail("a track keyed a sampler");
        } catch (CgAssetException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("_MainTex") && e.getMessage().contains("track tex"));
        }
    }

    @Test
    public void aMaterialBuiltInJavaIsTheOneItsFileLoads() {
        CgMaterialDefinitions.archetypes(ARCHETYPES);
        try {
            CgMaterialDefinition.Builder b = CgMaterialDefinition.of("test:glow", FX)
                    .blend(CgBlendMode.ADDITIVE).twoSided().keyword("SOFT").color("_Color", 0x80FF8040, 2f)
                    .value("_Speed", 1.5f).texture("_MainTex", "test:textures/noise.png", CgSampler.Filter.BILINEAR,
                            CgSampler.Address.WRAP);
            CgMaterialDefinition built = b.build();
            CgMaterialDefinition loaded = CgMaterialDefinitions.compile(asset("test:glow", b.tree(), null));
            assertSame(loaded.permutation(), built.permutation());
            assertEquals(loaded.textures(), built.textures());
            assertEquals(loaded.draw(), built.draw());
            assertArrayEquals(new float[] {2f, 0x80 / 255f * 2f, 0x40 / 255f * 2f, 0x80 / 255f},
                    built.constants().get("_Color"), 1e-6f);
            assertEquals("additive", built.permutation().blend());
            assertEquals(Arrays.asList("_v", "shader", "state", "keywords", "values", "textures"),
                    new ArrayList<>(b.tree().keySet()));
            try {
                CgMaterialDefinition.of("test:bad", FX).value("_Nope", 1f).build();
                fail("an undeclared property must fail at build");
            } catch (CgAssetException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("_Nope"));
            }
        } finally {
            CgMaterialDefinitions.archetypes(CgMaterialArchetypes.FILES);
        }
    }

    @Test
    public void aDerivedDefinitionIsInternedByItsParentAndOverrides() {
        CgMaterialDefinitions.archetypes(ARCHETYPES);
        try {
            CgMaterialDefinition glow = CgMaterialDefinitions.compile(root());
            CgMaterialDefinition soft = CgMaterialDefinitions.derive(glow, map("keywords", Arrays.asList("SOFT", "GLOW")));
            assertSame(soft, CgMaterialDefinitions.derive(glow, map("keywords", Arrays.asList("GLOW", "SOFT"))));
            assertNotSame(glow.permutation(), soft.permutation());
            assertEquals("test:glow", soft.parent());

            CgMaterialDefinition fast = CgMaterialDefinitions.derive(glow, map("values", map("_Speed", 4.0)));
            assertTrue("values alone share their parent's programs", fast.sharesProgramsWith(glow));
            assertArrayEquals(new float[] {4f}, fast.constants().get("_Speed"), 0f);
            assertArrayEquals(new float[] {1f, 0.5f, 0.25f, 1f}, fast.constants().get("_Color"), 0f);
            assertSame("a float and a double of one value are one key", fast,
                    CgMaterialDefinitions.derive(glow, map("values", map("_Speed", 4f))));
            assertNotSame(fast, CgMaterialDefinitions.derive(glow, map("values", map("_Speed", 4.5))));

            CgMaterialDefinition again = CgMaterialDefinitions.compile(root());
            assertNotSame("a new generation derives its own", fast,
                    CgMaterialDefinitions.derive(again, map("values", map("_Speed", 4.0))));
        } finally {
            CgMaterialDefinitions.archetypes(CgMaterialArchetypes.FILES);
        }
    }

    @Test
    public void permutationsIntern() {
        assertSame(CgMaterialPermutation.of(FX, Arrays.asList("B", "A"), "opaque", false, true, null),
                CgMaterialPermutation.of(FX, Arrays.asList("A", "B"), "opaque", false, true, null));
    }
}
