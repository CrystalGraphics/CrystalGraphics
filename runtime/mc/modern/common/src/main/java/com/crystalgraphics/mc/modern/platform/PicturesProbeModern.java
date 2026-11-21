package com.crystalgraphics.mc.modern.platform;

import com.crystalgraphics.probe.CgPicturesProbe;
//? if >=1.20 <1.20.2 {
import com.crystalgraphics.probe.CgPicturesProbe.Cell;
import com.crystalgraphics.probe.CgPicturesProbe.Drawn;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
//?}
//? if >=26.2 <26.3 {
/*import com.crystalgraphics.mc.modern.platform.vulkan.Blaze3dVulkanHost;
import com.crystalgraphics.probe.CgPicturesProbe.Cell;
import com.crystalgraphics.probe.CgPicturesProbe.Drawn;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.render.pip.GuiEntityRenderer;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.renderer.state.WindowRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector4f;
import java.util.function.Function;
*///?}

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * NR0's host code for {@link CgPicturesProbe}: vanilla's own GUI calls drawn into the main target and into a
 * {@code TextureTarget} of the host's. 1.20.1 only; every other node gives the probe its absent value.
 */
public final class PicturesProbeModern {

    private PicturesProbeModern() {
    }

    public static CgPicturesProbe.Host create() {
        //? if >=1.20 <1.20.2 {
        return new Gui1201();
        //?} elif >=26.2 <26.3 {
        /*return new Gui262();
        *///?} else {
        /*return CgPicturesProbe.Host.NONE;
        *///?}
    }

    //? if >=1.20 <1.20.2 {
    // GuiGraphics drawing at once into whatever target is bound, as 1.20's GUI does
    private static final class Gui1201 implements CgPicturesProbe.Host {
        private TextureTarget target;
        private final Map<Cell, ItemStack> stacks = new IdentityHashMap<>();

        @Override
        public String absent() {
            return null;
        }

        @Override
        public boolean knows(String item) {
            return BuiltInRegistries.ITEM.getOptional(ResourceIds.parse(item)).isPresent();
        }

        @Override
        public boolean bottomUp() {
            return true;
        }

        @Override
        public void reference(Object gui, List<Cell> cells, int x, int y, int w, int h, int bx, int by, int background,
                              Drawn out) {
            Minecraft mc = Minecraft.getInstance();
            GuiGraphics g = gui instanceof GuiGraphics given ? given : new GuiGraphics(mc, mc.renderBuffers().bufferSource());
            long start = System.nanoTime();
            g.fill(x, y, x + w, y + h, background);
            g.fill(bx, by, bx + w, by + h, background);
            g.flush();
            g.pose().pushPose();
            g.pose().translate(x, y, 0);
            for (Cell cell : cells) draw(mc, g, cell);
            g.pose().popPose();
            out.nanos = System.nanoTime() - start;
        }

        @Override
        public Object picture(List<Cell> cells, int w, int h, Drawn out) {
            Minecraft mc = Minecraft.getInstance();
            double scale = mc.getWindow().getGuiScale();
            int pw = (int) Math.round(w * scale), ph = (int) Math.round(h * scale);
            if (target == null) target = new TextureTarget(pw, ph, true, Minecraft.ON_OSX);
            else if (target.width != pw || target.height != ph) target.resize(pw, ph, Minecraft.ON_OSX);
            long start = System.nanoTime();
            Matrix4f projection = new Matrix4f(RenderSystem.getProjectionMatrix());
            VertexSorting sorting = RenderSystem.getVertexSorting();
            target.setClearColor(0f, 0f, 0f, 0f);
            target.clear(Minecraft.ON_OSX);
            target.bindWrite(true);
            try {
                // The GUI's own ortho, its depth range kept, over the picture's GUI units instead of the window's
                RenderSystem.setProjectionMatrix(new Matrix4f(projection).m00(2f / w).m11(-2f / h).m30(-1f).m31(1f),
                        VertexSorting.ORTHOGRAPHIC_Z);
                GuiGraphics g = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
                for (Cell cell : cells) {
                    draw(mc, g, cell);
                    if (out.checkTarget && GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING) != target.frameBufferId) {
                        out.retargeted.add(cell.item().isEmpty() ? cell.kind().name() : cell.item());
                        target.bindWrite(true);
                    }
                }
            } finally {
                RenderSystem.setProjectionMatrix(projection, sorting);
                mc.getMainRenderTarget().bindWrite(true);
            }
            out.nanos = System.nanoTime() - start;
            return target.getColorTextureId();
        }

