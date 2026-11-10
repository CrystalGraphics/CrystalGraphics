package com.crystalgraphics.mc.modern.platform.gl;

import com.crystalgraphics.platform.gl.GLDebug;
import com.crystalgraphics.platform.gl.driver.CgGLDriver;
import com.crystalgraphics.platform.gl.state.GLHostState;
//? if <26.3 {
import com.crystalgraphics.platform.service.CgShaderPacks;
import com.crystalgraphics.mc.modern.platform.Blaze3dTextureUnits;
import com.crystalgraphics.platform.gl.state.GLCheckedHostState;
import com.crystalgraphics.platform.gl.state.GLContextState;
import com.crystalgraphics.platform.gl.state.GLHostCache;
import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
//?}
//? if <1.21.5 {
import java.util.Arrays;
//?}
//? if >=1.17 <1.19.2 {
/*import java.util.ArrayList;
import java.util.List;
*///?}
//? if >=1.19.2 <1.21.5 {
import com.mojang.blaze3d.vertex.BufferUploader;
//?} elif >=1.17 <1.19.2 {
/*import com.mojang.blaze3d.vertex.BufferUploader;
*///?}
//? if >=26.1 <26.3 {
/*import com.mojang.blaze3d.opengl.GlProgram;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import com.mojang.blaze3d.systems.RenderSystem;
*///?} elif >=1.21.5 <26.1 {
/*import com.mojang.blaze3d.opengl.GlProgram;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
*///?} elif >=1.15 <1.21.5 {
import com.mojang.blaze3d.platform.GlStateManager;
//?}

/**
 * What Minecraft believes its GL context holds, 1.13 to 26.2, for the GL device's host sections: Blaze3D's
 * {@code GlStateManager} ({@link GLHostCache}), each domain checked against the driver at first
 * ({@link GLCheckedHostState}). {@code glGet} where the cache is not found, and on 26.3.
 *
 * <pre>{@code
 * CgGLDriver driver = new Lwjgl3GLDriver();
 * CgGLDevice device = new CgGLDevice(driver, translator, width, height, false, HostStateModern.create(driver));
 * }</pre>
 *
 * <ul>
 *   <li>What this adds to the cache: the program (0 below 1.21.5, where each draw unbinds or none is used; from
 *       1.21.5 its encoder's, which rebinds only when its own changes), the framebuffers (the main target below
 *       1.21.5; from 1.21.5 {@code GlStateManager}'s, found by name only) and the vertex array (none below 1.17,
 *       {@code BufferUploader}'s record to 1.19.1, and from 1.19.2 none, Minecraft binding its own before each draw).</li>
 *   <li>Every texture unit with {@code glGet} while an Iris or Oculus pack runs: it binds units above Blaze3D's table
 *       itself.</li>
 *   <li>The first section turns off the {@code GL_DEBUG_OUTPUT} Minecraft enables; 1.19.2 to 1.21.4 forget
 *       {@code BufferUploader}'s vertex array as each closes.</li>
 * </ul>
 */
//? if <26.3 {
public final class HostStateModern extends GLCheckedHostState {

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");
    /** {@code GL_DEBUG_OUTPUT}, from KHR_debug. */
    private static final int DEBUG_OUTPUT = 0x92E0;
    // gl.debugStacks and gl.debugPerf turn it on for themselves.
    private static final boolean KEEP_DEBUG_OUTPUT = GLDebug.KEEP_HOST_DEBUG_OUTPUT.get()
            || GLDebug.DEBUG_STACKS.get() || GLDebug.DEBUG_PERF.get();

    private boolean debugOutputChecked;

    private HostStateModern(CgGLDriver gl, Answers answers, boolean clipZeroToOne) {
        super("Blaze3D", gl, answers, clipZeroToOne);
    }

