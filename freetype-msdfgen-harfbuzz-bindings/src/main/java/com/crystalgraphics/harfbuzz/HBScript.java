package com.crystalgraphics.harfbuzz;

/**
 * HarfBuzz script constants. Subset of ISO 15924 script tags packed as 4-byte integers.
 * Generated from hb_script_t - includes the most commonly needed scripts.
 */
public final class HBScript {
    public static final int HB_SCRIPT_COMMON = tag('Z','y','y','y');
    public static final int HB_SCRIPT_INHERITED = tag('Z','i','n','h');
    public static final int HB_SCRIPT_UNKNOWN = tag('Z','z','z','z');

    public static final int HB_SCRIPT_ARABIC = tag('A','r','a','b');
    public static final int HB_SCRIPT_ARMENIAN = tag('A','r','m','n');
    public static final int HB_SCRIPT_BENGALI = tag('B','e','n','g');
    public static final int HB_SCRIPT_CYRILLIC = tag('C','y','r','l');
    public static final int HB_SCRIPT_DEVANAGARI = tag('D','e','v','a');
    public static final int HB_SCRIPT_GEORGIAN = tag('G','e','o','r');
    public static final int HB_SCRIPT_GREEK = tag('G','r','e','k');
    public static final int HB_SCRIPT_GUJARATI = tag('G','u','j','r');
    public static final int HB_SCRIPT_GURMUKHI = tag('G','u','r','u');
    public static final int HB_SCRIPT_HANGUL = tag('H','a','n','g');
    public static final int HB_SCRIPT_HAN = tag('H','a','n','i');
    public static final int HB_SCRIPT_HEBREW = tag('H','e','b','r');
    public static final int HB_SCRIPT_HIRAGANA = tag('H','i','r','a');
    public static final int HB_SCRIPT_KANNADA = tag('K','n','d','a');
    public static final int HB_SCRIPT_KATAKANA = tag('K','a','n','a');
    public static final int HB_SCRIPT_LAO = tag('L','a','o','o');
    public static final int HB_SCRIPT_LATIN = tag('L','a','t','n');
    public static final int HB_SCRIPT_MALAYALAM = tag('M','l','y','m');
    public static final int HB_SCRIPT_ORIYA = tag('O','r','y','a');
    public static final int HB_SCRIPT_TAMIL = tag('T','a','m','l');
    public static final int HB_SCRIPT_TELUGU = tag('T','e','l','u');
    public static final int HB_SCRIPT_THAI = tag('T','h','a','i');
    public static final int HB_SCRIPT_TIBETAN = tag('T','i','b','t');
    public static final int HB_SCRIPT_CANADIAN_SYLLABICS = tag('C','a','n','s');
    public static final int HB_SCRIPT_CHEROKEE = tag('C','h','e','r');
    public static final int HB_SCRIPT_ETHIOPIC = tag('E','t','h','i');
    public static final int HB_SCRIPT_KHMER = tag('K','h','m','r');
    public static final int HB_SCRIPT_MONGOLIAN = tag('M','o','n','g');
    public static final int HB_SCRIPT_MYANMAR = tag('M','y','m','r');
    public static final int HB_SCRIPT_SINHALA = tag('S','i','n','h');
    public static final int HB_SCRIPT_SYRIAC = tag('S','y','r','c');
    public static final int HB_SCRIPT_THAANA = tag('T','h','a','a');
    public static final int HB_SCRIPT_YI = tag('Y','i','i','i');

    private HBScript() {
    }

    /** Packs 4 ASCII chars into an int tag (HarfBuzz HB_TAG macro). */
    public static int tag(char c1, char c2, char c3, char c4) {
        return ((c1 & 0xFF) << 24) | ((c2 & 0xFF) << 16) | ((c3 & 0xFF) << 8) | (c4 & 0xFF);
    }
}
