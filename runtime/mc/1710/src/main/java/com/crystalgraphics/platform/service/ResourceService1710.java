package com.crystalgraphics.platform.service;

import com.crystalgraphics.platform.service.CgResourceService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.util.ResourceLocation;

import java.io.InputStream;

/**
 * MC 1.7.10 implementation of {@link CgResourceService}.
 * Delegates asset loading to Minecraft's {@link IResourceManager}.
 */
public final class ResourceService1710 implements CgResourceService {

    @Override
    public InputStream openStream(String domain, String path) {
        try {
            IResourceManager rm = Minecraft.getMinecraft().getResourceManager();
            IResource res = rm.getResource(new ResourceLocation(domain, path));
            return res != null ? res.getInputStream() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
