package com.crystalgraphics.mc.legacy.platform.service;

import com.crystalgraphics.platform.service.CgResourceService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IResource;
import net.minecraft.util.ResourceLocation;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

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

    @Override
    public List<InputStream> openStack(String domain, String path) {
        List<InputStream> out = new ArrayList<>();
        try {
            for (IResource r : Minecraft.getMinecraft().getResourceManager().getAllResources(new ResourceLocation(domain, path))) {
                out.add(r.getInputStream());
            }
        } catch (Throwable absent) {
            // getAllResources throws where no pack has it.
        }
        return out;
    }

    @Override
    public Set<String> namespaces() {
        try {
            return Minecraft.getMinecraft().getResourceManager().getResourceDomains();
        } catch (Throwable early) {
            return Collections.emptySet();
        }
    }
}
