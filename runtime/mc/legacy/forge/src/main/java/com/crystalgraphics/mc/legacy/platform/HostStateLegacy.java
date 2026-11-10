package com.crystalgraphics.mc.legacy.platform;

import java.lang.reflect.Modifier;
import java.lang.reflect.Field;
import java.lang.reflect.Array;
import com.crystalgraphics.platform.gl.driver.CgGLDriver;
import com.crystalgraphics.platform.gl.state.GLCheckedHostState;
import com.crystalgraphics.platform.gl.state.GLContextState;
import com.crystalgraphics.platform.gl.state.GLHostCache;
import com.crystalgraphics.platform.gl.state.GLHostState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import org.apache.logging.log4j.LogManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

/**
 * What Forge 1.8.9 to 1.12.2 believes its GL context holds, for the GL device's host sections: {@link GlStateManager}
 * ({@link GLHostCache}), each domain checked against the driver at first ({@link GLCheckedHostState}). {@code glGet}
 * where the cache is not found.
 *
 * <pre>{@code
 * CgGLDriver driver = new Lwjgl2GLDriver();
 * CgGLDevice device = new CgGLDevice(driver, translator, width, height, false, HostStateLegacy.create(driver));
 * }</pre>
 *
 * <ul>
 *   <li>What this adds to the cache, as Minecraft holds it at every hook: program 0 and no vertex array (it draws
 *       fixed-function), and the main framebuffer bound with the viewport covering it.</li>
 *   <li>Blend reads {@code glGet}: {@code blendFunc} sets the driver's alpha factors too, and records only the colour
 *       pair.</li>
 *   <li>A shader pack (OptiFine) binds its own framebuffer during the world: the checks catch one active at startup,
 *       and that domain reads {@code glGet} from then on. One switched on later is not caught.</li>
 * </ul>
 */
public final class HostStateLegacy {

    private HostStateLegacy() {}

    /** Minecraft's host state over {@code gl}, or {@code glGet}'s where the cache cannot be found. Render thread. */
    public static GLHostState create(CgGLDriver gl) {
        try {
            GLHostCache cache = new GLHostCache(GlStateManager.class, textureUnits(),
                    GlStateManager::setActiveTexture, () -> GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE));
            return new GLCheckedHostState("GlStateManager", gl, new Answers(cache), false);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError refused) {
            LogManager.getLogger("CrystalGraphics").info("[cg] GlStateManager's cache not found ({}): host sections read"
                    + " glGet", refused.toString());
            return GLHostState.byQuery(gl);
        }
    }

    private static final class Answers implements GLCheckedHostState.Answers {
        private final GLHostCache cache;

        Answers(GLHostCache cache) {
            this.cache = cache;
        }

        @Override
        public boolean answer(GLHostState.Domain domain, GLContextState s) {
            if (domain == GLHostState.Domain.BLEND) return false;
            if (cache.read(domain, s)) return true;
            Minecraft mc = Minecraft.getMinecraft();
            switch (domain) {
                case PROGRAM:
                    s.program = 0;
                    return true;
                case VERTEX_ARRAY:
                    s.vertexArray = 0;
                    return true;
                case FRAMEBUFFERS:
                    s.drawFramebuffer = s.readFramebuffer = OpenGlHelper.isFramebufferEnabled()
                            ? mc.getFramebuffer().framebufferObject : 0;
                    return true;
                case VIEWPORT:
                    s.viewportX = s.viewportY = 0;
                    s.viewportW = mc.displayWidth;
                    s.viewportH = mc.displayHeight;
                    return true;
                default:
                    return false;
            }
        }

        @Override
        public int alphaTest() {
            return cache.alphaTest();
        }
    }

    /** What vanilla's {@code GlStateManager.textureState} holds on every legacy plateau. */
    private static final int VANILLA_TEXTURE_UNITS = 8;

    /**
     * The units {@code GlStateManager}'s texture table holds, read from it: 8 in vanilla, more where OptiFine widens the
     * table. By name in a dev run, else by shape (SRG): the static array whose element holds an int and no boolean.
     */
    private static int textureUnits() {
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
}