        private void draw(Minecraft mc, GuiGraphics g, Cell cell) {
            switch (cell.kind()) {
                case ITEM -> g.renderItem(stack(cell), cell.x(), cell.y());
                case DECORATED -> {
                    g.renderItem(stack(cell), cell.x(), cell.y());
                    g.renderItemDecorations(mc.font, stack(cell), cell.x(), cell.y());
                }
                case PLAYER -> {
                    if (mc.player != null) {
                        InventoryScreen.renderEntityInInventoryFollowsMouse(g, cell.x(), cell.y(), cell.count(),
                                CgPicturesProbe.LOOK_X, CgPicturesProbe.LOOK_Y, mc.player);
                    }
                }
                case TOOLTIP -> g.renderTooltip(mc.font, stack(cell), cell.x(), cell.y());
            }
            g.flush();
        }

        private ItemStack stack(Cell cell) {
            if (stacks.size() > 256) stacks.clear();   // the probe's cells change by phase
            return stacks.computeIfAbsent(cell, c -> {
                ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(ResourceIds.parse(c.item())), Math.max(1, c.count()));
                if (c.damage() > 0) stack.setDamageValue(c.damage());
                if (c.glint()) stack.enchant(Enchantments.SHARPNESS, 1);
                return stack;
            });
        }
    }
    //?} elif >=26.2 <26.3 {
    /*private static RenderTarget pictureTarget;
    private static int pictureTargetAnswers;
    // GuiRenderer's third argument: vanilla's renderers; NeoForge patches it to registrations, every mod's gathered
    private static Function<Minecraft, List<?>> pictureInPicture =
            mc -> List.of(new GuiEntityRenderer(mc.getEntityRenderDispatcher()));

    // A loader whose GuiRenderer takes something else than vanilla's list says what, as its client starts.
    public static void pictureInPicture(Function<Minecraft, List<?>> renderers) {
        pictureInPicture = renderers;
    }

    // What GameRenderer.mainRenderTarget() answers while a picture draws (PictureTargetHook); null otherwise.
    public static RenderTarget pictureTarget() {
        RenderTarget t = pictureTarget;
        if (t != null) pictureTargetAnswers++;
        return t;
    }

    // A GuiRenderer of the host's own over a GuiRenderState of its own: items, text, tooltips and picture-in-picture
    // entities as the GUI records them, drawn into the picture's target while GameRenderer answers it for the main one
    // and the window state has the picture's size.
    private static final class Gui262 implements CgPicturesProbe.Host {
        private static final Vector4f CLEAR = new Vector4f(0f);
        private TextureTarget target;
        private GuiRenderState state;
        private GuiRenderer renderer;
        private final Map<Cell, ItemStack> stacks = new IdentityHashMap<>();

        @Override
        public String absent() {
            return null;
        }

        @Override
        public boolean knows(String item) {
            return BuiltInRegistries.ITEM.getOptional(ResourceIds.parse(item)).isPresent();
        }

        @Override
        public boolean bottomUp() {
            return true;
        }

        @Override
        public void reference(Object gui, List<Cell> cells, int x, int y, int w, int h, int bx, int by, int background,
                              Drawn out) {
            if (!(gui instanceof GuiGraphicsExtractor g)) {
                out.state = "the HUD hook gave no GuiGraphicsExtractor";
                return;
            }
            long start = System.nanoTime();
            g.fill(x, y, x + w, y + h, background);
            g.fill(bx, by, bx + w, by + h, background);
            // Offsets, not a pose: an entity's picture-in-picture is placed by its box and ignores the pose's translation
            for (Cell cell : cells) draw(Minecraft.getInstance(), g, cell, x, y);
            out.nanos = System.nanoTime() - start;
        }

        @Override
        public Object picture(List<Cell> cells, int w, int h, Drawn out) {
            Minecraft mc = Minecraft.getInstance();
            int scale = mc.getWindow().getGuiScale();
            int pw = w * scale, ph = h * scale;
            if (target == null) target = new TextureTarget("crystalgraphics pictures probe", pw, ph, true, GpuFormat.RGBA8_UNORM);
            else if (target.width != pw || target.height != ph) target.resize(pw, ph);
            if (renderer == null) {
                state = new GuiRenderState();
                @SuppressWarnings({"unchecked", "rawtypes"})
                List renderers = pictureInPicture.apply(mc);
                renderer = new GuiRenderer(state, mc.gameRenderer.featureRenderDispatcher(), renderers);
            }
            long start = System.nanoTime();
            GuiGraphicsExtractor g = new GuiGraphicsExtractor(mc, state, -1, -1);
            for (Cell cell : cells) draw(mc, g, cell, 0, 0);
            // Reversed depth on 26.2: cleared to 0, as the GUI's own targets
            RenderSystem.getDevice().createCommandEncoder()
                    .clearColorAndDepthTextures(target.getColorTexture(), CLEAR, target.getDepthTexture(), 0.0);
            WindowRenderState window = mc.gameRenderer.gameRenderState().windowRenderState;
            int windowWidth = window.width, windowHeight = window.height, answers = pictureTargetAnswers;
            window.width = pw;
            window.height = ph;
            pictureTarget = target;
            try {
                renderer.render();
            } finally {
                pictureTarget = null;
                window.width = windowWidth;
                window.height = windowHeight;
            }
            renderer.endFrame();
            if (pictureTargetAnswers == answers) out.retargeted.add("GameRenderer.mainRenderTarget was never answered (no hook)");
            out.nanos = System.nanoTime() - start;
            return GraphicsApi.vulkan() ? Blaze3dVulkanHost.current().importTexture(target.getColorTexture())
                    : (Object) ((GlTexture) target.getColorTexture()).glId();
        }

        private void draw(Minecraft mc, GuiGraphicsExtractor g, Cell cell, int ox, int oy) {
            int cx = cell.x() + ox, cy = cell.y() + oy;
            switch (cell.kind()) {
                case ITEM -> g.item(stack(cell), cx, cy);
                case DECORATED -> {
                    g.item(stack(cell), cx, cy);
                    g.itemDecorations(mc.font, stack(cell), cx, cy);
                }
                case PLAYER -> {
                    if (mc.player != null) {
                        int x = cx, y = cy;
                        // The inventory's box and pointer: the angle is the box's centre against the pointer
                        InventoryScreen.extractEntityInInventoryFollowsMouse(g, x - 25, y - 70, x + 25, y, cell.count(),
                                0.0625f, x - CgPicturesProbe.LOOK_X, y - 35 - CgPicturesProbe.LOOK_Y, mc.player);
                    }
                }
                case TOOLTIP -> {
                    // Deferred to the frame's end by the GUI; emitted here, under this cell's pose
                    g.setTooltipForNextFrame(mc.font, stack(cell), cx, cy);
                    g.extractDeferredElements(-1, -1, 0f);
                }
            }
        }

        private ItemStack stack(Cell cell) {
            if (stacks.size() > 256) stacks.clear();   // the probe's cells change by phase
            return stacks.computeIfAbsent(cell, c -> {
                ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.getValue(ResourceIds.parse(c.item())), Math.max(1, c.count()));
                if (c.damage() > 0) stack.setDamageValue(c.damage());
                if (c.glint()) stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
                return stack;
            });
        }
    }
    *///?}
}
