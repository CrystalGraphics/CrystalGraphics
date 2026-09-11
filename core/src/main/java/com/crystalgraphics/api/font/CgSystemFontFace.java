package com.crystalgraphics.api.font;

import com.crystalgraphics.text.font.Sfnt;

import java.nio.file.Path;
import java.util.List;

/**
 * One installed font face as {@link CgSystemFonts} indexed it: where it is, what it is called, how
 * heavy it is and which characters it draws. Nothing native is open until
 * {@link CgSystemFonts#load} is called.
 *
 * <pre>{@code
 * CgSystemFontFace face = CgSystemFonts.get().find("Meiryo UI", CgFontStyle.REGULAR);
 * face.getPath();        // C:\Windows\Fonts\meiryo.ttc
 * face.getFaceIndex();   // 2
 * face.covers(0x3042);   // あ -> true
 * }</pre>
 *
 * <p>Equal by {@code (path, faceIndex)}.</p>
 */
public final class CgSystemFontFace {

    private final Path path;
    private final Sfnt.Face face;

    CgSystemFontFace(Path path, Sfnt.Face face) {
        this.path = path;
        this.face = face;
    }

    public Path getPath() {
        return path;
    }

    public int getFaceIndex() {
        return face.index();
    }

    /** The family CSS and DirectWrite use: "Segoe UI" for every weight of it. */
    public String getFamily() {
        return face.family();
    }

    /** The GDI family, which folds a non-regular weight into the name: "Segoe UI Semibold". */
    public String getLegacyFamily() {
        return face.legacyFamily();
    }

    /** Every family name this face answers to, in every language its font is named in: "Meiryo", "メイリオ". */
    public List<String> getFamilyNames() {
        return face.familyNames();
    }

    public String getStyleName() {
        return face.style();
    }

    /** CSS weight of the default instance, 1–1000. */
    public int getWeight() {
        return face.weight();
    }

    public boolean isItalic() {
        return face.italic();
    }

    /** {@code usWidthClass}: 1–9, 5 normal. */
    public int getWidth() {
        return face.width();
    }

    public float getMinWeight() {
        return face.minWeight();
    }

    public float getMaxWeight() {
        return face.maxWeight();
    }

    public boolean isVariableWeight() {
        return face.isVariableWeight();
    }

    /** Whether this face maps {@code codePoint} to a glyph. */
    public boolean covers(int codePoint) {
        return face.coverage().contains(codePoint);
    }

    public int getCoverageSize() {
        return face.coverage().size();
    }

    /** False for a bitmap-only face, such as a colour emoji font, which this renderer cannot draw. */
    public boolean hasOutlines() {
        return face.outlines();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CgSystemFontFace)) {
            return false;
        }
        CgSystemFontFace other = (CgSystemFontFace) o;
        return face.index() == other.face.index() && path.equals(other.path);
    }

    @Override
    public int hashCode() {
        return path.hashCode() * 31 + face.index();
    }

    @Override
    public String toString() {
        return face.family() + " " + face.style() + " (" + path.getFileName() + "#" + face.index() + ")";
    }
}
