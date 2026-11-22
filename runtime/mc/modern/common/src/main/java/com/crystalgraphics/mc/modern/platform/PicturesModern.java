package com.crystalgraphics.mc.modern.platform;

import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.mc.render.CgHostPictures;
import com.crystalgraphics.mc.render.CgPictureLighting;
import com.crystalgraphics.mc.render.custom.CgHostDrawContext;
import com.crystalgraphics.mc.render.custom.CgPictureRenderer;
//? if >=1.21.6 {
/*import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
*///?}
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import javax.annotation.Nullable;
//? if <1.15 {
/*import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.Lighting;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
*///?} elif <1.17 {
/*import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
*///?} elif <1.19.3 {
/*import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
*///?} elif <1.20 {
/*import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
*///?} elif <1.21.2 {
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.gui.GuiGraphics;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
//?} elif <1.21.5 {
/*import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
*///?} elif <1.21.6 {
/*import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import org.joml.Matrix4f;
*///?} elif <1.21.10 {
/*import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.render.pip.GuiBannerResultRenderer;
import net.minecraft.client.gui.render.pip.GuiBookModelRenderer;
import net.minecraft.client.gui.render.pip.GuiEntityRenderer;
import net.minecraft.client.gui.render.pip.GuiProfilerChartRenderer;
import net.minecraft.client.gui.render.pip.GuiSignRenderer;
import net.minecraft.client.gui.render.pip.GuiSkinRenderer;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.renderer.fog.FogRenderer;
import java.util.List;
import java.util.function.Function;
*///?} elif <26.1 {
/*import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.render.pip.GuiBannerResultRenderer;
import net.minecraft.client.gui.render.pip.GuiBookModelRenderer;
import net.minecraft.client.gui.render.pip.GuiEntityRenderer;
import net.minecraft.client.gui.render.pip.GuiProfilerChartRenderer;
import net.minecraft.client.gui.render.pip.GuiSignRenderer;
import net.minecraft.client.gui.render.pip.GuiSkinRenderer;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.renderer.fog.FogRenderer;
import java.util.List;
import java.util.function.Function;
*///?} elif <26.2 {
/*import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.render.pip.GuiBannerResultRenderer;
import net.minecraft.client.gui.render.pip.GuiBookModelRenderer;
import net.minecraft.client.gui.render.pip.GuiEntityRenderer;
import net.minecraft.client.gui.render.pip.GuiProfilerChartRenderer;
import net.minecraft.client.gui.render.pip.GuiSignRenderer;
import net.minecraft.client.gui.render.pip.GuiSkinRenderer;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.state.WindowRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import java.util.List;
import java.util.function.Function;
*///?} else {
/*import com.crystalgraphics.mc.modern.platform.vulkan.Blaze3dVulkanHost;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.render.pip.GuiBannerResultRenderer;
import net.minecraft.client.gui.render.pip.GuiBookModelRenderer;
import net.minecraft.client.gui.render.pip.GuiEntityRenderer;
import net.minecraft.client.gui.render.pip.GuiProfilerChartRenderer;
import net.minecraft.client.gui.render.pip.GuiSkinRenderer;
import net.minecraft.client.renderer.state.WindowRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.joml.Vector4f;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Function;
*///?}

/**
 * The modern host of {@link CgHostPictures}: a target of Minecraft's own per picture, and its GUI pointed at it.
 *
 * <ul>
 *   <li>1.13.2 - 1.21.4: the target bound ({@code bindWrite}), the GUI's orthographic projection over the picture's
 *       units, drawing at once; the main target bound back. Alpha from two clears: the immediate GUI writes it raw.
 *       From 1.21.2 the target is also answered for the main one ({@code GuiTargetHook}): a render type's
 *       {@code MAIN_TARGET} binds whatever {@code getMainRenderTarget} answers.</li>
 *   <li>1.21.5: the same, answered rather than bound.</li>
 *   <li>1.21.6 on: a {@code GuiRenderer} of the host's own over a {@code GuiRenderState} of its own, as vanilla's GUI,
 *       its main target answered by {@code GuiTargetHook} and its window sized to the picture. One draw: it keeps
 *       alpha.</li>
 * </ul>
 */
