package com.crystalgraphics.api.material;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.BeforeClass;
import org.junit.Test;

import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.asset.kind.CgLookKinds;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.gl.material.parse.CgParsedShader;
import com.crystalgraphics.gl.material.parse.CgShaderParser;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Materials compile from assets: names checked, the parent chain merged, value-only children sharing programs. */
public class CgMaterialDefinitionsTest {

    private static final String FX = "test:shaders/fx.shader";
    private static final String SOURCE = String.join("\n",
            "#type spatial",
            "#pragma cg_use rows",
            "#pragma cg_feature SOFT",
            "#pragma cg_feature GLOW",
            "Queue = \"Transparent\"",
            "Properties {",
            "    _Color (\"Colour\", color) = (1, 1, 1, 1)",
            "    _Bands (\"Bands\", color[3]) = (1, 1, 1, 1), (0, 1, 1, 1), (0, 0.5, 0.9, 1)",
            "    _Speed (\"Speed\", float) = 1.0",
            "    _MainTex (\"Main\", sampler2D) = \"white\"",
            "}",
            "struct v2f { vec2 uv; };",
            "Pass {",
            "    void vertex(out v2f o) { o.uv = cg_TexCoord0; gl_Position = CG_MATRIX_MVP * vec4(cg_Position, 1.0); }",
            "    void fragment(in v2f i, out vec4 fragColor) { fragColor = _Color * _Bands(1) * _Speed; }",
            "}");

    private static final String POST = "test:shaders/post.shader", DECAL = "test:shaders/decal.shader";
    private static final String DECAL_SOURCE = String.join("\n",
            "#type spatial",
            "#pragma cg_feature CELL_TONE",
            "Tags { \"Domain\" = \"Decal\" }",
            "Properties {",
            "    _Clip (\"Clip\", float) = 0.5",
            "}",
            "Pass {",
            "    void vertex(out v2f o) { gl_Position = CG_MATRIX_MVP * vec4(cg_Position, 1.0); }",
            "    void fragment(in v2f i, out vec4 fragColor) { fragColor = vec4(_Clip); }",
            "}");

    static final CgMaterialArchetypes ARCHETYPES = new CgMaterialArchetypes() {
        @Override
        public CgParsedShader parse(String path) {
            if (path.equals(FX) || path.equals(POST)) return CgShaderParser.parse(SOURCE, path);
            if (path.equals(DECAL)) return CgShaderParser.parse(DECAL_SOURCE, path);
            throw new IllegalArgumentException(path + " is not a shader");
        }

        @Override
        public CgMaterialDomain domain(String path) {
            return path.equals(POST) ? CgMaterialDomain.POST : path.equals(DECAL)
                    ? CgMaterialDomain.ofSource(DECAL_SOURCE) : CgMaterialDomain.SURFACE;
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
        assertEquals(5, d.rows().vec4s());
        assertEquals(-1, d.rows().slot("_MainTex"));
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
    public void permutationsIntern() {
        assertSame(CgMaterialPermutation.of(FX, Arrays.asList("B", "A"), "opaque", false, null),
                CgMaterialPermutation.of(FX, Arrays.asList("A", "B"), "opaque", false, null));
    }
}
