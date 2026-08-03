package com.crystalgraphics.mc.platform;

import com.crystalgraphics.mc.platform.gl.GL1201Backend;
import com.crystalgraphics.mc.platform.gl.GL1201Context;
import com.crystalgraphics.mc.platform.service.CursorService1201;
import com.crystalgraphics.mc.platform.service.LifecycleService1201;
import com.crystalgraphics.mc.platform.service.ReloadService1201;
import com.crystalgraphics.mc.platform.service.RenderingService1201;
import com.crystalgraphics.mc.platform.service.ResourceService1201;
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
 * <p>No GL calls are made in the constructor or static initializer — all GL work is deferred
 * to {@link CgClientLifecycleBridge#onRenderFrame} / {@code onContextInit}.</p>
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

    private static final PlatformService1201 INSTANCE = new PlatformService1201();

    public static PlatformService1201 getInstance() {
        return INSTANCE;
    }

    private final GL1201Context      glContext  = new GL1201Context();
    private final GL1201Backend      glBackend  = new GL1201Backend();
    private final LifecycleService1201 lifecycle  = new LifecycleService1201();
    private final ReloadService1201    reload     = new ReloadService1201();
    private final ResourceService1201  resources  = new ResourceService1201();
    private final RenderingService1201 rendering  = new RenderingService1201();

    @Override public CgGLBackend        gl()           { return glBackend; }
    @Override public CgGLContext         capabilities() { return glContext; }
    @Override public CgLifecycleService  lifecycle()    { return lifecycle; }
    @Override public CgReloadService     reload()       { return reload; }
    @Override public CgResourceService   resources()    { return resources; }
    @Override public CgRenderingService  rendering()    { return rendering; }

    // ── UI services — see the class javadoc ───────────────────────────────────────────────────────

    /**
     * <b>Stub.</b> A real one needs a GLFW keycode table: {@code CgKeyCodes} is LWJGL2-shaped, so unlike
     * mc1710 the translation methods here cannot be the identity. Key and button state come from
     * {@code GLFW.glfwGetKey} / {@code glfwGetMouseButton} against
     * {@code Minecraft.getInstance().getWindow().getWindow()}, and {@code howManyMouseButtons()} is
     * {@code GLFW_MOUSE_BUTTON_LAST + 1}. The clipboard is {@code Minecraft.keyboardHandler}'s
     * {@code getClipboard()} / {@code setClipboard(String)}.
     */
    private final CgInputService input = new CgInputService() {
        @Override public int getCurrentModifiers() { return 0; }
        @Override public int translateKeyboardCodes(int platformCode) { return platformCode; }
        @Override public boolean isKeyDown(int localKeyCode) { return false; }
        @Override public int translateMouseCodes(int platformCode) { return platformCode; }
        @Override public boolean isMouseDown(int localMouseCode) { return false; }
        @Override public int howManyMouseButtons() { return 0; }
        @Override public String getClipboard() { return ""; }
        @Override public void setClipboard(String text) { }
    };

    /**
     * <b>Stub.</b> A real one is
     * {@code Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(...))}, resolving
     * {@code soundId} through a {@code ResourceLocation} as {@code SoundService1710} does.
     */
    private final CgSoundService sound = soundId -> {};

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
    private final CgCursorService cursor = new CursorService1201();

    @Override public CgInputService      input()        { return input; }
    @Override public CgSoundService      sound()        { return sound; }
    @Override public CgCursorService     cursor()       { return cursor; }
}