public final class PicturesModern implements CgHostPictures.Host {

    /** What {@code GuiTargetHook} answers for the main target while a picture draws; null otherwise. */
    @Nullable
    private static RenderTarget drawing;
    /**
     * The window Minecraft's GUI code reads while a picture draws (GuiWindowHook): the picture's pixels, its GUI scale
     * (an integer for a GuiRenderer, the picture's own below 1.21.6) and its size in GUI units. Scissors, a tooltip's
     * screen clamp and a mod's window maths then land in the picture.
     */
    private static boolean window;
    private static int windowWidth, windowHeight, windowScale, windowUnitsWidth, windowUnitsHeight;
    private static double windowGuiScale;

    //? if >=1.21.6 {
    /*private static Function<Minecraft, List<?>> pictureInPicture = PicturesModern::vanillaPictureInPicture;
    private GuiRenderState state;
    private GuiRenderer renderer;
    *///?}
    //? if >=1.21.6 <26.2 {
    /*private FogRenderer fog;
    *///?}
    //? if >=1.19.3 <1.21.6 {
    private Matrix4f savedProjection;
    //?}
    //? if >=1.20 <1.21.2 {
    private VertexSorting savedSorting;
    //?} elif >=1.21.2 <1.21.6 {
    /*private ProjectionType savedType;
    *///?}
    // What was bound as the batch began, put back after each picture: the main target at a HUD, but not always
    private int boundBefore = -1;
    private final int[] viewportBefore = new int[4];
    private boolean checking;

    private PicturesModern() {
    }

    public static CgHostPictures.Host create() {
        return new PicturesModern();
    }

    /** The main target {@code GuiTargetHook} answers: the picture's while one draws, else {@code main}. */
    public static RenderTarget target(RenderTarget main) {
        RenderTarget t = drawing;
        return t != null ? t : main;
    }

    /** Whether a picture draws now: {@code GuiWindowHook} answers the picture's window. */
    public static boolean answersWindow() {
        return window;
    }

    /** The window's width in pixels while a picture draws, else {@code real}. */
    public static int windowWidth(int real) {
        return window ? windowWidth : real;
    }

    /** @see #windowWidth */
    public static int windowHeight(int real) {
        return window ? windowHeight : real;
    }

    /** The GuiRenderer's integer GUI scale while a picture draws, 1.21.6 on. */
    public static int windowScale(int real) {
        return window ? windowScale : real;
    }

    /** The picture's own GUI scale while it draws, below 1.21.6. */
    public static double windowGuiScale(double real) {
        return window ? windowGuiScale : real;
    }

    /** The window's width in GUI units while a picture draws, else {@code real}. */
    public static int windowUnitsWidth(int real) {
        return window ? windowUnitsWidth : real;
    }

    /** @see #windowUnitsWidth */
    public static int windowUnitsHeight(int real) {
        return window ? windowUnitsHeight : real;
    }

    // The window answered for the picture: its pixels, its scale, its units
    private static void answerWindow(int pixelsWide, int pixelsHigh, int scale, double guiScale, float units, float unitsHigh) {
        windowWidth = pixelsWide;
        windowHeight = pixelsHigh;
        windowScale = scale;
        windowGuiScale = guiScale;
        windowUnitsWidth = (int) Math.ceil(units - 1e-3f);
        windowUnitsHeight = (int) Math.ceil(unitsHigh - 1e-3f);
        window = true;
    }

    //? if >=1.21.6 {
    /*// A loader whose GuiRenderer takes something else than vanilla's list says what as its client starts: NeoForge's
    // registrations, every mod's gathered.
    public static void pictureInPicture(Function<Minecraft, List<?>> renderers) {
        pictureInPicture = renderers;
    }
    *///?}

