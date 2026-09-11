/*
 * The Windows table and the rules choosing a row are ported from Chromium's Blink, under this licence:
 *
 * Copyright (c) 2006, 2007, 2008, 2009, 2010, 2012 Google Inc. All rights
 * reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *     * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *     * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.crystalgraphics.text.font;

import com.crystalgraphics.api.font.CgGenericFamily;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Which installed families to try, in order, for a character the requested fonts cannot draw —
 * per platform, the way an application's text stack chooses before it searches every installed
 * font for coverage.
 *
 * <pre>{@code
 * HanScript han = ScriptFallbacks.hanScript(Locale.JAPAN);                    // JAPANESE
 * List<String> names = ScriptFallbacks.candidates(0x65E5, han, Platform.WINDOWS);
 * // [Noto Sans JP, Noto Sans CJK JP, Meiryo, Yu Gothic, MS PGothic, Microsoft YaHei, Lucida Sans Unicode]
 * }</pre>
 *
 * <p>The Windows table and the rules that pick its row — Unicode-block overrides for symbols, math
 * and emoji, the character's script, full-width ASCII as Han, Han by locale, and the
 * supplementary-plane names — are Chromium's Blink:
 * {@code platform/fonts/win/font_fallback_win.cc} ({@code InitializeScriptFontMap},
 * {@code GetFontBasedOnUnicodeBlock}, {@code GetFallbackFamily}),
 * {@code platform/text/character.cc} ({@code GetScriptBasedOnUnicodeBlock}), and
 * {@code platform/text/layout_locale.cc} with {@code locale_to_script_mapping.cc} for Han. The
 * macOS and Linux tables are this project's, from the fonts those platforms ship.</p>
 *
 * <p>Two departures from Blink: a supplementary-plane character still tries its script's row
 * (Blink skips it, under a TODO saying it should not), and Blink's truncated {@code "pmingli"} is
 * spelled {@code "PMingLiU"} so it can match. Names are compared case-insensitively.</p>
 */
public final class ScriptFallbacks {

    public enum Platform {
        WINDOWS, MAC, LINUX;

