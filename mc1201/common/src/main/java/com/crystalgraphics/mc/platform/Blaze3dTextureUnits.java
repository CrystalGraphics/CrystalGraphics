package com.crystalgraphics.mc.platform;

import java.lang.reflect.Array;
import java.lang.reflect.Field;

import com.mojang.blaze3d.platform.GlStateManager;

import org.apache.logging.log4j.LogManager;

/**
 * How many texture units Minecraft's own GL state tracker models — <b>asked, not assumed</b>.
 *
 * <p>{@code GlStateManager} caches the active unit and each unit's binding in a fixed table. Binding
 * above it leaves the driver in a state that shadow cannot represent, and the damage lands on whoever
 * samples <b>unit 0</b> next — which in an embedded renderer is nearly every draw. A sampler that misses
 * its texture draws its declared default, so a premultiplied composite floods or erases rather than
 * simply missing an image.</p>
 *
 * <p>Read from Blaze3D rather than written down, so a later Minecraft is correct for free. A refusal is
 * a supported outcome, not an error — hence the fallback, and hence logging which one was used: a
 * derived ceiling and a guessed one render identically until the guess is wrong.</p>
 *
 * <p><b>CLIENT ONLY</b> — naming {@link GlStateManager} loads a client class.</p>
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
