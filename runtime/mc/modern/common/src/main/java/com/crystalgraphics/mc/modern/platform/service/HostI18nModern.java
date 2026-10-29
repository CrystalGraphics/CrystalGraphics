package com.crystalgraphics.mc.modern.platform.service;

import com.crystalgraphics.platform.service.CgHostI18n;
import net.minecraft.client.resources.language.I18n;

/** A key's text in the player's language, through Minecraft's {@code I18n}, which answers a missing key with itself. */
public final class HostI18nModern implements CgHostI18n {

    @Override
    public String translate(String key) {
        String text = I18n.get(key);
        return text.equals(key) ? null : text;
    }
}
