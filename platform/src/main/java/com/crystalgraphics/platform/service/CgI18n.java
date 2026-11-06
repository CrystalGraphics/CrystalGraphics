package com.crystalgraphics.platform.service;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgService;

import java.util.Locale;

/**
 * Text in the player's language: a translation key read through the host's language files ({@code I18n} in Minecraft).
 * What a Minecraft sound event's subtitle key, or any key a resource pack carries, reads as on screen.
 *
 * <pre>{@code
 * String text = CgI18n.translate("subtitles.entity.generic.explode");     // "Explosion", or null
 * String label = CgI18n.translate("mymod.move.kamehameha", "Kamehameha"); // a fallback where the host has none
 * Locale locale = CgI18n.locale();
 *
 * // a host, once, on a client
 * CgPlatform.provide(CgI18n.HOST, new HostI18nModern());
 * }</pre>
 *
 * <ul>
 *   <li>{@link #translate(String)} is null where the host has no text for the key, never the key itself; the harness
 *       and a server have none.</li>
 *   <li>Client only; render thread, as the host's language is.</li>
 * </ul>
 */
public final class CgI18n {

    /** What a host answers. One class per era, {@code HostI18n<Era>}. */
    public interface Host {

        /** The text for {@code key}; null where the host has none. */
        String translate(String key);

        /** The player's language as the host names it, a locale ({@code en_us}, {@code ja_jp}); {@code en_us} where it has none. */
        default String language() {
            return "en_us";
        }

        Host NONE = key -> null;
    }

    /** The slot a host fills. */
    public static final CgService<Host> HOST = CgService.of("crystalgraphics:i18n", Host.NONE);

    // Kept once a real host answers; NONE is never kept, as a call before the host provides it must not pin it
    private static volatile Host host;

    private CgI18n() {
    }

    private static Host host() {
        Host h = host;
        if (h != null) return h;
        h = CgPlatform.get(HOST);
        if (h != Host.NONE) host = h;
        return h;
    }

    /** {@code key}'s text in the player's language; null where the host has none. */
    public static String translate(String key) {
        return host().translate(key);
    }

    /** {@code key}'s text in the player's language, or {@code fallback} where the host has none. */
    public static String translate(String key, String fallback) {
        String text = translate(key);
        return text != null ? text : fallback;
    }

    /** The player's language as a kit's string tables name it, a locale in lower case: {@code en_us}, {@code fr_fr}. */
    public static String language() {
        return host().language().toLowerCase(Locale.ROOT);
    }

    /** The player's language as a {@link Locale} ({@code ja_jp} is Japanese), the JVM's where the host has none. */
    public static Locale locale() {
        String code = host().language();
        if (code == null || code.isEmpty()) return Locale.getDefault();
        Locale locale = Locale.forLanguageTag(code.replace('_', '-'));
        return locale.getLanguage().isEmpty() ? Locale.getDefault() : locale;
    }
}
