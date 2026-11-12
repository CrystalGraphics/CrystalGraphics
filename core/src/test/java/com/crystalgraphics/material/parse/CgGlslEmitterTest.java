package com.crystalgraphics.material.parse;

import com.crystalgraphics.api.buffer.CgBufferLayout;
import com.crystalgraphics.api.buffer.CgBufferLayout.MemoryLayout;
import com.crystalgraphics.api.material.CgAttachedBuffer;
import com.crystalgraphics.api.vertex.CgAttribType;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.api.vertex.CgVertexSemantic;
import com.crystalgraphics.render.shader.CgBindingModel;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Exhaustive unit tests for {@link CgGlslEmitter}.
 *
 * <p>All tests are pure string generation — no GL context required. Core methods are called
 * directly (package-private seam: {@code emitSsbo/emitUbo(format, name, ...)}).</p>
 */
public class CgGlslEmitterTest {

    // F1 — all VEC4 (simplest happy path); stride=48, texels=3
    static final CgBufferLayout ALL_VEC4 = CgBufferLayout.builder("InstanceData", MemoryLayout.STD430)
        .vec4("color").vec4("emissive").vec4("params").build();

    // F2 — all MAT4; stride=128, texels=8
    static final CgBufferLayout ALL_MAT4 = CgBufferLayout.builder("TransformPair", MemoryLayout.STD430)
        .mat4("modelA").mat4("modelB").build();

    // F3 — mixed VEC2 + FLOAT packing into shared texels; stride=48, texels=3
    // bbox @0/texel0, uv0 @16/texel1.xy, uv1 @24/texel1.zw,
    // advance @32/texel2.x, bearing @36/texel2.y, descent @40/texel2.z, pad @44/texel2.w
    static final CgBufferLayout PACKED_SMALL = CgBufferLayout.builder("GlyphMetrics", MemoryLayout.STD430)
        .vec4("bbox")
        .vec2("uv0")
        .vec2("uv1")
        .float_("advance")
        .float_("bearing")
        .float_("descent")
        .float_("pad")
        .build();

    // F4 — VEC3 + MAT4 + VEC4; stride=96, texels=6
    // transform @0/texels0-3, color @64/texel4.xyz, params @80/texel5
    static final CgBufferLayout VEC3_FORMAT = CgBufferLayout.builder("LightData", MemoryLayout.STD430)
        .mat4("transform")
        .vec3("color")
        .vec4("params")
        .build();

    // F5 — MAT3; stride=80, texels=5
    // origin @0/texel0, normalMat @16/texels1-3, extra @64/texel4
    static final CgBufferLayout MAT3_FORMAT = CgBufferLayout.builder("NormalData", MemoryLayout.STD430)
        .vec4("origin")
        .mat3("normalMat")
        .vec4("extra")
        .build();

    // F6 — MAT4 + MAT3 + VEC4; stride=128, texels=8
    // bindPose @0/texels0-3, normalMat @64/texels4-6, weights @112/texel7
    static final CgBufferLayout MIXED_MAT = CgBufferLayout.builder("SkinData", MemoryLayout.STD430)
        .mat4("bindPose")
        .mat3("normalMat")
        .vec4("weights")
        .build();

    // F7 — UVEC2 (bindless handle) — SSBO-only; stride=32
    // uvec2 @0 (align 8), vec4 @16 (align 16)
    static final CgBufferLayout BINDLESS = CgBufferLayout.builder("TexturePool", MemoryLayout.STD430)
        .uvec2("handle").vec4("uvRect").build();

    @Test
    public void testF1_allVec4_ssbo() {
        String out = CgGlslEmitter.emitSsbo(ALL_VEC4, "InstBuf", "INST");

        assertTrue(out.contains("struct InstanceData {"));
        assertTrue(out.contains("vec4 color;"));
        assertTrue(out.contains("vec4 emissive;"));
        assertTrue(out.contains("vec4 params;"));
        assertTrue(out.contains("layout(std430) readonly buffer InstBuf {"));
        assertTrue(out.contains("InstanceData _cg_instanceDataArr[];"));
        assertTrue(out.contains("#define INST(n) _cg_instanceDataArr[n]"));
        assertFalse("No binding qualifier allowed", out.contains("binding ="));
    }

    @Test
    public void testF5_mat3_ssbo() {
        String out = CgGlslEmitter.emitSsbo(MAT3_FORMAT, "NormBuf", "NORM_DATA");

        assertTrue(out.contains("mat3 normalMat;"));
        assertTrue(out.contains("layout(std430) readonly buffer"));
        assertFalse("SSBO should not contain texelFetch", out.contains("texelFetch"));
    }

