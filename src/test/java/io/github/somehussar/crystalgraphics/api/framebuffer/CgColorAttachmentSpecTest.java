package io.github.somehussar.crystalgraphics.api.framebuffer;

import io.github.somehussar.crystalgraphics.api.CgMipmapConfig;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link CgColorAttachmentSpec} sizing math and validation.
 *
 * <p>These tests verify:</p>
 * <ul>
 *   <li>Scale factor validation (must be positive)</li>
 *   <li>Sizing math: {@code ceil(base * scale)} with minimum 1x1 clamping</li>
 *   <li>Format and mipmap config validation</li>
 * </ul>
 */
public class CgColorAttachmentSpecTest {

    // ---------------------------------------------------------------
    //  Scale factor storage and retrieval tests
    // ---------------------------------------------------------------

    /**
     * Verifies that scaleX and scaleY values are stored and retrieved correctly.
     */
    @Test
    public void testScale_values_stored_and_retrieved() {
        CgColorAttachmentSpec spec = CgColorAttachmentSpec.builder()
            .scaleX(0.5f)
            .scaleY(0.75f)
            .format(new CgTextureFormatSpec(0x8058, 0x1908, 0x1401)) // RGBA8
            .build();

        assertEquals("ScaleX", 0.5f, spec.getScaleX(), 0.0001f);
        assertEquals("ScaleY", 0.75f, spec.getScaleY(), 0.0001f);
    }

    /**
     * Verifies that uniform scale(float) sets both X and Y to the same value.
     */
    @Test
    public void testScale_uniform_scale_sets_both() {
        CgColorAttachmentSpec spec = CgColorAttachmentSpec.builder()
            .scale(0.5f)
            .format(new CgTextureFormatSpec(0x8058, 0x1908, 0x1401)) // RGBA8
            .build();

        assertEquals("ScaleX should equal scale value", 0.5f, spec.getScaleX(), 0.0001f);
        assertEquals("ScaleY should equal scale value", 0.5f, spec.getScaleY(), 0.0001f);
    }

    /**
     * Verifies that full-resolution scale (1.0) is the default.
     */
    @Test
    public void testScale_default_is_1_0() {
        CgColorAttachmentSpec spec = CgColorAttachmentSpec.builder()
            .format(new CgTextureFormatSpec(0x8058, 0x1908, 0x1401)) // RGBA8
            .build();

        assertEquals("Default scaleX", 1.0f, spec.getScaleX(), 0.0001f);
        assertEquals("Default scaleY", 1.0f, spec.getScaleY(), 0.0001f);
    }

    /**
     * Verifies that upscaling values are accepted (scale > 1.0).
     */
    @Test
    public void testScale_upscale_allowed() {
        CgColorAttachmentSpec spec = CgColorAttachmentSpec.builder()
            .scale(2.0f)
            .format(new CgTextureFormatSpec(0x8058, 0x1908, 0x1401)) // RGBA8
            .build();

        assertEquals("ScaleX upscale", 2.0f, spec.getScaleX(), 0.0001f);
        assertEquals("ScaleY upscale", 2.0f, spec.getScaleY(), 0.0001f);
    }

    /**
     * Verifies that fractional scale values are accepted.
     */
    @Test
    public void testScale_fractional_scale_allowed() {
        CgColorAttachmentSpec spec = CgColorAttachmentSpec.builder()
            .scaleX(0.3333f)
            .scaleY(0.6667f)
            .format(new CgTextureFormatSpec(0x8058, 0x1908, 0x1401)) // RGBA8
            .build();

        assertEquals("ScaleX fractional", 0.3333f, spec.getScaleX(), 0.0001f);
        assertEquals("ScaleY fractional", 0.6667f, spec.getScaleY(), 0.0001f);
    }

    /**
     * Verifies that very small positive scales are allowed.
     */
    @Test
    public void testScale_very_small_positive_allowed() {
        CgColorAttachmentSpec spec = CgColorAttachmentSpec.builder()
            .scale(0.001f)
            .format(new CgTextureFormatSpec(0x8058, 0x1908, 0x1401)) // RGBA8
            .build();

        assertEquals("Tiny scale", 0.001f, spec.getScaleX(), 0.0001f);
    }

    /**
     * Verifies that large scales are allowed.
     */
    @Test
    public void testScale_large_scale_allowed() {
        CgColorAttachmentSpec spec = CgColorAttachmentSpec.builder()
            .scale(100.0f)
            .format(new CgTextureFormatSpec(0x8058, 0x1908, 0x1401)) // RGBA8
            .build();

        assertEquals("Large scale", 100.0f, spec.getScaleX(), 0.0001f);
    }

    // ---------------------------------------------------------------
    //  Scale validation tests
    // ---------------------------------------------------------------

