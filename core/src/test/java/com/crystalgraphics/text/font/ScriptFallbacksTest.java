package com.crystalgraphics.text.font;

import com.crystalgraphics.text.font.ScriptFallbacks.HanScript;
import org.junit.Test;

import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Which convention a Han character is drawn in. Wrong answers are invisible to anyone who cannot read
 * the difference: a Chinese 語 in Japanese text is a plausible glyph from the wrong country.
 */
public class ScriptFallbacksTest {

    @Test
    public void aTextsOwnLettersNameItsLanguage() {
        assertEquals(Locale.JAPANESE, ScriptFallbacks.languageShownBy("日本語です"));
        assertEquals(Locale.KOREAN, ScriptFallbacks.languageShownBy("한국 韓國"));
        assertNull(ScriptFallbacks.languageShownBy("中文"));
        assertNull(ScriptFallbacks.languageShownBy("plain"));
    }

    /** Blink's rules: the language if it is one of the four, else a script or region subtag, else Simplified. */
    @Test
    public void aLocaleNamesItsHanConvention() {
        assertEquals(HanScript.JAPANESE, ScriptFallbacks.hanScript(Locale.forLanguageTag("ja-JP")));
        assertEquals(HanScript.JAPANESE, ScriptFallbacks.hanScript(Locale.forLanguageTag("en-JP")));
        assertEquals(HanScript.TRADITIONAL, ScriptFallbacks.hanScript(Locale.forLanguageTag("zh-TW")));
        assertEquals(HanScript.TRADITIONAL, ScriptFallbacks.hanScript(Locale.forLanguageTag("zh-Hant")));
        assertEquals(HanScript.KOREAN, ScriptFallbacks.hanScript(Locale.forLanguageTag("ko-KR")));
        assertEquals(HanScript.SIMPLIFIED, ScriptFallbacks.hanScript(Locale.forLanguageTag("zh-CN")));
        assertEquals(HanScript.SIMPLIFIED, ScriptFallbacks.hanScript(Locale.US));
    }

    @Test
    public void hanIsIdeographsCjkPunctuationAndFullWidthAscii() {
        assertTrue(ScriptFallbacks.isHan(0x65E5));
        assertTrue(ScriptFallbacks.isHan(0x3002));
        assertTrue(ScriptFallbacks.isHan(0xFF21));
        assertFalse(ScriptFallbacks.isHan(0x3042));
        assertFalse(ScriptFallbacks.isHan('A'));
    }
}
