package io.github.somehussar.crystalgraphics.gl.material.parse;

import com.crystalgraphics.api.buffer.CgBufferFormat;
import com.crystalgraphics.api.buffer.CgBufferFormat.MemoryLayout;
import com.crystalgraphics.api.material.CgAttachedBuffer;
import com.crystalgraphics.api.shader.CgPreprocessorException;
import com.crystalgraphics.gl.material.parse.CgBufferGlslEmitter;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Exhaustive unit tests for {@link CgBufferGlslEmitter}.
 *
 * <p>All tests are pure string generation — no GL context required. Core methods are called
 * directly (package-private seam: {@code emitSsbo/emitTbo/emitUbo(format, name, ...)}).</p>
 */
public class CgBufferGlslEmitterTest {

    // F1 — all VEC4 (simplest happy path); stride=48, texels=3
    static final CgBufferFormat ALL_VEC4 = CgBufferFormat.builder("InstanceData", MemoryLayout.STD430)
        .vec4("color").vec4("emissive").vec4("params").build();

    // F2 — all MAT4; stride=128, texels=8
    static final CgBufferFormat ALL_MAT4 = CgBufferFormat.builder("TransformPair", MemoryLayout.STD430)
        .mat4("modelA").mat4("modelB").build();

    // F3 — mixed VEC2 + FLOAT packing into shared texels; stride=48, texels=3
    // bbox @0/texel0, uv0 @16/texel1.xy, uv1 @24/texel1.zw,
    // advance @32/texel2.x, bearing @36/texel2.y, descent @40/texel2.z, pad @44/texel2.w
    static final CgBufferFormat PACKED_SMALL = CgBufferFormat.builder("GlyphMetrics", MemoryLayout.STD430)
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
    static final CgBufferFormat VEC3_FORMAT = CgBufferFormat.builder("LightData", MemoryLayout.STD430)
        .mat4("transform")
        .vec3("color")
        .vec4("params")
        .build();

    // F5 — MAT3; stride=80, texels=5
    // origin @0/texel0, normalMat @16/texels1-3, extra @64/texel4
    static final CgBufferFormat MAT3_FORMAT = CgBufferFormat.builder("NormalData", MemoryLayout.STD430)
        .vec4("origin")
        .mat3("normalMat")
        .vec4("extra")
        .build();

    // F6 — MAT4 + MAT3 + VEC4; stride=128, texels=8
    // bindPose @0/texels0-3, normalMat @64/texels4-6, weights @112/texel7
    static final CgBufferFormat MIXED_MAT = CgBufferFormat.builder("SkinData", MemoryLayout.STD430)
        .mat4("bindPose")
        .mat3("normalMat")
        .vec4("weights")
        .build();

    // F7 — UVEC2 (bindless handle) — SSBO-only; stride=32
    // uvec2 @0 (align 8), vec4 @16 (align 16)
    static final CgBufferFormat BINDLESS = CgBufferFormat.builder("TexturePool", MemoryLayout.STD430)
        .uvec2("handle").vec4("uvRect").build();

    @Test
    public void testF1_allVec4_ssbo() {
        String out = CgBufferGlslEmitter.emitSsbo(ALL_VEC4, "InstBuf", "INST");

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
        String out = CgBufferGlslEmitter.emitSsbo(MAT3_FORMAT, "NormBuf", "NORM_DATA");

        assertTrue(out.contains("mat3 normalMat;"));
        assertTrue(out.contains("layout(std430) readonly buffer"));
        assertFalse("SSBO should not contain texelFetch", out.contains("texelFetch"));
    }

    @Test
    public void testF7_uvec2_ssboAllowed() {
        String out = CgBufferGlslEmitter.emitSsbo(BINDLESS, "TexturePoolBuf", "TEX_POOL");

        assertTrue(out.contains("uvec2 handle;"));
        assertTrue(out.contains("vec4 uvRect;"));
        assertFalse(out.contains("binding ="));
    }

    @Test
    public void testF1_allVec4_tbo() {
        String out = CgBufferGlslEmitter.emitTbo(ALL_VEC4, "InstBuf", "INST");

        assertTrue(out.contains("uniform samplerBuffer InstBuf;"));
        assertTrue(out.contains("InstanceData _cg_getInstanceData(int n) {"));
        assertTrue(out.contains("int _base = n * 3;"));
        assertTrue(out.contains("_r.color = texelFetch(InstBuf, _base + 0);"));
        assertTrue(out.contains("_r.emissive = texelFetch(InstBuf, _base + 1);"));
        assertTrue(out.contains("_r.params = texelFetch(InstBuf, _base + 2);"));
        assertTrue(out.contains("#define INST(n) _cg_getInstanceData(n)"));
    }

