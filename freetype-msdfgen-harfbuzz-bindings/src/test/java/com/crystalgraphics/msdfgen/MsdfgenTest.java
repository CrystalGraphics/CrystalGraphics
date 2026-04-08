package com.crystalgraphics.msdfgen;

import org.junit.Test;
import static org.junit.Assert.*;
import com.crystalgraphics.NativeLoader;

public class MsdfgenTest {

    @Test
    public void testNativeLoaderPlatformDetection() {
        String os = NativeLoader.detectOS();
        String arch = NativeLoader.detectArch();
        assertNotNull(os);
        assertNotNull(arch);
        assertFalse(os.equals("unknown"));
        assertTrue(arch.equals("x64") || arch.equals("aarch64") || arch.equals("x86") || arch.length() > 0);
    }

    @Test
    public void testNativeLoaderLibraryMapping() {
        String libName = NativeLoader.getLibraryFileName();
        assertNotNull(libName);
        String os = NativeLoader.detectOS();
        if ("windows".equals(os)) {
            assertEquals("msdfgen-jni.dll", libName);
        } else if ("macos".equals(os)) {
            assertEquals("libmsdfgen-jni.dylib", libName);
        } else {
            assertEquals("libmsdfgen-jni.so", libName);
        }
    }

    @Test
    public void testMsdfResultDescriptions() {
        assertEquals("Success", MSDFResult.describe(MSDFResult.SUCCESS));
        assertEquals("Operation failed", MSDFResult.describe(MSDFResult.ERR_FAILED));
        assertEquals("Invalid argument", MSDFResult.describe(MSDFResult.ERR_INVALID_ARG));
        assertEquals("Invalid type", MSDFResult.describe(MSDFResult.ERR_INVALID_TYPE));
        assertEquals("Invalid size", MSDFResult.describe(MSDFResult.ERR_INVALID_SIZE));
        assertEquals("Invalid index", MSDFResult.describe(MSDFResult.ERR_INVALID_INDEX));
    }

    @Test
    public void testMsdfResultIsSuccess() {
        assertTrue(MSDFResult.isSuccess(MSDFResult.SUCCESS));
        assertFalse(MSDFResult.isSuccess(MSDFResult.ERR_FAILED));
    }

    @Test(expected = MSDFException.class)
    public void testMsdfResultCheckThrowsOnError() {
        MSDFResult.check(MSDFResult.ERR_INVALID_ARG);
    }

    @Test
    public void testMsdfExceptionContainsErrorCode() {
        MSDFException ex = new MSDFException(MSDFResult.ERR_INVALID_TYPE);
        assertEquals(MSDFResult.ERR_INVALID_TYPE, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("Invalid type"));
    }

    @Test
    public void testMsdfConstantsChannelCount() {
        assertEquals(1, MSDFConstants.channelCountForType(MSDFConstants.BITMAP_TYPE_SDF));
        assertEquals(1, MSDFConstants.channelCountForType(MSDFConstants.BITMAP_TYPE_PSDF));
        assertEquals(3, MSDFConstants.channelCountForType(MSDFConstants.BITMAP_TYPE_MSDF));
        assertEquals(4, MSDFConstants.channelCountForType(MSDFConstants.BITMAP_TYPE_MTSDF));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testMsdfConstantsInvalidType() {
        MSDFConstants.channelCountForType(99);
    }

    @Test
    public void testEdgeColorConstants() {
        assertEquals(0, MSDFEdgeColor.BLACK);
        assertEquals(7, MSDFEdgeColor.WHITE);
        assertEquals(1, MSDFEdgeColor.RED);
        assertEquals(2, MSDFEdgeColor.GREEN);
        assertEquals(4, MSDFEdgeColor.BLUE);
    }

    @Test
    public void testTransformBuilder() {
        MSDFTransform t = new MSDFTransform()
            .scale(32.0)
            .translate(0.5, 0.5)
            .range(-0.125, 0.125);

        assertEquals(32.0, t.getScaleX(), 1e-10);
        assertEquals(32.0, t.getScaleY(), 1e-10);
        assertEquals(0.5, t.getTranslateX(), 1e-10);
        assertEquals(0.5, t.getTranslateY(), 1e-10);
        assertEquals(-0.125, t.getRangeLower(), 1e-10);
        assertEquals(0.125, t.getRangeUpper(), 1e-10);
    }

    @Test
    public void testTransformSymmetricRange() {
        MSDFTransform t = new MSDFTransform().range(4.0);
        assertEquals(-4.0, t.getRangeLower(), 1e-10);
        assertEquals(4.0, t.getRangeUpper(), 1e-10);
    }

    @Test
    public void testSegmentTypeValidation() {
        assertTrue(MSDFSegmentType.isValid(MSDFSegmentType.LINEAR));
        assertTrue(MSDFSegmentType.isValid(MSDFSegmentType.QUADRATIC));
        assertTrue(MSDFSegmentType.isValid(MSDFSegmentType.CUBIC));
        assertFalse(MSDFSegmentType.isValid(-1));
        assertFalse(MSDFSegmentType.isValid(3));
        assertFalse(MSDFSegmentType.isValid(99));
    }

    @Test
    public void testSegmentTypePointCount() {
        assertEquals(2, MSDFSegmentType.pointCount(MSDFSegmentType.LINEAR));
        assertEquals(3, MSDFSegmentType.pointCount(MSDFSegmentType.QUADRATIC));
        assertEquals(4, MSDFSegmentType.pointCount(MSDFSegmentType.CUBIC));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSegmentTypeInvalidPointCount() {
        MSDFSegmentType.pointCount(99);
    }

    @Test
    public void testSegmentTypeName() {
        assertEquals("LINEAR", MSDFSegmentType.name(MSDFSegmentType.LINEAR));
        assertEquals("QUADRATIC", MSDFSegmentType.name(MSDFSegmentType.QUADRATIC));
        assertEquals("CUBIC", MSDFSegmentType.name(MSDFSegmentType.CUBIC));
        assertTrue(MSDFSegmentType.name(99).contains("UNKNOWN"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSegmentCreateInvalidType() {
        MSDFSegment.create(-1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSegmentCreateTooHighType() {
        MSDFSegment.create(3);
    }

    @Test
    public void testMsdfConstantsDefaults() {
        assertEquals(1.11111111111111111, MSDFConstants.DEFAULT_MIN_DEVIATION_RATIO, 1e-15);
        assertEquals(1.11111111111111111, MSDFConstants.DEFAULT_MIN_IMPROVE_RATIO, 1e-15);
    }

    @Test
    public void testMsdfConstantsBooleans() {
        assertEquals(0, MSDFConstants.MSDF_FALSE);
        assertEquals(1, MSDFConstants.MSDF_TRUE);
    }

    @Test
    public void testMsdfConstantsMaxTypes() {
        assertEquals(3, MSDFConstants.BITMAP_TYPE_MAX);
        assertEquals(2, MSDFConstants.SEGMENT_TYPE_MAX);
    }
}
