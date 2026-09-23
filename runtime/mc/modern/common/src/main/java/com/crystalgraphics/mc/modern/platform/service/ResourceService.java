package com.crystalgraphics.mc.modern.platform.service;

import com.crystalgraphics.mc.modern.platform.ResourceIds;
import com.crystalgraphics.platform.service.CgResourceService;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import java.io.InputStream;
//? if >=1.19 {
import java.util.Optional;
//?}

/**
 * MC 1.20.x implementation of {@link CgResourceService}.
 * Delegates asset loading to Minecraft's {@code ResourceManager}.
 *
 * <p>Returns {@code null} on not-found — never throws. Used by {@code CgIO.openStream()}
 * when the platform is registered; falls through to classpath if not yet registered.</p>
 */
public final class ResourceService implements CgResourceService {

    @Override
    public InputStream openStream(String domain, String path) {
        try {
            // Before 1.19 getResource throws on a missing resource instead of answering empty.
            //? if >=1.19 {
            Optional<Resource> opt = Minecraft.getInstance()
                    .getResourceManager()
                    .getResource(ResourceIds.of(domain, path));
            return opt.isPresent() ? opt.get().open() : null;
            //?} else {
            /*Resource resource = Minecraft.getInstance()
                    .getResourceManager()
                    .getResource(ResourceIds.of(domain, path));
            return resource.getInputStream();
            *///?}
        } catch (Throwable ignored) {
            return null;
        }
    }
}