    @Override
    @Nullable
    public String absent() {
        return null;
    }

    @Override
    public boolean twoClears() {
        //? if <1.21.6 {
        return true;
        //?} else {
        /*return false;
        *///?}
    }

    @Override
    public boolean bottomUp() {
        return true;
    }

    @Override
    public Object createTarget(int width, int height) {
        RenderTarget t = make(width, height);
        rebind();
        return t;
    }

    private static RenderTarget make(int width, int height) {
        //? if <1.14.3 {
        /*return new RenderTarget(width, height, true);
        *///?} elif <1.17 {
        /*return new RenderTarget(width, height, true, Minecraft.ON_OSX);
        *///?} elif <1.21.2 {
        return new TextureTarget(width, height, true, Minecraft.ON_OSX);
        //?} elif <1.21.5 {
        /*return new TextureTarget(width, height, true);
        *///?} elif <26.2 {
        /*return new TextureTarget("crystalgraphics picture", width, height, true);
        *///?} elif <26.3 {
        /*return new TextureTarget("crystalgraphics picture", width, height, true, GpuFormat.RGBA8_UNORM);
        *///?} else {
        /*return new TextureTarget("crystalgraphics picture", width, height, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
        *///?}
    }

    @Override
    public void deleteTarget(Object target, @Nullable CgTexture texture) {
        CgHostPictures.forget(texture);
        ((RenderTarget) target).destroyBuffers();
        rebind();
    }

    // Making or freeing a target binds framebuffer 0 below 1.21.5: what the batch found bound goes back
    private void rebind() {
        //? if <1.21.5 {
        if (boundBefore >= 0) unbind(Minecraft.getInstance());
        //?}
    }

    @Override
    public CgTexture texture(Object target) {
        return CgHostPictures.imported(handle((RenderTarget) target));
    }

    // The target's colour as CgHostImports takes it: a GL name, or the device texture 26.2's Vulkan image was wrapped in
    private static Object handle(RenderTarget t) {
        //? if <1.16 {
        /*return t.colorTextureId;
        *///?} elif <1.21.5 {
        return t.getColorTextureId();
        //?} elif <26.2 {
        /*return ((GlTexture) t.getColorTexture()).glId();
        *///?} else {
        /*return GraphicsApi.vulkan() ? Blaze3dVulkanHost.current().importTexture(t.getColorTexture())
                : (Object) ((GlTexture) t.getColorTexture()).glId();
        *///?}
    }

