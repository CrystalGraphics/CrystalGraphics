package com.crystalgraphics.mc.platform;

import com.crystalgraphics.mc.platform.gl.GL1201Backend;
import com.crystalgraphics.mc.platform.gl.GL1201Context;
import com.crystalgraphics.mc.platform.service.CursorService1201;
import com.crystalgraphics.mc.platform.service.InputService1201;
import com.crystalgraphics.mc.platform.service.LifecycleService1201;
import com.crystalgraphics.mc.platform.service.ReloadService1201;
import com.crystalgraphics.mc.platform.service.RenderingService1201;
import com.crystalgraphics.mc.platform.service.ResourceService1201;
import com.crystalgraphics.mc.platform.service.SoundService1201;
import com.crystalgraphics.platform.CgPlatformService;
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
 * Complete MC 1.20.x platform bundle. Implements {@link CgPlatformService} by composing
 * the mc1201 service adapters. Register via {@code CgPlatform.register(PlatformService1201.getInstance())}.
 *
 * <p><b>Every service is built on demand and every field is typed as its SPI interface.</b> This used
 * to read "no GL calls are made in the constructor or static initializer" — true about what the code
 * <em>does</em>, and irrelevant to whether a class can be <em>loaded</em>, which is the trap that stopped
 * CrystalGraphics loading on a 1.7.10 dedicated server at all. See the note on the fields below.</p>
 *
 * <h3>⚠️ Two of the three UI services below are unimplemented stubs</h3>
 * <p>{@link #input()} and {@link #sound()} exist and answer, but do nothing. They are written out rather
 * than inherited because {@link CgPlatformService} has no defaults — a platform must state its answer,
 * and "not yet" is a legitimate one as long as it is <em>visible</em>, which a stub in this file is and
 * an inherited no-op would not be. {@link #cursor()} is now real; see {@link CursorService1201}.</p>
 *
 * <p>This module is commented out of {@code settings.gradle.kts} and does not compile from this build, so
 * <b>none of it is verified</b> — including the cursor service. Each stub records what a real
 * implementation needs; both remaining ones are LWJGL3/GLFW jobs and materially easier than the LWJGL2
 * equivalents in {@code mc1710}.</p>
 */
public final class PlatformService1201 implements CgPlatformService {

    private static PlatformService1201 instance;

    public static synchronized PlatformService1201 getInstance() {
        if (instance == null) instance = new PlatformService1201();
        return instance;
    }

    // BUILT ON DEMAND, and typed as the SPI interfaces rather than the implementations.
    //
    // This is the mc1710 fix, applied ahead of the failure rather than after it. There it was
    // `public final` fields built at preInit, and a DEDICATED SERVER died at the first one:
    // NoClassDefFoundError: org/lwjgl/LWJGLException. Here it was worse -- the fields sat inside a
    // `static final INSTANCE`, so all eight were built at CLASS INIT, one step earlier in the
    // lifecycle than 1710's, and the `@Mod` constructor that calls getInstance() runs on BOTH SIDES.
    // (Fabric is exempt by construction: CrystalGraphics1201Fabric is a ClientModInitializer.)
    //
    // GL1201Context is the concrete hazard -- it holds `private volatile GLCapabilities caps`, an
    // org.lwjgl.opengl FIELD DESCRIPTOR, and a dedicated 1.20.x server has no LWJGL on its classpath.
    // CursorService1201, ResourceService1201 and RenderingService1201 name net.minecraft.client types,
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
        if (glBackend == null) glBackend = new GL1201Backend();
        return glBackend;
    }

    @Override public CgGLContext capabilities() {
        if (glContext == null) glContext = new GL1201Context();
        return glContext;
    }

    @Override public CgLifecycleService lifecycle() {
        if (lifecycle == null) lifecycle = new LifecycleService1201();
        return lifecycle;
    }

    @Override public CgReloadService reload() {
        if (reload == null) reload = new ReloadService1201();
        return reload;
    }

    @Override public CgResourceService resources() {
        if (resources == null) resources = new ResourceService1201();
        return resources;
    }

    @Override public CgRenderingService rendering() {
        if (rendering == null) rendering = new RenderingService1201();
        return rendering;
    }

    // ── UI services — see the class javadoc ───────────────────────────────────────────────────────

    /** @see InputService1201 */
    private CgInputService input;

    /** @see SoundService1201 */
    private CgSoundService sound;

    /**
     * <b>Implemented</b> — see {@link CursorService1201}. Still unverified, like everything in this
     * module, because it does not compile from this build.
     *
     * <p>The note that used to sit here said this was the easy one, "no bitmaps, unlike
     * {@code CursorService1710}". Mostly right, and worth correcting rather than deleting: GLFW's standard
     * set does cover almost everything, but <b>not {@code slide-arrow}</b> — no toolkit has it, which is
     * why the {@code CgCursor} value exists at all — so that one is drawn from {@code CgCursorBitmaps},
     * the same artwork mc1710 uses. The diagonals and the four-way keep a bitmap fallback for a subtler
     * reason spelled out in {@link CursorService1201}: their standard shapes are GLFW 3.4, and a native
     * that does not know one returns {@code NULL} rather than complaining.</p>
     */
    private CgCursorService cursor;

    @Override public CgInputService input() {
        if (input == null) input = new InputService1201();
        return input;
    }

    @Override public CgSoundService sound() {
        if (sound == null) sound = new SoundService1201();
        return sound;
    }

    @Override public CgCursorService cursor() {
        if (cursor == null) cursor = new CursorService1201();
        return cursor;
    }
}
