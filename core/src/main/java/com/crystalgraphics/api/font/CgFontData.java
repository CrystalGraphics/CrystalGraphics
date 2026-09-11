package com.crystalgraphics.api.font;

import com.crystalgraphics.freetype.FTFace;
import com.crystalgraphics.freetype.FTFontData;
import com.crystalgraphics.freetype.FreeTypeLibrary;
import com.crystalgraphics.msdfgen.FreeTypeMSDFIntegration;
import com.crystalgraphics.text.font.Sfnt;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where a font's data lives: bytes in memory, or a file the natives open themselves. A font, every
 * size of it and every glyph worker share one, and so share what the natives read — an installed face
 * from its file, a font held in memory from a single native copy — instead of copying the font once
 * per size and once per worker.
 *
 * <pre>{@code
 * CgFontData data = font.getData();
 * FTFace face = data.openFace(library);                          // the file, or the one native copy
 * FreeTypeMSDFIntegration.Font outlines = data.openMsdfFont(msdf);
 * byte[] bytes = data.bytes();                                   // a standalone font; a file is read once
 * }</pre>
 *
 * <ul>
 *   <li>{@link #bytes()} is always one standalone font — a collection face extracted — so it opens
 *       as face 0. {@link #faceIndex()} is the face within {@link #file()}.</li>
 *   <li>A file stays open while a face on it is, without stopping it being deleted: an installed
 *       font can be uninstalled or replaced while the game runs.</li>
 *   <li>Opening from the file falls back to the bytes when the natives cannot open it.</li>
 * </ul>
 */
public final class CgFontData {

    private final Path file;
    private final int faceIndex;
    private volatile byte[] bytes;
    /** Made by the first face opened from memory, and read by every one after it. Guarded by this. */
    private FTFontData nativeCopy;

    private CgFontData(Path file, int faceIndex, byte[] bytes) {
        this.file = file;
        this.faceIndex = faceIndex;
        this.bytes = bytes;
    }

    /** Bytes that already stand alone as one font. */
    static CgFontData ofBytes(byte[] standaloneFont) {
        if (standaloneFont == null || standaloneFont.length == 0) {
            throw new IllegalArgumentException("font bytes must not be null or empty");
        }
        return new CgFontData(null, 0, standaloneFont);
    }

    /** Face {@code faceIndex} of a font file, opened from disk; its bytes are read only if asked for. */
    static CgFontData ofFile(Path file, int faceIndex) {
        if (file == null) {
            throw new IllegalArgumentException("file must not be null");
        }
        if (faceIndex < 0) {
            throw new IllegalArgumentException("faceIndex must be >= 0, got: " + faceIndex);
        }
        return new CgFontData(file, faceIndex, null);
    }

    /** The file natives open directly, or {@code null} for a font held in memory. */
    public Path file() {
        return file;
    }

    /** The face within {@link #file()}; 0 for a font held in memory. */
    public int faceIndex() {
        return faceIndex;
    }

    /**
     * The font as standalone bytes. For a file, read and extracted on the first call and kept.
     *
     * @throws UncheckedIOException when the file can no longer be read
     */
    public byte[] bytes() {
        byte[] local = bytes;
        if (local == null) {
            synchronized (this) {
                local = bytes;
                if (local == null) {
                    try {
                        local = Sfnt.extractFace(Files.readAllBytes(file), faceIndex);
                    } catch (IOException e) {
                        throw new UncheckedIOException("Cannot read font file " + file, e);
                    }
                    bytes = local;
                }
            }
        }
        return local;
    }

    /** A FreeType face on this font: from the file where there is one, from the native copy otherwise. */
    public FTFace openFace(FreeTypeLibrary library) {
        if (file != null) {
            try {
                return library.newFace(file.toString(), faceIndex);
            } catch (RuntimeException unreadableByNatives) {
                // the copy below reads the same face through Java
            }
        }
        return library.newFaceFromFontData(nativeCopy(), 0);
    }

    /** An msdfgen font on this font, likewise. */
    public FreeTypeMSDFIntegration.Font openMsdfFont(FreeTypeMSDFIntegration msdf) {
        if (file != null) {
            try {
                return msdf.loadFont(file.toString(), faceIndex);
            } catch (RuntimeException unreadableByNatives) {
                // the copy below reads the same face through Java
            }
        }
        return msdf.loadFontData(nativeCopy(), 0);
    }

    private synchronized FTFontData nativeCopy() {
        if (nativeCopy == null) {
            nativeCopy = FTFontData.copyOf(bytes());
        }
        return nativeCopy;
    }

    @Override
    public String toString() {
        return file != null ? "CgFontData{" + file + "#" + faceIndex + "}" : "CgFontData{" + bytes.length + " bytes}";
    }
}