    @Test
    public void testF7_uvec2_ssboAllowed() {
        String out = CgGlslEmitter.emitSsbo(BINDLESS, "TexturePoolBuf", "TEX_POOL");

        assertTrue(out.contains("uvec2 handle;"));
        assertTrue(out.contains("vec4 uvRect;"));
        assertFalse(out.contains("binding ="));
    }

    @Test
    public void testError_invalidMacroName_lowercase() {
        try {
            CgAttachedBuffer.of(null, "myBuffer");
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            // expected — null buffer
        }
    }

    @Test
    public void testUbo_simpleFields() {
        CgBufferLayout f = CgBufferLayout.builder("SceneParams", MemoryLayout.STD140)
            .vec4("ambientColor").float_("exposure").vec4("fogColor").build();
        String out = CgGlslEmitter.emitUbo(f, "SceneParams");

        assertTrue(out.contains("layout(std140) uniform SceneParams {"));
        assertTrue(out.contains("    vec4 ambientColor;"));
        assertTrue(out.contains("    float exposure;"));
        assertTrue(out.contains("    vec4 fogColor;"));
        assertTrue(out.endsWith("};\n"));
        assertFalse("No struct for UBO", out.contains("struct "));
        assertFalse("No macro for UBO", out.contains("#define"));
        assertFalse("No binding qualifier", out.contains("binding ="));
    }

    @Test
    public void testUbo_matrixFields() {
        CgBufferLayout f = CgBufferLayout.builder("CameraExtras", MemoryLayout.STD140)
            .mat4("prevViewProj").vec4("jitter").build();
        String out = CgGlslEmitter.emitUbo(f, "CameraExtras");

        assertTrue(out.contains("mat4 prevViewProj;"));
        assertTrue(out.contains("vec4 jitter;"));
        assertTrue(out.contains("layout(std140) uniform CameraExtras {"));
    }

    @Test
    public void testUbo_blockNameUsedForBlock() {
        CgBufferLayout f = CgBufferLayout.builder("SomeData", MemoryLayout.STD140)
            .vec4("value").build();
        String out = CgGlslEmitter.emitUbo(f, "MyCustomBlock");

        assertTrue("Block name must be the passed blockName", out.contains("uniform MyCustomBlock {"));
    }

    @Test
    public void testUbo_noInstanceName() {
        CgBufferLayout f = CgBufferLayout.builder("SceneParams2", MemoryLayout.STD140)
            .vec4("ambientColor").build();
        String out = CgGlslEmitter.emitUbo(f, "SceneParams2");

        assertTrue(out.endsWith("};\n"));
    }

    @Test
    public void testSsbo_blockNameEqualsBufferName() {
        String out = CgGlslEmitter.emitSsbo(ALL_VEC4, "MyBufferName", "MACRO");
        assertTrue("Block interface name must equal bufferName",
            out.contains("readonly buffer MyBufferName {"));
    }

    @Test
    public void testLowerFirst_helper() {
        assertEquals("fontMetrics", CgGlslEmitter.lowerFirst("FontMetrics"));
        assertEquals("x", CgGlslEmitter.lowerFirst("X"));
        assertEquals("", CgGlslEmitter.lowerFirst(""));
        assertEquals("already", CgGlslEmitter.lowerFirst("already"));
    }

    // ── The vertex fetch ──────────────────────────────────────────────────────

    @Test
    public void spatial_declaresItsWordsAndAGlobalPerAttribute() {
        String out = CgGlslEmitter.emitVertexDeclarations(CgVertexFormat.SPATIAL);
        assertTrue(out, out.contains("CG_BIND(1, 4) layout(std430) readonly buffer CgVertexWords { uint cg_VertexWords[]; };"));
        assertTrue(out, out.contains("\nvec3 cg_Position;") && out.contains("\nvec3 cg_Normal;") && out.contains("\nvec4 cg_Color;"));
        assertFalse("no vertex input", out.contains("in vec"));
        assertEquals("", CgGlslEmitter.emitVertexDeclarations(CgVertexFormat.NONE));
    }

