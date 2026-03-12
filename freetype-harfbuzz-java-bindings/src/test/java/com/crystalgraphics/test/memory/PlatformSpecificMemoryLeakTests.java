package com.crystalgraphics.test.memory;

import com.crystalgraphics.freetype.*;
import com.crystalgraphics.harfbuzz.*;
import com.crystalgraphics.text.FreeTypeHarfBuzzIntegration;
import org.junit.Test;
import static org.junit.Assert.*;

public class PlatformSpecificMemoryLeakTests extends MemoryLeakDetectionBase {

    @Test
    public void testH1_crossPlatformConsistency() {
        logTestStart();
        byte[] fontData = loadTestFontData();

        PlatformTools.Platform platform = PlatformTools.detectPlatform();
        String arch = PlatformTools.detectArch();
        System.out.println("  Platform: " + platform + " / " + arch);

        MemoryMetrics.Snapshot baseline = MemoryMetrics.takeSnapshot();

        FreeTypeLibrary ft = FreeTypeLibrary.create();
        int[] version = ft.getVersion();
        System.out.println("  FreeType version: " + version[0] + "." + version[1] + "." + version[2]);

        FTFace face = ft.newFaceFromMemory(fontData, 0);
        face.setPixelSizes(0, 24);

        String familyName = face.getFamilyName();
        int numGlyphs = face.getNumGlyphs();
        int unitsPerEM = face.getUnitsPerEM();
        int ascender = face.getAscender();
        int descender = face.getDescender();

        System.out.println("  Font: " + familyName + " (" + numGlyphs + " glyphs, " + unitsPerEM + " upem)");
        System.out.println("  Ascender: " + ascender + ", Descender: " + descender);

        HBFont hbFont = FreeTypeHarfBuzzIntegration.createHBFontFromFTFace(face);

        HBBuffer buffer = HBBuffer.create();
        buffer.addUTF8("Cross-platform consistency test string");
        buffer.guessSegmentProperties();
        HBShape.shape(hbFont, buffer);

        HBGlyphInfo[] infos = buffer.getGlyphInfos();
        HBGlyphPosition[] positions = buffer.getGlyphPositions();

        assertTrue("Should produce glyphs", infos.length > 0);
        assertEquals(infos.length, positions.length);

        int totalAdvance = 0;
        for (int i = 0; i < positions.length; i++) {
            totalAdvance += positions[i].getXAdvance();
        }
        System.out.println("  Shaped " + infos.length + " glyphs, total advance: " + (totalAdvance >> 6) + " px");

        long rssBeforeCleanup = PlatformTools.getProcessRSS();
        if (rssBeforeCleanup > 0) {
            System.out.println("  RSS before cleanup: " + MemoryMetrics.formatBytes(rssBeforeCleanup));
        }

        buffer.destroy();
        hbFont.destroy();
        face.destroy();
        ft.destroy();

        MemoryMetrics.Snapshot afterCleanup = MemoryMetrics.takeSnapshot();

        long rssAfterCleanup = PlatformTools.getProcessRSS();
        if (rssAfterCleanup > 0) {
            System.out.println("  RSS after cleanup: " + MemoryMetrics.formatBytes(rssAfterCleanup));
        }

        assertMemoryReturnsToBaseline(baseline, afterCleanup);

        // Log reference values for cross-platform comparison
        System.out.println("  === CROSS-PLATFORM REFERENCE VALUES ===");
        System.out.println("  Platform: " + platform + "-" + arch);
        System.out.println("  GlyphCount: " + infos.length);
        System.out.println("  TotalAdvance: " + totalAdvance);
        System.out.println("  HeapBaseline: " + MemoryMetrics.formatBytes(baseline.heapUsed));
        System.out.println("  HeapFinal: " + MemoryMetrics.formatBytes(afterCleanup.heapUsed));
    }

    @Test
    public void testH2_nativeArchitectureValidation() {
        logTestStart();

        PlatformTools.Platform platform = PlatformTools.detectPlatform();
        String arch = PlatformTools.detectArch();
        String nativePlatformId = com.crystalgraphics.freetype.NativeLoader.getPlatformIdentifier();
        String libraryFileName = com.crystalgraphics.freetype.NativeLoader.getLibraryFileName();

        System.out.println("  Detected platform: " + platform);
        System.out.println("  Detected arch: " + arch);
        System.out.println("  Native platform ID: " + nativePlatformId);
        System.out.println("  Library filename: " + libraryFileName);

        assertNotNull(nativePlatformId);
        assertTrue(nativePlatformId.contains("-"));

        switch (platform) {
            case WINDOWS:
                assertTrue("Library should be .dll", libraryFileName.endsWith(".dll"));
                assertFalse("Should not have lib prefix on Windows", libraryFileName.startsWith("lib"));
                break;
            case LINUX:
                assertTrue("Library should be .so", libraryFileName.endsWith(".so"));
                assertTrue("Should have lib prefix on Linux", libraryFileName.startsWith("lib"));
                break;
            case MACOS:
                assertTrue("Library should be .dylib", libraryFileName.endsWith(".dylib"));
                assertTrue("Should have lib prefix on macOS", libraryFileName.startsWith("lib"));
                break;
            default:
                fail("Unexpected platform: " + platform);
        }

        assertTrue("Arch should be x86_64 or aarch64",
                arch.equals("x86_64") || arch.equals("aarch64"));

        assertTrue("Native library should be loaded",
                com.crystalgraphics.freetype.NativeLoader.isLoaded());

        // Validate the loaded library actually works
        FreeTypeLibrary ft = FreeTypeLibrary.create();
        int[] version = ft.getVersion();
        assertTrue("FreeType major version should be >= 2", version[0] >= 2);
        ft.destroy();

        if (platform == PlatformTools.Platform.MACOS && arch.equals("aarch64")) {
            System.out.println("  APPLE SILICON DETECTED: Running native ARM64");
            System.out.println("  Verify with: file <path-to-dylib> should show arm64");
        } else if (platform == PlatformTools.Platform.MACOS && arch.equals("x86_64")) {
            System.out.println("  macOS Intel or Rosetta 2 detected");
        }

        System.out.println("  Architecture validation: PASSED");
    }
}
