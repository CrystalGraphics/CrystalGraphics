package com.crystalgraphics.mc.modern.platform;

import java.lang.reflect.Array;
import java.lang.reflect.Field;

import com.mojang.blaze3d.platform.GlStateManager;

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

    /** What 1.20.x declares. Used only when the real value cannot be read. */
    private static final int KNOWN_1_20_X = 12;

    private static int cached = -1;

    /** @return the number of texture units {@code GlStateManager} models */
    public static int count() {
        if (cached > 0) return cached;
        cached = derive();
        return cached;
    }

    private static int derive() {
        try {
            Field textures = GlStateManager.class.getDeclaredField("TEXTURES");
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
                    refused, KNOWN_1_20_X);
        }
        return KNOWN_1_20_X;
    }
}
