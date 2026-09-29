package com.crystalgraphics.mc.legacy.platform.service;

import com.crystalgraphics.platform.service.CgResourceService;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;

import java.io.InputStream;

/** Forge 1.8–1.12.2's {@link CgResourceService}: assets through Minecraft's resource manager, packs included. */
public final class ResourceService implements CgResourceService {

    @Override
    public InputStream openStream(String domain, String path) {
        try {
            return Minecraft.getMinecraft().getResourceManager().getResource(new ResourceLocation(domain, path)).getInputStream();
        } catch (Throwable absent) {
            return null;
        }
    }
}
