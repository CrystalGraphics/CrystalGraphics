package io.github.somehussar.crystalgraphics.api.framebuffer;

import io.github.somehussar.crystalgraphics.api.CgMipmapConfig;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link CgFramebufferSpec} validation and construction.
 *
 * <p>These tests verify:</p>
 * <ul>
 *   <li>Base dimension validation (must be positive)</li>
 *   <li>Color attachment requirements (at least one required)</li>
 *   <li>Depth/stencil configuration validation</li>
 *   <li>Builder pattern enforcement</li>
 * </ul>
 */
public class CgFramebufferSpecTest {

    // Helper method to create a default RGBA8 attachment
    private CgColorAttachmentSpec defaultRgba8Attachment() {
        return CgColorAttachmentSpec.builder()
            .format(new CgTextureFormatSpec(0x8058, 0x1908, 0x1401)) // RGBA8
            .build();
    }

    // ---------------------------------------------------------------
    //  Base dimension validation tests
    // ---------------------------------------------------------------

    /**
     * Verifies that positive base dimensions are accepted.
     */
    @Test
    public void testValidation_positive_dimensions_accepted() {
        CgFramebufferSpec spec = CgFramebufferSpec.builder()
            .baseWidth(1024)
            .baseHeight(768)
            .addColorAttachment(defaultRgba8Attachment())
            .build();

        assertEquals("Base width", 1024, spec.getBaseWidth());
        assertEquals("Base height", 768, spec.getBaseHeight());
    }

    /**
     * Verifies that zero base width throws IllegalArgumentException.
     */
    @Test
    public void testValidation_zero_base_width_throws() {
        try {
            CgFramebufferSpec.builder()
                .baseWidth(0)
                .baseHeight(768)
                .addColorAttachment(defaultRgba8Attachment())
                .build();
            fail("Expected IllegalArgumentException for zero base width");
        } catch (IllegalArgumentException e) {
            assertTrue("Error message should mention positive", e.getMessage().contains("positive"));
        }
    }

    /**
     * Verifies that negative base width throws IllegalArgumentException.
     */
    @Test
    public void testValidation_negative_base_width_throws() {
        try {
            CgFramebufferSpec.builder()
                .baseWidth(-1024)
                .baseHeight(768)
                .addColorAttachment(defaultRgba8Attachment())
                .build();
            fail("Expected IllegalArgumentException for negative base width");
        } catch (IllegalArgumentException e) {
            assertTrue("Error message should mention positive", e.getMessage().contains("positive"));
        }
    }

    /**
     * Verifies that zero base height throws IllegalArgumentException.
     */
    @Test
    public void testValidation_zero_base_height_throws() {
        try {
            CgFramebufferSpec.builder()
                .baseWidth(1024)
                .baseHeight(0)
                .addColorAttachment(defaultRgba8Attachment())
                .build();
            fail("Expected IllegalArgumentException for zero base height");
        } catch (IllegalArgumentException e) {
            assertTrue("Error message should mention positive", e.getMessage().contains("positive"));
        }
    }

    /**
     * Verifies that negative base height throws IllegalArgumentException.
     */
    @Test
    public void testValidation_negative_base_height_throws() {
        try {
            CgFramebufferSpec.builder()
                .baseWidth(1024)
                .baseHeight(-768)
                .addColorAttachment(defaultRgba8Attachment())
                .build();
            fail("Expected IllegalArgumentException for negative base height");
        } catch (IllegalArgumentException e) {
            assertTrue("Error message should mention positive", e.getMessage().contains("positive"));
        }
    }

    /**
     * Verifies that unset base dimensions (build without setting them) throw.
     */
    @Test
    public void testValidation_unset_base_dimensions_throw() {
        try {
            CgFramebufferSpec.builder()
                .addColorAttachment(defaultRgba8Attachment())
                .build();
            fail("Expected IllegalArgumentException for unset base dimensions");
        } catch (IllegalArgumentException e) {
            assertTrue("Error message should mention base", e.getMessage().contains("base") || e.getMessage().contains("width") || e.getMessage().contains("height"));
        }
    }

