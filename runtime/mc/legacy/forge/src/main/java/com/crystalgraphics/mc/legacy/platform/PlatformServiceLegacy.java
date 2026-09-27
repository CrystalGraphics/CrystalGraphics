package com.crystalgraphics.mc.legacy.platform;

import com.crystalgraphics.lwjgl2.Lwjgl2CursorService;
import com.crystalgraphics.lwjgl2.Lwjgl2GLContext;
import com.crystalgraphics.lwjgl2.Lwjgl2InputService;
import com.crystalgraphics.mc.legacy.platform.service.LifecycleService;
import com.crystalgraphics.mc.legacy.platform.service.ReloadService;
import com.crystalgraphics.mc.legacy.platform.service.RenderingService;
import com.crystalgraphics.mc.legacy.platform.service.ResourceService;
import com.crystalgraphics.mc.legacy.platform.service.SoundService;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgPlatformService;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.platform.gl.CgGLBackend;
import com.crystalgraphics.platform.gl.CgGLContext;
import com.crystalgraphics.platform.service.CgCursorService;
import com.crystalgraphics.platform.service.CgInputService;
import com.crystalgraphics.platform.service.CgLifecycleService;
import com.crystalgraphics.platform.service.CgReloadService;
import com.crystalgraphics.platform.service.CgRenderingService;
import com.crystalgraphics.platform.service.CgResourceService;
import com.crystalgraphics.platform.service.CgSoundService;

/**
 * The Forge 1.8–1.12.2 platform bundle: tier 1's LWJGL2 services, the {@link GlStateManagerGLBackend}, and
 * this era's Minecraft-facing services.
 *
 * <pre>
 * PlatformServiceLegacy.register(client);   // preInit, both sides
 * </pre>
 *
 * <ul>
 *   <li>Every service is built on first use and held as its SPI type: they name LWJGL and client classes
 *       a dedicated server does not have, and a field of such a type fails at class load.</li>
 *   <li>The cursor is filled on a client only, for the same reason.</li>
 * </ul>
 */
public final class PlatformServiceLegacy implements CgPlatformService {

    private static PlatformServiceLegacy instance;

    private CgGLBackend        glBackend;
    private CgGLContext        glContext;
    private CgLifecycleService lifecycle;
    private CgReloadService    reload;
    private CgResourceService  resources;
    private CgRenderingService rendering;
    private CgInputService     input;
    private CgSoundService     sound;

    public static synchronized PlatformServiceLegacy getInstance() {
        if (instance == null) instance = new PlatformServiceLegacy();
        return instance;
    }

    /** Registers the bundle, and on a client fills the cursor slot. */
    public static void register(boolean client) {
        CgPlatform.register(getInstance());
        if (client) CgPlatform.provide(CgCursorService.SERVICE, new Lwjgl2CursorService());
    }

    @Override public CgGLBackend gl() {
        if (glBackend == null) {
            // Before any GL work: CgBindingPoints allocates units counting down from this ceiling.
            CgCapabilities.setHostTextureUnitCeiling(GlStateManagerGLBackend.TRACKED_TEXTURE_UNITS);
            glBackend = new GlStateManagerGLBackend();
        }
        return glBackend;
    }

    @Override public CgGLContext capabilities() {
        if (glContext == null) glContext = new Lwjgl2GLContext();
        return glContext;
    }

    @Override public CgLifecycleService lifecycle() {
        if (lifecycle == null) lifecycle = new LifecycleService();
        return lifecycle;
    }

    @Override public CgReloadService reload() {
        if (reload == null) reload = new ReloadService();
        return reload;
    }

    @Override public CgResourceService resources() {
        if (resources == null) resources = new ResourceService();
        return resources;
    }

    @Override public CgRenderingService rendering() {
        if (rendering == null) rendering = new RenderingService();
        return rendering;
    }

    @Override public CgInputService input() {
        if (input == null) input = new Lwjgl2InputService();
        return input;
    }

    @Override public CgSoundService sound() {
        if (sound == null) sound = new SoundService();
        return sound;
    }
}
