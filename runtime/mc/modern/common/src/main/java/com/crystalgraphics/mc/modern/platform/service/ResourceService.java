package com.crystalgraphics.mc.modern.platform.service;

import com.crystalgraphics.mc.modern.platform.ResourceIds;
import com.crystalgraphics.platform.service.CgResourceService;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
//? if >=1.19 {
import java.util.Optional;
//?}

/**
 * Modern implementation of {@link CgResourceService}.
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

    @Override
    public List<InputStream> openStack(String domain, String path) {
        List<InputStream> out = new ArrayList<>();
        try {
            //? if >=1.19 {
            for (Resource r : Minecraft.getInstance().getResourceManager().getResourceStack(ResourceIds.of(domain, path))) {
                out.add(r.open());
            }
            //?} else {
            /*for (Resource r : Minecraft.getInstance().getResourceManager().getResources(ResourceIds.of(domain, path))) {
                out.add(r.getInputStream());
            }
            *///?}
        } catch (Throwable absent) {
            // getResources throws before 1.19 where no pack has it.
        }
        return out;
    }

    @Override
    public Set<String> namespaces() {
        try {
            return Minecraft.getInstance().getResourceManager().getNamespaces();
        } catch (Throwable early) {
            return Collections.emptySet();
        }
    }
}
