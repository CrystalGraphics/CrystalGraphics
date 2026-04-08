package com.crystalgraphics.examples;

import com.crystalgraphics.freetype.*;
import com.crystalgraphics.harfbuzz.*;
import com.crystalgraphics.text.FreeTypeHarfBuzzIntegration;

/**
 * Example showing how CrystalGraphics would use FreeType + HarfBuzz for
 * text rendering in Minecraft 1.7.10. This demonstrates the complete pipeline:
 * load font -> shape text -> rasterize glyphs -> produce positioned bitmap data.
 */
public class CrystalGraphicsTextExample {

    public static void renderTextToConsole(String fontPath, String text, int pixelSize) {
        FreeTypeLibrary ftLib = FreeTypeLibrary.create();
        try {
            System.out.println("FreeType version: " + ftLib.getVersionString());

            FTFace face = ftLib.newFace(fontPath, 0);
            face.setPixelSizes(0, pixelSize);
            System.out.println("Font: " + face.getFamilyName() + " " + face.getStyleName());
            System.out.println("Glyphs: " + face.getNumGlyphs() + ", UnitsPerEM: " + face.getUnitsPerEM());

            HBFont hbFont = FreeTypeHarfBuzzIntegration.createHBFontFromFTFace(face);

            HBBuffer buffer = HBBuffer.create();
            buffer.addUTF8(text);
            buffer.guessSegmentProperties();

            HBShape.shape(hbFont, buffer);

            HBGlyphInfo[] infos = buffer.getGlyphInfos();
            HBGlyphPosition[] positions = buffer.getGlyphPositions();

            System.out.println("\nShaped " + infos.length + " glyphs:");
            int totalAdvance = 0;
            for (int i = 0; i < infos.length; i++) {
                int glyphId = infos[i].getCodepoint();
                int cluster = infos[i].getCluster();
                int xAdv = positions[i].getXAdvance();
                int xOff = positions[i].getXOffset();
                int yOff = positions[i].getYOffset();

                face.loadGlyph(glyphId, FTLoadFlags.FT_LOAD_RENDER);
                FTBitmap bitmap = face.getGlyphBitmap();
                FTGlyphMetrics metrics = face.getGlyphMetrics();

                System.out.printf("  [%d] glyph=%d cluster=%d advance=%d offset=(%d,%d) bitmap=%dx%d%n",
                        i, glyphId, cluster, xAdv >> 6, xOff >> 6, yOff >> 6,
                        bitmap.getWidth(), bitmap.getHeight());

                totalAdvance += xAdv >> 6;
            }

            System.out.println("\nTotal text width: " + totalAdvance + " pixels");

            buffer.destroy();
            hbFont.destroy();
            face.destroy();
        } finally {
            ftLib.destroy();
        }
    }

    /**
     * Example: How to integrate with CrystalGraphics' OpenGL rendering.
     * This would be called from a Minecraft mod's render loop.
     */
    public static int[] renderTextToGLTexture(String fontPath, String text, int pixelSize) {
        FreeTypeLibrary ftLib = FreeTypeLibrary.create();
        FTFace face = ftLib.newFace(fontPath, 0);
        face.setPixelSizes(0, pixelSize);

        HBFont hbFont = FreeTypeHarfBuzzIntegration.createHBFontFromFTFace(face);
        HBBuffer buffer = HBBuffer.create();
        buffer.addUTF8(text);
        buffer.guessSegmentProperties();
        HBShape.shape(hbFont, buffer);

        HBGlyphInfo[] infos = buffer.getGlyphInfos();
        HBGlyphPosition[] positions = buffer.getGlyphPositions();

        int totalWidth = 0;
        for (HBGlyphPosition pos : positions) {
            totalWidth += pos.getXAdvance() >> 6;
        }
        int textureHeight = pixelSize * 2;
        int baseline = pixelSize;

        byte[] textureData = new byte[totalWidth * textureHeight];

        int cursorX = 0;
        for (int i = 0; i < infos.length; i++) {
            face.loadGlyph(infos[i].getCodepoint(), FTLoadFlags.FT_LOAD_RENDER);
            FTBitmap bitmap = face.getGlyphBitmap();

            int drawX = cursorX + (positions[i].getXOffset() >> 6) + bitmap.getLeft();
            int drawY = baseline - (positions[i].getYOffset() >> 6) - bitmap.getTop();

            byte[] glyphPixels = bitmap.getBuffer();
            for (int row = 0; row < bitmap.getHeight(); row++) {
                int srcOffset = row * Math.abs(bitmap.getPitch());
                int dstY = drawY + row;
                if (dstY < 0 || dstY >= textureHeight) continue;
                for (int col = 0; col < bitmap.getWidth(); col++) {
                    int dstX = drawX + col;
                    if (dstX < 0 || dstX >= totalWidth) continue;
                    int alpha = glyphPixels[srcOffset + col] & 0xFF;
                    int existing = textureData[dstY * totalWidth + dstX] & 0xFF;
                    textureData[dstY * totalWidth + dstX] = (byte) Math.min(255, existing + alpha);
                }
            }

            cursorX += positions[i].getXAdvance() >> 6;
        }

        buffer.destroy();
        hbFont.destroy();
        face.destroy();
        ftLib.destroy();

        return new int[] { totalWidth, textureHeight };
    }
}
