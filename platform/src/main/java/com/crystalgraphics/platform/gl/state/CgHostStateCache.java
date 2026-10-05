package com.crystalgraphics.platform.gl.state;

import com.crystalgraphics.platform.gl.CgGL;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

import static com.crystalgraphics.platform.gl.state.CgCheckedProvider.bit;

/**
 * Reads a host GL state cache shaped like Minecraft's {@code GlStateManager}, on every version that has one (legacy
 * Forge 1.8.9 to 26.2), for a {@link CgCheckedProvider} to answer from.
 *
 * <pre>{@code
 * CgHostStateCache cache = new CgHostStateCache(GlStateManager.class, 8,
 *         unit -> GlStateManager.setActiveTexture(unit),            // how the host switches units, through its cache
 *         () -> GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE));         // the driver's unit, read once at construction
 * if (cache.answer(slot, t)) return;                                // what it holds; false for PROGRAM, FBO, VERTEX_INPUT
 * cache.textures(t, units);
 * }</pre>
 *
 * <ul>
 *   <li>Fields are found by shape, so SRG, intermediary and MCP names all work: each state object carries the GL cap
 *       of its {@code BooleanState}. Mojang's names are tried first.</li>
 *   <li>{@link #missing()} is what it found no state for: the alpha test from 1.17, the viewport on legacy Forge and
 *       from 1.21.5. Where it keeps no scissor (legacy Forge to 1.16.3) the test is answered off, as at every hook.</li>
 *   <li>Construct it on the render thread: where the active unit's field is renamed, it is found by switching the
 *       unit through the host and back.</li>
 *   <li>{@code -Dcrystalgraphics.host.stateCache.byShape=true} skips the names, as on a renamed host, for a dev run.</li>
 * </ul>
 */
public final class CgHostStateCache {

    private static final boolean BY_SHAPE = Boolean.getBoolean("crystalgraphics.host.stateCache.byShape");

    private static final int GL_BLEND = 3042, GL_DEPTH_TEST = 2929, GL_CULL_FACE = 2884, GL_POLYGON_OFFSET_FILL = 32823,
            GL_POLYGON_OFFSET_LINE = 10754, GL_SCISSOR_TEST = 3089, GL_ALPHA_TEST = 3008;

    private final Class<?> gsm;
    private final int units, unitsMask;
    private int missing;

    private Flag blendOn, depthOn, cullOn, fillOn, lineOn, scissorOn, alphaOn;
    private Object blend, depth, cull, offset, alpha, colorMask, textures, viewport;
    private Field depthMask, depthFunc, cullFace, offsetFactor, offsetUnits, alphaFunc, alphaRef, colorMaskInt;
    private Field binding, activeTexture;
    private Field[] blendFactors, colorBits, viewportBox;

    /**
     * @param gsm               the host's cache class
     * @param units             the texture units it models
     * @param setActiveTexture  switches the unit through the host's cache, given {@code GL_TEXTURE0 + unit}
     * @param driverActiveTexture the driver's {@code GL_ACTIVE_TEXTURE}, read at most once, here
     */
    public CgHostStateCache(Class<?> gsm, int units, IntConsumer setActiveTexture, IntSupplier driverActiveTexture)
            throws IllegalAccessException {
        this.gsm = gsm;
        this.units = units;
        this.unitsMask = (1 << units) - 1;
        blend = state("BLEND", GL_BLEND);
        depth = state("DEPTH", GL_DEPTH_TEST);
        cull = state("CULL", GL_CULL_FACE);
        offset = state("POLY_OFFSET", GL_POLYGON_OFFSET_FILL);
        alpha = state("ALPHA_TEST", GL_ALPHA_TEST);
        Object scissor = state("SCISSOR", GL_SCISSOR_TEST);
        if (blend != null) {
            blendOn = Flag.of(blend, GL_BLEND);
            blendFactors = fieldsOf(blend.getClass(), int.class);
        }
        if (blendOn == null || (blendFactors.length != 4 && blendFactors.length != 6)) missing |= bit(CgGlSlot.BLEND);
        if (depth != null) {
            depthOn = Flag.of(depth, GL_DEPTH_TEST);
            depthMask = first(depth.getClass(), boolean.class);
            depthFunc = first(depth.getClass(), int.class);
        }
        if (depthOn == null || depthMask == null || depthFunc == null) missing |= bit(CgGlSlot.DEPTH);
        if (cull != null) {
            cullOn = Flag.of(cull, GL_CULL_FACE);
            cullFace = first(cull.getClass(), int.class);
        }
        if (cullOn == null) missing |= bit(CgGlSlot.CULL);
        if (offset != null) {
            fillOn = Flag.of(offset, GL_POLYGON_OFFSET_FILL);
            lineOn = Flag.of(offset, GL_POLYGON_OFFSET_LINE);
            Field[] floats = fieldsOf(offset.getClass(), float.class);
            if (floats.length == 2) {
                offsetFactor = floats[0];
                offsetUnits = floats[1];
            }
        }
        if (fillOn == null || offsetFactor == null) missing |= bit(CgGlSlot.POLYGON_OFFSET);
        if (alpha != null) {
            alphaOn = Flag.of(alpha, GL_ALPHA_TEST);
            alphaFunc = first(alpha.getClass(), int.class);
            alphaRef = first(alpha.getClass(), float.class);
        }
        if (alphaOn == null || alphaFunc == null || alphaRef == null) missing |= bit(CgGlSlot.ALPHA_TEST);
        scissorOn = scissor == null ? null : Flag.of(scissor, GL_SCISSOR_TEST);
        findColorMask();
        findTextures(setActiveTexture, driverActiveTexture);
        findViewport();
    }

