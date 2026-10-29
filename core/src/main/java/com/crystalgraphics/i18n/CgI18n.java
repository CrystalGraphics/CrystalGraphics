package com.crystalgraphics.i18n;

import java.util.Locale;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgHostI18n;

import javax.annotation.Nullable;

/**
 * Text in the player's language: a translation key read through the host's language files ({@code I18n} in Minecraft).
 * What a Minecraft sound event's subtitle key, or any key a resource pack carries, reads as on screen.
 *
 * <pre>{@code
 * String text = CgI18n.translate("subtitles.entity.generic.explode");   // "Explosion", or null
 * String label = CgI18n.translate("mymod.move.kamehameha", "Kamehameha"); // a fallback where the host has none
 * }</pre>
 *
 * <ul>
 *   <li>Null where the host has no text for the key, never the key itself; the harness and a server have none.</li>
 *   <li>Render thread, as the host's language is.</li>
 * </ul>
 */
public final class CgI18n {

    private CgI18n() {
    }

    /** {@code key}'s text in the player's language; null where the host has none. */
    @Nullable
    public static String translate(String key) {
        return CgPlatform.get(CgHostI18n.SERVICE).translate(key);
    }

    /** The player's language as a kit's string tables name it, a locale in lower case: {@code en_us}, {@code fr_fr}. */
    public static String language() {
        return CgPlatform.get(CgHostI18n.SERVICE).language().toLowerCase(Locale.ROOT);
    }

    /** {@code key}'s text in the player's language, or {@code fallback} where the host has none. */
    public static String translate(String key, String fallback) {
        String text = translate(key);
        return text != null ? text : fallback;
    }
}
