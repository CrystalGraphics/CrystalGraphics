package com.crystalgraphics.mc.legacy.platform.service;

import com.crystalgraphics.mc.CgAssetReloader;
import com.crystalgraphics.platform.service.CgHostResources;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.util.ResourceLocation;
import org.apache.logging.log4j.LogManager;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** {@link CgHostResources} over Forge 1.8–1.12.2's resource manager; and its reloads, which reload the engine's assets. */
public final class HostResourcesLegacy implements CgHostResources.Host {

    public static final HostResourcesLegacy INSTANCE = new HostResourcesLegacy();

    private HostResourcesLegacy() {
    }

    /** F3+T and a pack change reload the engine's assets. Client thread, once the resource manager exists: init. */
    public static void listenForReloads() {
        IResourceManager manager = Minecraft.getMinecraft().getResourceManager();
        if (manager instanceof IReloadableResourceManager) {
            ((IReloadableResourceManager) manager).registerReloadListener(reloaded -> CgAssetReloader.reload());
        } else {
            LogManager.getLogger("CrystalGraphics").warn("The resource manager is not reloadable: F3+T will not reload CrystalGraphics assets");
        }
    }

    @Override
    public InputStream open(String namespace, String path) {
        try {
            return Minecraft.getMinecraft().getResourceManager().getResource(new ResourceLocation(namespace, path)).getInputStream();
        } catch (Throwable absent) {
            return null;   // getResource throws where no pack has it
        }
    }

    @Override
    public List<InputStream> openStack(String namespace, String path) {
        List<InputStream> out = new ArrayList<>();
        try {
            for (IResource r : Minecraft.getMinecraft().getResourceManager().getAllResources(new ResourceLocation(namespace, path))) {
                out.add(r.getInputStream());
            }
        } catch (Throwable absent) {
            // getAllResources throws where no pack has it
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