    // ---------------------------------------------------------------
    //  Color attachment validation tests
    // ---------------------------------------------------------------

    /**
     * Verifies that at least one color attachment is required.
     */
    @Test
    public void testValidation_empty_color_attachments_throws() {
        try {
            CgFramebufferSpec.builder()
                .baseWidth(1024)
                .baseHeight(768)
                .build();
            fail("Expected IllegalArgumentException for no color attachments");
        } catch (IllegalArgumentException e) {
            assertTrue("Error message should mention attachment", e.getMessage().contains("attachment") || e.getMessage().contains("color"));
        }
    }

    /**
     * Verifies that one color attachment is sufficient.
     */
    @Test
    public void testValidation_single_color_attachment_ok() {
        CgFramebufferSpec spec = CgFramebufferSpec.builder()
            .baseWidth(1024)
            .baseHeight(768)
            .addColorAttachment(defaultRgba8Attachment())
            .build();

        assertEquals("Color attachment count", 1, spec.getColorAttachmentCount());
    }

    /**
     * Verifies that multiple color attachments are allowed.
     */
    @Test
    public void testValidation_multiple_color_attachments_ok() {
        CgFramebufferSpec spec = CgFramebufferSpec.builder()
            .baseWidth(1024)
            .baseHeight(768)
            .addColorAttachment(CgColorAttachmentSpec.builder()
                .format(new CgTextureFormatSpec(0x8058, 0x1908, 0x1401)) // RGBA8
                .build())
            .addColorAttachment(CgColorAttachmentSpec.builder()
                .scale(0.5f)
                .format(new CgTextureFormatSpec(0x881A, 0x1908, 0x140B)) // RGBA16F
                .build())
            .addColorAttachment(CgColorAttachmentSpec.builder()
                .scaleX(0.25f)
                .scaleY(0.25f)
                .format(new CgTextureFormatSpec(0x8F97, 0x1908, 0x1406)) // RGBA32F
                .build())
            .build();

        assertEquals("Color attachment count", 3, spec.getColorAttachmentCount());
    }

    /**
     * Verifies that null color attachment throws IllegalArgumentException.
     */
    @Test
    public void testValidation_null_color_attachment_throws() {
        try {
            CgFramebufferSpec.builder()
                .baseWidth(1024)
                .baseHeight(768)
                .addColorAttachment(null)
                .build();
            fail("Expected IllegalArgumentException for null color attachment");
        } catch (IllegalArgumentException e) {
            assertTrue("Error message should mention null", e.getMessage().contains("null") || e.getMessage().contains("must not be null"));
        }
    }

    /**
     * Verifies that color attachments can be retrieved by index.
     */
    @Test
    public void testValidation_color_attachment_access_by_index() {
        CgColorAttachmentSpec attach1 = CgColorAttachmentSpec.builder()
            .scale(1.0f)
            .format(new CgTextureFormatSpec(0x8058, 0x1908, 0x1401))
            .build();

        CgColorAttachmentSpec attach2 = CgColorAttachmentSpec.builder()
            .scale(0.5f)
            .format(new CgTextureFormatSpec(0x881A, 0x1908, 0x140B))
            .build();

        CgFramebufferSpec spec = CgFramebufferSpec.builder()
            .baseWidth(1024)
            .baseHeight(768)
            .addColorAttachment(attach1)
            .addColorAttachment(attach2)
            .build();

        assertEquals("First attachment by index", attach1, spec.getColorAttachment(0));
        assertEquals("Second attachment by index", attach2, spec.getColorAttachment(1));
    }

