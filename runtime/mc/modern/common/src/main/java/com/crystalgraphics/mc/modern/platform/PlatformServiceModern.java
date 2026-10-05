package com.crystalgraphics.mc.modern.platform;

import com.crystalgraphics.mc.modern.platform.gl.Blaze3dGLBackend;
import com.crystalgraphics.mc.modern.platform.gl.HostStateModern;
// 26.3 ships SDL3 and no GLFW, so a 26.3 node cannot even load the GLFW pair.
//? if >=26.3 {
/*import com.crystalgraphics.sdl.SdlCursorService;
import com.crystalgraphics.sdl.SdlInputService;
*///?} else {
import com.crystalgraphics.lwjgl3.GlfwCursorService;
import com.crystalgraphics.lwjgl3.GlfwInputService;
//?}
import com.crystalgraphics.lwjgl3.Lwjgl3GLContext;

import com.crystalgraphics.mc.modern.platform.service.LifecycleService;
import com.crystalgraphics.mc.modern.platform.service.ReloadService;
import com.crystalgraphics.mc.modern.platform.service.RenderingService;
import com.crystalgraphics.mc.modern.platform.service.ResourceService;
import com.crystalgraphics.mc.modern.platform.service.SoundService;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgPlatformService;
import com.crystalgraphics.platform.service.CgCursorService;
import com.crystalgraphics.platform.service.CgWorldQuery;
import com.crystalgraphics.platform.service.CgEntityQuery;
import com.crystalgraphics.platform.service.CgGameDirectory;
import com.crystalgraphics.platform.service.CgHostCamera;
import com.crystalgraphics.platform.service.CgWorldSound;
import com.crystalgraphics.platform.service.CgWorldEvents;
import com.crystalgraphics.platform.service.CgWorldStimulus;
import com.crystalgraphics.mc.modern.platform.world.WorldStimulusModern;
import com.crystalgraphics.mc.modern.platform.world.WorldQueryModern;
import com.crystalgraphics.mc.modern.platform.world.EntityQueryModern;
import com.crystalgraphics.mc.modern.platform.world.HostCameraModern;
import com.crystalgraphics.mc.modern.platform.world.WorldSoundModern;
import com.crystalgraphics.platform.gl.CgGLBackend;
import com.crystalgraphics.platform.gl.CgGLContext;
import com.crystalgraphics.platform.gl.tracked.CgTrackedGLContext;
import com.crystalgraphics.platform.service.CgInputService;
import com.crystalgraphics.platform.service.CgLifecycleService;
import com.crystalgraphics.platform.service.CgReloadService;
import com.crystalgraphics.platform.service.CgRenderingService;
import com.crystalgraphics.platform.service.CgResourceService;
import com.crystalgraphics.platform.service.CgSoundService;

import net.minecraft.client.Minecraft;
//? if >=26.2 {
/*import com.crystalgraphics.mc.modern.platform.vulkan.Blaze3dVulkanHost;
*///?}

/**
 * The modern platform bundle. Implements {@link CgPlatformService} by composing
 * the modern service adapters. Register via {@code CgPlatform.register(PlatformServiceModern.getInstance())}.
 *
 * <p><b>Every service is built on demand and every field is typed as its SPI interface.</b> This used
 * to read "no GL calls are made in the constructor or static initializer" — true about what the code
 * <em>does</em>, and irrelevant to whether a class can be <em>loaded</em>, which is the trap that stopped
 * CrystalGraphics loading on a 1.7.10 dedicated server at all. See the note on the fields below.</p>
 *
 * <h3>An assembler, not an implementation</h3>
 *
 * <p>Nothing here does GL or GLFW work of its own. Every service is either the era's own tier-2 class
 * or a <b>tier-1</b> one from {@code runtime/lwjgl/3}, which knows nothing about Minecraft and is
 * handed the one fact it needs — {@link #windowHandle()}, as a supplier. That is §12's rule in one
 * file: a class lives in the lowest tier its dependencies allow, and a value from a higher tier is
 * passed in rather than reached for.
 *
 * <p>{@link #gl()} is the exception worth naming: the backend it builds <em>is</em> tier 2, because
 * telling Minecraft what state we changed is the one thing tier 1 must not know how to do. See
 * {@code Blaze3dGLBackend}.</p>
 */
public final class PlatformServiceModern implements CgPlatformService {

    private static PlatformServiceModern instance;

    public static synchronized PlatformServiceModern getInstance() {
        if (instance == null) instance = new PlatformServiceModern();
        return instance;
    }

