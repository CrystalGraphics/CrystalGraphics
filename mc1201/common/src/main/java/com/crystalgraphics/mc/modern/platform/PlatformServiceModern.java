package com.crystalgraphics.mc.modern.platform;

import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.mc.modern.platform.gl.Blaze3dGLBackend;
import com.crystalgraphics.mc.lwjgl3.GlfwCursorService;
import com.crystalgraphics.mc.lwjgl3.Lwjgl3GLContext;
import com.crystalgraphics.mc.modern.platform.service.GlfwInputService;
import com.crystalgraphics.mc.modern.platform.service.LifecycleService;
import com.crystalgraphics.mc.modern.platform.service.ReloadService;
import com.crystalgraphics.mc.modern.platform.service.RenderingService;
import com.crystalgraphics.mc.modern.platform.service.ResourceService;
import com.crystalgraphics.mc.modern.platform.service.SoundService;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgPlatformService;
import com.crystalgraphics.platform.service.CgCursorService;
import com.crystalgraphics.platform.gl.CgGLBackend;
import com.crystalgraphics.platform.gl.CgGLContext;
import com.crystalgraphics.platform.service.CgInputService;
import com.crystalgraphics.platform.service.CgLifecycleService;
import com.crystalgraphics.platform.service.CgReloadService;
import com.crystalgraphics.platform.service.CgRenderingService;
import com.crystalgraphics.platform.service.CgResourceService;
import com.crystalgraphics.platform.service.CgSoundService;

import net.minecraft.client.Minecraft;

/**
 * Complete MC 1.20.x platform bundle. Implements {@link CgPlatformService} by composing
 * the mc1201 service adapters. Register via {@code CgPlatform.register(PlatformServiceModern.getInstance())}.
 *
 * <p><b>Every service is built on demand and every field is typed as its SPI interface.</b> This used
 * to read "no GL calls are made in the constructor or static initializer" — true about what the code
 * <em>does</em>, and irrelevant to whether a class can be <em>loaded</em>, which is the trap that stopped
 * CrystalGraphics loading on a 1.7.10 dedicated server at all. See the note on the fields below.</p>
 *
 * <h3>⚠️ Both UI services below are unimplemented stubs</h3>
 * <p>{@link #input()} and {@link #sound()} exist and answer, but do nothing. They are written out rather
 * than inherited because {@link CgPlatformService} has no defaults — a platform must state its answer,
 * and "not yet" is a legitimate one as long as it is <em>visible</em>, which a stub in this file is and
 * an inherited no-op would not be.</p>
 *
 * <p>Each stub records what a real implementation needs; both are LWJGL3/GLFW jobs and materially easier
 * than the LWJGL2 equivalents in {@code mc1710}.</p>
 */
public final class PlatformServiceModern implements CgPlatformService {

    private static PlatformServiceModern instance;

    public static synchronized PlatformServiceModern getInstance() {
        if (instance == null) instance = new PlatformServiceModern();
        return instance;
    }

    // BUILT ON DEMAND, and typed as the SPI interfaces rather than the implementations.
    //
    // This is the mc1710 fix, applied ahead of the failure rather than after it. There it was
    // `public final` fields built at preInit, and a DEDICATED SERVER died at the first one:
    // NoClassDefFoundError: org/lwjgl/LWJGLException. Here it was worse -- the fields sat inside a
    // `static final INSTANCE`, so all eight were built at CLASS INIT, one step earlier in the
    // lifecycle than 1710's, and the `@Mod` constructor that calls getInstance() runs on BOTH SIDES.
    // (Fabric is exempt by construction: CrystalGraphicsFabric is a ClientModInitializer.)
    //
    // Lwjgl3GLContext is the concrete hazard -- it holds `private volatile GLCapabilities caps`, an
    // org.lwjgl.opengl FIELD DESCRIPTOR, and a dedicated 1.20.x server has no LWJGL on its classpath.
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
    // NOTE THIS CANNOT BE TESTED FROM ANY BUILD WE HAVE. mc1201 is commented out of settings.gradle.kts
    // in both repos, so nothing compiles it, let alone runs a server with it.
    //
    // And whether a Forge/NeoForge dev runServer would even show the fault is UNKNOWN. It uses the joined
    // artifact, so Minecraft's client classes are present; whether LWJGL is, is a ModDevGradle question
    // nobody here has measured. (The equivalent WAS measured on 1.7.10, where the guess turned out wrong
    // in the reassuring direction: RFG's server run has no LWJGL, so a dev server there fails exactly as
    // production does. A guess that was wrong once is not a basis for the other loader family.)
    //
    // So the shape is made unable to fail rather than argued about.
    private CgGLBackend        glBackend;
    private CgGLContext        glContext;
    private CgLifecycleService lifecycle;
    private CgReloadService    reload;
    private CgResourceService  resources;
    private CgRenderingService rendering;

    @Override public CgGLBackend gl() {
        if (glBackend == null) {
            // Declared before any GL work: CgBindingPoints allocates by counting down from the limit.
            // Here rather than onContextInit, which this loader never calls -- the context initialises
            // lazily from onOpaquePass on the first world render. Building the GL backend is a client
            // event by construction, so naming Blaze3D cannot reach a server.
            CgCapabilities.setHostTextureUnitCeiling(Blaze3dTextureUnits.count());
            glBackend = new Blaze3dGLBackend();

            // The cursor slot, filled here so no consumer has to -- and HERE rather than in
            // getInstance() for the reason the note above gives: getInstance() runs on both sides, and
            // GlfwCursorService names org.lwjgl.glfw, which a dedicated server does not ship. This
            // method is a client event by construction, which is the same protection Blaze3D gets.
            //
            // The window arrives as a SUPPLIER, not a handle: GLFW's window outlives no recreation and
            // is not open yet when the backend is first built, so a captured long would be stale
            // exactly when it mattered. That supplier is the only Minecraft fact the adapter needs,
            // which is what lets it sit in tier 1 knowing nothing about this era.
            CgPlatform.provide(CgCursorService.SERVICE, new GlfwCursorService(
                    () -> Minecraft.getInstance().getWindow().getWindow()));
        }
        return glBackend;
    }

    @Override public CgGLContext capabilities() {
        if (glContext == null) glContext = new Lwjgl3GLContext();
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

    // ── UI services — see the class javadoc ───────────────────────────────────────────────────────

    /** @see GlfwInputService */
    private CgInputService input;

    /** @see SoundService */
    private CgSoundService sound;


    @Override public CgInputService input() {
        if (input == null) input = new GlfwInputService();
        return input;
    }

    @Override public CgSoundService sound() {
        if (sound == null) sound = new SoundService();
        return sound;
    }
}