    /**
     * Verifies that getColorAttachments() returns an unmodifiable list.
     */
    @Test
    public void testValidation_color_attachments_list_is_unmodifiable() {
        CgFramebufferSpec spec = CgFramebufferSpec.builder()
            .baseWidth(1024)
            .baseHeight(768)
            .addColorAttachment(defaultRgba8Attachment())
            .build();

        try {
            spec.getColorAttachments().add(defaultRgba8Attachment());
            fail("Expected UnsupportedOperationException when modifying color attachments");
        } catch (UnsupportedOperationException e) {
            // Expected
        }
    }

    // ---------------------------------------------------------------
    //  Depth/stencil configuration validation tests
    // ---------------------------------------------------------------

    /**
     * Verifies that depth/stencil config defaults to "none" when not specified.
     */
    @Test
    public void testValidation_depth_stencil_defaults_to_none() {
        CgFramebufferSpec spec = CgFramebufferSpec.builder()
            .baseWidth(1024)
            .baseHeight(768)
            .addColorAttachment(defaultRgba8Attachment())
            .build();

        CgDepthStencilSpec ds = spec.getDepthStencil();
        assertFalse("Should have no depth by default", ds.hasDepth());
        assertFalse("Should have no stencil by default", ds.hasStencil());
    }

    /**
     * Verifies that null depth/stencil config throws IllegalArgumentException.
     */
    @Test
    public void testValidation_null_depth_stencil_throws() {
        try {
            CgFramebufferSpec.builder()
                .baseWidth(1024)
                .baseHeight(768)
                .addColorAttachment(defaultRgba8Attachment())
                .depthStencil(null)
                .build();
            fail("Expected IllegalArgumentException for null depth/stencil");
        } catch (IllegalArgumentException e) {
            assertTrue("Error message should mention depth or stencil", e.getMessage().contains("depth") || e.getMessage().contains("stencil") || e.getMessage().contains("null"));
        }
    }

    /**
     * Verifies that packed depth-stencil config works.
     */
    @Test
    public void testValidation_packed_depth_stencil_ok() {
        CgFramebufferSpec spec = CgFramebufferSpec.builder()
            .baseWidth(1024)
            .baseHeight(768)
            .addColorAttachment(defaultRgba8Attachment())
            .depthStencil(CgDepthStencilSpec.packedDepthStencil(0x88F0)) // GL_DEPTH24_STENCIL8
            .build();

        CgDepthStencilSpec ds = spec.getDepthStencil();
        assertTrue("Should have depth", ds.hasDepth());
        assertTrue("Should have stencil", ds.hasStencil());
        assertTrue("Should be packed", ds.isPacked());
    }

    /**
     * Verifies that separate depth-stencil config works.
     */
    @Test
    public void testValidation_separate_depth_stencil_ok() {
        CgFramebufferSpec spec = CgFramebufferSpec.builder()
            .baseWidth(1024)
            .baseHeight(768)
            .addColorAttachment(defaultRgba8Attachment())
            .depthStencil(CgDepthStencilSpec.separate(0x1902, 0x1901)) // GL_DEPTH_COMPONENT, GL_STENCIL_INDEX
            .build();

        CgDepthStencilSpec ds = spec.getDepthStencil();
        assertTrue("Should have depth", ds.hasDepth());
        assertTrue("Should have stencil", ds.hasStencil());
        assertFalse("Should not be packed", ds.isPacked());
    }

    /**
     * Verifies that depth-only config works.
     */
    @Test
    public void testValidation_depth_only_ok() {
        CgFramebufferSpec spec = CgFramebufferSpec.builder()
            .baseWidth(1024)
            .baseHeight(768)
            .addColorAttachment(defaultRgba8Attachment())
            .depthStencil(CgDepthStencilSpec.depthOnly(0x1902)) // GL_DEPTH_COMPONENT
            .build();

        CgDepthStencilSpec ds = spec.getDepthStencil();
        assertTrue("Should have depth", ds.hasDepth());
        assertFalse("Should not have stencil", ds.hasStencil());
    }

