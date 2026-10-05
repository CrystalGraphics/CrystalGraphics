package com.crystalgraphics.mc.modern.platform.gl;

//? if <26.3 {
import com.crystalgraphics.mc.compat.CgIrisCompat;
import com.crystalgraphics.mc.modern.platform.Blaze3dTextureUnits;
import com.crystalgraphics.platform.gl.state.CgCheckedProvider;
import com.crystalgraphics.platform.gl.state.CgGlSlot;
import com.crystalgraphics.platform.gl.state.CgGlStateShadow;
import com.crystalgraphics.platform.gl.state.CgHostStateCache;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
//?}
//? if >=1.17 <1.19.2 {
/*import com.mojang.blaze3d.vertex.BufferUploader;

import java.util.ArrayList;
import java.util.List;
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
 * The state shadow's provider on Minecraft's OpenGL, 1.13 to 26.2: a scope that opens at a host entry takes the
 * host's state from Blaze3D's {@code GlStateManager} ({@link CgHostStateCache}) instead of {@code glGet}, checked
 * against the driver as {@link CgCheckedProvider} does.
 *
 * <pre>{@code
 * glBackend = new Blaze3dGLBackend();
 * HostStateModern.install();   // on the render thread, once the backend is in
 * }</pre>
 *
 * <ul>
 *   <li>What this adds to the cache: the program (0 below 1.21.5, where each draw unbinds or none is used; from
 *       1.21.5 its encoder's, which rebinds only when its own changes), the framebuffer (the main target below
 *       1.21.5; from 1.21.5 {@code GlStateManager}'s, found by name only) and the vertex input (none below 1.17,
 *       {@code BufferUploader}'s record to 1.19.1, and from 1.19.2 Minecraft's own before each draw).</li>
 *   <li>Nothing on 26.3 or under Vulkan.</li>
 * </ul>
 */
//? if <26.3 {
public final class HostStateModern extends CgCheckedProvider {

    private final CgHostStateCache cache;
    //? if >=1.21.5 {
    /*private final Object encoder;
    private final Field lastProgram, readFbo, writeFbo;
    *///?} elif >=1.17 <1.19.2 {
    /*private final Field[] uploaderBindings;   // BufferUploader's record of the array and buffers: restored as it stands
    *///?}

    private HostStateModern(CgHostStateCache cache, Era extra) {
        super("Blaze3D", cache.missing() | extra.slots);
        this.cache = cache;
        //? if >=1.21.5 {
        /*encoder = extra.encoder;
        lastProgram = extra.lastProgram;
        readFbo = extra.readFbo;
        writeFbo = extra.writeFbo;
        *///?} elif >=1.17 <1.19.2 {
        /*uploaderBindings = extra.uploaderBindings;
        *///?}
    }

    /** Makes this the shadow's provider, or leaves {@code glGet} where Blaze3D's cache cannot be found. */
    public static void install() {
        install("Blaze3D", () -> {
            //? if <1.15 {
            /*Class<?> gsm = GlStateManager.HOST;   // the same-package shim stands in for 1.14's names
            *///?} else {
            Class<?> gsm = GlStateManager.class;
            //?}
            CgHostStateCache cache = new CgHostStateCache(gsm, Blaze3dTextureUnits.count(), GlStateManager::_activeTexture,
                    () -> GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE));   // before CgGL has its backend
            return new HostStateModern(cache, new Era(cache));
        });
    }

    @Override
    protected void answer(CgGlSlot slot, CgGlStateShadow t) {
        if (cache.answer(slot, t)) return;
        switch (slot) {
            case PROGRAM:
                //? if >=1.21.5 {
                /*Object program = get(lastProgram, encoder);
                t.programId = program == null ? 0 : ((GlProgram) program).getProgramId();
                *///?} else {
                t.programId = 0;
                //?}
                return;
            case FBO:
                //? if >=1.21.5 {
                /*t.drawFbo = (Integer) get(writeFbo, null);
                t.readFbo = (Integer) get(readFbo, null);
                *///?} else {
                t.drawFbo = t.readFbo = Minecraft.getInstance().getMainRenderTarget().frameBufferId;
                //?}
                return;
            case VERTEX_INPUT:
                //? if <1.17 {
                /*// Fixed-function: Minecraft binds no vertex array, and unbinds its buffers after each draw.
                t.vertexArray = 0;
                t.arrayBuffer = 0;
                t.elementArrayBuffer = 0;
                *///?} elif <1.19.2 {
                /*t.vertexArray = (Integer) get(uploaderBindings[0], null);
                t.arrayBuffer = (Integer) get(uploaderBindings[1], null);
                t.elementArrayBuffer = (Integer) get(uploaderBindings[2], null);
                *///?} else {
                // Minecraft binds its own before each draw; Blaze3dGLBackend.toHost resets its record.
                t.vertexArray = 0;
                t.arrayBuffer = 0;
                t.elementArrayBuffer = CgGlStateShadow.UNKNOWN_BINDING;
                //?}
                return;
            default:
                throw new IllegalStateException("no Blaze3D answer for " + slot);
        }
    }

    private static Object get(Field f, Object holder) {
        try {
            return f.get(holder);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    protected void answerTextures(CgGlStateShadow t, int units) {
        cache.textures(t, units);
    }

    @Override
    protected int unitsMask() {
        return cache.unitsMask();
    }

    /** Every unit while an Iris or Oculus pack runs: it binds units above Blaze3D's table itself. */
    @Override
    public int hostUnits() {
        return CgIrisCompat.isShaderPackActive() ? -1 : super.hostUnits();
    }

    @Override
    protected void excuse(CgGlSlot slot, CgGlStateShadow answer, CgGlStateShadow truth) {
        cache.excuse(slot, answer, truth);
        //? if >=1.19.2 {
        if (slot == CgGlSlot.VERTEX_INPUT) {
            answer.vertexArray = truth.vertexArray;
            answer.arrayBuffer = truth.arrayBuffer;
            answer.elementArrayBuffer = truth.elementArrayBuffer;
        }
        //?}
        //? if >=1.21.5 {
        /*if (slot == CgGlSlot.PROGRAM && answer.programId == 0) answer.programId = truth.programId;   // none of its own yet
        *///?}
    }

    /** What this era adds to the cache, found once, and the domains it could not find. */
    private static final class Era {
        int slots;
        //? if >=1.21.5 {
        /*Object encoder;
        Field lastProgram, readFbo, writeFbo;
        *///?} elif >=1.17 <1.19.2 {
        /*Field[] uploaderBindings;
        *///?}

        Era(CgHostStateCache cache) throws IllegalAccessException {
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
                slots |= bit(CgGlSlot.PROGRAM);
            }
            readFbo = cache.field("readFbo");
            writeFbo = cache.field("writeFbo");
            // Renamed (Fabric's intermediary): the driver's, seldom asked since a stage entry binds the main target itself.
            if (readFbo == null || writeFbo == null) slots |= bit(CgGlSlot.FBO);
            *///?} elif >=1.17 <1.19.2 {
            /*List<Field> ints = new ArrayList<>();
            for (Field f : BufferUploader.class.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) || f.getType() != int.class || Modifier.isFinal(f.getModifiers())) continue;
                f.setAccessible(true);
                ints.add(f);
            }
            if (ints.size() >= 3) uploaderBindings = new Field[] {ints.get(0), ints.get(1), ints.get(2)};
            else slots |= bit(CgGlSlot.VERTEX_INPUT);
            *///?}
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

    /^* Nothing to install: {@code glGet} answers on 26.3. ^/
    public static void install() {}
}
*///?}