    /** SPATIAL's 40 bytes as ten words: three floats, two snorm16 pairs, unorm8 x4, four half pairs. */
    @Test
    public void spatial_fetchDecodesEachAttributeFromItsWords() {
        String out = CgGlslEmitter.emitVertexFetch(CgVertexFormat.SPATIAL, null, null);
        assertTrue(out, out.contains("uint v = uint(gl_VertexID) * 10u;"));
        assertTrue(out, out.contains("cg_Position = vec3(uintBitsToFloat(cg_VertexWords[v + 0u]), "
                + "uintBitsToFloat(cg_VertexWords[v + 1u]), uintBitsToFloat(cg_VertexWords[v + 2u]));"));
        assertTrue(out, out.contains("cg_Normal = vec3(unpackSnorm2x16(cg_VertexWords[v + 3u]).x, "
                + "unpackSnorm2x16(cg_VertexWords[v + 3u]).y, unpackSnorm2x16(cg_VertexWords[v + 4u]).x);"));
        assertTrue(out, out.contains("cg_Color = vec4(unpackUnorm4x8(cg_VertexWords[v + 5u]).x, "
                + "unpackUnorm4x8(cg_VertexWords[v + 5u]).y, unpackUnorm4x8(cg_VertexWords[v + 5u]).z, "
                + "unpackUnorm4x8(cg_VertexWords[v + 5u]).w);"));
        assertTrue(out, out.contains("cg_TexCoord3 = vec2(unpackHalf2x16(cg_VertexWords[v + 9u]).x, "
                + "unpackHalf2x16(cg_VertexWords[v + 9u]).y);"));
    }

    /** The skinned factory reads either width by the draw: 12 words with u8 indices, 13 with u16. */
    @Test
    public void skinned_fetchPicksTheWidthByItsCondition() {
        String out = CgGlslEmitter.emitVertexFetch(CgVertexFormat.SKINNED, CgVertexFormat.SKINNED_WIDE, "CG_BONES_WIDE");
        int wide = out.indexOf("if (CG_BONES_WIDE)"), narrow = out.indexOf("} else {");
        assertTrue(out, wide >= 0 && narrow > wide);
        assertTrue(out, out.indexOf("* 13u;") > wide && out.indexOf("* 13u;") < narrow);
        assertTrue(out, out.indexOf("* 12u;") > narrow);
        assertTrue(out, out.contains("cg_BoneIndices = uvec4(bitfieldExtract(cg_VertexWords[v + 10u], 0, 16), "
                + "bitfieldExtract(cg_VertexWords[v + 10u], 16, 16), bitfieldExtract(cg_VertexWords[v + 11u], 0, 16), "
                + "bitfieldExtract(cg_VertexWords[v + 11u], 16, 16));"));
        assertTrue(out, out.contains("cg_BoneIndices = uvec4(bitfieldExtract(cg_VertexWords[v + 10u], 0, 8), "
                + "bitfieldExtract(cg_VertexWords[v + 10u], 8, 8), bitfieldExtract(cg_VertexWords[v + 10u], 16, 8), "
                + "bitfieldExtract(cg_VertexWords[v + 10u], 24, 8));"));
    }

    /** The block as a struct padded to the slot, its array at the material block's slot, each value loaded from the draw's. */
    @Test
    public void arena_theBlockAsAPaddedStructAndEachValueLoaded() {
        CgBufferLayout block = CgBufferLayout.builder("CgMaterialBlock", MemoryLayout.STD140).vec4("_Color").float_("_Speed").build();
        String out = CgGlslEmitter.emitArena(block, CgBindingModel.MATERIAL_BLOCK, 256);
        int bytes = block.getFloatCount() * 4;
        assertTrue(out, out.contains("struct CgMaterialData {\n    vec4 _Color;\n    float _Speed;\n    vec4 _cg_pad["
                + (256 - bytes) / 16 + "];\n};\n"));
        assertTrue(out, out.contains("CG_BIND(2, 0) layout(std140) readonly buffer CgMaterialArena {\n    CgMaterialData cg_Materials[];\n};"));
        assertTrue(out, out.contains("\nvec4 _Color;\nfloat _Speed;\nvoid _cg_material() {"));
        assertTrue(out, out.contains("    _Speed = cg_Materials[s]._Speed;\n"));
        assertTrue(out, out.startsWith("#define CG_MATERIAL_ARENA 1\n"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void arena_aBlockLargerThanItsSlotIsRefused() {
        CgBufferLayout block = CgBufferLayout.builder("CgMaterialBlock", MemoryLayout.STD140).mat4("a").mat4("b").mat4("c")
                .mat4("d").mat4("e").build();
        CgGlslEmitter.emitArena(block, CgBindingModel.MATERIAL_BLOCK, 256);
    }

    @Test(expected = IllegalStateException.class)
    public void aStrideOfPartWordsIsRefused() {
        CgVertexFormat fmt = CgVertexFormat.builder("test_part_word")
            .add(CgVertexSemantic.POSITION, "a_pos", 3, CgAttribType.FLOAT)
            .add(CgVertexSemantic.GENERIC, "a_flag", 1, CgAttribType.UNSIGNED_BYTE, false)
            .build();
        CgGlslEmitter.emitVertexFetch(fmt, null, null);
    }
}
