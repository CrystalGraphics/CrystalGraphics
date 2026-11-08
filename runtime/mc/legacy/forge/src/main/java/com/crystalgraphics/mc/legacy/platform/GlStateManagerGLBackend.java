package com.crystalgraphics.mc.legacy.platform;

import com.crystalgraphics.lwjgl2.Lwjgl2GLBackend;

import net.minecraft.client.renderer.GlStateManager;
import org.apache.logging.log4j.LogManager;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * {@link Lwjgl2GLBackend} plus the host bookkeeping a section's edges cannot do. Forge 1.8–1.12.2's
 * {@link GlStateManager} skips a call its cache believes made; what the state shadow models of it, a host section sets
 * back to Minecraft's belief as it closes ({@code HostStateLegacy.sectionSlots()}), so every call reaches the driver
 * from tier 1 but the clear colour, which the cache keeps and the shadow does not model. {@code Blaze3dGLBackend} is
 * the modern counterpart.
 *
 * <ul>
 *   <li>{@link #trackedTextureUnits()} is how many units the cache models: what {@code HostStateLegacy} reads and sets
 *       back.</li>
 * </ul>
 */
public final class GlStateManagerGLBackend extends Lwjgl2GLBackend {

    /** What vanilla's {@code GlStateManager.textureState} holds on every legacy plateau. */
    private static final int VANILLA_TEXTURE_UNITS = 8;

    private static int trackedUnits = -1;

    /**
     * The units {@code GlStateManager}'s texture table holds, read from it: 8 in vanilla, more where OptiFine widens
     * the table.
     */
    public static int trackedTextureUnits() {
        if (trackedUnits < 0) trackedUnits = readTextureTable();
        return trackedUnits;
    }

    /** By name in a dev run, else by shape (SRG): the static array whose element holds an int and no boolean. */
    private static int readTextureTable() {
        try {
            Field table = null;
            for (Field field : GlStateManager.class.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) || !field.getType().isArray()) continue;
                if (field.getName().equals("textureState")) {
                    table = field;
                    break;
                }
                boolean binding = false, flag = false;
                for (Field member : field.getType().getComponentType().getDeclaredFields()) {
                    if (Modifier.isStatic(member.getModifiers())) continue;
                    binding |= member.getType() == int.class;
                    flag |= member.getType() == boolean.class;
                }
                if (binding && !flag && table == null) table = field;
            }
            if (table != null) {
                table.setAccessible(true);
                int length = Array.getLength(table.get(null));
                LogManager.getLogger("CrystalGraphics").info("[cg] GlStateManager models {} texture units", length);
                return length;
            }
        } catch (ReflectiveOperationException | RuntimeException refused) {
            LogManager.getLogger("CrystalGraphics").info("[cg] could not read GlStateManager's texture table ({}); "
                    + "assuming {} units", refused, VANILLA_TEXTURE_UNITS);
        }
        return VANILLA_TEXTURE_UNITS;
    }

    // Cached, and in no domain of the shadow's, so no section can set it back.
    @Override
    public void glClearColor(float r, float g, float b, float a) {
        GlStateManager.clearColor(r, g, b, a);
    }
}