    @Override
    public void beginBatch(boolean check) {
        checking = check;
        //? if <1.21.5 {
        boundBefore = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewportBefore);
        //?}
    }

    @Override
    @Nullable
    public String endBatch() {
        if (HostStateVerifier.enabled()) HostStateVerifier.verify("pictures");
        //? if >=1.21.6 <26.2 {
        /*if (fog != null) fog.endFrame();
        *///?}
        if (!checking) return null;
        //? if <1.21.5 {
        int now = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        if (now != boundBefore) return "the draw framebuffer was " + boundBefore + ", now " + now;
        //?}
        return null;
    }

    @Override
    public void draw(Object target, int width, int height, float scale, float clear, CgPictureLighting lighting,
                     CgPictureRenderer drawer) {
        Minecraft mc = Minecraft.getInstance();
        RenderTarget t = (RenderTarget) target;
        Context ctx = new Context(width / scale, height / scale, scale, partialTick());
        if (twoClears()) immediate(mc, t, clear, null, 0, lighting, drawer, ctx);
        else recorded(mc, t, null, 0, drawer, ctx);
    }

    @Override
    public void drawCells(Object target, int width, int height, float scale, float clear, int[] rects, int count,
                          CgPictureLighting lighting, CgPictureRenderer drawer) {
        Minecraft mc = Minecraft.getInstance();
        RenderTarget t = (RenderTarget) target;
        Context ctx = new Context(width / scale, height / scale, scale, partialTick());
        int[] kept = clearsRects() ? rects : null;
        if (twoClears()) immediate(mc, t, clear, kept, count, lighting, drawer, ctx);
        else recorded(mc, t, kept, count, drawer, ctx);
    }

    // 1.21.5's encoder and 26.3's render passes clear a whole target, never a rect
    @Override
    public boolean clearsRects() {
        //? if >=1.21.5 <1.21.6 {
        /*return false;
        *///?} elif >=26.3 {
        /*return false;
        *///?} else {
        return true;
        //?}
    }

    // ── The immediate GUI, 1.13.2 - 1.21.5 ──────────────────────────────

    // The target bound (or answered, 1.21.5), the GUI's projection over the picture's units, the drawing landing at
    // once; everything put back as the HUD had it
    private void immediate(Minecraft mc, RenderTarget t, float clear, @Nullable int[] rects, int count,
                           CgPictureLighting lighting, CgPictureRenderer drawer, Context ctx) {
        if (rects == null) clearAndBind(t, clear);
        else clearRectsAndBind(t, clear, rects, count);
        project(ctx.width, ctx.height);
        light(lighting);
        answerWindow(Math.round(ctx.width * ctx.scale), Math.round(ctx.height * ctx.scale), 0, ctx.scale, ctx.width,
                ctx.height);
        try {
            drawAtOnce(mc, drawer, ctx);
        } finally {
            window = false;
            unproject();
            unlight();
            unbind(mc);
        }
    }

    private static void clearAndBind(RenderTarget t, float clear) {
        //? if <1.14.3 {
        /*t.bindWrite(true);
        GlStateManager.clearColor(clear, clear, clear, 0f);
        GlStateManager.clearDepth(1.0);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        *///?} elif <1.21.2 {
        t.setClearColor(clear, clear, clear, 0f);
        t.clear(Minecraft.ON_OSX);
        t.bindWrite(true);
        //?} elif <1.21.5 {
        /*t.setClearColor(clear, clear, clear, 0f);
        t.clear();
        t.bindWrite(true);
        drawing = t;
        *///?} elif <1.21.6 {
        /*int c = Math.round(clear * 255f);
        RenderSystem.getDevice().createCommandEncoder()
                .clearColorAndDepthTextures(t.getColorTexture(), c << 16 | c << 8 | c, t.getDepthTexture(), 1.0);
        drawing = t;
        *///?}
    }

    // Each rect (pixels from the top-left) cleared, the rest kept; then the target bound. GL's scissor is bottom-up
    private static void clearRectsAndBind(RenderTarget t, float clear, int[] rects, int count) {
        int height = t.height;
        //? if <1.14.3 {
        /*t.bindWrite(true);
        GlStateManager.clearColor(clear, clear, clear, 0f);
        GlStateManager.clearDepth(1.0);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        for (int i = 0; i < count; i++) {
            int k = i * 4;
            GL11.glScissor(rects[k], height - rects[k + 1] - rects[k + 3], rects[k + 2], rects[k + 3]);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        }
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        *///?} elif <1.16.5 {
        /*t.setClearColor(clear, clear, clear, 0f);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        for (int i = 0; i < count; i++) {
            int k = i * 4;
            GL11.glScissor(rects[k], height - rects[k + 1] - rects[k + 3], rects[k + 2], rects[k + 3]);
            t.clear(Minecraft.ON_OSX);
        }
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        t.bindWrite(true);
        *///?} elif <1.21.2 {
        t.setClearColor(clear, clear, clear, 0f);
        for (int i = 0; i < count; i++) {
            int k = i * 4;
            RenderSystem.enableScissor(rects[k], height - rects[k + 1] - rects[k + 3], rects[k + 2], rects[k + 3]);
            t.clear(Minecraft.ON_OSX);
        }
        RenderSystem.disableScissor();
        t.bindWrite(true);
        //?} elif <1.21.5 {
        /*t.setClearColor(clear, clear, clear, 0f);
        for (int i = 0; i < count; i++) {
            int k = i * 4;
            RenderSystem.enableScissor(rects[k], height - rects[k + 1] - rects[k + 3], rects[k + 2], rects[k + 3]);
            t.clear();
        }
        RenderSystem.disableScissor();
        t.bindWrite(true);
        drawing = t;
        *///?} else {
        /*clearAndBind(t, clear);
        *///?}
    }

    private void unbind(Minecraft mc) {
        //? if <1.15 {
        /*RenderTarget main = mc.getMainRenderTarget();
        if (boundBefore == main.frameBufferId) {
            main.bindWrite(true);
        } else {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, boundBefore);
            GlStateManager.viewport(viewportBefore[0], viewportBefore[1], viewportBefore[2], viewportBefore[3]);
        }
        *///?} elif <1.21.5 {
        // Before asking for the main target, which answers the picture's while one draws (1.21.2+)
        drawing = null;
        RenderTarget main = mc.getMainRenderTarget();
        if (boundBefore == main.frameBufferId) {
            main.bindWrite(true);
        } else {
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, boundBefore);
            GlStateManager._viewport(viewportBefore[0], viewportBefore[1], viewportBefore[2], viewportBefore[3]);
        }
        //?} else {
        /*drawing = null;
        *///?}
    }

    // The GUI's own projection, as GameRenderer sets it, over the picture's units instead of the window's
    private void project(float w, float h) {
        //? if <1.15 {
        /*GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();
        GlStateManager.ortho(0.0, w, h, 0.0, 1000.0, 3000.0);
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();
        GlStateManager.translatef(0f, 0f, -2000f);
        *///?} elif <1.17 {
        /*RenderSystem.matrixMode(GL11.GL_PROJECTION);
        RenderSystem.pushMatrix();
        RenderSystem.loadIdentity();
        RenderSystem.ortho(0.0, w, h, 0.0, 1000.0, 3000.0);
        RenderSystem.matrixMode(GL11.GL_MODELVIEW);
        RenderSystem.pushMatrix();
        RenderSystem.loadIdentity();
        RenderSystem.translatef(0f, 0f, -2000f);
        *///?} elif <1.19.3 {
        /*RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(Matrix4f.orthographic(0f, w, 0f, h, 1000f, 3000f));
        *///?} elif <1.20 {
        /*savedProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        RenderSystem.setProjectionMatrix(guiProjection(savedProjection, w, h));
        *///?} elif <1.21.2 {
        savedProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        savedSorting = RenderSystem.getVertexSorting();
        RenderSystem.setProjectionMatrix(guiProjection(savedProjection, w, h), VertexSorting.ORTHOGRAPHIC_Z);
        //?} elif <1.21.6 {
        /*savedProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        savedType = RenderSystem.getProjectionType();
        RenderSystem.setProjectionMatrix(guiProjection(savedProjection, w, h), ProjectionType.ORTHOGRAPHIC);
        *///?}
    }

    private void unproject() {
        //? if <1.15 {
        /*GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.popMatrix();
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.popMatrix();
        *///?} elif <1.17 {
        /*RenderSystem.matrixMode(GL11.GL_PROJECTION);
        RenderSystem.popMatrix();
        RenderSystem.matrixMode(GL11.GL_MODELVIEW);
        RenderSystem.popMatrix();
        *///?} elif <1.19.3 {
        /*RenderSystem.restoreProjectionMatrix();
        *///?} elif <1.20 {
        /*RenderSystem.setProjectionMatrix(savedProjection);
        *///?} elif <1.21.2 {
        RenderSystem.setProjectionMatrix(savedProjection, savedSorting);
        //?} elif <1.21.6 {
        /*RenderSystem.setProjectionMatrix(savedProjection, savedType);
        *///?}
    }

    //? if >=1.19.3 <1.21.6 {
    // The GUI's projection as the HUD has it, its depth range kept, over the picture's units
    private static Matrix4f guiProjection(Matrix4f gui, float w, float h) {
        return new Matrix4f(gui).m00(2f / w).m11(-2f / h).m30(-1f).m31(1f);
    }
    //?}

    private static void drawAtOnce(Minecraft mc, CgPictureRenderer drawer, Context ctx) {
        //? if <1.15 {
        /*drawer.draw(ctx);
        *///?} elif <1.20 {
        /*ctx.pose = new PoseStack();
        ctx.buffers = mc.renderBuffers().bufferSource();
        drawer.draw(ctx);
        mc.renderBuffers().bufferSource().endBatch();
        *///?} elif <1.21.6 {
        GuiGraphics g = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
        ctx.gui = g;
        ctx.pose = g.pose();
        ctx.buffers = mc.renderBuffers().bufferSource();
        drawer.draw(ctx);
        g.flush();
        //?}
    }

    private static void light(CgPictureLighting lighting) {
        //? if <1.15 {
        /*Lighting.turnOnGui();
        *///?} elif <1.17 {
        /*if (lighting == CgPictureLighting.ITEMS_FLAT) Lighting.setupForFlatItems();
        else Lighting.setupFor3DItems();
        *///?} elif <1.21.6 {
        switch (lighting) {
            case ITEMS_FLAT -> Lighting.setupForFlatItems();
            case ITEMS_3D -> Lighting.setupFor3DItems();
            case ENTITY_IN_UI -> Lighting.setupForEntityInInventory();
        }
        //?}
    }

    // The GUI's own, which the HUD draws under: lighting off before 1.15, as the hotbar leaves it
    private static void unlight() {
        //? if <1.15 {
        /*Lighting.turnOff();
        *///?} elif <1.21.6 {
        Lighting.setupFor3DItems();
        //?}
    }

    // ── The recorded GUI, 1.21.6 on ──────────────────────────────────────

    // Drawn into a state of our own, rendered by a GuiRenderer of our own whose main target and window are the
    // picture's for the call. The window is sized so its projection spans the picture's units at an integer GUI scale.
    private void recorded(Minecraft mc, RenderTarget t, @Nullable int[] rects, int count, CgPictureRenderer drawer,
                          Context ctx) {
        int guiScale = Math.max(1, (int) Math.ceil(ctx.scale));
        int pixelsWide = Math.round(ctx.width * guiScale), pixelsHigh = Math.round(ctx.height * guiScale);
        //? if >=1.21.6 <26.1 {
        /*if (renderer == null) makeRenderer(mc);
        ctx.gui = guiGraphics(mc);
        long zone = CgTrace.begin(CgChannels.PICTURES, "pictures.record");
        drawer.draw(ctx);
        CgTrace.end(zone);
        zone = CgTrace.begin(CgChannels.PICTURES, "pictures.clear");
        clearRecorded(t, rects, count);
        CgTrace.end(zone);
        drawing = t;
        answerWindow(pixelsWide, pixelsHigh, guiScale, guiScale, ctx.width, ctx.height);
        try {
            zone = CgTrace.begin(CgChannels.PICTURES, "pictures.render");
            renderer.render(fog.getBuffer(FogRenderer.FogMode.NONE));
            CgTrace.end(zone);
        } finally {
            drawing = null;
            window = false;
        }
        renderer.incrementFrameNumber();
        *///?} elif >=26.1 <26.2 {
        /*if (renderer == null) makeRenderer(mc);
        ctx.gui = new GuiGraphicsExtractor(mc, state, -1, -1);
        long zone = CgTrace.begin(CgChannels.PICTURES, "pictures.record");
        drawer.draw(ctx);
        CgTrace.end(zone);
        zone = CgTrace.begin(CgChannels.PICTURES, "pictures.clear");
        clearRecorded(t, rects, count);
        CgTrace.end(zone);
        WindowRenderState state = mc.gameRenderer.getGameRenderState().windowRenderState;
        int realWidth = state.width, realHeight = state.height, realScale = state.guiScale;
        state.width = pixelsWide;
        state.height = pixelsHigh;
        state.guiScale = guiScale;
        drawing = t;
        try {
            zone = CgTrace.begin(CgChannels.PICTURES, "pictures.render");
            renderer.render(fog.getBuffer(FogRenderer.FogMode.NONE));
            CgTrace.end(zone);
        } finally {
            drawing = null;
            state.width = realWidth;
            state.height = realHeight;
            state.guiScale = realScale;
        }
        renderer.endFrame();
        *///?} elif >=26.2 {
        /*if (renderer == null) makeRenderer(mc);
        ctx.gui = new GuiGraphicsExtractor(mc, state, -1, -1);
        long zone = CgTrace.begin(CgChannels.PICTURES, "pictures.record");
        drawer.draw(ctx);
        CgTrace.end(zone);
        zone = CgTrace.begin(CgChannels.PICTURES, "pictures.clear");
        clearRecorded(t, rects, count);
        CgTrace.end(zone);
        WindowRenderState state = mc.gameRenderer.gameRenderState().windowRenderState;
        int realWidth = state.width, realHeight = state.height, realScale = state.guiScale;
        state.width = pixelsWide;
        state.height = pixelsHigh;
        state.guiScale = guiScale;
        drawing = t;
        try {
            zone = CgTrace.begin(CgChannels.PICTURES, "pictures.render");
            renderer.render();
            CgTrace.end(zone);
        } finally {
            drawing = null;
            state.width = realWidth;
            state.height = realHeight;
            state.guiScale = realScale;
        }
        renderer.endFrame();
        *///?}
    }

    //? if >=1.21.6 <1.21.11 {
    /*private GuiGraphics guiGraphics(Minecraft mc) {
        return new GuiGraphics(mc, state);
    }
    *///?} elif >=1.21.11 <26.1 {
    /*private GuiGraphics guiGraphics(Minecraft mc) {
        return new GuiGraphics(mc, state, -1, -1);
    }
    *///?}

    // The whole target cleared, or each rect (pixels from the top-left) as Mojang's item atlas clears a slot:
    // the device's rect is bottom-up
    //? if >=1.21.6 <26.2 {
    /*private static void clearRecorded(RenderTarget t, @Nullable int[] rects, int count) {
        if (rects == null) {
            RenderSystem.getDevice().createCommandEncoder()
                    .clearColorAndDepthTextures(t.getColorTexture(), 0, t.getDepthTexture(), 1.0);
            return;
        }
        for (int i = 0; i < count; i++) {
            int k = i * 4;
            RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(t.getColorTexture(), 0,
                    t.getDepthTexture(), 1.0, rects[k], t.height - rects[k + 1] - rects[k + 3], rects[k + 2], rects[k + 3]);
        }
    }
    *///?} elif >=26.2 <26.3 {
    /*// Reversed depth from 26.2: cleared to 0, as the GUI's own targets
    private static void clearRecorded(RenderTarget t, @Nullable int[] rects, int count) {
        if (rects == null) {
            RenderSystem.getDevice().createCommandEncoder()
                    .clearColorAndDepthTextures(t.getColorTexture(), new Vector4f(0f), t.getDepthTexture(), 0.0);
            return;
        }
        for (int i = 0; i < count; i++) {
            int k = i * 4;
            RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(t.getColorTexture(),
                    new Vector4f(0f), t.getDepthTexture(), 0.0, rects[k], t.height - rects[k + 1] - rects[k + 3],
                    rects[k + 2], rects[k + 3]);
        }
    }
    *///?} elif >=26.3 {
    /*// 26.3 clears as a render pass opens, whole (clearsRects is false); reversed depth, cleared to 0
    private static void clearRecorded(RenderTarget t, @Nullable int[] rects, int count) {
        RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "crystalgraphics picture clear",
                t.getColorTextureView(), Optional.of(new Vector4f(0f)), t.getDepthTextureView(), OptionalDouble.of(0.0)).close();
    }
    *///?}

    //? if >=1.21.6 <1.21.10 {
    /*@SuppressWarnings({"unchecked", "rawtypes"})
    private void makeRenderer(Minecraft mc) {
        state = new GuiRenderState();
        fog = new FogRenderer();
        renderer = new GuiRenderer(state, mc.renderBuffers().bufferSource(), (List) pictureInPicture.apply(mc));
    }

    // Vanilla's picture-in-picture renderers, as GameRenderer makes its own
    private static List<?> vanillaPictureInPicture(Minecraft mc) {
        var buffers = mc.renderBuffers().bufferSource();
        return List.of(new GuiEntityRenderer(buffers, mc.getEntityRenderDispatcher()), new GuiSkinRenderer(buffers),
                new GuiBookModelRenderer(buffers), new GuiBannerResultRenderer(buffers), new GuiSignRenderer(buffers),
                new GuiProfilerChartRenderer(buffers));
    }
    *///?} elif >=1.21.10 <26.2 {
    /*@SuppressWarnings({"unchecked", "rawtypes"})
    private void makeRenderer(Minecraft mc) {
        state = new GuiRenderState();
        fog = new FogRenderer();
        renderer = new GuiRenderer(state, mc.renderBuffers().bufferSource(), mc.gameRenderer.getSubmitNodeStorage(),
                mc.gameRenderer.getFeatureRenderDispatcher(), (List) pictureInPicture.apply(mc));
    }

    private static List<?> vanillaPictureInPicture(Minecraft mc) {
        var buffers = mc.renderBuffers().bufferSource();
        return List.of(new GuiEntityRenderer(buffers, mc.getEntityRenderDispatcher()), new GuiSkinRenderer(buffers),
                new GuiBookModelRenderer(buffers), new GuiBannerResultRenderer(buffers, mc.getAtlasManager()),
                new GuiSignRenderer(buffers, mc.getAtlasManager()), new GuiProfilerChartRenderer(buffers));
    }
    *///?} elif >=26.2 {
    /*@SuppressWarnings({"unchecked", "rawtypes"})
    private void makeRenderer(Minecraft mc) {
        state = new GuiRenderState();
        renderer = new GuiRenderer(state, mc.gameRenderer.featureRenderDispatcher(), (List) pictureInPicture.apply(mc));
    }

    private static List<?> vanillaPictureInPicture(Minecraft mc) {
        return List.of(new GuiEntityRenderer(mc.getEntityRenderDispatcher()), new GuiSkinRenderer(),
                new GuiBookModelRenderer(), new GuiBannerResultRenderer(mc.getAtlasManager()), new GuiProfilerChartRenderer());
    }
    *///?}

    private static float partialTick() {
        //? if >=1.21.3 {
        /*return Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);
        *///?} elif >=1.21 {
        /*return Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(true);
        *///?} else {
        return Minecraft.getInstance().getFrameTime();
        //?}
    }

    /** What a renderer draws with: the era's GUI objects, which differ only in which are set. */
    private static final class Context implements CgHostDrawContext {
        final float width, height, scale, partialTick;
        @Nullable Object gui, pose, buffers;

        Context(float width, float height, float scale, float partialTick) {
            this.width = width;
            this.height = height;
            this.scale = scale;
            this.partialTick = partialTick;
        }

        @Override public Object gui() { return gui; }
        @Override public Object poseStack() { return pose; }
        @Override public Object buffers() { return buffers; }
        @Override public float width() { return width; }
        @Override public float height() { return height; }
        @Override public float scale() { return scale; }
        @Override public float partialTick() { return partialTick; }
    }
}