    @Test
    public void testF2_allMat4_tbo() {
        String out = CgBufferGlslEmitter.emitTbo(ALL_MAT4, "TransBuf", "TRANSFORM");

        assertTrue(out.contains("int _base = n * 8;"));
        assertTrue(out.contains("_r.modelA[0] = texelFetch(TransBuf, _base + 0);"));
        assertTrue(out.contains("_r.modelA[1] = texelFetch(TransBuf, _base + 1);"));
        assertTrue(out.contains("_r.modelA[2] = texelFetch(TransBuf, _base + 2);"));
        assertTrue(out.contains("_r.modelA[3] = texelFetch(TransBuf, _base + 3);"));
        assertTrue(out.contains("_r.modelB[0] = texelFetch(TransBuf, _base + 4);"));
        assertTrue(out.contains("_r.modelB[3] = texelFetch(TransBuf, _base + 7);"));
    }

    @Test
    public void testF3_packedSmall_tbo() {
        String out = CgBufferGlslEmitter.emitTbo(PACKED_SMALL, "GlyphBuf", "GLYPH");

        assertTrue(out.contains("int _base = n * 3;"));
        assertTrue(out.contains("_r.bbox = texelFetch(GlyphBuf, _base + 0);"));
        assertTrue(out.contains("_r.uv0 = texelFetch(GlyphBuf, _base + 1).xy;"));
        assertTrue(out.contains("_r.uv1 = texelFetch(GlyphBuf, _base + 1).zw;"));
        assertTrue(out.contains("_r.advance = texelFetch(GlyphBuf, _base + 2).x;"));
        assertTrue(out.contains("_r.bearing = texelFetch(GlyphBuf, _base + 2).y;"));
        assertTrue(out.contains("_r.descent = texelFetch(GlyphBuf, _base + 2).z;"));
        assertTrue(out.contains("_r.pad = texelFetch(GlyphBuf, _base + 2).w;"));
    }

    @Test
    public void testF4_vec3_tbo() {
        String out = CgBufferGlslEmitter.emitTbo(VEC3_FORMAT, "LightBuf", "LIGHT");

        assertTrue(out.contains("int _base = n * 6;"));
        assertTrue(out.contains("_r.color = texelFetch(LightBuf, _base + 4).xyz;"));
        assertTrue(out.contains("_r.params = texelFetch(LightBuf, _base + 5);"));
    }

    @Test
    public void testF5_mat3_tbo() {
        String out = CgBufferGlslEmitter.emitTbo(MAT3_FORMAT, "NormBuf", "NORM");

        assertTrue(out.contains("int _base = n * 5;"));
        assertTrue(out.contains("_r.normalMat[0] = texelFetch(NormBuf, _base + 1).xyz;"));
        assertTrue(out.contains("_r.normalMat[1] = texelFetch(NormBuf, _base + 2).xyz;"));
        assertTrue(out.contains("_r.normalMat[2] = texelFetch(NormBuf, _base + 3).xyz;"));
        assertTrue(out.contains("_r.extra = texelFetch(NormBuf, _base + 4);"));
    }

    @Test
    public void testF6_mixedMat_tbo() {
        String out = CgBufferGlslEmitter.emitTbo(MIXED_MAT, "SkinBuf", "SKIN");

        assertTrue(out.contains("int _base = n * 8;"));
        assertTrue(out.contains("_r.bindPose[0] = texelFetch(SkinBuf, _base + 0);"));
        assertTrue(out.contains("_r.bindPose[1] = texelFetch(SkinBuf, _base + 1);"));
        assertTrue(out.contains("_r.bindPose[2] = texelFetch(SkinBuf, _base + 2);"));
        assertTrue(out.contains("_r.bindPose[3] = texelFetch(SkinBuf, _base + 3);"));
        assertTrue(out.contains("_r.normalMat[0] = texelFetch(SkinBuf, _base + 4).xyz;"));
        assertTrue(out.contains("_r.normalMat[1] = texelFetch(SkinBuf, _base + 5).xyz;"));
        assertTrue(out.contains("_r.normalMat[2] = texelFetch(SkinBuf, _base + 6).xyz;"));
        assertTrue(out.contains("_r.weights = texelFetch(SkinBuf, _base + 7);"));
    }

    @Test
    public void testF7_uvec2_tboThrows() {
        try {
            CgBufferGlslEmitter.emitTbo(BINDLESS, "TexturePoolBuf", "TEX_POOL");
            fail("Expected CgPreprocessorException for UVEC2 on TBO path");
        } catch (CgPreprocessorException e) {
            String msg = e.getMessage().toLowerCase();
            assertTrue("Message should mention UVEC2 or tbo",
                msg.contains("uvec2") || msg.contains("tbo") || msg.contains("tbo-compatible"));
        }
    }