    /** The domains it found no state for, as {@link CgCheckedProvider#bit} bits. */
    public int missing() {
        return missing;
    }

    /** The units it models, as a mask. */
    public int unitsMask() {
        return unitsMask;
    }

    /** The host's static field {@code name}, or null where it is renamed. */
    public Field field(String name) {
        return named(gsm, name);
    }

    /**
     * Fills {@code slot}'s fields of {@code t}: from the cache, or for stencil, polygon mode, line width and point size
     * with what Minecraft holds at every hook. False for a domain it does not answer, which the caller's is.
     */
    public boolean answer(CgGlSlot slot, CgGlStateShadow t) {
        try {
            switch (slot) {
                case BLEND: {
                    t.blendEnabled = blendOn.get();
                    t.blendSrcRgb = blendFactors[0].getInt(blend);
                    t.blendDstRgb = blendFactors[1].getInt(blend);
                    boolean equations = blendFactors.length == 6;   // 26.2 keeps the equation beside each pair
                    t.blendSrcAlpha = blendFactors[equations ? 3 : 2].getInt(blend);
                    t.blendDstAlpha = blendFactors[equations ? 4 : 3].getInt(blend);
                    t.blendEqRgb = equations ? blendFactors[2].getInt(blend) : CgGL.GL_FUNC_ADD;
                    t.blendEqAlpha = equations ? blendFactors[5].getInt(blend) : CgGL.GL_FUNC_ADD;
                    return true;
                }
                case DEPTH:
                    t.depthTest = depthOn.get();
                    t.depthMask = depthMask.getBoolean(depth);
                    t.depthFunc = depthFunc.getInt(depth);
                    return true;
                case CULL:
                    t.cullEnabled = cullOn.get();
                    t.cullFace = cullFace != null ? cullFace.getInt(cull) : CgGL.GL_BACK;
                    t.frontFace = CgGL.GL_CCW;
                    return true;
                case ALPHA_TEST:
                    t.alphaTest = alphaOn.get();
                    t.alphaFunc = alphaFunc.getInt(alpha);
                    t.alphaRef = alphaRef.getFloat(alpha);
                    return true;
                case STENCIL:
                    // Vanilla never tests stencil; NeoForge sets all of it with each draw that does.
                    t.stencilTest = false;
                    t.stencilFunc = CgGL.GL_ALWAYS;
                    t.stencilRef = 0;
                    t.stencilValueMask = -1;
                    t.stencilWriteMask = -1;
                    t.stencilFail = t.stencilZFail = t.stencilZPass = CgGL.GL_KEEP;
                    return true;
                case COLOR_MASK: {
                    int nibble;
                    if (colorMaskInt != null) {
                        nibble = colorMask != null ? Array.getInt(colorMask, 0) : colorMaskInt.getInt(null);
                    } else {
                        nibble = (colorBits[0].getBoolean(colorMask) ? 1 : 0) | (colorBits[1].getBoolean(colorMask) ? 2 : 0)
                                | (colorBits[2].getBoolean(colorMask) ? 4 : 0) | (colorBits[3].getBoolean(colorMask) ? 8 : 0);
                    }
                    int packed = 0;
                    for (int i = 0; i < 8; i++) packed |= (nibble & 0xF) << (i * 4);
                    t.colorMaskPacked = packed;
                    return true;
                }
                case VIEWPORT:
                    if (viewport == null) return false;
                    t.viewportX = viewportBox[0].getInt(viewport);
                    t.viewportY = viewportBox[1].getInt(viewport);
                    t.viewportW = viewportBox[2].getInt(viewport);
                    t.viewportH = viewportBox[3].getInt(viewport);
                    return true;
                case SCISSOR:
                    t.scissorTest = scissorOn != null && scissorOn.get();   // off at every hook where none is kept
                    // The box is set with every enable, so what stands here is never read.
                    t.scissorX = t.scissorY = 0;
                    t.scissorW = t.scissorH = 1;
                    return true;
                case POLYGON_OFFSET:
                    t.polygonOffsetFill = fillOn.get();
                    t.polygonOffsetLine = lineOn != null && lineOn.get();
                    t.polygonOffsetPoint = false;
                    t.polygonOffsetFactor = offsetFactor.getFloat(offset);
                    t.polygonOffsetUnits = offsetUnits.getFloat(offset);
                    return true;
                case POLYGON_MODE:
                    t.polygonModeFront = t.polygonModeBack = CgGL.GL_FILL;
                    return true;
                case LINE_WIDTH:
                    t.lineWidth = 1f;
                    return true;
                case POINT_SIZE:
                    t.pointSize = 1f;
                    return true;
                default:
                    return false;
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The active unit and each unit in {@code units}' 2D binding. */
    public void textures(CgGlStateShadow t, int units) {
        try {
            int active = activeTexture.getInt(null);
            if (active >= CgGL.GL_TEXTURE0) active -= CgGL.GL_TEXTURE0;   // a version keeping the enum
            t.activeTextureUnit = active;
            for (int unit = 0; unit < this.units; unit++) {
                if ((units & (1 << unit)) != 0) t.boundTexture2D[unit] = binding.getInt(Array.get(textures, unit));
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The scissor box, which it guesses: see {@link CgCheckedProvider#excuse}. */
    public void excuse(CgGlSlot slot, CgGlStateShadow answer, CgGlStateShadow truth) {
        if (slot != CgGlSlot.SCISSOR) return;
        answer.scissorX = truth.scissorX;
        answer.scissorY = truth.scissorY;
        answer.scissorW = truth.scissorW;
        answer.scissorH = truth.scissorH;
    }

    // ── Finding the cache ─────────────────────────────────────────────────

    private void findColorMask() throws IllegalAccessException {
        Field f = named(gsm, "COLOR_MASK");
        if (f != null && (f.getType() == int.class || f.getType() == int[].class)) {
            colorMaskInt = f;                       // 26.1 keeps a write mask, 26.2 one per draw buffer
            colorMask = f.getType() == int[].class ? f.get(null) : null;
            return;
        }
        if (f != null && fieldsOf(f.getType(), boolean.class).length == 4) {
            colorMask = f.get(null);
            colorBits = fieldsOf(f.getType(), boolean.class);
            return;
        }
        for (Field s : statics(gsm)) {
            Object value = s.get(null);
            if (value == null || value.getClass().isArray() || value.getClass().getDeclaringClass() != gsm) continue;
            Field[] booleans = fieldsOf(value.getClass(), boolean.class);
            if (booleans.length == 4 && fields(value.getClass()).size() == 4) {
                colorMask = value;
                colorBits = booleans;
                return;
            }
        }
        missing |= bit(CgGlSlot.COLOR_MASK);
    }

    private void findTextures(IntConsumer setActiveTexture, IntSupplier driverActiveTexture) throws IllegalAccessException {
        Field f = named(gsm, "TEXTURES");
        if (f == null) {
            Class<?> flag = flagClass();
            for (Field s : statics(gsm)) {
                if (!s.getType().isArray() || s.getType().getComponentType().isPrimitive()) continue;
                // One int, the binding: not 26.2's blend per draw buffer, nor the light table of BooleanStates below 1.17.
                Class<?> element = s.getType().getComponentType();
                if (element.getDeclaringClass() != gsm || fieldsOf(element, int.class).length != 1 || element == flag) continue;
                f = s;
                break;
            }
        }
        Object table = f == null ? null : f.get(null);
        if (table == null || Array.getLength(table) < units) {
            missing |= bit(CgGlSlot.TEXTURES);
            return;
        }
        textures = table;
        binding = first(table.getClass().getComponentType(), int.class);
        activeTexture = named(gsm, "activeTexture");
        if (activeTexture == null) activeTexture = probeActiveTexture(setActiveTexture, driverActiveTexture);
        if (activeTexture == null || activeTexture.getType() != int.class) missing |= bit(CgGlSlot.TEXTURES);
    }

    /** The static int switching units writes, where it is renamed: set to a unit none holds, then back. */
    private Field probeActiveTexture(IntConsumer setActiveTexture, IntSupplier driverActiveTexture) throws IllegalAccessException {
        Field[] ints = staticInts(gsm);
        int[] before = new int[ints.length];
        for (int i = 0; i < ints.length; i++) before[i] = ints[i].getInt(null);
        int unit = 1;
        while (unit < units && contains(before, unit)) unit++;
        if (unit == units) return null;
        int original = driverActiveTexture.getAsInt();
        setActiveTexture.accept(CgGL.GL_TEXTURE0 + unit);
        Field found = null;
        boolean ambiguous = false;
        for (int i = 0; i < ints.length; i++) {
            if (ints[i].getInt(null) != unit) continue;
            ambiguous |= found != null;
            found = ints[i];
        }
        setActiveTexture.accept(original);
        return ambiguous ? null : found;
    }

    /** A nested enum of one constant and four ints, before 1.21.5; legacy Forge keeps none. */
    private void findViewport() {
        for (Class<?> nested : gsm.getDeclaredClasses()) {
            if (!nested.isEnum() || nested.getEnumConstants().length != 1) continue;
            Field[] ints = fieldsOf(nested, int.class);
            if (ints.length != 4) continue;
            viewport = nested.getEnumConstants()[0];
            viewportBox = ints;
            return;
        }
        missing |= bit(CgGlSlot.VIEWPORT);
    }

    /** {@code BooleanState}'s class, from a flag already found, or null. */
    private Class<?> flagClass() {
        Flag any = blendOn != null ? blendOn : depthOn != null ? depthOn : cullOn;
        return any == null ? null : any.state.getClass();
    }

    // ── Shape ─────────────────────────────────────────────────────────────

    /** The static state object by Mojang's name, else the one holding a {@code BooleanState} of {@code cap}. */
    private Object state(String name, int cap) throws IllegalAccessException {
        Field f = named(gsm, name);
        if (f != null) return element(f.get(null));
        for (Field s : statics(gsm)) {
            Object value = element(s.get(null));
            if (value != null && value.getClass().getDeclaringClass() == gsm && Flag.of(value, cap) != null) return value;
        }
        return null;
    }

    /** An array's first element: 26.2 keeps blend per draw buffer, and the engine draws into the first. */
    private static Object element(Object value) {
        return value != null && value.getClass().isArray() && !value.getClass().getComponentType().isPrimitive()
                && Array.getLength(value) > 0 ? Array.get(value, 0) : value;
    }

    private static Field named(Class<?> c, String name) {
        if (BY_SHAPE) return null;
        try {
            Field f = c.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException renamed) {
            return null;
        }
    }

    private static Field[] staticInts(Class<?> c) {
        List<Field> out = new ArrayList<>();
        for (Field f : c.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || f.getType() != int.class || Modifier.isFinal(f.getModifiers())) continue;
            f.setAccessible(true);
            out.add(f);
        }
        return out.toArray(new Field[0]);
    }

    private static List<Field> statics(Class<?> c) {
        List<Field> out = new ArrayList<>();
        for (Field f : c.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
            f.setAccessible(true);
            out.add(f);
        }
        return out;
    }

    /** {@code c}'s instance fields, in declaration order. */
    private static List<Field> fields(Class<?> c) {
        List<Field> out = new ArrayList<>();
        for (Field f : c.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            f.setAccessible(true);
            out.add(f);
        }
        return out;
    }

    /** {@code c}'s instance fields of {@code type} in order; {@code Object} for every reference type. */
    private static Field[] fieldsOf(Class<?> c, Class<?> type) {
        List<Field> out = new ArrayList<>();
        for (Field f : fields(c)) if (type == Object.class ? !f.getType().isPrimitive() : f.getType() == type) out.add(f);
        return out.toArray(new Field[0]);
    }

    private static Field first(Class<?> c, Class<?> type) {
        Field[] all = fieldsOf(c, type);
        return all.length == 0 ? null : all[0];
    }

    private static boolean contains(int[] values, int v) {
        for (int value : values) if (value == v) return true;
        return false;
    }

    /** A {@code BooleanState}: a GL cap and whether the host believes it enabled. */
    private static final class Flag {
        private final Object state;
        private final Field enabled;

        private Flag(Object state, Field enabled) {
            this.state = state;
            this.enabled = enabled;
        }

        boolean get() throws IllegalAccessException {
            return enabled.getBoolean(state);
        }

        /** The {@code BooleanState} of {@code cap} among {@code holder}'s fields, or null. */
        static Flag of(Object holder, int cap) throws IllegalAccessException {
            for (Field f : fields(holder.getClass())) {
                if (f.getType().isPrimitive()) continue;
                Object candidate = f.get(holder);
                if (candidate == null) continue;
                Field[] ints = fieldsOf(candidate.getClass(), int.class);
                Field[] flags = fieldsOf(candidate.getClass(), boolean.class);
                if (ints.length != 1 || flags.length != 1 || fields(candidate.getClass()).size() != 2) continue;
                if (ints[0].getInt(candidate) == cap) return new Flag(candidate, flags[0]);
            }
            return null;
        }
    }
}
