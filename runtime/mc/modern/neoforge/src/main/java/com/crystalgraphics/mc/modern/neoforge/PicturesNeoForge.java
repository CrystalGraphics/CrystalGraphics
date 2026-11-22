package com.crystalgraphics.mc.modern.neoforge;

//? if >=1.21.6 <1.21.10 {
/*import com.crystalgraphics.mc.modern.platform.PicturesModern;
import java.util.List;
import net.minecraft.client.gui.render.pip.GuiBannerResultRenderer;
import net.minecraft.client.gui.render.pip.GuiBookModelRenderer;
import net.minecraft.client.gui.render.pip.GuiEntityRenderer;
import net.minecraft.client.gui.render.pip.GuiProfilerChartRenderer;
import net.minecraft.client.gui.render.pip.GuiSignRenderer;
import net.minecraft.client.gui.render.pip.GuiSkinRenderer;
import net.minecraft.client.gui.render.state.pip.GuiBannerResultRenderState;
import net.minecraft.client.gui.render.state.pip.GuiBookModelRenderState;
import net.minecraft.client.gui.render.state.pip.GuiEntityRenderState;
import net.minecraft.client.gui.render.state.pip.GuiProfilerChartRenderState;
import net.minecraft.client.gui.render.state.pip.GuiSignRenderState;
import net.minecraft.client.gui.render.state.pip.GuiSkinRenderState;
import net.neoforged.neoforge.client.ClientHooks;
import net.neoforged.neoforge.client.gui.PictureInPictureRendererRegistration;
*///?} elif >=1.21.10 <26.1 {
/*import com.crystalgraphics.mc.modern.platform.PicturesModern;
import java.util.List;
import net.minecraft.client.gui.render.pip.GuiBannerResultRenderer;
import net.minecraft.client.gui.render.pip.GuiBookModelRenderer;
import net.minecraft.client.gui.render.pip.GuiEntityRenderer;
import net.minecraft.client.gui.render.pip.GuiProfilerChartRenderer;
import net.minecraft.client.gui.render.pip.GuiSignRenderer;
import net.minecraft.client.gui.render.pip.GuiSkinRenderer;
import net.minecraft.client.gui.render.state.pip.GuiBannerResultRenderState;
import net.minecraft.client.gui.render.state.pip.GuiBookModelRenderState;
import net.minecraft.client.gui.render.state.pip.GuiEntityRenderState;
import net.minecraft.client.gui.render.state.pip.GuiProfilerChartRenderState;
import net.minecraft.client.gui.render.state.pip.GuiSignRenderState;
import net.minecraft.client.gui.render.state.pip.GuiSkinRenderState;
import net.neoforged.neoforge.client.ClientHooks;
import net.neoforged.neoforge.client.gui.PictureInPictureRendererRegistration;
*///?} elif >=26.1 <26.2 {
/*import com.crystalgraphics.mc.modern.platform.PicturesModern;
import java.util.List;
import net.minecraft.client.gui.render.pip.GuiBannerResultRenderer;
import net.minecraft.client.gui.render.pip.GuiBookModelRenderer;
import net.minecraft.client.gui.render.pip.GuiEntityRenderer;
import net.minecraft.client.gui.render.pip.GuiProfilerChartRenderer;
import net.minecraft.client.gui.render.pip.GuiSignRenderer;
import net.minecraft.client.gui.render.pip.GuiSkinRenderer;
import net.minecraft.client.renderer.state.gui.pip.GuiBannerResultRenderState;
import net.minecraft.client.renderer.state.gui.pip.GuiBookModelRenderState;
import net.minecraft.client.renderer.state.gui.pip.GuiEntityRenderState;
import net.minecraft.client.renderer.state.gui.pip.GuiProfilerChartRenderState;
import net.minecraft.client.renderer.state.gui.pip.GuiSignRenderState;
import net.minecraft.client.renderer.state.gui.pip.GuiSkinRenderState;
import net.neoforged.neoforge.client.ClientHooks;
import net.neoforged.neoforge.client.gui.PictureInPictureRendererRegistration;
*///?} elif >=26.2 {
/*import com.crystalgraphics.mc.modern.platform.PicturesModern;
import java.util.List;
import net.minecraft.client.gui.render.pip.GuiBannerResultRenderer;
import net.minecraft.client.gui.render.pip.GuiBookModelRenderer;
import net.minecraft.client.gui.render.pip.GuiEntityRenderer;
import net.minecraft.client.gui.render.pip.GuiProfilerChartRenderer;
import net.minecraft.client.gui.render.pip.GuiSkinRenderer;
import net.minecraft.client.renderer.state.gui.pip.GuiBannerResultRenderState;
import net.minecraft.client.renderer.state.gui.pip.GuiBookModelRenderState;
import net.minecraft.client.renderer.state.gui.pip.GuiEntityRenderState;
import net.minecraft.client.renderer.state.gui.pip.GuiProfilerChartRenderState;
import net.minecraft.client.renderer.state.gui.pip.GuiSkinRenderState;
import net.neoforged.neoforge.client.ClientHooks;
import net.neoforged.neoforge.client.gui.PictureInPictureRendererRegistration;
*///?}