        public static Platform current() {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("win")) {
                return WINDOWS;
            }
            if (os.contains("mac") || os.contains("darwin")) {
                return MAC;
            }
            return LINUX;
        }
    }

    /** Which of the four CJK conventions a Han ideograph is drawn in. */
    public enum HanScript {
        SIMPLIFIED, TRADITIONAL, JAPANESE, KOREAN
    }

    private static final String MONO_EMOJI = "MONO_EMOJI";
    private static final String SYMBOL = "SYMBOL";
    private static final String MATH = "MATH";
    private static final String JAPANESE = "KATAKANA_OR_HIRAGANA";
    private static final String SIMPLIFIED_HAN = "SIMPLIFIED_HAN";
    private static final String TRADITIONAL_HAN = "TRADITIONAL_HAN";
    private static final String HAN = "HAN";

    private static final Map<Character.UnicodeBlock, String> BLOCK_FONTS = blockFonts();
    private static final Map<Character.UnicodeBlock, String> BLOCK_SCRIPTS = blockScripts();
    private static final Map<Platform, Map<String, List<String>>> TABLES = new EnumMap<>(Platform.class);
    private static final Map<Platform, Map<CgGenericFamily, List<String>>> GENERICS = new EnumMap<>(Platform.class);
    private static final Map<Platform, List<String>> LAST_RESORT = new EnumMap<>(Platform.class);

    static {
        TABLES.put(Platform.WINDOWS, windows());
        TABLES.put(Platform.MAC, mac());
        TABLES.put(Platform.LINUX, linux());
        genericFamilies();
        LAST_RESORT.put(Platform.WINDOWS, List.of("Segoe UI", "Segoe UI Symbol", "Segoe UI Historic",
                "Segoe UI Emoji", "Arial", "Microsoft Sans Serif", "Arial Unicode MS", "Lucida Sans Unicode",
                "Nirmala UI", "Ebrima", "Gadugi", "Leelawadee UI", "Malgun Gothic", "Microsoft YaHei",
                "Meiryo", "Cambria Math"));
        LAST_RESORT.put(Platform.MAC, List.of("Helvetica Neue", "Lucida Grande", "Apple Symbols",
                "Arial Unicode MS", "Hiragino Sans", "PingFang SC", "Apple SD Gothic Neo", "STIXGeneral"));
        LAST_RESORT.put(Platform.LINUX, List.of("DejaVu Sans", "Noto Sans", "Liberation Sans", "FreeSans",
                "FreeSerif", "Noto Sans CJK SC", "Droid Sans Fallback", "Symbola", "Unifont"));
    }

    private ScriptFallbacks() {
    }

    /**
     * The Han convention {@code locale} asks for: a language that is one of the four, else its
     * script or region subtag ({@code en-JP} is Japanese), else Simplified — Blink's default.
     */
    public static HanScript hanScript(Locale locale) {
        if (locale == null) {
            return HanScript.SIMPLIFIED;
        }
        String language = locale.getLanguage();
        String script = locale.getScript();
        String region = locale.getCountry();
        switch (language) {
            case "ja":
                return HanScript.JAPANESE;
            case "ko":
                return HanScript.KOREAN;
            case "yue":
            case "lzh":
            case "nan":
            case "hak":
                return HanScript.TRADITIONAL;
            case "zh":
                if ("Hant".equalsIgnoreCase(script) || isTraditionalRegion(region)) {
                    return HanScript.TRADITIONAL;
                }
                return HanScript.SIMPLIFIED;
            default:
                break;
        }
        HanScript fromScript = hanFromScriptSubtag(script);
        if (fromScript != null) {
            return fromScript;
        }
        if (isTraditionalRegion(region)) {
            return HanScript.TRADITIONAL;
        }
        if ("JP".equalsIgnoreCase(region)) {
            return HanScript.JAPANESE;
        }
        if ("KR".equalsIgnoreCase(region)) {
            return HanScript.KOREAN;
        }
        return HanScript.SIMPLIFIED;
    }

    /**
     * Family names to try for {@code codePoint}, most preferred first; empty when the platform names
     * none. A name here may not be installed, and an installed one may still lack the character —
     * the caller checks both.
     */
    public static List<String> candidates(int codePoint, HanScript han, Platform platform) {
        Map<String, List<String>> table = TABLES.get(platform);
        List<String> out = new ArrayList<String>();
        Character.UnicodeBlock block = blockOf(codePoint);

        String blockKey = block != null ? BLOCK_FONTS.get(block) : null;
        if (blockKey != null) {
            addAll(out, table.get(blockKey));
        }

        String scriptKey = scriptKey(codePoint, block, han);
        if (scriptKey != null) {
            addAll(out, table.get(scriptKey));
        }

        if (platform == Platform.WINDOWS) {
            addAll(out, windowsPlaneFallback(codePoint, han));
        }
        return out;
    }

    /** What {@code generic} means on {@code platform}, most preferred first. */
    public static List<String> generic(CgGenericFamily generic, Platform platform) {
        List<String> names = GENERICS.get(platform).get(generic);
        return names != null ? names : GENERICS.get(platform).get(CgGenericFamily.SANS_SERIF);
    }

    /** Families to prefer when searching every installed face for coverage. */
    public static List<String> lastResort(Platform platform) {
        return LAST_RESORT.get(platform);
    }

    /** Whether {@code codePoint} ever takes a fallback: controls, format characters, private use and unassigned ones never do. */
    public static boolean isFallbackCandidate(int codePoint) {
        if (!Character.isValidCodePoint(codePoint)) {
            return false;
        }
        switch (Character.getType(codePoint)) {
            case Character.CONTROL:
            case Character.FORMAT:
            case Character.LINE_SEPARATOR:
            case Character.PARAGRAPH_SEPARATOR:
            case Character.PRIVATE_USE:
            case Character.SURROGATE:
            case Character.UNASSIGNED:
                return false;
            default:
                return true;
        }
    }

    /** Fonts that claim every code point and draw a placeholder for each: never a fallback. */
    public static boolean isPlaceholderFamily(String family) {
        String name = family.toLowerCase(Locale.ROOT);
        return name.equals("lastresort") || name.equals(".lastresort") || name.startsWith("adobe blank")
                || name.startsWith("adobe notdef");
    }

    // ── the rules (font_fallback_win.cc GetFallbackFamily) ──────────────────

    /**
     * Whether {@code codePoint} takes the Han row — an ideograph, CJK punctuation or full-width ASCII —
     * and so is drawn in whichever of the four conventions applies.
     */
    public static boolean isHan(int codePoint) {
        return HAN.equals(baseScriptKey(codePoint, blockOf(codePoint)));
    }

    /**
     * The language {@code text}'s own letters show for its Han characters: Japanese at the first kana,
     * Korean at the first Hangul, {@code null} with neither. Unicode groups the scripts the same way —
     * {@code Jpan} is Han with kana, {@code Kore} Han with Hangul (ISO 15924, UAX #24).
     *
     * <pre>{@code
     * languageShownBy("日本語です");   // ja: the kanji come before the kana that settle it
     * languageShownBy("中文");         // null: the locale decides
     * }</pre>
     */
    public static Locale languageShownBy(CharSequence text) {
        for (int i = 0; i < text.length(); ) {
            int codePoint = Character.codePointAt(text, i);
            Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
            if (script == Character.UnicodeScript.HIRAGANA || script == Character.UnicodeScript.KATAKANA) {
                return Locale.JAPANESE;
            }
            if (script == Character.UnicodeScript.HANGUL) {
                return Locale.KOREAN;
            }
            i += Character.charCount(codePoint);
        }
        return null;
    }

    private static String scriptKey(int codePoint, Character.UnicodeBlock block, HanScript han) {
        String key = baseScriptKey(codePoint, block);
        if (!HAN.equals(key)) {
            return key;
        }
        switch (han) {
            case TRADITIONAL:
                return TRADITIONAL_HAN;
            case JAPANESE:
                return JAPANESE;
            case KOREAN:
                return "HANGUL";
            default:
                return SIMPLIFIED_HAN;
        }
    }

    /** The row a character takes, before Han is split by convention. */
    private static String baseScriptKey(int codePoint, Character.UnicodeBlock block) {
        Character.UnicodeScript script;
        try {
            script = Character.UnicodeScript.of(codePoint);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (codePoint > 0xFF00 && codePoint < 0xFF5F) {
            // Full-width ASCII: widely used in CJK text and covered by every CJK font.
            return HAN;
        }
        if (script == Character.UnicodeScript.COMMON || script == Character.UnicodeScript.INHERITED) {
            return block != null ? BLOCK_SCRIPTS.get(block) : null;
        }
        return script == Character.UnicodeScript.UNKNOWN ? null : script.name();
    }

    private static List<String> windowsPlaneFallback(int codePoint, HanScript han) {
        switch (codePoint >> 16) {
            case 0:
                return List.of("Lucida Sans Unicode");
            case 1:
                return List.of("Code2001", "Lucida Sans Unicode");
            case 2:
                if (codePoint >= 0x2EBF0 && codePoint <= 0x2EE5F) {
                    return List.of("SimSun-ExtG");
                }
                return han == HanScript.TRADITIONAL ? List.of("PMingLiU-ExtB") : List.of("SimSun-ExtB");
            case 3:
                return List.of("SimSun-ExtG");
            default:
                return List.of("Lucida Sans Unicode");
        }
    }

    private static HanScript hanFromScriptSubtag(String script) {
        switch (script.toLowerCase(Locale.ROOT)) {
            case "hans":
                return HanScript.SIMPLIFIED;
            case "hant":
                return HanScript.TRADITIONAL;
            case "jpan":
            case "hira":
            case "kana":
            case "hrkt":
                return HanScript.JAPANESE;
            case "kore":
            case "hang":
                return HanScript.KOREAN;
            default:
                return null;
        }
    }

    private static boolean isTraditionalRegion(String region) {
        return "TW".equalsIgnoreCase(region) || "HK".equalsIgnoreCase(region) || "MO".equalsIgnoreCase(region);
    }

    private static Character.UnicodeBlock blockOf(int codePoint) {
        try {
            return Character.UnicodeBlock.of(codePoint);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void addAll(List<String> out, List<String> names) {
        if (names == null) {
            return;
        }
        for (String name : names) {
            if (!out.contains(name)) {
                out.add(name);
            }
        }
    }

    // GetFontBasedOnUnicodeBlock: blocks that go to a symbol, math or emoji font whatever their script.
    private static Map<Character.UnicodeBlock, String> blockFonts() {
        Map<Character.UnicodeBlock, String> map = new HashMap<>();
        for (Character.UnicodeBlock block : Arrays.asList(Character.UnicodeBlock.EMOTICONS,
                Character.UnicodeBlock.ENCLOSED_ALPHANUMERIC_SUPPLEMENT)) {
            map.put(block, MONO_EMOJI);
        }
        for (Character.UnicodeBlock block : Arrays.asList(Character.UnicodeBlock.PLAYING_CARDS,
                Character.UnicodeBlock.MISCELLANEOUS_SYMBOLS,
                Character.UnicodeBlock.MISCELLANEOUS_SYMBOLS_AND_ARROWS,
                Character.UnicodeBlock.MISCELLANEOUS_SYMBOLS_AND_PICTOGRAPHS,
                Character.UnicodeBlock.TRANSPORT_AND_MAP_SYMBOLS,
                Character.UnicodeBlock.ALCHEMICAL_SYMBOLS,
                Character.UnicodeBlock.DINGBATS,
                Character.UnicodeBlock.GOTHIC)) {
            map.put(block, SYMBOL);
        }
        for (Character.UnicodeBlock block : Arrays.asList(Character.UnicodeBlock.ARROWS,
                Character.UnicodeBlock.MATHEMATICAL_OPERATORS,
                Character.UnicodeBlock.MISCELLANEOUS_TECHNICAL,
                Character.UnicodeBlock.GEOMETRIC_SHAPES,
                Character.UnicodeBlock.MISCELLANEOUS_MATHEMATICAL_SYMBOLS_A,
                Character.UnicodeBlock.SUPPLEMENTAL_ARROWS_A,
                Character.UnicodeBlock.SUPPLEMENTAL_ARROWS_B,
                Character.UnicodeBlock.MISCELLANEOUS_MATHEMATICAL_SYMBOLS_B,
                Character.UnicodeBlock.SUPPLEMENTAL_MATHEMATICAL_OPERATORS,
                Character.UnicodeBlock.MATHEMATICAL_ALPHANUMERIC_SYMBOLS,
                Character.UnicodeBlock.ARABIC_MATHEMATICAL_ALPHABETIC_SYMBOLS)) {
            map.put(block, MATH);
        }
        // By name: the constant is Java 9, and the 1.7.10 client runs on Java 8.
        try {
            map.put(Character.UnicodeBlock.forName("GEOMETRIC_SHAPES_EXTENDED"), MATH);
        } catch (IllegalArgumentException absentOnThisRuntime) {
            // no such block here, so no character can be in it
        }
        return map;
    }

    // Character::GetScriptBasedOnUnicodeBlock: a script for Common/Inherited characters by block.
    private static Map<Character.UnicodeBlock, String> blockScripts() {
        Map<Character.UnicodeBlock, String> map = new HashMap<>();
        map.put(Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION, HAN);
        map.put(Character.UnicodeBlock.HIRAGANA, JAPANESE);
        map.put(Character.UnicodeBlock.KATAKANA, JAPANESE);
        map.put(Character.UnicodeBlock.ARABIC, "ARABIC");
        map.put(Character.UnicodeBlock.THAI, "THAI");
        map.put(Character.UnicodeBlock.GREEK, "GREEK");
        map.put(Character.UnicodeBlock.DEVANAGARI, "DEVANAGARI");
        map.put(Character.UnicodeBlock.ARMENIAN, "ARMENIAN");
        map.put(Character.UnicodeBlock.GEORGIAN, "GEORGIAN");
        map.put(Character.UnicodeBlock.KANNADA, "KANNADA");
        map.put(Character.UnicodeBlock.GOTHIC, "GOTHIC");
        return map;
    }

    // ── the tables ──────────────────────────────────────────────────────────

    /** InitializeScriptFontMap. Windows 10 font first, then 8.1, 8.0 and 7's. */
    private static Map<String, List<String>> windows() {
        Map<String, List<String>> t = new HashMap<>();
        List<String> japanese = List.of("Noto Sans JP", "Noto Sans CJK JP", "Meiryo", "Yu Gothic",
                "MS PGothic", "Microsoft YaHei");
        List<String> traditional = List.of("Noto Sans TC", "Noto Sans CJK TC", "Microsoft JhengHei", "PMingLiU");
        List<String> historic = List.of("Segoe UI Historic");
        List<String> historicOrSymbol = List.of("Segoe UI Historic", "Segoe UI Symbol");
        t.put("ARABIC", List.of("Tahoma", "Segoe UI"));
        t.put("ARMENIAN", List.of("Segoe UI", "Sylfaen"));
        t.put("BENGALI", List.of("Nirmala UI", "Vrinda"));
        t.put("BRAHMI", historic);
        t.put("BRAILLE", List.of("Segoe UI Symbol"));
        t.put("BUGINESE", List.of("Leelawadee UI"));
        t.put("CANADIAN_ABORIGINAL", List.of("Gadugi", "Euphemia"));
        t.put("CARIAN", historic);
        t.put("CHEROKEE", List.of("Gadugi", "Plantagenet"));
        t.put("COPTIC", List.of("Segoe UI Symbol"));
        t.put("CUNEIFORM", historic);
        t.put("CYPRIOT", historic);
        t.put("CYRILLIC", List.of("Times New Roman"));
        t.put("DESERET", List.of("Segoe UI Symbol"));
        t.put("DEVANAGARI", List.of("Nirmala UI", "Mangal"));
        t.put("EGYPTIAN_HIEROGLYPHS", historic);
        t.put("ETHIOPIC", List.of("Nyala", "Abyssinica SIL", "Ethiopia Jiret", "Visual Geez Unicode",
                "GF Zemen Unicode", "Ebrima"));
        t.put("GEORGIAN", List.of("Sylfaen", "Segoe UI"));
        t.put("GLAGOLITIC", historicOrSymbol);
        t.put("GOTHIC", historicOrSymbol);
        t.put("GREEK", List.of("Times New Roman"));
        t.put("GUJARATI", List.of("Nirmala UI", "Shruti"));
        t.put("GURMUKHI", List.of("Nirmala UI", "Raavi"));
        t.put("HANGUL", List.of("Noto Sans KR", "Noto Sans CJK KR", "Malgun Gothic", "Gulim"));
        t.put("HEBREW", List.of("David", "Segoe UI"));
        t.put("HIRAGANA", japanese);
        t.put("IMPERIAL_ARAMAIC", historic);
        t.put("INSCRIPTIONAL_PAHLAVI", historic);
        t.put("INSCRIPTIONAL_PARTHIAN", historic);
        t.put("JAVANESE", List.of("Javanese Text"));
        t.put("KANNADA", List.of("Tunga", "Nirmala UI"));
        t.put("KATAKANA", japanese);
        t.put(JAPANESE, japanese);
        t.put("KHAROSHTHI", historic);
        t.put("KHMER", List.of("Leelawadee UI", "Khmer UI", "Khmer OS", "MoolBoran", "DaunPenh"));
        t.put("LAO", List.of("Leelawadee UI", "Lao UI", "DokChampa", "Saysettha OT", "Phetsarath OT", "Code2000"));
        t.put("LATIN", List.of("Times New Roman"));
        t.put("LISU", List.of("Segoe UI"));
        t.put("LYCIAN", historic);
        t.put("LYDIAN", historic);
        t.put("MALAYALAM", List.of("Nirmala UI", "Kartika"));
        t.put("MEETEI_MAYEK", List.of("Nirmala UI"));
        t.put("MEROITIC_CURSIVE", historicOrSymbol);
        t.put("MONGOLIAN", List.of("Mongolian Baiti"));
        t.put("MYANMAR", List.of("Myanmar Text", "Padauk", "Parabaik", "Myanmar3", "Code2000"));
        t.put("NEW_TAI_LUE", List.of("Microsoft New Tai Lue"));
        t.put("NKO", List.of("Ebrima"));
        t.put("OGHAM", historicOrSymbol);
        t.put("OL_CHIKI", List.of("Nirmala UI"));
        t.put("OLD_ITALIC", historicOrSymbol);
        t.put("OLD_PERSIAN", historic);
        t.put("OLD_SOUTH_ARABIAN", historic);
        t.put("ORIYA", List.of("Kalinga", "ori1Uni", "Lohit Oriya", "Nirmala UI"));
        t.put("OLD_TURKIC", historicOrSymbol);
        t.put("OSMANYA", List.of("Ebrima"));
        t.put("PHAGS_PA", List.of("Microsoft PhagsPa"));
        t.put("RUNIC", historicOrSymbol);
        t.put("SHAVIAN", historic);
        t.put(SIMPLIFIED_HAN, List.of("Noto Sans SC", "Noto Sans CJK SC", "Microsoft YaHei", "SimSun"));
        t.put("SINHALA", List.of("Iskoola Pota", "AksharUnicode", "Nirmala UI"));
        t.put("SORA_SOMPENG", List.of("Nirmala UI"));
        t.put("SYRIAC", List.of("Estrangelo Edessa", "Estrangelo Nisibin", "Code2000"));
        t.put("TAI_LE", List.of("Microsoft Tai Le"));
        t.put("TAMIL", List.of("Nirmala UI", "Latha"));
        t.put("TELUGU", List.of("Nirmala UI", "Gautami"));
        t.put("THAANA", List.of("MV Boli"));
        t.put("THAI", List.of("Tahoma", "Leelawadee UI", "Leelawadee"));
        t.put("TIBETAN", List.of("Microsoft Himalaya", "Jomolhari", "Tibetan Machine Uni"));
        t.put("TIFINAGH", List.of("Ebrima"));
        t.put(TRADITIONAL_HAN, traditional);
        t.put("BOPOMOFO", traditional);
        t.put("VAI", List.of("Ebrima"));
        t.put("YI", List.of("Microsoft Yi Baiti", "Nuosu SIL", "Code2000"));
        t.put(MONO_EMOJI, List.of("Segoe UI Symbol", "Segoe UI Emoji"));
        t.put(SYMBOL, List.of("Segoe UI Symbol"));
        t.put(MATH, List.of("Cambria Math", "Segoe UI Symbol", "Code2000"));
        return t;
    }

    private static Map<String, List<String>> mac() {
        Map<String, List<String>> t = new HashMap<>();
        List<String> japanese = List.of("Hiragino Sans", "Hiragino Kaku Gothic ProN", "Hiragino Kaku Gothic Pro",
                "Osaka", "Noto Sans JP");
        List<String> traditional = List.of("PingFang TC", "PingFang HK", "Heiti TC", "Songti TC", "Noto Sans TC");
        t.put("ARABIC", List.of("Geeza Pro", "SF Arabic", "Al Nile", "Baghdad"));
        t.put("ARMENIAN", List.of("Mshtakan", "Noto Sans Armenian"));
        t.put("BENGALI", List.of("Kohinoor Bangla", "Bangla Sangam MN", "Bangla MN"));
        t.put("CHEROKEE", List.of("Plantagenet Cherokee"));
        t.put("CYRILLIC", List.of("Helvetica Neue", "Helvetica"));
        t.put("DEVANAGARI", List.of("Kohinoor Devanagari", "Devanagari Sangam MN", "Devanagari MT"));
        t.put("ETHIOPIC", List.of("Kefa", "Noto Sans Ethiopic"));
        t.put("GEORGIAN", List.of("Noto Sans Georgian", "Arial Unicode MS"));
        t.put("GREEK", List.of("Helvetica Neue", "Helvetica"));
        t.put("GUJARATI", List.of("Kohinoor Gujarati", "Gujarati Sangam MN", "Gujarati MT"));
        t.put("GURMUKHI", List.of("Gurmukhi Sangam MN", "Gurmukhi MN", "Gurmukhi MT"));
        t.put("HANGUL", List.of("Apple SD Gothic Neo", "AppleGothic", "Noto Sans KR", "Nanum Gothic"));
        t.put("HEBREW", List.of("Arial Hebrew", "SF Hebrew", "Lucida Grande"));
        t.put("HIRAGANA", japanese);
        t.put("KATAKANA", japanese);
        t.put(JAPANESE, japanese);
        t.put("KANNADA", List.of("Kannada Sangam MN", "Kannada MN", "Noto Sans Kannada"));
        t.put("KHMER", List.of("Khmer Sangam MN", "Khmer MN"));
        t.put("LAO", List.of("Lao Sangam MN", "Lao MN"));
        t.put("LATIN", List.of("Helvetica Neue", "Helvetica"));
        t.put("MALAYALAM", List.of("Malayalam Sangam MN", "Malayalam MN"));
        t.put("MONGOLIAN", List.of("Noto Sans Mongolian"));
        t.put("MYANMAR", List.of("Myanmar Sangam MN", "Myanmar MN", "Noto Sans Myanmar"));
        t.put("ORIYA", List.of("Oriya Sangam MN", "Oriya MN"));
        t.put(SIMPLIFIED_HAN, List.of("PingFang SC", "Hiragino Sans GB", "STHeiti", "Heiti SC", "Songti SC",
                "Noto Sans SC"));
        t.put("SINHALA", List.of("Sinhala Sangam MN", "Sinhala MN"));
        t.put("SYRIAC", List.of("Noto Sans Syriac"));
        t.put("TAMIL", List.of("Tamil Sangam MN", "Tamil MN", "InaiMathi"));
        t.put("TELUGU", List.of("Kohinoor Telugu", "Telugu Sangam MN", "Telugu MN"));
        t.put("THAI", List.of("Thonburi", "Ayuthaya", "Sathu", "Silom"));
        t.put("TIBETAN", List.of("Kokonor", "Noto Serif Tibetan"));
        t.put(TRADITIONAL_HAN, traditional);
        t.put("BOPOMOFO", traditional);
        t.put(MONO_EMOJI, List.of("Apple Symbols"));
        t.put(SYMBOL, List.of("Apple Symbols", "Menlo", "Arial Unicode MS", "STIXGeneral"));
        t.put(MATH, List.of("STIX Two Math", "STIXGeneral", "Apple Symbols"));
        return t;
    }

    private static Map<String, List<String>> linux() {
        Map<String, List<String>> t = new HashMap<>();
        List<String> japanese = List.of("Noto Sans CJK JP", "Noto Sans JP", "Source Han Sans JP", "IPAexGothic",
                "IPAPGothic", "TakaoPGothic", "VL PGothic", "Droid Sans Japanese");
        List<String> traditional = List.of("Noto Sans CJK TC", "Noto Sans TC", "Source Han Sans TC",
                "AR PL UMing TW", "WenQuanYi Zen Hei");
        List<String> latin = List.of("DejaVu Sans", "Noto Sans", "Liberation Sans", "FreeSans");
        t.put("ARABIC", List.of("Noto Sans Arabic", "Noto Naskh Arabic", "DejaVu Sans", "KacstOne", "FreeSerif"));
        t.put("ARMENIAN", List.of("Noto Sans Armenian", "DejaVu Sans"));
        t.put("BENGALI", List.of("Noto Sans Bengali", "Lohit Bengali"));
        t.put("CYRILLIC", latin);
        t.put("DEVANAGARI", List.of("Noto Sans Devanagari", "Lohit Devanagari", "FreeSans"));
        t.put("ETHIOPIC", List.of("Noto Sans Ethiopic", "Abyssinica SIL"));
        t.put("GEORGIAN", List.of("Noto Sans Georgian", "DejaVu Sans"));
        t.put("GREEK", latin);
        t.put("GUJARATI", List.of("Noto Sans Gujarati", "Lohit Gujarati"));
        t.put("GURMUKHI", List.of("Noto Sans Gurmukhi", "Lohit Gurmukhi"));
        t.put("HANGUL", List.of("Noto Sans CJK KR", "Noto Sans KR", "Source Han Sans KR", "NanumGothic",
                "UnDotum", "Baekmuk Gulim"));
        t.put("HEBREW", List.of("Noto Sans Hebrew", "DejaVu Sans", "FreeSans"));
        t.put("HIRAGANA", japanese);
        t.put("KATAKANA", japanese);
        t.put(JAPANESE, japanese);
        t.put("KANNADA", List.of("Noto Sans Kannada", "Lohit Kannada"));
        t.put("KHMER", List.of("Noto Sans Khmer", "Khmer OS"));
        t.put("LAO", List.of("Noto Sans Lao", "Phetsarath OT"));
        t.put("LATIN", latin);
        t.put("MALAYALAM", List.of("Noto Sans Malayalam", "Lohit Malayalam"));
        t.put("MONGOLIAN", List.of("Noto Sans Mongolian"));
        t.put("MYANMAR", List.of("Noto Sans Myanmar", "Padauk"));
        t.put("ORIYA", List.of("Noto Sans Oriya", "Lohit Odia"));
        t.put(SIMPLIFIED_HAN, List.of("Noto Sans CJK SC", "Noto Sans SC", "Source Han Sans SC",
                "WenQuanYi Micro Hei", "WenQuanYi Zen Hei", "Droid Sans Fallback", "AR PL UMing CN"));
        t.put("SINHALA", List.of("Noto Sans Sinhala", "LKLUG"));
        t.put("SYRIAC", List.of("Noto Sans Syriac"));
        t.put("TAMIL", List.of("Noto Sans Tamil", "Lohit Tamil"));
        t.put("TELUGU", List.of("Noto Sans Telugu", "Lohit Telugu"));
        t.put("THAI", List.of("Noto Sans Thai", "Loma", "Garuda", "Waree"));
        t.put("TIBETAN", List.of("Noto Serif Tibetan", "Jomolhari"));
        t.put(TRADITIONAL_HAN, traditional);
        t.put("BOPOMOFO", traditional);
        t.put(MONO_EMOJI, List.of("Noto Sans Symbols", "Noto Sans Symbols2", "Noto Emoji", "Symbola"));
        t.put(SYMBOL, List.of("Noto Sans Symbols", "Noto Sans Symbols2", "DejaVu Sans", "Symbola"));
        t.put(MATH, List.of("Noto Sans Math", "STIX Two Math", "DejaVu Sans", "FreeSerif"));
        return t;
    }

    private static void genericFamilies() {
        Map<CgGenericFamily, List<String>> windows = new EnumMap<>(CgGenericFamily.class);
        windows.put(CgGenericFamily.SERIF, List.of("Times New Roman"));
        windows.put(CgGenericFamily.SANS_SERIF, List.of("Arial"));
        windows.put(CgGenericFamily.MONOSPACE, List.of("Consolas", "Courier New"));
        windows.put(CgGenericFamily.CURSIVE, List.of("Comic Sans MS"));
        windows.put(CgGenericFamily.FANTASY, List.of("Impact"));
        windows.put(CgGenericFamily.SYSTEM_UI, List.of("Segoe UI"));
        windows.put(CgGenericFamily.UI_SERIF, List.of("Cambria", "Times New Roman"));
        windows.put(CgGenericFamily.UI_SANS_SERIF, List.of("Segoe UI"));
        windows.put(CgGenericFamily.UI_MONOSPACE, List.of("Cascadia Mono", "Consolas"));
        windows.put(CgGenericFamily.UI_ROUNDED, List.of("Segoe UI"));
        windows.put(CgGenericFamily.EMOJI, List.of("Segoe UI Emoji", "Segoe UI Symbol"));
        windows.put(CgGenericFamily.MATH, List.of("Cambria Math"));
        GENERICS.put(Platform.WINDOWS, windows);

        Map<CgGenericFamily, List<String>> mac = new EnumMap<>(CgGenericFamily.class);
        mac.put(CgGenericFamily.SERIF, List.of("Times", "Times New Roman"));
        mac.put(CgGenericFamily.SANS_SERIF, List.of("Helvetica", "Helvetica Neue", "Arial"));
        mac.put(CgGenericFamily.MONOSPACE, List.of("Menlo", "Monaco", "Courier New", "Courier"));
        mac.put(CgGenericFamily.CURSIVE, List.of("Apple Chancery", "Snell Roundhand"));
        mac.put(CgGenericFamily.FANTASY, List.of("Papyrus"));
        mac.put(CgGenericFamily.SYSTEM_UI, List.of("System Font", ".SF NS", "SF Pro", "SF Pro Text",
                "Helvetica Neue", "Helvetica"));
        mac.put(CgGenericFamily.UI_SERIF, List.of("New York", "Times"));
        mac.put(CgGenericFamily.UI_SANS_SERIF, List.of("System Font", ".SF NS", "SF Pro", "Helvetica Neue"));
        mac.put(CgGenericFamily.UI_MONOSPACE, List.of("SF Mono", "Menlo"));
        mac.put(CgGenericFamily.UI_ROUNDED, List.of("SF Pro Rounded", "Arial Rounded MT Bold"));
        mac.put(CgGenericFamily.EMOJI, List.of("Apple Symbols"));
        mac.put(CgGenericFamily.MATH, List.of("STIX Two Math", "STIXGeneral"));
        GENERICS.put(Platform.MAC, mac);

        Map<CgGenericFamily, List<String>> linux = new EnumMap<>(CgGenericFamily.class);
        List<String> sans = List.of("DejaVu Sans", "Noto Sans", "Liberation Sans", "FreeSans");
        linux.put(CgGenericFamily.SERIF, List.of("DejaVu Serif", "Noto Serif", "Liberation Serif", "FreeSerif"));
        linux.put(CgGenericFamily.SANS_SERIF, sans);
        linux.put(CgGenericFamily.MONOSPACE, List.of("DejaVu Sans Mono", "Noto Sans Mono", "Liberation Mono",
                "FreeMono"));
        linux.put(CgGenericFamily.SYSTEM_UI, List.of("Cantarell", "Ubuntu", "Noto Sans", "DejaVu Sans"));
        linux.put(CgGenericFamily.UI_SERIF, List.of("DejaVu Serif", "Noto Serif"));
        linux.put(CgGenericFamily.UI_SANS_SERIF, List.of("Cantarell", "Ubuntu", "Noto Sans", "DejaVu Sans"));
        linux.put(CgGenericFamily.UI_MONOSPACE, List.of("DejaVu Sans Mono", "Noto Sans Mono", "Ubuntu Mono"));
        linux.put(CgGenericFamily.EMOJI, List.of("Noto Emoji", "Symbola"));
        linux.put(CgGenericFamily.MATH, List.of("Noto Sans Math", "STIX Two Math"));
        GENERICS.put(Platform.LINUX, linux);

        for (Map<CgGenericFamily, List<String>> map : GENERICS.values()) {
            for (Map.Entry<CgGenericFamily, List<String>> entry : map.entrySet()) {
                entry.setValue(Collections.unmodifiableList(entry.getValue()));
            }
        }
    }
}
