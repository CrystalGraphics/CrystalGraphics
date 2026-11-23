package com.crystalgraphics.mc.modern.platform;

//? if >=1.21.5 {
/*import com.mojang.blaze3d.textures.GpuTexture;
*///?}
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.mc.render.CgHostPictures;
import com.crystalgraphics.mc.render.CgPictureLighting;
import com.crystalgraphics.mc.render.custom.CgHostDrawContext;
import com.crystalgraphics.mc.render.custom.CgPictureRenderer;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
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
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;
*///?} elif <1.20 {
/*import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
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
import org.lwjgl.opengl.GL14;
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
import org.lwjgl.opengl.GL14;
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
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.gui.render.state.pip.GuiBannerResultRenderState;
import net.minecraft.client.gui.render.state.pip.GuiBookModelRenderState;
import net.minecraft.client.gui.render.state.pip.GuiEntityRenderState;
import net.minecraft.client.gui.render.state.pip.GuiProfilerChartRenderState;
import net.minecraft.client.gui.render.state.pip.GuiSignRenderState;
import net.minecraft.client.gui.render.state.pip.GuiSkinRenderState;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.joml.Matrix3x2f;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
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
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.gui.render.state.pip.GuiBannerResultRenderState;
import net.minecraft.client.gui.render.state.pip.GuiBookModelRenderState;
import net.minecraft.client.gui.render.state.pip.GuiEntityRenderState;
import net.minecraft.client.gui.render.state.pip.GuiProfilerChartRenderState;
import net.minecraft.client.gui.render.state.pip.GuiSignRenderState;
import net.minecraft.client.gui.render.state.pip.GuiSkinRenderState;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.joml.Matrix3x2f;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
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
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.state.WindowRenderState;
import net.minecraft.client.renderer.state.gui.pip.GuiBannerResultRenderState;
import net.minecraft.client.renderer.state.gui.pip.GuiBookModelRenderState;
import net.minecraft.client.renderer.state.gui.pip.GuiEntityRenderState;
import net.minecraft.client.renderer.state.gui.pip.GuiProfilerChartRenderState;
import net.minecraft.client.renderer.state.gui.pip.GuiSignRenderState;
import net.minecraft.client.renderer.state.gui.pip.GuiSkinRenderState;
import org.joml.Matrix3x2f;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
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
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.state.WindowRenderState;
import net.minecraft.client.renderer.state.gui.pip.GuiBannerResultRenderState;
import net.minecraft.client.renderer.state.gui.pip.GuiBookModelRenderState;
import net.minecraft.client.renderer.state.gui.pip.GuiEntityRenderState;
import net.minecraft.client.renderer.state.gui.pip.GuiProfilerChartRenderState;
import net.minecraft.client.renderer.state.gui.pip.GuiSkinRenderState;
import org.joml.Matrix3x2f;
import org.joml.Vector4f;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;
*///?}

