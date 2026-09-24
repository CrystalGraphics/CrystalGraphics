package com.crystalgraphics.mc.modern.platform;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

//? if >=1.21.5 {
/*import com.mojang.blaze3d.opengl.GlStateManager;
*///?} else {
import com.mojang.blaze3d.platform.GlStateManager;
//?}

import org.apache.logging.log4j.LogManager;

/**
 * How many texture units Minecraft's own GL state tracker models, read from Blaze3D.
 *
 * <p>Declare it as the engine's ceiling before anything reserves a binding point:</p>
 * <pre>{@code
 * CgCapabilities.setHostTextureUnitCeiling(Blaze3dTextureUnits.count());
 * CgGraphicsLifecycle.initContext(width, height);
 * }</pre>
 *
 * <p>Binding above the table Blaze3D models corrupts sampling of unit 0, which in an embedded renderer
 * is nearly every draw. Read rather than hardcoded, so a later Minecraft is correct without an edit;
 * a refusal falls back and logs which value was used.</p>
 *
 * <p><b>Client only</b> — naming {@link GlStateManager} loads a client class.</p>
 */
public final class Blaze3dTextureUnits {

    private Blaze3dTextureUnits() {}

    /** What this node's Minecraft declares, should neither the name nor the shape find the table. */
    //? if >=1.16 {
    private static final int KNOWN = 12;
    //?} else {
    /*private static final int KNOWN = 8;
    *///?}

    private static int cached = -1;

    /** @return the number of texture units {@code GlStateManager} models */
    public static int count() {
        if (cached > 0) return cached;
        cached = derive();
        return cached;
    }

    private static int derive() {
        try {
            Field textures = textureTable();
            textures.setAccessible(true);
            int length = Array.getLength(textures.get(null));
            if (length > 0) {
                LogManager.getLogger("CrystalGraphics").info(
                        "[cg] Blaze3D models {} texture units (read from GlStateManager)", length);
                return length;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError refused) {
            // Not an error: a refusal here is a supported outcome. Said out loud because the fallback is
            // a guess about a version this build has never seen.
            LogManager.getLogger("CrystalGraphics").info(
                    "[cg] could not read GlStateManager's texture table ({}); assuming {} units",
                    refused, KNOWN);
        }
        return KNOWN;
    }

    /**
     * {@code TEXTURES} by name where the runtime is Mojang-named, else by shape: SRG and intermediary rename
     * it. The table is the static array whose element holds an {@code int} binding and no {@code boolean},
     * which is what sets it apart from the light table beside it.
     */
    private static Field textureTable() throws NoSuchFieldException {
        try {
            return GlStateManager.class.getDeclaredField("TEXTURES");
        } catch (NoSuchFieldException renamed) {
            for (Field field : GlStateManager.class.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) || !field.getType().isArray()) continue;
                boolean binding = false, flag = false;
                for (Field member : field.getType().getComponentType().getDeclaredFields()) {
                    if (Modifier.isStatic(member.getModifiers())) continue;
                    binding |= member.getType() == int.class;
                    flag |= member.getType() == boolean.class;
                }
                if (binding && !flag) return field;
            }
            throw renamed;
        }
    }
}