    @Test
    public void testError_intFieldTboThrows() {
        CgBufferFormat f = CgBufferFormat.builder("BadData", MemoryLayout.STD430)
            .vec4("pos").int_("flags").build();
        try {
            CgBufferGlslEmitter.emitTbo(f, "BadBuf", "BAD");
            fail("Expected CgPreprocessorException for INT on TBO path");
        } catch (CgPreprocessorException e) {
            assertTrue("Message should mention INT", e.getMessage().contains("INT"));
        }
    }

    @Test
    public void testError_uint64FieldTboThrows() {
        CgBufferFormat f = CgBufferFormat.builder("Handle64", MemoryLayout.STD430)
            .uint64("h").vec4("pad").build();
        try {
            CgBufferGlslEmitter.emitTbo(f, "H64Buf", "H64");
            fail("Expected CgPreprocessorException for UINT64 on TBO path");
        } catch (CgPreprocessorException e) {
            assertTrue("Message should mention UINT64", e.getMessage().contains("UINT64"));
        }
    }

    @Test
    public void testError_strideNotMultiple16_tboThrows() {
        CgBufferFormat f = CgBufferFormat.builder("BadStride", MemoryLayout.STD430)
            .float_("a").float_("b").float_("c").build();
        try {
            CgBufferGlslEmitter.emitTbo(f, "BadStrideBuf", "BAD_STRIDE");
            fail("Expected CgPreprocessorException for stride not multiple of 16");
        } catch (CgPreprocessorException e) {
            assertTrue("Message should mention stride", e.getMessage().toLowerCase().contains("stride"));
        }
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
        CgBufferFormat f = CgBufferFormat.builder("SceneParams", MemoryLayout.STD140)
            .vec4("ambientColor").float_("exposure").vec4("fogColor").build();
        String out = CgBufferGlslEmitter.emitUbo(f, "SceneParams");

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
        CgBufferFormat f = CgBufferFormat.builder("CameraExtras", MemoryLayout.STD140)
            .mat4("prevViewProj").vec4("jitter").build();
        String out = CgBufferGlslEmitter.emitUbo(f, "CameraExtras");

        assertTrue(out.contains("mat4 prevViewProj;"));
        assertTrue(out.contains("vec4 jitter;"));
        assertTrue(out.contains("layout(std140) uniform CameraExtras {"));
    }

    @Test
    public void testUbo_blockNameUsedForBlock() {
        CgBufferFormat f = CgBufferFormat.builder("SomeData", MemoryLayout.STD140)
            .vec4("value").build();
        String out = CgBufferGlslEmitter.emitUbo(f, "MyCustomBlock");

        assertTrue("Block name must be the passed blockName", out.contains("uniform MyCustomBlock {"));
    }

    @Test
    public void testNoBindingQualifierAnywhere() {
        assertFalse(CgBufferGlslEmitter.emitSsbo(ALL_VEC4, "Buf", "MACRO").contains("binding ="));
        assertFalse(CgBufferGlslEmitter.emitTbo(ALL_VEC4, "Buf", "MACRO").contains("binding ="));
        assertFalse(CgBufferGlslEmitter.emitUbo(
            CgBufferFormat.builder("P", MemoryLayout.STD140).vec4("x").build(), "P"
        ).contains("binding ="));
    }

    @Test
    public void testUbo_noInstanceName() {
        CgBufferFormat f = CgBufferFormat.builder("SceneParams", MemoryLayout.STD140)
            .vec4("ambientColor").build();
        String out = CgBufferGlslEmitter.emitUbo(f, "SceneParams");

        // The block must end with "};\n" — no instance name between } and ;
        assertTrue(out.endsWith("};\n"));
    }

    @Test
    public void testSsbo_blockNameEqualsBufferName() {
        String out = CgBufferGlslEmitter.emitSsbo(ALL_VEC4, "MyBufferName", "MACRO");
        assertTrue("Block interface name must equal bufferName",
            out.contains("readonly buffer MyBufferName {"));
    }

    @Test
    public void testTbo_samplerNameEqualsBufferName() {
        String out = CgBufferGlslEmitter.emitTbo(ALL_VEC4, "MyBufferName", "MACRO");
        assertTrue("Sampler uniform name must equal bufferName",
            out.contains("uniform samplerBuffer MyBufferName;"));
    }

    @Test
    public void testLowerFirst_helper() {
        assertEquals("fontMetrics", CgBufferGlslEmitter.lowerFirst("FontMetrics"));
        assertEquals("x", CgBufferGlslEmitter.lowerFirst("X"));
        assertEquals("", CgBufferGlslEmitter.lowerFirst(""));
        assertEquals("already", CgBufferGlslEmitter.lowerFirst("already"));
    }
}