/**
 * The modern host of {@link CgHostPictures}: a target of Minecraft's own per page, and its GUI pointed at each cell.
 *
 * <ul>
 *   <li>1.13.2 - 1.21.4: the page bound once ({@code bindWrite}); per cell its rect cleared, the viewport on it, the
 *       GUI's orthographic projection over its units, drawing at once; the main target bound back. Alpha from two
 *       clears: the immediate GUI writes it raw. From 1.21.2 the page is also answered for the main target
 *       ({@code GuiTargetHook}): a render type's {@code MAIN_TARGET} binds whatever {@code getMainRenderTarget}
 *       answers.</li>
 *   <li>1.21.5: a cell cannot be reached by viewport (the encoder binds a target whole): each is drawn into a scratch
 *       target its size, answered rather than bound, and copied into the page.</li>
 *   <li>1.21.6 on: a {@code GuiRenderer} of the host's own, one render a page straight into it: each cell recorded
 *       under its pose and inside its scissor ({@link RecordedPages}), the window the page's at the cells' GUI scale.
 *       One draw: it keeps alpha.</li>
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
    /** While a picture draws on an immediate GUI (1.15.2 - 1.21.4): GuiBlendHook pairs each blend's alpha. */
    private static boolean pairsAlpha;
    /** GuiBlendHook held a disable back while a picture drew: it lands as the draw ends. */
    private static boolean blendHeld;
    /** Where the drawing cell's bottom-left is on its page, in pixels: what GuiScissorHook adds to a scissor. */
    private static int scissorX, scissorY;
    private static int windowWidth, windowHeight, windowScale, windowUnitsWidth, windowUnitsHeight;
    private static double windowGuiScale;

    //? if >=1.21.6 {
    /*private static PictureInPicture pictureInPicture = new PictureInPicture() {
        @Override
        public Map<Class<?>, Supplier<PictureInPictureRenderer<?>>> factories(Minecraft mc) {
            return vanillaPictureInPicture(mc);
        }

        @Override
        public List<?> list(PictureInPictureRenderer<?> ours) {
            return List.of(ours);
        }
    };
    private static boolean makingRenderer;
    private RecordedPages.State state;
    private RecordedPages.Pictures pictures;
    private GuiRenderer renderer;
    // The pose of the cell recording, while a page records
    private final Matrix3x2f cellPose = new Matrix3x2f();
    private int[] pageRects = new int[64];
    *///?}
    //? if >=1.21.6 <26.2 {
    /*private FogRenderer fog;
    *///?}
    //? if >=1.19.3 <1.21.6 {
    private Matrix4f savedProjection;
    private final Matrix4f cellProjection = new Matrix4f();
    //?}
    //? if >=1.20 <1.21.2 {
    private VertexSorting savedSorting;
    //?} elif >=1.21.2 <1.21.6 {
    /*private ProjectionType savedType;
    *///?}
    // What was bound as the batch began, put back after each page: the main target at a HUD, answered from Minecraft
    // once the first CHECKED batches' glGet agreed (a glGet waits for the driver to drain, each frame); one
    // disagreement and every batch reads
    private static final int CHECKED = 120;
    private int boundBefore = -1;
    private final int[] viewportBefore = new int[4];
    private int agreed;
    private boolean reads;
    private boolean checking;
    private final Context context = new Context();
    // The page's cells while it draws, and the one drawing: what cellDrawer draws
    @Nullable
    private CgHostPictures.Cells cells;
    private int cell;
    private final CgPictureRenderer cellDrawer = ctx -> cells.draw(cell, ctx);
    //? if >=1.21.5 <1.21.6 {
    /*// Scratch targets by size, a cell drawn into one its size; given back once unused for SCRATCH_KEPT cell draws
    private static final int SCRATCH_KEPT = 4096;
    private final PageCopy copy = new PageCopy();
    private RenderTarget[] scratchTargets = new RenderTarget[8];
    private long[] scratchUsed = new long[8];
    private int scratchCount;
    private long scratchClock;
    *///?}

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

    /**
     * {@code t} answered for the main target from now (null: the main target again), and what was answered before:
     * what a pocket level's draw sets, so the render types binding the main target (1.21.2 on) bind its.
     */
    @Nullable
    static RenderTarget answerMain(@Nullable RenderTarget t) {
        RenderTarget before = drawing;
        drawing = t;
        return before;
    }

    /** Whether a picture draws now: {@code GuiWindowHook} answers the picture's window. */
    public static boolean answersWindow() {
        return window;
    }

    /** Whether a picture draws now on an immediate GUI, its blends' alpha to be paired (1.15.2 - 1.21.4). */
    public static boolean pairsAlpha() {
        return pairsAlpha;
    }

    /** GuiBlendHook kept blending on for an unblended draw: the disable is the host's to make. */
    public static void holdBlend() {
        blendHeld = true;
    }

    // Minecraft's draw of a picture: its blends paired, an unblended draw writing alpha 1
    private static void startPairing() {
        pairsAlpha = true;
        blendAlpha(1f);
    }

    private static void endPairing() {
        pairsAlpha = false;
        //? if >=1.15 <1.21.5 {
        if (blendHeld) {
            blendHeld = false;
            GlStateManager._disableBlend();
        }
        //?}
        blendAlpha(0f);
    }

    // The blend colour's alpha, what a held blend writes; 0, GL's own, otherwise. Cached by the state manager to 1.16
    private static void blendAlpha(float alpha) {
        //? if >=1.15 <1.17 {
        /*GlStateManager._blendColor(0f, 0f, 0f, alpha);
        *///?} elif >=1.17 <1.21.5 {
        GL14.glBlendColor(0f, 0f, 0f, alpha);
        //?}
    }

    // Mojang's alpha factors for a colour blend from 1.21.6 (BlendFunction): TRANSLUCENT and the premultiplied over take
    // coverage, GLINT keeps what is below; any other blend keeps its own
    private static final int ZERO = 0, ONE = 1, SRC_COLOR = 768, SRC_ALPHA = 770, ONE_MINUS_SRC_ALPHA = 771;

    /** The source alpha factor Mojang pairs with colour factors {@code (src, dst)}; {@code given} where it pairs none. */
    public static int pairedSrcAlpha(int src, int dst, int given) {
        if (dst == ONE_MINUS_SRC_ALPHA && (src == SRC_ALPHA || src == ONE)) return ONE;
        if (src == SRC_COLOR && dst == ONE) return ZERO;
        return given;
    }

    /** @see #pairedSrcAlpha */
    public static int pairedDstAlpha(int src, int dst, int given) {
        if (dst == ONE_MINUS_SRC_ALPHA && (src == SRC_ALPHA || src == ONE)) return ONE_MINUS_SRC_ALPHA;
        if (src == SRC_COLOR && dst == ONE) return ONE;
        return given;
    }

    /** Pixels a scissor moves right while a page's cell draws (1.16.5 - 1.21.4); 0 otherwise. */
    public static int scissorX() {
        return scissorX;
    }

    /** @see #scissorX */
    public static int scissorY() {
        return scissorY;
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
    /*/^*
     * A loader's picture-in-picture renderers for the pictures' {@code GuiRenderer}: vanilla's by default. A loader whose
     * {@code GuiRenderer} takes something else says what as its client starts (NeoForge: registrations, every mod's).
     ^/
    public interface PictureInPicture {
        /^* Each state kind's factory, a new renderer each call. ^/
        Map<Class<?>, Supplier<PictureInPictureRenderer<?>>> factories(Minecraft mc);

        /^* What the loader's {@code GuiRenderer} takes for its picture-in-picture renderers, holding {@code ours} alone. ^/
        List<?> list(PictureInPictureRenderer<?> ours);
    }

    public static void pictureInPicture(PictureInPicture source) {
        pictureInPicture = source;
    }

    /^* Whether the pictures' own {@code GuiRenderer} is being made: Fabric API's registry hook stands aside. ^/
    public static boolean makingRenderer() {
        return makingRenderer;
    }

    private void ensureRenderer(Minecraft mc) {
        if (renderer != null) return;
        makingRenderer = true;
        try {
            makeRenderer(mc);
        } finally {
            makingRenderer = false;
        }
    }
    *///?}

    @Override
    @Nullable
    public String absent() {
        return null;
    }

    @Override
    // From 1.15.2 one draw: GuiBlendHook pairs each blend's alpha as 1.21.6's pipelines do, and 1.21.5's already do
    public boolean twoClears() {
        //? if <1.15 {
        /*return true;
        *///?} else {
        return false;
        //?}
    }

    @Override
    public boolean bottomUp() {
        return true;
    }

    @Override
    public Object createTarget(int width, int height, float clear) {
        RenderTarget t = make(width, height);
        //? if <1.21.6 {
        clearAndBind(t, clear);
        drawing = null;
        //?} else {
        /*clearRecorded(t, null, 0);
        *///?}
        rebind();
        return t;
    }

    static RenderTarget make(int width, int height) {
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

    // The target's colour as CgHostImports takes it: a GL name, or the device texture 26.2's Vulkan image was wrapped in.
    // Under a loader's wrapper (NeoForge's dev-time validation) to 26.1, the GL texture beneath it
    static Object handle(RenderTarget t) {
        //? if <1.16 {
        /*return t.colorTextureId;
        *///?} elif <1.21.5 {
        return t.getColorTextureId();
        //?} else {
        /*return deviceHandle(t.getColorTexture());
        *///?}
    }

    //? if >=1.21.5 {
    /*// The target's depth, as handle answers its colour
    static Object depthHandle(RenderTarget t) {
        return deviceHandle(t.getDepthTexture());
    }

    private static Object deviceHandle(GpuTexture texture) {
        //? if <26.1 {
        return ((GlTexture) LifecycleModern.hostTexture(texture)).glId();
        //?} elif <26.2 {
        /^return ((GlTexture) texture).glId();
        ^///?} else {
        /^return GraphicsApi.vulkan() ? Blaze3dVulkanHost.current().importTexture(texture) : (Object) ((GlTexture) texture).glId();
        ^///?}
    }
    *///?}

    @Override
    public void beginBatch(boolean check) {
        checking = check;
        //? if >=1.21.6 {
        /*if (pictures != null) pictures.frame();
        *///?}
        //? if <1.21.5 {
        int main = Minecraft.getInstance().getMainRenderTarget().frameBufferId;
        if (!reads && agreed >= CHECKED) {
            boundBefore = main;
            return;
        }
        boundBefore = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        if (boundBefore == main) {
            agreed++;
            return;
        }
        reads = true;
        // Not the main target: its viewport is put back too (the main one's bindWrite sets its own)
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
    public void drawPage(Object target, int width, int height, float clear, CgHostPictures.Cells cells) {
        Minecraft mc = Minecraft.getInstance();
        //? if <1.21.5 {
        immediatePage(mc, (RenderTarget) target, height, clear, cells, partialTick());
        //?} elif <1.21.6 {
        /*scratchPage(mc, (RenderTarget) target, height, clear, cells, partialTick());
        *///?} else {
        /*recordedPage(mc, (RenderTarget) target, width, cells, partialTick());
        *///?}
    }

    @Override
    public void drawCells(Object target, int width, int height, float scale, float clear, int[] rects, int count,
                          CgPictureLighting lighting, CgPictureRenderer drawer) {
        Minecraft mc = Minecraft.getInstance();
        RenderTarget t = (RenderTarget) target;
        Context ctx = context.set(width / scale, height / scale, scale, partialTick());
        int[] kept = clearsRects() ? rects : null;
        //? if <1.21.6 {
        immediate(mc, t, clear, kept, count, lighting, drawer, ctx);
        //?} else {
        /*recorded(mc, t, kept, count, drawer, ctx);
        *///?}
    }

    //? if <1.21.5 {
    // One bind for the page; per cell its rect cleared, the viewport on it, the GUI's projection over its units
    private void immediatePage(Minecraft mc, RenderTarget page, int height, float clear, CgHostPictures.Cells cells,
                               float partialTick) {
        long zone = CgTrace.begin(CgChannels.PICTURES, "pictures.setup");
        //? if >=1.21.2 {
        /*drawing = page;
        *///?}
        page.bindWrite(false);
        saveProjection();
        this.cells = cells;
        CgTrace.end(zone);
        try {
            for (int i = 0; i < cells.count(); i++) {
                int w = cells.width(i), h = cells.height(i), x = cells.x(i), y = cells.y(i);
                float scale = cells.scale(i), units = w / scale, unitsHigh = h / scale;
                zone = CgTrace.begin(CgChannels.PICTURES, "pictures.setup");
                clearCell(x, height - y - h, w, h, clear);
                viewport(x, height - y - h, w, h);
                project(units, unitsHigh);
                light(cells.lighting(i));
                answerWindow(w, h, 0, scale, units, unitsHigh);
                context.set(units, unitsHigh, scale, partialTick);
                cell = i;
                CgTrace.end(zone);
                zone = CgTrace.begin(CgChannels.PICTURES, "pictures.minecraft");
                scissorX = x;
                scissorY = height - y - h;
                startPairing();
                try {
                    drawAtOnce(mc, cellDrawer, context);
                } finally {
                    endPairing();
                    scissorX = scissorY = 0;
                    CgTrace.end(zone);
                    window = false;
                    unlight();
                }
            }
        } finally {
            zone = CgTrace.begin(CgChannels.PICTURES, "pictures.restore");
            this.cells = null;
            unproject();
            unbind(mc);
            CgTrace.end(zone);
        }
    }

    // A cell's rect (bottom-up) cleared on the bound page, the binding kept: RenderTarget.clear binds framebuffer 0 after
    private static void clearCell(int x, int y, int w, int h, float clear) {
        //? if <1.15 {
        /*GlStateManager.clearColor(clear, clear, clear, 0f);
        GlStateManager.clearDepth(1.0);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(x, y, w, h);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        *///?} elif <1.16.5 {
        /*GlStateManager._clearColor(clear, clear, clear, 0f);
        GlStateManager._clearDepth(1.0);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(x, y, w, h);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        *///?} elif <1.21.2 {
        GlStateManager._clearColor(clear, clear, clear, 0f);
        GlStateManager._clearDepth(1.0);
        RenderSystem.enableScissor(x, y, w, h);
        GlStateManager._clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        RenderSystem.disableScissor();
        //?} else {
        /*GlStateManager._clearColor(clear, clear, clear, 0f);
        GlStateManager._clearDepth(1.0);
        RenderSystem.enableScissor(x, y, w, h);
        GlStateManager._clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        RenderSystem.disableScissor();
        *///?}
    }

    private static void viewport(int x, int y, int w, int h) {
        //? if <1.15 {
        /*GlStateManager.viewport(x, y, w, h);
        *///?} else {
        GlStateManager._viewport(x, y, w, h);
        //?}
    }
    //?}

    //? if >=1.21.5 <1.21.6 {
    /*// 1.21.5: each cell drawn as a picture of its own into a scratch target its size (the encoder binds a target
    // whole), then copied into its rect of the page (the device's rect is bottom-up)
    private void scratchPage(Minecraft mc, RenderTarget page, int height, float clear, CgHostPictures.Cells cells,
                             float partialTick) {
        this.cells = cells;
        try {
            for (int i = 0; i < cells.count(); i++) {
                int w = cells.width(i), h = cells.height(i);
                float scale = cells.scale(i);
                RenderTarget scratch = scratch(w, h);
                context.set(w / scale, h / scale, scale, partialTick);
                cell = i;
                immediate(mc, scratch, clear, null, 0, cells.lighting(i), cellDrawer, context);
                long zone = CgTrace.begin(CgChannels.PICTURES, "pictures.copy");
                copy.copy(scratch, page, cells.x(i), height - cells.y(i) - h, w, h);
                CgTrace.end(zone);
            }
        } finally {
            this.cells = null;
        }
    }

    // A scratch target w by h: kept from an earlier cell that size, else made; those unused long enough given back
    private RenderTarget scratch(int w, int h) {
        long now = ++scratchClock;
        RenderTarget found = null;
        for (int i = scratchCount - 1; i >= 0; i--) {
            RenderTarget t = scratchTargets[i];
            if (found == null && t.width == w && t.height == h) {
                found = t;
                scratchUsed[i] = now;
            } else if (now - scratchUsed[i] > SCRATCH_KEPT) {
                t.destroyBuffers();
                scratchCount--;
                scratchTargets[i] = scratchTargets[scratchCount];
                scratchUsed[i] = scratchUsed[scratchCount];
                scratchTargets[scratchCount] = null;
            }
        }
        if (found != null) return found;
        if (scratchCount == scratchTargets.length) {
            RenderTarget[] targets = new RenderTarget[scratchCount * 2];
            long[] used = new long[scratchCount * 2];
            System.arraycopy(scratchTargets, 0, targets, 0, scratchCount);
            System.arraycopy(scratchUsed, 0, used, 0, scratchCount);
            scratchTargets = targets;
            scratchUsed = used;
        }
        found = make(w, h);
        scratchTargets[scratchCount] = found;
        scratchUsed[scratchCount++] = now;
        return found;
    }
    *///?}

    // A recorded page clears cell by cell where the device clears a rect (26.3's render passes clear a whole target)
    @Override
    public boolean keepsPage() {
        //? if >=26.3 {
        /*return false;
        *///?} else {
        return true;
        //?}
    }

    //? if >=1.21.6 {
    /*// A recorded GUI scissors and places in whole GUI units: a cell starts on its GUI scale's pixels
    @Override
    public int cellAlign(float scale) {
        return guiScale(scale);
    }
    *///?}

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
        long zone = CgTrace.begin(CgChannels.PICTURES, "pictures.setup");
        if (rects == null) clearAndBind(t, clear);
        else clearRectsAndBind(t, clear, rects, count);
        saveProjection();
        project(ctx.width, ctx.height);
        light(lighting);
        answerWindow(Math.round(ctx.width * ctx.scale), Math.round(ctx.height * ctx.scale), 0, ctx.scale, ctx.width,
                ctx.height);
        CgTrace.end(zone);
        zone = CgTrace.begin(CgChannels.PICTURES, "pictures.minecraft");
        startPairing();
        try {
            drawAtOnce(mc, drawer, ctx);
        } finally {
            endPairing();
            CgTrace.end(zone);
            zone = CgTrace.begin(CgChannels.PICTURES, "pictures.restore");
            window = false;
            unproject();
            unlight();
            unbind(mc);
            CgTrace.end(zone);
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

    // The GUI's own projection as the HUD has it, kept once a page for unproject
    private void saveProjection() {
        //? if <1.15 {
        /*GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.pushMatrix();
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.pushMatrix();
        *///?} elif <1.17 {
        /*RenderSystem.matrixMode(GL11.GL_PROJECTION);
        RenderSystem.pushMatrix();
        RenderSystem.matrixMode(GL11.GL_MODELVIEW);
        RenderSystem.pushMatrix();
        *///?} elif <1.19.3 {
        /*RenderSystem.backupProjectionMatrix();
        *///?} elif <1.20 {
        /*savedProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        *///?} elif <1.21.2 {
        savedProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        savedSorting = RenderSystem.getVertexSorting();
        //?} elif <1.21.6 {
        /*savedProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        savedType = RenderSystem.getProjectionType();
        *///?}
    }

    // The GUI's own projection, as GameRenderer sets it, over a picture's units instead of the window's
    private void project(float w, float h) {
        //? if <1.15 {
        /*GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.loadIdentity();
        GlStateManager.ortho(0.0, w, h, 0.0, 1000.0, 3000.0);
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.loadIdentity();
        GlStateManager.translatef(0f, 0f, -2000f);
        *///?} elif <1.17 {
        /*RenderSystem.matrixMode(GL11.GL_PROJECTION);
        RenderSystem.loadIdentity();
        RenderSystem.ortho(0.0, w, h, 0.0, 1000.0, 3000.0);
        RenderSystem.matrixMode(GL11.GL_MODELVIEW);
        RenderSystem.loadIdentity();
        RenderSystem.translatef(0f, 0f, -2000f);
        *///?} elif <1.19.3 {
        /*RenderSystem.setProjectionMatrix(Matrix4f.orthographic(0f, w, 0f, h, 1000f, 3000f));
        *///?} elif <1.20 {
        /*RenderSystem.setProjectionMatrix(guiProjection(savedProjection, w, h));
        *///?} elif <1.21.2 {
        RenderSystem.setProjectionMatrix(guiProjection(savedProjection, w, h), VertexSorting.ORTHOGRAPHIC_Z);
        //?} elif <1.21.6 {
        /*RenderSystem.setProjectionMatrix(guiProjection(savedProjection, w, h), ProjectionType.ORTHOGRAPHIC);
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
    // The GUI's projection as the HUD has it, its depth range kept, over the picture's units: one matrix, set per cell
    private Matrix4f guiProjection(Matrix4f gui, float w, float h) {
        return cellProjection.set(gui).m00(2f / w).m11(-2f / h).m30(-1f).m31(1f);
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

    // The icon atlas's draw: one drawer over the target, rendered by a GuiRenderer of our own whose main target and
    // window are the target's for the call, its projection spanning the drawer's units at an integer GUI scale
    private void recorded(Minecraft mc, RenderTarget t, @Nullable int[] rects, int count, CgPictureRenderer drawer,
                          Context ctx) {
        //? if >=1.21.6 {
        /*ensureRenderer(mc);
        int guiScale = guiScale(ctx.scale);
        ctx.gui = guiGraphics(mc);
        long zone = CgTrace.begin(CgChannels.PICTURES, "pictures.record");
        drawer.draw(ctx);
        CgTrace.end(zone);
        zone = CgTrace.begin(CgChannels.PICTURES, "pictures.clear");
        clearRecorded(t, rects, count);
        CgTrace.end(zone);
        renderInto(t, Math.round(ctx.width * guiScale), Math.round(ctx.height * guiScale), guiScale, ctx.width, ctx.height);
        *///?}
    }

    //? if >=1.21.6 {
    /*// A page in one render: each cell recorded under its pose and inside its scissor, its picture-in-picture elements
    // placed on it (RecordedPages); the page cleared whole where every cell draws, else cell by cell
    private void recordedPage(Minecraft mc, RenderTarget page, int side, CgHostPictures.Cells cells, float partialTick) {
        ensureRenderer(mc);
        int guiScale = guiScale(cells.scale(0));
        float units = (float) side / guiScale;
        // The window the page's from the first record: a GUI's scissors start from the window's size
        answerWindow(side, side, guiScale, guiScale, units, units);
        try {
            long zone = CgTrace.begin(CgChannels.PICTURES, "pictures.record");
            this.cells = cells;
            try {
                for (int i = 0; i < cells.count(); i++) {
                    float scale = cells.scale(i), cellUnits = cells.width(i) / scale, cellUnitsHigh = cells.height(i) / scale;
                    context.set(cellUnits, cellUnitsHigh, scale, partialTick);
                    context.gui = cellGui(mc, (float) cells.x(i) / guiScale, (float) cells.y(i) / guiScale,
                            scale / guiScale, (int) Math.ceil(cellUnits - 1e-3f), (int) Math.ceil(cellUnitsHigh - 1e-3f));
                    state.cell(cellPose);
                    cell = i;
                    cellDrawer.draw(context);
                }
            } finally {
                state.cell(null);
                this.cells = null;
                CgTrace.end(zone);
            }
            zone = CgTrace.begin(CgChannels.PICTURES, "pictures.clear");
            if (cells.whole() || !clearsRects()) {
                clearRecorded(page, null, 0);
            } else {
                clearRecorded(page, cellRects(cells, guiScale), cells.count());
            }
            CgTrace.end(zone);
            renderInto(page, side, side, guiScale, units, units);
        } finally {
            window = false;
        }
    }

    // A cell's GUI: its pose on the page (kept in cellPose) and its scissor, in the cell's units
    private Object cellGui(Minecraft mc, float x, float y, float scale, int unitsWide, int unitsHigh) {
        var gui = guiGraphics(mc);
        gui.pose().translate(x, y).scale(scale);
        cellPose.set(gui.pose());
        gui.enableScissor(0, 0, unitsWide, unitsHigh);
        return gui;
    }

    // Each cell's rect as it is packed: whole GUI units, the scissor's
    private int[] cellRects(CgHostPictures.Cells cells, int guiScale) {
        int n = cells.count();
        if (pageRects.length < n * 4) pageRects = new int[Math.max(n * 4, pageRects.length * 2)];
        for (int i = 0; i < n; i++) {
            int k = i * 4;
            pageRects[k] = cells.x(i);
            pageRects[k + 1] = cells.y(i);
            pageRects[k + 2] = (cells.width(i) + guiScale - 1) / guiScale * guiScale;
            pageRects[k + 3] = (cells.height(i) + guiScale - 1) / guiScale * guiScale;
        }
        return pageRects;
    }

    // A GuiRenderer's GUI scale for a picture's pixels per unit: whole, rounded up
    private static int guiScale(float scale) {
        return Math.max(1, (int) Math.ceil(scale - 1e-3f));
    }
    *///?}

    // The state rendered into t: its main target answered as t, the window t's at guiScale
    //? if >=1.21.6 <26.1 {
    /*private void renderInto(RenderTarget t, int pixelsWide, int pixelsHigh, int guiScale, float unitsWide,
                            float unitsHigh) {
        drawing = t;
        answerWindow(pixelsWide, pixelsHigh, guiScale, guiScale, unitsWide, unitsHigh);
        try {
            long zone = CgTrace.begin(CgChannels.PICTURES, "pictures.render");
            renderer.render(fog.getBuffer(FogRenderer.FogMode.NONE));
            CgTrace.end(zone);
        } finally {
            drawing = null;
            window = false;
        }
        renderer.incrementFrameNumber();
    }
    *///?} elif >=26.1 <26.2 {
    /*private void renderInto(RenderTarget t, int pixelsWide, int pixelsHigh, int guiScale, float unitsWide,
                            float unitsHigh) {
        WindowRenderState win = Minecraft.getInstance().gameRenderer.getGameRenderState().windowRenderState;
        int realWidth = win.width, realHeight = win.height, realScale = win.guiScale;
        win.width = pixelsWide;
        win.height = pixelsHigh;
        win.guiScale = guiScale;
        drawing = t;
        try {
            long zone = CgTrace.begin(CgChannels.PICTURES, "pictures.render");
            renderer.render(fog.getBuffer(FogRenderer.FogMode.NONE));
            CgTrace.end(zone);
        } finally {
            drawing = null;
            win.width = realWidth;
            win.height = realHeight;
            win.guiScale = realScale;
        }
        renderer.endFrame();
    }
    *///?} elif >=26.2 {
    /*private void renderInto(RenderTarget t, int pixelsWide, int pixelsHigh, int guiScale, float unitsWide,
                            float unitsHigh) {
        WindowRenderState win = Minecraft.getInstance().gameRenderer.gameRenderState().windowRenderState;
        int realWidth = win.width, realHeight = win.height, realScale = win.guiScale;
        win.width = pixelsWide;
        win.height = pixelsHigh;
        win.guiScale = guiScale;
        drawing = t;
        try {
            long zone = CgTrace.begin(CgChannels.PICTURES, "pictures.render");
            renderer.render();
            CgTrace.end(zone);
        } finally {
            drawing = null;
            win.width = realWidth;
            win.height = realHeight;
            win.guiScale = realScale;
        }
        renderer.endFrame();
    }
    *///?}

    //? if >=1.21.6 <1.21.11 {
    /*private GuiGraphics guiGraphics(Minecraft mc) {
        return new GuiGraphics(mc, state);
    }
    *///?} elif >=1.21.11 <26.1 {
    /*private GuiGraphics guiGraphics(Minecraft mc) {
        return new GuiGraphics(mc, state, -1, -1);
    }
    *///?} elif >=26.1 {
    /*private GuiGraphicsExtractor guiGraphics(Minecraft mc) {
        return new GuiGraphicsExtractor(mc, state, -1, -1);
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

    // The pictures' GuiRenderer over a page state, its one picture-in-picture renderer RecordedPages' over the loader's
    //? if >=1.21.6 <1.21.10 {
    /*@SuppressWarnings({"unchecked", "rawtypes"})
    private void makeRenderer(Minecraft mc) {
        var buffers = mc.renderBuffers().bufferSource();
        state = new RecordedPages.State();
        fog = new FogRenderer();
        pictures = new RecordedPages.Pictures(pictureInPicture.factories(mc), state, buffers);
        renderer = new GuiRenderer(state, buffers, (List) pictureInPicture.list(pictures));
    }

    // Vanilla's picture-in-picture renderers, as GameRenderer makes its own
    private static Map<Class<?>, Supplier<PictureInPictureRenderer<?>>> vanillaPictureInPicture(Minecraft mc) {
        var buffers = mc.renderBuffers().bufferSource();
        return Map.<Class<?>, Supplier<PictureInPictureRenderer<?>>>of(
                GuiEntityRenderState.class, () -> new GuiEntityRenderer(buffers, mc.getEntityRenderDispatcher()),
                GuiSkinRenderState.class, () -> new GuiSkinRenderer(buffers),
                GuiBookModelRenderState.class, () -> new GuiBookModelRenderer(buffers),
                GuiBannerResultRenderState.class, () -> new GuiBannerResultRenderer(buffers),
                GuiSignRenderState.class, () -> new GuiSignRenderer(buffers),
                GuiProfilerChartRenderState.class, () -> new GuiProfilerChartRenderer(buffers));
    }
    *///?} elif >=1.21.10 <26.2 {
    /*@SuppressWarnings({"unchecked", "rawtypes"})
    private void makeRenderer(Minecraft mc) {
        var buffers = mc.renderBuffers().bufferSource();
        state = new RecordedPages.State();
        fog = new FogRenderer();
        pictures = new RecordedPages.Pictures(pictureInPicture.factories(mc), state, buffers);
        renderer = new GuiRenderer(state, buffers, mc.gameRenderer.getSubmitNodeStorage(),
                mc.gameRenderer.getFeatureRenderDispatcher(), (List) pictureInPicture.list(pictures));
    }

    private static Map<Class<?>, Supplier<PictureInPictureRenderer<?>>> vanillaPictureInPicture(Minecraft mc) {
        var buffers = mc.renderBuffers().bufferSource();
        return Map.<Class<?>, Supplier<PictureInPictureRenderer<?>>>of(
                GuiEntityRenderState.class, () -> new GuiEntityRenderer(buffers, mc.getEntityRenderDispatcher()),
                GuiSkinRenderState.class, () -> new GuiSkinRenderer(buffers),
                GuiBookModelRenderState.class, () -> new GuiBookModelRenderer(buffers),
                GuiBannerResultRenderState.class, () -> new GuiBannerResultRenderer(buffers, mc.getAtlasManager()),
                GuiSignRenderState.class, () -> new GuiSignRenderer(buffers, mc.getAtlasManager()),
                GuiProfilerChartRenderState.class, () -> new GuiProfilerChartRenderer(buffers));
    }
    *///?} elif >=26.2 {
    /*@SuppressWarnings({"unchecked", "rawtypes"})
    private void makeRenderer(Minecraft mc) {
        state = new RecordedPages.State();
        pictures = new RecordedPages.Pictures(pictureInPicture.factories(mc), state);
        renderer = new GuiRenderer(state, mc.gameRenderer.featureRenderDispatcher(), (List) pictureInPicture.list(pictures));
    }

    private static Map<Class<?>, Supplier<PictureInPictureRenderer<?>>> vanillaPictureInPicture(Minecraft mc) {
        return Map.<Class<?>, Supplier<PictureInPictureRenderer<?>>>of(
                GuiEntityRenderState.class, () -> new GuiEntityRenderer(mc.getEntityRenderDispatcher()),
                GuiSkinRenderState.class, GuiSkinRenderer::new,
                GuiBookModelRenderState.class, GuiBookModelRenderer::new,
                GuiBannerResultRenderState.class, () -> new GuiBannerResultRenderer(mc.getAtlasManager()),
                GuiProfilerChartRenderState.class, GuiProfilerChartRenderer::new);
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

    /** What a renderer draws with: the era's GUI objects, which differ only in which are set. One, set per draw. */
    private static final class Context implements CgHostDrawContext {
        float width, height, scale, partialTick;
        @Nullable Object gui, pose, buffers;

        Context set(float width, float height, float scale, float partialTick) {
            this.width = width;
            this.height = height;
            this.scale = scale;
            this.partialTick = partialTick;
            gui = pose = buffers = null;
            return this;
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