/**
 * NeoForge's {@code GuiRenderer} takes picture-in-picture registrations, every mod's gathered, where vanilla's takes
 * renderers: the pictures' own {@code GuiRenderer} (1.21.6 on) gets vanilla's list as registrations, and every mod's.
 * Each factory makes a new renderer: NeoForge pools them.
 */
final class PicturesNeoForge {

    private PicturesNeoForge() {
    }

    static void install() {
        //? if >=1.21.6 <1.21.10 {
        /*PicturesModern.pictureInPicture(mc -> ClientHooks.gatherPictureInPictureRenderers(List.of(
                new PictureInPictureRendererRegistration<>(GuiEntityRenderState.class,
                        buffers -> new GuiEntityRenderer(buffers, mc.getEntityRenderDispatcher())),
                new PictureInPictureRendererRegistration<>(GuiSkinRenderState.class, GuiSkinRenderer::new),
                new PictureInPictureRendererRegistration<>(GuiBookModelRenderState.class, GuiBookModelRenderer::new),
                new PictureInPictureRendererRegistration<>(GuiBannerResultRenderState.class, GuiBannerResultRenderer::new),
                new PictureInPictureRendererRegistration<>(GuiSignRenderState.class, GuiSignRenderer::new),
                new PictureInPictureRendererRegistration<>(GuiProfilerChartRenderState.class, GuiProfilerChartRenderer::new))));
        *///?} elif >=1.21.10 <26.2 {
        /*PicturesModern.pictureInPicture(mc -> ClientHooks.gatherPictureInPictureRenderers(List.of(
                new PictureInPictureRendererRegistration<>(GuiEntityRenderState.class,
                        buffers -> new GuiEntityRenderer(buffers, mc.getEntityRenderDispatcher())),
                new PictureInPictureRendererRegistration<>(GuiSkinRenderState.class, GuiSkinRenderer::new),
                new PictureInPictureRendererRegistration<>(GuiBookModelRenderState.class, GuiBookModelRenderer::new),
                new PictureInPictureRendererRegistration<>(GuiBannerResultRenderState.class,
                        buffers -> new GuiBannerResultRenderer(buffers, mc.getAtlasManager())),
                new PictureInPictureRendererRegistration<>(GuiSignRenderState.class,
                        buffers -> new GuiSignRenderer(buffers, mc.getAtlasManager())),
                new PictureInPictureRendererRegistration<>(GuiProfilerChartRenderState.class, GuiProfilerChartRenderer::new))));
        *///?} elif >=26.2 {
        /*PicturesModern.pictureInPicture(mc -> ClientHooks.gatherPictureInPictureRenderers(List.of(
                new PictureInPictureRendererRegistration<>(GuiEntityRenderState.class,
                        () -> new GuiEntityRenderer(mc.getEntityRenderDispatcher())),
                new PictureInPictureRendererRegistration<>(GuiSkinRenderState.class, GuiSkinRenderer::new),
                new PictureInPictureRendererRegistration<>(GuiBookModelRenderState.class, GuiBookModelRenderer::new),
                new PictureInPictureRendererRegistration<>(GuiBannerResultRenderState.class,
                        () -> new GuiBannerResultRenderer(mc.getAtlasManager())),
                new PictureInPictureRendererRegistration<>(GuiProfilerChartRenderState.class, GuiProfilerChartRenderer::new))));
        *///?}
    }
}
