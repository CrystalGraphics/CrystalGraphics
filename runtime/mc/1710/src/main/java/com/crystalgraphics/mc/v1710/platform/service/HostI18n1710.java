package com.crystalgraphics.mc.v1710.platform.service;

import com.crystalgraphics.platform.service.CgHostI18n;
import net.minecraft.client.resources.I18n;

/** A key's text in the player's language, through Minecraft's {@code I18n}, which answers a missing key with itself. */
public final class HostI18n1710 implements CgHostI18n {

    @Override
    public String translate(String key) {
        String text = I18n.format(key);
        return text.equals(key) ? null : text;
    }
}
