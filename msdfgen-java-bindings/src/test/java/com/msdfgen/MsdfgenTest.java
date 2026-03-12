package com.msdfgen;

import org.junit.Test;
import static org.junit.Assert.*;

public class MsdfgenTest {

    @Test
    public void testNativeLoaderPlatformDetection() {
        String os = NativeLoader.getOsName();
        String arch = NativeLoader.getArchName();
        assertNotNull(os);
        assertNotNull(arch);
        assertFalse(os.equals("unknown"));
        assertTrue(arch.equals("x64") || arch.equals("aarch64") || arch.equals("x86") || arch.length() > 0);
    }

    @Test
    public void testNativeLoaderLibraryMapping() {
        String libName = NativeLoader.mapLibraryName("msdfgen-jni");
        assertNotNull(libName);
        String os = NativeLoader.getOsName();
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
        assertEquals("Success", MsdfResult.describe(MsdfResult.SUCCESS));
        assertEquals("Operation failed", MsdfResult.describe(MsdfResult.ERR_FAILED));
        assertEquals("Invalid argument", MsdfResult.describe(MsdfResult.ERR_INVALID_ARG));
        assertEquals("Invalid type", MsdfResult.describe(MsdfResult.ERR_INVALID_TYPE));
        assertEquals("Invalid size", MsdfResult.describe(MsdfResult.ERR_INVALID_SIZE));
        assertEquals("Invalid index", MsdfResult.describe(MsdfResult.ERR_INVALID_INDEX));
    }

    @Test
    public void testMsdfResultIsSuccess() {
        assertTrue(MsdfResult.isSuccess(MsdfResult.SUCCESS));
        assertFalse(MsdfResult.isSuccess(MsdfResult.ERR_FAILED));
    }

    @Test(expected = MsdfException.class)
    public void testMsdfResultCheckThrowsOnError() {
        MsdfResult.check(MsdfResult.ERR_INVALID_ARG);
    }

    @Test
    public void testMsdfExceptionContainsErrorCode() {
        MsdfException ex = new MsdfException(MsdfResult.ERR_INVALID_TYPE);
        assertEquals(MsdfResult.ERR_INVALID_TYPE, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("Invalid type"));
    }

    @Test
    public void testMsdfConstantsChannelCount() {
        assertEquals(1, MsdfConstants.channelCountForType(MsdfConstants.BITMAP_TYPE_SDF));
        assertEquals(1, MsdfConstants.channelCountForType(MsdfConstants.BITMAP_TYPE_PSDF));
        assertEquals(3, MsdfConstants.channelCountForType(MsdfConstants.BITMAP_TYPE_MSDF));
        assertEquals(4, MsdfConstants.channelCountForType(MsdfConstants.BITMAP_TYPE_MTSDF));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testMsdfConstantsInvalidType() {
        MsdfConstants.channelCountForType(99);
    }

    @Test
    public void testEdgeColorConstants() {
        assertEquals(0, EdgeColor.BLACK);
        assertEquals(7, EdgeColor.WHITE);
        assertEquals(1, EdgeColor.RED);
        assertEquals(2, EdgeColor.GREEN);
        assertEquals(4, EdgeColor.BLUE);
    }

    @Test
    public void testTransformBuilder() {
        Transform t = new Transform()
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
        Transform t = new Transform().range(4.0);
        assertEquals(-4.0, t.getRangeLower(), 1e-10);
        assertEquals(4.0, t.getRangeUpper(), 1e-10);
    }

    @Test
    public void testSegmentTypeValidation() {
        assertTrue(SegmentType.isValid(SegmentType.LINEAR));
        assertTrue(SegmentType.isValid(SegmentType.QUADRATIC));
        assertTrue(SegmentType.isValid(SegmentType.CUBIC));
        assertFalse(SegmentType.isValid(-1));
        assertFalse(SegmentType.isValid(3));
        assertFalse(SegmentType.isValid(99));
    }

    @Test
    public void testSegmentTypePointCount() {
        assertEquals(2, SegmentType.pointCount(SegmentType.LINEAR));
        assertEquals(3, SegmentType.pointCount(SegmentType.QUADRATIC));
        assertEquals(4, SegmentType.pointCount(SegmentType.CUBIC));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSegmentTypeInvalidPointCount() {
        SegmentType.pointCount(99);
    }

    @Test
    public void testSegmentTypeName() {
        assertEquals("LINEAR", SegmentType.name(SegmentType.LINEAR));
        assertEquals("QUADRATIC", SegmentType.name(SegmentType.QUADRATIC));
        assertEquals("CUBIC", SegmentType.name(SegmentType.CUBIC));
        assertTrue(SegmentType.name(99).contains("UNKNOWN"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSegmentCreateInvalidType() {
        Segment.create(-1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSegmentCreateTooHighType() {
        Segment.create(3);
    }

    @Test
    public void testMsdfConstantsDefaults() {
        assertEquals(1.11111111111111111, MsdfConstants.DEFAULT_MIN_DEVIATION_RATIO, 1e-15);
        assertEquals(1.11111111111111111, MsdfConstants.DEFAULT_MIN_IMPROVE_RATIO, 1e-15);
    }

    @Test
    public void testMsdfConstantsBooleans() {
        assertEquals(0, MsdfConstants.MSDF_FALSE);
        assertEquals(1, MsdfConstants.MSDF_TRUE);
    }

    @Test
    public void testMsdfConstantsMaxTypes() {
        assertEquals(3, MsdfConstants.BITMAP_TYPE_MAX);
        assertEquals(2, MsdfConstants.SEGMENT_TYPE_MAX);
    }
}