    /** Minecraft's host state over {@code gl}, or {@code glGet}'s where Blaze3D's cache cannot be found. Render thread. */
    public static GLHostState create(CgGLDriver gl) {
        try {
            //? if <1.15 {
            /*Class<?> gsm = GlStateManager.HOST;   // the same-package shim stands in for 1.14's names
            *///?} else {
            Class<?> gsm = GlStateManager.class;
            //?}
            GLHostCache cache = new GLHostCache(gsm, Blaze3dTextureUnits.count(), GlStateManager::_activeTexture,
                    () -> GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE));
            // Forge's early loading window binds its font on unit 6 with GL before GlStateManager exists (SimpleFont):
            // each unit back to the cache's belief, once, so the first check compares what Minecraft bound
            GLContextState held = new GLContextState();
            if (cache.read(Domain.TEXTURES, held)) {
                for (int unit = 0; unit < Blaze3dTextureUnits.count(); unit++) gl.glBindTextureUnit(unit, held.unitTexture[unit]);
            }
            //? if >=26.2 {
            /*boolean clipZeroToOne = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
            *///?} else {
            boolean clipZeroToOne = false;
            //?}
            return new HostStateModern(gl, new Blaze3d(cache), clipZeroToOne);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError refused) {
            LOGGER.info("[cg] Blaze3D's state cache not found ({}): host sections read glGet", refused.toString());
            return GLHostState.byQuery(gl);
        }
    }

    // Minecraft turns GL_DEBUG_OUTPUT on at its default glDebugVerbosity of 1; on NVIDIA that made every GL call
    // several times dearer (the blasts scene 15.4 -> 9.6 ms a frame). Off for the context, once: switching it per
    // section cost the driver 1.1 ms a switch.
    @Override
    public void entered(CgGLDriver gl) {
        super.entered(gl);
        if (debugOutputChecked) return;
        debugOutputChecked = true;
        if (!KEEP_DEBUG_OUTPUT && gl.hasExtension("GL_KHR_debug") && gl.glIsEnabled(DEBUG_OUTPUT)) {
            gl.glDisable(DEBUG_OUTPUT);
            LOGGER.info("[cg] Minecraft's GL debug output is off: on some drivers it slows every GL call "
                    + "(-Dcrystalgraphics.gl.keepHostDebugOutput=true keeps it)");
        }
    }

    // Minecraft binds its own vertex array before each draw, but remembers its immediate buffer's: that record goes
    // when the frame goes back.
    //? if >=1.19.2 <1.21.5 {
    @Override
    public void left() {
        BufferUploader.reset();
    }
    //?}

    /** The cache's answers and this era's additions to them. */
    private static final class Blaze3d implements Answers {
        private final GLHostCache cache;
        //? if >=1.21.5 {
        /*private Object encoder;
        private Field lastProgram;
        private final Field readFbo, writeFbo;
        *///?} elif >=1.17 <1.19.2 {
        /*private Field[] uploaderBindings;   // BufferUploader's record of the array and buffers
        *///?}

        Blaze3d(GLHostCache cache) throws IllegalAccessException {
            this.cache = cache;
            //? if >=1.21.5 {
            /*try {
                //? if >=26.1 {
                GpuDeviceBackend backend = (GpuDeviceBackend) fieldOfType(GpuDevice.class, GpuDeviceBackend.class).get(RenderSystem.getDevice());
                encoder = unwrap(backend.createCommandEncoder());
                //?} else {
                /^encoder = unwrap(RenderSystem.getDevice().createCommandEncoder());
                ^///?}
                lastProgram = fieldOfType(encoder.getClass(), GlProgram.class);
            } catch (ReflectiveOperationException | RuntimeException e) {
                lastProgram = null;   // glGet
            }
            // Renamed (Fabric's intermediary): glGet, seldom asked since a stage entry names the main target itself.
            readFbo = cache.field("readFbo");
            writeFbo = cache.field("writeFbo");
            *///?} elif >=1.17 <1.19.2 {
            /*List<Field> ints = new ArrayList<>();
            for (Field f : BufferUploader.class.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) || f.getType() != int.class || Modifier.isFinal(f.getModifiers())) continue;
                f.setAccessible(true);
                ints.add(f);
            }
            if (ints.size() >= 3) uploaderBindings = new Field[] {ints.get(0), ints.get(1), ints.get(2)};
            *///?}
        }

        @Override
        public boolean answer(Domain domain, GLContextState s) {
            if (domain == Domain.TEXTURES && CgShaderPacks.packInUse()) return false;
            if (cache.read(domain, s)) return true;
            switch (domain) {
                case PROGRAM:
                    //? if >=1.21.5 {
                    /*if (lastProgram == null) return false;
                    Object program = get(lastProgram, encoder);
                    s.program = program == null ? 0 : ((GlProgram) program).getProgramId();
                    *///?} else {
                    s.program = 0;
                    //?}
                    return true;
                case FRAMEBUFFERS:
                    //? if >=1.21.5 {
                    /*if (readFbo == null || writeFbo == null) return false;
                    s.drawFramebuffer = (Integer) get(writeFbo, null);
                    s.readFramebuffer = (Integer) get(readFbo, null);
                    *///?} else {
                    s.drawFramebuffer = s.readFramebuffer = Minecraft.getInstance().getMainRenderTarget().frameBufferId;
                    //?}
                    return true;
                case VERTEX_ARRAY:
                    //? if >=1.17 <1.19.2 {
                    /*if (uploaderBindings == null) return false;
                    s.vertexArray = (Integer) get(uploaderBindings[0], null);
                    *///?} else {
                    // None bound at a hook below 1.17; from 1.19.2 Minecraft binds its own before each draw
                    s.vertexArray = 0;
                    //?}
                    return true;
                default:
                    return false;
            }
        }

        @Override
        public void excuse(Domain domain, GLContextState answer) {
            //? if <1.21.5 {
            // _blendFunc caches the RGB pair alone, and glBlendFunc sets the alpha pair too
            if (domain == Domain.BLEND) {
                Arrays.fill(answer.blendSrcAlpha, GLContextState.UNKNOWN);
                Arrays.fill(answer.blendDstAlpha, GLContextState.UNKNOWN);
            }
            //?}
            //? if >=1.19.2 {
            if (domain == Domain.VERTEX_ARRAY) answer.vertexArray = GLContextState.UNKNOWN;   // whatever its last draw left
            //?}
            //? if >=1.21.5 {
            /*if (domain == Domain.PROGRAM && answer.program == 0) answer.program = GLContextState.UNKNOWN;   // none of its own yet
            *///?}
        }

        @Override
        public int alphaTest() {
            return cache.alphaTest();
        }
    }

    private static Object get(Field f, Object holder) {
        try {
            return f.get(holder);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    //? if >=1.21.5 {
    /*private static Field fieldOfType(Class<?> c, Class<?> type) throws NoSuchFieldException {
        for (Field f : c.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || !type.isAssignableFrom(f.getType())) continue;
            f.setAccessible(true);
            return f;
        }
        throw new NoSuchFieldException(c.getName() + " has no " + type.getSimpleName());
    }

    /^* The object holding a {@code GlProgram} under any delegating wrappers: NeoForge's validation layer is one. ^/
    private static Object unwrap(Object at) throws IllegalAccessException {
        for (int depth = 0; depth < 4 && at != null; depth++) {
            Object delegate = null;
            for (Field f : at.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (GlProgram.class.isAssignableFrom(f.getType())) return at;
                if (delegate == null && f.getType().isInterface() && f.getType().isInstance(at)) {
                    f.setAccessible(true);
                    delegate = f.get(at);
                }
            }
            if (delegate == null) return at;
            at = delegate;
        }
        return at;
    }
    *///?}
}
//?} else {
/*public final class HostStateModern {

    private HostStateModern() {}

    /^* {@code glGet}: 26.3 keeps no state cache to read. ^/
    public static GLHostState create(CgGLDriver gl) {
        return GLHostState.byQuery(gl);
    }
}
*///?}
