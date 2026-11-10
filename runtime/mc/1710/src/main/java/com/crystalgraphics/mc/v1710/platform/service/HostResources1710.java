package com.crystalgraphics.mc.v1710.platform.service;

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

/** {@link CgHostResources} over 1.7.10's {@link IResourceManager}; and its reloads, which reload the engine's assets. */
public final class HostResources1710 implements CgHostResources.Host {

    public static final HostResources1710 INSTANCE = new HostResources1710();

    private HostResources1710() {
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
            IResource resource = Minecraft.getMinecraft().getResourceManager().getResource(new ResourceLocation(namespace, path));
            return resource != null ? resource.getInputStream() : null;
        } catch (Throwable absent) {
            return null;   // getResource throws where no pack has it
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<InputStream> openStack(String namespace, String path) {
        List<InputStream> out = new ArrayList<>();
        try {
            List<IResource> all = Minecraft.getMinecraft().getResourceManager().getAllResources(new ResourceLocation(namespace, path));
            for (IResource r : all) out.add(r.getInputStream());
        } catch (Throwable absent) {
            // getAllResources throws where no pack has it
        }
        return out;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Set<String> namespaces() {
        try {
            return Minecraft.getMinecraft().getResourceManager().getResourceDomains();
        } catch (Throwable early) {
            return Collections.emptySet();
        }
    }
}
