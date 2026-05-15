package io.github.somehussar.crystalgraphics.gl.material;

import com.crystalgraphics.gl.material.CgMaterialProperty;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link CgMaterialProperty} and its inner {@link CgMaterialProperty.Type} enum.
 *
 * <p>All tests are pure Java — no GL context required. Uses {@link CgMaterialProperty#fromDecl}
 * as the primary factory, mirroring how the parser creates properties at parse time.</p>
 */
public class CgMaterialPropertyTest {

    // ── Type enum ─────────────────────────────────────────────────────────────

    @Test
    public void type_float_isSampler_false() {
        assertFalse(CgMaterialProperty.Type.FLOAT.isSampler());
    }

    @Test
    public void type_sampler2D_isSampler_true() {
        assertTrue(CgMaterialProperty.Type.SAMPLER2D.isSampler());
    }

    @Test
    public void type_sampler2DArray_isSampler_true() {
        assertTrue(CgMaterialProperty.Type.SAMPLER2D_ARRAY.isSampler());
    }

    @Test
    public void type_sampler3D_isSampler_true() {
        assertTrue(CgMaterialProperty.Type.SAMPLER3D.isSampler());
    }

    @Test
    public void type_samplerCube_isSampler_true() {
        assertTrue(CgMaterialProperty.Type.SAMPLER_CUBE.isSampler());
    }

    @Test
    public void type_color_glslType_isVec4() {
        assertEquals("vec4", CgMaterialProperty.Type.COLOR.getGlslName());
    }

    @Test
    public void type_range_glslType_isFloat() {
        assertEquals("float", CgMaterialProperty.Type.RANGE.getGlslName());
    }

    @Test
    public void type_boolean_glslType_isBool() {
        assertEquals("bool", CgMaterialProperty.Type.BOOLEAN.getGlslName());
    }

    @Test
    public void type_fromPropertyType_float() {
        assertSame(CgMaterialProperty.Type.FLOAT, CgMaterialProperty.Type.fromPropertyType("float"));
    }

    @Test
    public void type_fromPropertyType_caseInsensitive() {
        assertSame(CgMaterialProperty.Type.VEC4, CgMaterialProperty.Type.fromPropertyType("VEC4"));
    }

    @Test
    public void type_fromPropertyType_unknown_returnsNull() {
        assertNull(CgMaterialProperty.Type.fromPropertyType("mat4"));
    }

    @Test
    public void type_fromGlsl_float() {
        assertSame(CgMaterialProperty.Type.FLOAT, CgMaterialProperty.Type.fromGlsl("float"));
    }

    @Test
    public void type_fromGlsl_color_returnsNull() {
        // "color" is not a GLSL keyword — fromGlsl must return null for it
        assertNull(CgMaterialProperty.Type.fromGlsl("color"));
    }

    // ── fromDecl factory ──────────────────────────────────────────────────────

    @Test
    public void fromDecl_float_nameAndType() {
        CgMaterialProperty p = CgMaterialProperty.fromDecl("_Alpha", "Opacity", "float", "1.0");
        assertEquals("_Alpha", p.getName());
        assertEquals("Opacity", p.getDisplayName());
        assertSame(CgMaterialProperty.Type.FLOAT, p.getType());
        assertEquals("float", p.getGlslType());
    }

    @Test
    public void fromDecl_float_defaultApplied() {
        CgMaterialProperty p = CgMaterialProperty.fromDecl("_Alpha", null, "float", "0.5");
        float[] val = p.getFloatValue();
        assertEquals(0.5f, val[0], 0.0001f);
    }

    @Test
    public void fromDecl_vec4_defaultApplied() {
        CgMaterialProperty p = CgMaterialProperty.fromDecl("_Color", null, "vec4", "(1.0, 0.0, 0.0, 1.0)");
        float[] val = p.getFloatValue();
        assertEquals(1.0f, val[0], 0.0001f);
        assertEquals(0.0f, val[1], 0.0001f);
        assertEquals(0.0f, val[2], 0.0001f);
        assertEquals(1.0f, val[3], 0.0001f);
    }

    @Test
    public void fromDecl_nullDisplayName_fallsBackToName() {
        CgMaterialProperty p = CgMaterialProperty.fromDecl("_Speed", null, "float", null);
        assertEquals("_Speed", p.getDisplayName());
    }

    @Test
    public void fromDecl_sampler2D_noDefault() {
        CgMaterialProperty p = CgMaterialProperty.fromDecl("_MainTex", "Main Texture", "sampler2D", null);
        assertSame(CgMaterialProperty.Type.SAMPLER2D, p.getType());
        assertNull(p.getRawDefault());
        assertEquals(-1, p.getSamplerUnit());
        assertNull(p.getSamplerTexture());
    }

    @Test(expected = IllegalArgumentException.class)
    public void fromDecl_unknownType_throws() {
        CgMaterialProperty.fromDecl("_Bad", null, "mat4", null);
    }

    // ── set / getFloatValue ───────────────────────────────────────────────────

    @Test
    public void set_float_updatesValue() {
        CgMaterialProperty p = CgMaterialProperty.fromDecl("_A", null, "float", null);
        p.set(0.75f);
        assertEquals(0.75f, p.getFloatValue()[0], 0.0001f);
    }

    @Test
    public void set_vec2_updatesValue() {
        CgMaterialProperty p = CgMaterialProperty.fromDecl("_UV", null, "vec2", null);
        p.set(0.1f, 0.9f);
        float[] val = p.getFloatValue();
        assertEquals(0.1f, val[0], 0.0001f);
        assertEquals(0.9f, val[1], 0.0001f);
    }

    @Test
    public void setInt_updatesIntValue() {
        CgMaterialProperty p = CgMaterialProperty.fromDecl("_N", null, "int", null);
        p.setInt(42);
        assertEquals(42, p.getIntValue());
    }

    @Test
    public void setRange_updatesBounds() {
        CgMaterialProperty p = CgMaterialProperty.fromDecl("_Speed", null, "Range", null);
        p.setRange(0.0f, 10.0f);
        assertEquals(0.0f, p.getRangeMin(), 0.0001f);
        assertEquals(10.0f, p.getRangeMax(), 0.0001f);
    }

    @Test
    public void set_range_clampsToMax() {
        CgMaterialProperty p = CgMaterialProperty.fromDecl("_Speed", null, "Range", null);
        p.setRange(0.0f, 10.0f);
        p.set(15.0f);
        assertEquals(10.0f, p.getFloatValue()[0], 0.0001f);
    }

    @Test
    public void set_range_clampsToMin() {
        CgMaterialProperty p = CgMaterialProperty.fromDecl("_Speed", null, "Range", null);
        p.setRange(0.0f, 10.0f);
        p.set(-3.0f);
        assertEquals(0.0f, p.getFloatValue()[0], 0.0001f);
    }

    @Test
    public void set_range_withinBounds_unchanged() {
        CgMaterialProperty p = CgMaterialProperty.fromDecl("_Speed", null, "Range", null);
        p.setRange(0.0f, 10.0f);
        p.set(5.5f);
        assertEquals(5.5f, p.getFloatValue()[0], 0.0001f);
    }

    // ── copyWithDefaults ──────────────────────────────────────────────────────

    @Test
    public void copyWithDefaults_producesIndependentInstance() {
        CgMaterialProperty orig = CgMaterialProperty.fromDecl("_Color", "Color", "vec4", "(0.5, 0.5, 0.5, 1.0)");
        CgMaterialProperty copy = orig.copyWithDefaults();
        assertNotSame("copyWithDefaults() must return a new instance", orig, copy);
    }

    @Test
    public void copyWithDefaults_copiesDeclarationFields() {
        CgMaterialProperty orig = CgMaterialProperty.fromDecl("_Alpha", "Opacity", "float", "0.7");
        CgMaterialProperty copy = orig.copyWithDefaults();
        assertEquals(orig.getName(), copy.getName());
        assertEquals(orig.getDisplayName(), copy.getDisplayName());
        assertEquals(orig.getType(), copy.getType());
        assertEquals(orig.getRawDefault(), copy.getRawDefault());
    }

    @Test
    public void copyWithDefaults_copyHasDefaultValue() {
        CgMaterialProperty orig = CgMaterialProperty.fromDecl("_Color", "_Color", "vec4", "(0.2, 0.4, 0.6, 1.0)");
        CgMaterialProperty copy = orig.copyWithDefaults();
        assertArrayEquals(new float[]{0.2f, 0.4f, 0.6f, 1.0f}, copy.getFloatValue(), 1e-4f);
    }

    @Test
    public void copyWithDefaults_mutatingCopyDoesNotAffectOriginal() {
        CgMaterialProperty orig = CgMaterialProperty.fromDecl("_Alpha", "_Alpha", "float", "0.5");
        CgMaterialProperty copy = orig.copyWithDefaults();
        copy.set(0.9f);
        assertEquals("Original must be unaffected by mutation of copy",
                0.5f, orig.getFloatValue()[0], 1e-4f);
    }

    @Test
    public void copyWithDefaults_mutatingOriginalDoesNotAffectCopy() {
        CgMaterialProperty orig = CgMaterialProperty.fromDecl("_Alpha", "_Alpha", "float", "0.5");
        CgMaterialProperty copy = orig.copyWithDefaults();
        orig.set(0.1f);
        assertEquals("Copy must be unaffected by mutation of original",
                0.5f, copy.getFloatValue()[0], 1e-4f);
    }

    @Test
    public void copyWithDefaults_rangeBoundsPreserved() {
        CgMaterialProperty orig = CgMaterialProperty.fromDecl("_Speed", "_Speed", "Range", "5.0");
        orig.setRange(0.0f, 10.0f);
        CgMaterialProperty copy = orig.copyWithDefaults();
        assertEquals("Range bounds min must be copied", 0.0f, copy.getRangeMin(), 1e-4f);
        assertEquals("Range bounds max must be copied", 10.0f, copy.getRangeMax(), 1e-4f);
        assertEquals("Default value within range must be preserved", 5.0f, copy.getFloatValue()[0], 1e-4f);
    }
}
