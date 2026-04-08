package com.crystalgraphics.freetype;

import org.junit.Test;
import static org.junit.Assert.*;

public class FreeTypeTest {

    private void requireNativesAvailable() {
        try {
            NativeLoader.ensureLoaded();
        } catch (UnsatisfiedLinkError e) {
            fail("Native library not available: " + e.getMessage());
        }
    }

    @Test
    public void testPlatformDetection() {
        String platform = NativeLoader.getPlatformIdentifier();
        assertNotNull(platform);
        assertTrue(platform.contains("-"));
        String[] parts = platform.split("-");
        assertEquals(2, parts.length);
        assertTrue("OS should be windows, linux, or macos",
                parts[0].equals("windows") || parts[0].equals("linux") || parts[0].equals("macos"));
        assertTrue("Arch should be x64 or aarch64",
                parts[1].equals("x64") || parts[1].equals("aarch64"));
    }

    @Test
    public void testLibraryFileName() {
        String filename = NativeLoader.getLibraryFileName();
        assertNotNull(filename);
        assertTrue(filename.contains("freetype_msdfgen_harfbuzz_jni"));
    }

    @Test
    public void testFreeTypeLibraryCreateDestroy() {
        requireNativesAvailable();

        FreeTypeLibrary lib = FreeTypeLibrary.create();
        assertNotNull(lib);
        assertFalse(lib.isDestroyed());

        int[] version = lib.getVersion();
        assertEquals(3, version.length);
        assertTrue("FreeType major version should be >= 2", version[0] >= 2);

        String versionStr = lib.getVersionString();
        assertNotNull(versionStr);
        assertTrue(versionStr.matches("\\d+\\.\\d+\\.\\d+"));

        lib.destroy();
        assertTrue(lib.isDestroyed());
    }

    @Test(expected = IllegalStateException.class)
    public void testUseAfterDestroy() {
        requireNativesAvailable();

        FreeTypeLibrary lib = FreeTypeLibrary.create();
        lib.destroy();
        lib.getVersion();
    }

    @Test
    public void testLoadFontFromResource() {
        requireNativesAvailable();

        java.io.InputStream is = getClass().getResourceAsStream("/test-font.ttf");
        assertNotNull("Test font not found", is);

        FreeTypeLibrary lib = FreeTypeLibrary.create();
        try {
            byte[] fontData = readAllBytes(is);
            FTFace face = lib.newFaceFromMemory(fontData, 0);
            assertNotNull(face);
            assertFalse(face.isDestroyed());

            String familyName = face.getFamilyName();
            assertNotNull(familyName);

            assertTrue(face.getNumGlyphs() > 0);
            assertTrue(face.getUnitsPerEM() > 0);

            face.setPixelSizes(0, 24);

            int glyphIndex = face.getCharIndex('A');
            assertTrue("Glyph index for 'A' should be > 0", glyphIndex > 0);

            face.loadGlyph(glyphIndex, FTLoadFlags.FT_LOAD_DEFAULT);
            FTGlyphMetrics metrics = face.getGlyphMetrics();
            assertNotNull(metrics);
            assertTrue(metrics.getWidth() > 0);
            assertTrue(metrics.getHeight() > 0);

            face.renderGlyph(FTRenderMode.FT_RENDER_MODE_NORMAL);
            FTBitmap bitmap = face.getGlyphBitmap();
            assertNotNull(bitmap);
            assertTrue(bitmap.getWidth() > 0);
            assertTrue(bitmap.getHeight() > 0);
            assertNotNull(bitmap.getBuffer());
            assertEquals(FTBitmap.PIXEL_MODE_GRAY, bitmap.getPixelMode());

            face.destroy();
            assertTrue(face.isDestroyed());
        } finally {
            lib.destroy();
        }
    }

    @Test
    public void testErrorCodes() {
        assertEquals("No error", FTErrors.getMessage(FTErrors.FT_Err_Ok));
        assertEquals("Out of memory", FTErrors.getMessage(FTErrors.FT_Err_Out_Of_Memory));
        assertTrue(FTErrors.getMessage(0xFF).contains("Unknown"));
    }

    private static byte[] readAllBytes(java.io.InputStream is) {
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            is.close();
            return bos.toByteArray();
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }
}