    /**
     * Verifies that stencil-only config works.
     */
    @Test
    public void testValidation_stencil_only_ok() {
        CgFramebufferSpec spec = CgFramebufferSpec.builder()
            .baseWidth(1024)
            .baseHeight(768)
            .addColorAttachment(defaultRgba8Attachment())
            .depthStencil(CgDepthStencilSpec.stencilOnly(0x1901)) // GL_STENCIL_INDEX
            .build();

        CgDepthStencilSpec ds = spec.getDepthStencil();
        assertFalse("Should not have depth", ds.hasDepth());
        assertTrue("Should have stencil", ds.hasStencil());
    }

    // ---------------------------------------------------------------
    //  Builder method tests
    // ---------------------------------------------------------------

    /**
     * Verifies that baseDimensions() sets both width and height.
     */
    @Test
    public void testBuilder_baseDimensions_sets_both() {
        CgFramebufferSpec spec = CgFramebufferSpec.builder()
            .baseDimensions(1024, 768)
            .addColorAttachment(defaultRgba8Attachment())
            .build();

        assertEquals("Width", 1024, spec.getBaseWidth());
        assertEquals("Height", 768, spec.getBaseHeight());
    }

    /**
     * Verifies that baseDimensions() validates dimensions.
     */
    @Test
    public void testBuilder_baseDimensions_validates_width() {
        try {
            CgFramebufferSpec.builder()
                .baseDimensions(0, 768)
                .addColorAttachment(defaultRgba8Attachment())
                .build();
            fail("Expected IllegalArgumentException for zero width in baseDimensions");
        } catch (IllegalArgumentException e) {
            assertTrue("Error message should mention positive", e.getMessage().contains("positive"));
        }
    }

    /**
     * Verifies that baseDimensions() validates height.
     */
    @Test
    public void testBuilder_baseDimensions_validates_height() {
        try {
            CgFramebufferSpec.builder()
                .baseDimensions(1024, 0)
                .addColorAttachment(defaultRgba8Attachment())
                .build();
            fail("Expected IllegalArgumentException for zero height in baseDimensions");
        } catch (IllegalArgumentException e) {
            assertTrue("Error message should mention positive", e.getMessage().contains("positive"));
        }
    }

    // ---------------------------------------------------------------
    //  Equality and immutability tests
    // ---------------------------------------------------------------

    /**
     * Verifies that two specs with identical properties are equal.
     */
    @Test
    public void testEquality_identical_specs() {
        CgColorAttachmentSpec attach = CgColorAttachmentSpec.builder()
            .scale(0.5f)
            .format(new CgTextureFormatSpec(0x8058, 0x1908, 0x1401))
            .build();

        CgFramebufferSpec spec1 = CgFramebufferSpec.builder()
            .baseWidth(1024)
            .baseHeight(768)
            .addColorAttachment(attach)
            .depthStencil(CgDepthStencilSpec.packedDepthStencil(0x88F0))
            .build();

        CgFramebufferSpec spec2 = CgFramebufferSpec.builder()
            .baseWidth(1024)
            .baseHeight(768)
            .addColorAttachment(attach)
            .depthStencil(CgDepthStencilSpec.packedDepthStencil(0x88F0))
            .build();

        assertEquals("Identical specs should be equal", spec1, spec2);
        assertEquals("Equal specs should have same hashCode", spec1.hashCode(), spec2.hashCode());
    }

    /**
     * Verifies that specs with different base widths are not equal.
     */
    @Test
    public void testEquality_different_width() {
        CgFramebufferSpec spec1 = CgFramebufferSpec.builder()
            .baseWidth(1024)
            .baseHeight(768)
            .addColorAttachment(defaultRgba8Attachment())
            .build();

        CgFramebufferSpec spec2 = CgFramebufferSpec.builder()
            .baseWidth(512)
            .baseHeight(768)
            .addColorAttachment(defaultRgba8Attachment())
            .build();

        assertNotEquals("Specs with different widths should not be equal", spec1, spec2);
    }

