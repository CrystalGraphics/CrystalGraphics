package com.crystalgraphics.harfbuzz;

import com.crystalgraphics.NativeLoader;
import org.junit.Test;
import static org.junit.Assert.*;


public class HarfBuzzTest {

    private void requireNativesAvailable() {
        try {
            NativeLoader.ensureLoaded();
        } catch (UnsatisfiedLinkError e) {
            fail("Native library not available: " + e.getMessage());
        }
    }

    @Test
    public void testBufferCreateDestroy() {
        requireNativesAvailable();

        HBBuffer buffer = HBBuffer.create();
        assertNotNull(buffer);
        assertFalse(buffer.isDestroyed());

        buffer.destroy();
        assertTrue(buffer.isDestroyed());
    }

    @Test
    public void testBufferAddText() {
        requireNativesAvailable();

        HBBuffer buffer = HBBuffer.create();
        buffer.addUTF8("Hello, World!");
        assertTrue(buffer.getLength() > 0);
        buffer.destroy();
    }

    @Test
    public void testDirectionConstants() {
        assertTrue(HBDirection.isHorizontal(HBDirection.HB_DIRECTION_LTR));
        assertTrue(HBDirection.isHorizontal(HBDirection.HB_DIRECTION_RTL));
        assertFalse(HBDirection.isHorizontal(HBDirection.HB_DIRECTION_TTB));
        assertTrue(HBDirection.isForward(HBDirection.HB_DIRECTION_LTR));
        assertTrue(HBDirection.isBackward(HBDirection.HB_DIRECTION_RTL));
    }

    @Test
    public void testScriptTag() {
        int latin = HBScript.tag('L', 'a', 't', 'n');
        assertEquals(HBScript.HB_SCRIPT_LATIN, latin);

        int arabic = HBScript.tag('A', 'r', 'a', 'b');
        assertEquals(HBScript.HB_SCRIPT_ARABIC, arabic);
    }

    @Test
    public void testBufferDirectionAndScript() {
        requireNativesAvailable();

        HBBuffer buffer = HBBuffer.create();
        buffer.addUTF8("Test");
        buffer.setDirection(HBDirection.HB_DIRECTION_LTR);
        buffer.setScript(HBScript.HB_SCRIPT_LATIN);

        assertEquals(HBDirection.HB_DIRECTION_LTR, buffer.getDirection());
        assertEquals(HBScript.HB_SCRIPT_LATIN, buffer.getScript());

        buffer.destroy();
    }

    @Test
    public void testShapeWithFont() {
        requireNativesAvailable();

        java.io.InputStream is = getClass().getResourceAsStream("/test-font.ttf");
        assertNotNull("Test font not found", is);

        String fontPath = extractToTempFile(is);
        HBFont font = HBFont.createFromFile(fontPath, 0);
        assertNotNull(font);

        HBBuffer buffer = HBBuffer.create();
        buffer.addUTF8("Hello");
        buffer.setDirection(HBDirection.HB_DIRECTION_LTR);
        buffer.setScript(HBScript.HB_SCRIPT_LATIN);
        buffer.setLanguage("en");

        HBShape.shape(font, buffer);

        HBGlyphInfo[] infos = buffer.getGlyphInfos();
        HBGlyphPosition[] positions = buffer.getGlyphPositions();

        assertNotNull(infos);
        assertNotNull(positions);
        assertTrue(infos.length > 0);
        assertEquals(infos.length, positions.length);

        buffer.destroy();
        font.destroy();
    }

    @Test(expected = IllegalStateException.class)
    public void testBufferUseAfterDestroy() {
        requireNativesAvailable();

        HBBuffer buffer = HBBuffer.create();
        buffer.destroy();
        buffer.addUTF8("fail");
    }

    private String extractToTempFile(java.io.InputStream is) {
        try {
            java.io.File tempFile = java.io.File.createTempFile("test-font-", ".ttf");
            tempFile.deleteOnExit();
            java.io.FileOutputStream fos = new java.io.FileOutputStream(tempFile);
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) {
                fos.write(buf, 0, n);
            }
            fos.close();
            is.close();
            return tempFile.getAbsolutePath();
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }
}
