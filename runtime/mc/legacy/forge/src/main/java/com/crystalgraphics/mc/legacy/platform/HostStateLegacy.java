package com.crystalgraphics.mc.legacy.platform;

import com.crystalgraphics.platform.gl.state.CgCheckedProvider;
import com.crystalgraphics.platform.gl.state.CgGlSlot;
import com.crystalgraphics.platform.gl.state.CgGlStateShadow;
import com.crystalgraphics.platform.gl.state.CgHostStateCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

/**
 * The state shadow's provider on Forge 1.8.9 to 1.12.2: a scope that opens at a host entry takes the host's state
 * from {@link GlStateManager} ({@link CgHostStateCache}) instead of {@code glGet}, checked against the driver as
 * {@link CgCheckedProvider} does.
 *
 * <pre>{@code
 * glBackend = new GlStateManagerGLBackend();
 * HostStateLegacy.install();   // on the render thread, once the backend is in
 * }</pre>
 *
 * <ul>
 *   <li>What this adds to the cache, as Minecraft holds it at every hook: program 0 and no vertex array (it draws
 *       fixed-function), and the main framebuffer bound with the viewport covering it.</li>
 *   <li>A shader pack (OptiFine) binds its own framebuffer during the world: the checks catch one active at startup,
 *       and that domain reads {@code glGet} from then on. One switched on later is not caught.</li>
 * </ul>
 */
public final class HostStateLegacy extends CgCheckedProvider {

    private final CgHostStateCache cache;

    private HostStateLegacy(CgHostStateCache cache) {
        // Blend reads glGet: blendFunc sets the driver's alpha factors too, and records only the colour pair.
        super("GlStateManager", cache.missing() & ~bit(CgGlSlot.VIEWPORT) | bit(CgGlSlot.BLEND));
        this.cache = cache;
    }

    /** Makes this the shadow's provider, or leaves {@code glGet} where the cache cannot be found. */
    public static void install() {
        install("GlStateManager", () -> new HostStateLegacy(new CgHostStateCache(GlStateManager.class,
                GlStateManagerGLBackend.trackedTextureUnits(), GlStateManager::setActiveTexture,
                () -> GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE))));   // before CgGL has its backend
    }

    @Override
    protected void answer(CgGlSlot slot, CgGlStateShadow t) {
        if (cache.answer(slot, t)) return;
        Minecraft mc = Minecraft.getMinecraft();
        switch (slot) {
            case PROGRAM:
                t.programId = 0;
                return;
            case FBO:
                t.drawFbo = t.readFbo = OpenGlHelper.isFramebufferEnabled() ? mc.getFramebuffer().framebufferObject : 0;
                return;
            case VIEWPORT:
                t.viewportX = t.viewportY = 0;
                t.viewportW = mc.displayWidth;
                t.viewportH = mc.displayHeight;
                return;
            case VERTEX_INPUT:
                t.vertexArray = 0;
                t.arrayBuffer = 0;
                t.elementArrayBuffer = 0;
                return;
            default:
                throw new IllegalStateException("no GlStateManager answer for " + slot);
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

    @Override
    public void excuse(CgGlSlot slot, CgGlStateShadow answer, CgGlStateShadow truth) {
        cache.excuse(slot, answer, truth);
    }
}