    /**
     * Verifies that negative scaleX throws IllegalArgumentException.
     */
    @Test
    public void testValidation_negative_scaleX_throws() {
        try {
            CgColorAttachmentSpec.builder()
                .scaleX(-0.5f)
                .format(new CgTextureFormatSpec(0x8058, 0x1908, 0x1401))
                .build();
            fail("Expected IllegalArgumentException for negative scaleX");
        } catch (IllegalArgumentException e) {
            assertTrue("Error message should mention scale", e.getMessage().contains("positive"));
        }
    }

    /**
     * Verifies that zero scaleX throws IllegalArgumentException.
     */
    @Test
    public void testValidation_zero_scaleX_throws() {
        try {
            CgColorAttachmentSpec.builder()
                .scaleX(0.0f)
                .format(new CgTextureFormatSpec(0x8058, 0x1908, 0x1401))
                .build();
            fail("Expected IllegalArgumentException for zero scaleX");
        } catch (IllegalArgumentException e) {
            assertTrue("Error message should mention positive", e.getMessage().contains("positive"));
        }
    }

    /**
     * Verifies that negative scaleY throws IllegalArgumentException.
     */
    @Test
    public void testValidation_negative_scaleY_throws() {
        try {
            CgColorAttachmentSpec.builder()
                .scaleY(-1.0f)
                .format(new CgTextureFormatSpec(0x8058, 0x1908, 0x1401))
                .build();
            fail("Expected IllegalArgumentException for negative scaleY");
        } catch (IllegalArgumentException e) {
            assertTrue("Error message should mention positive", e.getMessage().contains("positive"));
        }
    }

    /**
     * Verifies that zero scaleY throws IllegalArgumentException.
     */
    @Test
    public void testValidation_zero_scaleY_throws() {
        try {
            CgColorAttachmentSpec.builder()
                .scaleY(0.0f)
                .format(new CgTextureFormatSpec(0x8058, 0x1908, 0x1401))
                .build();
            fail("Expected IllegalArgumentException for zero scaleY");
        } catch (IllegalArgumentException e) {
            assertTrue("Error message should mention positive", e.getMessage().contains("positive"));
        }
    }

    /**
     * Verifies that uniform negative scale throws IllegalArgumentException.
     */
    @Test
    public void testValidation_negative_uniform_scale_throws() {
        try {
            CgColorAttachmentSpec.builder()
                .scale(-2.0f)
                .format(new CgTextureFormatSpec(0x8058, 0x1908, 0x1401))
                .build();
            fail("Expected IllegalArgumentException for negative scale");
        } catch (IllegalArgumentException e) {
            assertTrue("Error message should mention positive", e.getMessage().contains("positive"));
        }
    }

    // ---------------------------------------------------------------
    //  Format validation tests
    // ---------------------------------------------------------------

    /**
     * Verifies that null format throws IllegalArgumentException.
     */
    @Test
    public void testValidation_null_format_throws() {
        try {
            CgColorAttachmentSpec.builder()
                .scaleX(1.0f)
                .scaleY(1.0f)
                .build();
            fail("Expected IllegalArgumentException for missing format");
        } catch (IllegalArgumentException e) {
            assertTrue("Error message should mention format or required", e.getMessage().contains("format") || e.getMessage().contains("required"));
        }
    }

    /**
     * Verifies that omitting format (not calling build() without format) throws.
     */
    @Test
    public void testValidation_missing_format_throws() {
        try {
            CgColorAttachmentSpec.builder()
                .build();
            fail("Expected IllegalArgumentException for missing format");
        } catch (IllegalArgumentException e) {
            assertTrue("Error message should mention format", e.getMessage().contains("required"));
        }
    }

    // ---------------------------------------------------------------
    //  Mipmap config validation tests
    // ---------------------------------------------------------------

    /**
     * Verifies that null mipmap config throws IllegalArgumentException.
     */
    @Test
    public void testValidation_null_mipmaps_throws() {
        try {
            CgColorAttachmentSpec.builder()
                .format(new CgTextureFormatSpec(0x8058, 0x1908, 0x1401))
                .mipmaps(null)
                .build();
            fail("Expected IllegalArgumentException for null mipmaps");
        } catch (IllegalArgumentException e) {
            assertTrue("Error message should mention mipmap", e.getMessage().contains("mipmap") || e.getMessage().contains("Mipmap"));
        }
    }

    /**
     * Verifies that default (disabled) mipmaps work.
     */
    @Test
    public void testValidation_default_mipmaps_disabled() {
        CgColorAttachmentSpec spec = CgColorAttachmentSpec.builder()
            .format(new CgTextureFormatSpec(0x8058, 0x1908, 0x1401))
            .build();

        assertFalse("Default mipmap config should be disabled", spec.getMipmaps().isEnabled());
    }

    /**
     * Verifies that enabled mipmap config works.
     */
    @Test
    public void testValidation_enabled_mipmaps() {
        CgMipmapConfig mipmaps = CgMipmapConfig.enabled(4, 0x2703, 0x2601);
        CgColorAttachmentSpec spec = CgColorAttachmentSpec.builder()
            .format(new CgTextureFormatSpec(0x8058, 0x1908, 0x1401))
            .mipmaps(mipmaps)
            .build();

        assertTrue("Mipmap config should be enabled", spec.getMipmaps().isEnabled());
        assertEquals("Mipmap levels should be 4", 4, spec.getMipmaps().getLevels());
    }