    // BUILT ON DEMAND, and typed as the SPI interfaces rather than the implementations.
    //
    // This is the 1.7.10 fix, applied ahead of the failure rather than after it. There it was
    // `public final` fields built at preInit, and a DEDICATED SERVER died at the first one:
    // NoClassDefFoundError: org/lwjgl/LWJGLException. Here it was worse -- the fields sat inside a
    // `static final INSTANCE`, so all eight were built at CLASS INIT, one step earlier in the
    // lifecycle than 1710's, and the `@Mod` constructor that calls getInstance() runs on BOTH SIDES.
    //
    // Lwjgl3GLContext is the concrete hazard -- it holds `private volatile GLCapabilities caps`, an
    // org.lwjgl.opengl FIELD DESCRIPTOR, and a dedicated server has no LWJGL on its classpath.
    // ResourceService and RenderingService name net.minecraft.client types,
    // which a server distribution does not ship either; those are method-body references today and so
    // survive loading, but only by luck, and nothing stops the next edit adding a field.
    //
    // Two halves and both are needed. LAZY, so a server that asks for no rendering constructs none; and
    // the fields are declared as the INTERFACE, so the LWJGL-touching class is named only inside a
    // method body. A field descriptor is resolved eagerly enough to matter -- the same rule that keeps
    // JOML and Taffy on CrystalGUI's headless classpath -- while a method-body reference is not, which
    // is why gl() on a server is fine right up until somebody calls it.
    //
    // `serverSmoke` checks it on every node with a dev run: it asserts no client-only class was loaded.
    private CgGLBackend        glBackend;
    private CgGLContext        glContext;
    private CgLifecycleService lifecycle;
    private CgReloadService    reload;
    private CgResourceService  resources;
    private CgRenderingService rendering;

    @Override public CgGLBackend gl() {
        if (glBackend == null) {
            // Asked for at the first host section or capability probe, on the render thread, and never by
            // registration, so naming Blaze3D cannot reach a server -- and Minecraft's device exists by then,
            // which is what says which API this session renders through.
            if (GraphicsApi.vulkan()) {
                glBackend = vulkanBackend();
            } else {
                HostStateVerifier.announceIfEnabled();
                glBackend = new Blaze3dGLBackend();
                HostStateModern.install();
            }

            // The world and entity queries: client only for the same reason as the cursor below (they read the client level).
            CgPlatform.provide(CgWorldQuery.SERVICE, new WorldQueryModern());
            CgPlatform.provide(CgEntityQuery.SERVICE, new EntityQueryModern());
            CgPlatform.provide(CgHostCamera.SERVICE, new HostCameraModern());
            CgPlatform.provide(CgWorldSound.SERVICE, new WorldSoundModern());
            CgPlatform.provide(CgWorldStimulus.SERVICE, new WorldStimulusModern());
            CgPlatform.provide(CgGameDirectory.SERVICE, () -> Minecraft.getInstance().gameDirectory.toPath());
            // What WorldEventsModern.poll reports; the explosion and the broken block are the loaders' mixins'.
            //? if >=1.14 {
            CgWorldEvents.declare(CgWorldEvents.ENTITY_HURT | CgWorldEvents.ENTITY_DIED);
            //?}
            //? if >=1.16.5 {
            CgWorldEvents.declare(CgWorldEvents.LIGHTNING);
            //?}

            // The cursor slot, filled here so no consumer has to -- and HERE rather than in
            // getInstance() for the reason the note above gives: getInstance() runs on both sides, and
            // GlfwCursorService names org.lwjgl.glfw, which a dedicated server does not ship. This
            // method is a client event by construction, which is the same protection Blaze3D gets.
            //
            // The window arrives as a SUPPLIER, not a handle: GLFW's window outlives no recreation and
            // is not open yet when the backend is first built, so a captured long would be stale
            // exactly when it mattered. That supplier is the only Minecraft fact the adapter needs,
            // which is what lets it sit in tier 1 knowing nothing about this era.
            //? if >=26.3 {
            /*CgPlatform.provide(CgCursorService.SERVICE, new SdlCursorService());
            *///?} else {
            CgPlatform.provide(CgCursorService.SERVICE,
                    new GlfwCursorService(PlatformServiceModern::windowHandle));
            //?}
        }
        return glBackend;
    }

    @Override public CgGLContext capabilities() {
        if (glContext == null) glContext = GraphicsApi.vulkan() ? new CgTrackedGLContext() : new Lwjgl3GLContext();
        return glContext;
    }

    // GL's semantics over Minecraft's own Vulkan device, which hosts ours: 26.2 and later.
    private static CgGLBackend vulkanBackend() {
        //? if >=26.2 {
        /*Minecraft mc = Minecraft.getInstance();
        return Blaze3dVulkanHost.start(Windows.of(mc).getWidth(), Windows.of(mc).getHeight());
        *///?} else {
        throw new IllegalStateException("Minecraft renders through Vulkan only from 26.2");
        //?}
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

    // ── UI services — see the class javadoc ───────────────────────────────────────────────────────

    /** @see GlfwInputService */
    private CgInputService input;

    /** @see SoundService */
    private CgSoundService sound;


    @Override public CgInputService input() {
        // The window is the one Minecraft fact tier 1 needs, and it takes it as a supplier -- see
        // GlfwInputService. Built lazily and held as the SPI type, like every field here, so a
        // dedicated server never loads a class that names GLFW.
        //? if >=26.3 {
        /*if (input == null) input = new SdlInputService();
        *///?} else {
        if (input == null) input = new GlfwInputService(PlatformServiceModern::windowHandle);
        //?}
        return input;
    }

    /** The GLFW window, or 0 before one exists. The value tier 1 is handed rather than reaching for. */
    static long windowHandle() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || Windows.of(mc) == null) return 0L;
        return Windows.handle(mc);
    }

    @Override public CgSoundService sound() {
        if (sound == null) sound = new SoundService();
        return sound;
    }
}
