package com.crystalgraphics.mc.platform.service;

import com.crystalgraphics.platform.service.CgResourceService;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import java.io.InputStream;
import java.util.Optional;

/**
 * MC 1.20.x implementation of {@link CgResourceService}.
 * Delegates asset loading to Minecraft's {@code ResourceManager}.
 *
 * <p>Returns {@code null} on not-found — never throws. Used by {@code CgIO.openStream()}
 * when the platform is registered; falls through to classpath if not yet registered.</p>
 */
public final class ResourceService1201 implements CgResourceService {

    @Override
    public InputStream openStream(String domain, String path) {
        try {
            Optional<Resource> opt = Minecraft.getInstance()
                    .getResourceManager()
                    .getResource(new ResourceLocation(domain, path));
            return opt.isPresent() ? opt.get().open() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
