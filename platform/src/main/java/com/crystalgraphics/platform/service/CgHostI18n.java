package com.crystalgraphics.platform.service;

import com.crystalgraphics.platform.CgService;

/**
 * The host's text for a translation key, in the language the player chose: what a Minecraft sound event's subtitle key
 * ({@code subtitles.entity.generic.explode}) reads as on screen.
 *
 * <pre>{@code
 * String text = CgI18n.translate("subtitles.entity.generic.explode");   // core reads it through CgI18n: "Explosion"
 * }</pre>
 *
 * <pre>{@code
 * CgPlatform.provide(CgHostI18n.SERVICE, new HostI18nModern());   // a host, on a client
 * }</pre>
 *
 * <ul>
 *   <li>Null where the host has no text for the key, never the key itself.</li>
 *   <li>{@link #NONE} has none: the harness, a server. Render thread.</li>
 * </ul>
 */
@FunctionalInterface
public interface CgHostI18n {

    /** The text for {@code key}; null where the host has none. */
    String translate(String key);

    CgHostI18n NONE = key -> null;

    CgService<CgHostI18n> SERVICE = CgService.of("crystalgraphics:host_i18n", NONE);
}