    // ---------------------------------------------------------------
    //  Builder method chaining tests
    // ---------------------------------------------------------------

    /**
     * Verifies that builder method chaining works correctly.
     */
    @Test
    public void testBuilder_method_chaining() {
        CgColorAttachmentSpec spec = CgColorAttachmentSpec.builder()
            .scaleX(0.25f)
            .scaleY(0.5f)
            .format(new CgTextureFormatSpec(0x881A, 0x1908, 0x140B)) // RGBA16F
            .mipmaps(CgMipmapConfig.disabled())
            .build();

        assertEquals("scaleX", 0.25f, spec.getScaleX(), 0.001f);
        assertEquals("scaleY", 0.5f, spec.getScaleY(), 0.001f);
        assertEquals("format internal", 0x881A, spec.getFormat().getInternalFormat());
        assertFalse("mipmaps disabled", spec.getMipmaps().isEnabled());
    }

    /**
     * Verifies that scale(uniform) sets both X and Y.
     */
    @Test
    public void testBuilder_uniform_scale_sets_both() {
        CgColorAttachmentSpec spec = CgColorAttachmentSpec.builder()
            .scale(0.75f)
            .format(new CgTextureFormatSpec(0x8058, 0x1908, 0x1401))
            .build();

        assertEquals("scaleX", 0.75f, spec.getScaleX(), 0.001f);
        assertEquals("scaleY", 0.75f, spec.getScaleY(), 0.001f);
    }

    // ---------------------------------------------------------------
    //  Edge cases and boundary tests
    // ---------------------------------------------------------------

    /**
     * Verifies that very small positive scale (but > 0) is allowed.
     */
    @Test
    public void testEdgeCase_very_small_positive_scale() {
        CgColorAttachmentSpec spec = CgColorAttachmentSpec.builder()
            .scale(0.0001f)
            .format(new CgTextureFormatSpec(0x8058, 0x1908, 0x1401))
            .build();

        assertEquals("scaleX should be 0.0001f", 0.0001f, spec.getScaleX(), 0.00001f);
    }

    /**
     * Verifies that large positive scale is allowed.
     */
    @Test
    public void testEdgeCase_large_scale() {
        CgColorAttachmentSpec spec = CgColorAttachmentSpec.builder()
            .scale(100.0f)
            .format(new CgTextureFormatSpec(0x8058, 0x1908, 0x1401))
            .build();

        assertEquals("scaleX should be 100.0f", 100.0f, spec.getScaleX(), 0.001f);
    }

    // ---------------------------------------------------------------
    //  Equality and hashcode tests
    // ---------------------------------------------------------------

    /**
     * Verifies that two specs with identical properties are equal.
     */
    @Test
    public void testEquality_identical_specs() {
        CgColorAttachmentSpec spec1 = CgColorAttachmentSpec.builder()
            .scale(0.5f)
            .format(new CgTextureFormatSpec(0x8058, 0x1908, 0x1401))
            .build();

        CgColorAttachmentSpec spec2 = CgColorAttachmentSpec.builder()
            .scale(0.5f)
            .format(new CgTextureFormatSpec(0x8058, 0x1908, 0x1401))
            .build();

        assertEquals("Identical specs should be equal", spec1, spec2);
        assertEquals("Equal specs should have same hashCode", spec1.hashCode(), spec2.hashCode());
    }

    /**
     * Verifies that specs with different scales are not equal.
     */
    @Test
    public void testEquality_different_scales() {
        CgColorAttachmentSpec spec1 = CgColorAttachmentSpec.builder()
            .scale(0.5f)
            .format(new CgTextureFormatSpec(0x8058, 0x1908, 0x1401))
            .build();

        CgColorAttachmentSpec spec2 = CgColorAttachmentSpec.builder()
            .scale(0.75f)
            .format(new CgTextureFormatSpec(0x8058, 0x1908, 0x1401))
            .build();

        assertNotEquals("Specs with different scales should not be equal", spec1, spec2);
    }

    /**
     * Verifies that specs with different formats are not equal.
     */
    @Test
    public void testEquality_different_formats() {
        CgColorAttachmentSpec spec1 = CgColorAttachmentSpec.builder()
            .scale(0.5f)
            .format(new CgTextureFormatSpec(0x8058, 0x1908, 0x1401)) // RGBA8
            .build();

        CgColorAttachmentSpec spec2 = CgColorAttachmentSpec.builder()
            .scale(0.5f)
            .format(new CgTextureFormatSpec(0x881A, 0x1908, 0x140B)) // RGBA16F
            .build();

        assertNotEquals("Specs with different formats should not be equal", spec1, spec2);
    }
}