    /**
     * Verifies that specs with different base heights are not equal.
     */
    @Test
    public void testEquality_different_height() {
        CgFramebufferSpec spec1 = CgFramebufferSpec.builder()
            .baseWidth(1024)
            .baseHeight(768)
            .addColorAttachment(defaultRgba8Attachment())
            .build();

        CgFramebufferSpec spec2 = CgFramebufferSpec.builder()
            .baseWidth(1024)
            .baseHeight(512)
            .addColorAttachment(defaultRgba8Attachment())
            .build();

        assertNotEquals("Specs with different heights should not be equal", spec1, spec2);
    }

    /**
     * Verifies that specs with different color attachments are not equal.
     */
    @Test
    public void testEquality_different_attachments() {
        CgFramebufferSpec spec1 = CgFramebufferSpec.builder()
            .baseWidth(1024)
            .baseHeight(768)
            .addColorAttachment(defaultRgba8Attachment())
            .build();

        CgFramebufferSpec spec2 = CgFramebufferSpec.builder()
            .baseWidth(1024)
            .baseHeight(768)
            .addColorAttachment(defaultRgba8Attachment())
            .addColorAttachment(defaultRgba8Attachment())
            .build();

        assertNotEquals("Specs with different attachment counts should not be equal", spec1, spec2);
    }

    /**
     * Verifies that specs with different depth/stencil configs are not equal.
     */
    @Test
    public void testEquality_different_depth_stencil() {
        CgFramebufferSpec spec1 = CgFramebufferSpec.builder()
            .baseWidth(1024)
            .baseHeight(768)
            .addColorAttachment(defaultRgba8Attachment())
            .depthStencil(CgDepthStencilSpec.depthOnly(0x1902))
            .build();

        CgFramebufferSpec spec2 = CgFramebufferSpec.builder()
            .baseWidth(1024)
            .baseHeight(768)
            .addColorAttachment(defaultRgba8Attachment())
            .depthStencil(CgDepthStencilSpec.stencilOnly(0x1901))
            .build();

        assertNotEquals("Specs with different depth/stencil configs should not be equal", spec1, spec2);
    }

    // ---------------------------------------------------------------
    //  Edge cases
    // ---------------------------------------------------------------

    /**
     * Verifies that minimum dimensions (1x1) are allowed.
     */
    @Test
    public void testEdgeCase_minimum_dimensions_1x1() {
        CgFramebufferSpec spec = CgFramebufferSpec.builder()
            .baseWidth(1)
            .baseHeight(1)
            .addColorAttachment(defaultRgba8Attachment())
            .build();

        assertEquals("Width", 1, spec.getBaseWidth());
        assertEquals("Height", 1, spec.getBaseHeight());
    }

    /**
     * Verifies that large dimensions are allowed.
     */
    @Test
    public void testEdgeCase_large_dimensions() {
        CgFramebufferSpec spec = CgFramebufferSpec.builder()
            .baseWidth(16384)
            .baseHeight(16384)
            .addColorAttachment(defaultRgba8Attachment())
            .build();

        assertEquals("Width", 16384, spec.getBaseWidth());
        assertEquals("Height", 16384, spec.getBaseHeight());
    }

    /**
     * Verifies that many color attachments can be added.
     */
    @Test
    public void testEdgeCase_many_color_attachments() {
        CgFramebufferSpec.Builder builder = CgFramebufferSpec.builder()
            .baseWidth(1024)
            .baseHeight(768);

        for (int i = 0; i < 16; i++) {
            builder.addColorAttachment(defaultRgba8Attachment());
        }

        CgFramebufferSpec spec = builder.build();
        assertEquals("Color attachment count", 16, spec.getColorAttachmentCount());
    }
}
