package com.crystalgraphics.mc.legacy.platform;

import com.crystalgraphics.probe.CgPicturesProbe;
//? if >=1.12 {
import com.crystalgraphics.probe.CgPicturesProbe.Cell;
import com.crystalgraphics.probe.CgPicturesProbe.Drawn;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.init.Enchantments;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
//?}

/**
 * NR0's host code for {@link CgPicturesProbe} on legacy Forge: vanilla's GUI calls drawn into the main framebuffer and
 * into a {@code Framebuffer} of the host's. 1.12.2 only; 1.8.9 and 1.10.2 give the probe its absent value.
 */
public final class PicturesProbeLegacy {

    private PicturesProbeLegacy() {
    }

    public static CgPicturesProbe.Host create() {
        //? if >=1.12 {
        return new Gui1122();
        //?} else {
        /*return CgPicturesProbe.Host.NONE;
        *///?}
    }

    //? if >=1.12 {
    /** Immediate GL through {@code GlStateManager}, the projection {@code setupOverlayRendering} leaves: ortho, 1000 to 3000. */
    private static final class Gui1122 implements CgPicturesProbe.Host {
        private Framebuffer black, white, picture;
        private int program;
        private Tips tips;
        private final Map<Cell, ItemStack> stacks = new IdentityHashMap<>();

        /** {@code renderToolTip} is a screen's: one never shown, sized as the window's GUI. */
        private static final class Tips extends GuiScreen {
            void tip(ItemStack stack, int x, int y) {
                renderToolTip(stack, x, y);
            }
        }

        @Override
        public String absent() {
            return OpenGlHelper.isFramebufferEnabled() ? null : "framebuffers are off (Fast Render or an old driver)";
        }

        @Override
        public boolean knows(String item) {
            return Item.getByNameOrId(item) != null;
        }

        @Override
        public boolean bottomUp() {
            return true;
        }

        @Override
        public void reference(Object gui, List<Cell> cells, int x, int y, int w, int h, int bx, int by, int background,
                              Drawn out) {
            Minecraft mc = Minecraft.getMinecraft();
            long start = System.nanoTime();
            Gui.drawRect(x, y, x + w, y + h, background);
            Gui.drawRect(bx, by, bx + w, by + h, background);
            GlStateManager.pushMatrix();
            GlStateManager.translate(x, y, 0f);
            for (Cell cell : cells) draw(mc, cell);
            GlStateManager.popMatrix();
            out.nanos = System.nanoTime() - start;
        }

        // Legacy GUI code writes alpha raw (a blend-off draw, glBlendFunc's alpha a^2): drawn over black and over white,
        // alpha = 1 - (white - black) and colour = black is what the screen would show over any background.
        @Override
        public Object picture(List<Cell> cells, int w, int h, Drawn out) {
            Minecraft mc = Minecraft.getMinecraft();
            int scale = new ScaledResolution(mc).getScaleFactor();
            int pw = w * scale, ph = h * scale;
            black = sized(black, pw, ph);
            white = sized(white, pw, ph);
            picture = sized(picture, pw, ph);
            long start = System.nanoTime();
            GlStateManager.matrixMode(GL11.GL_PROJECTION);
            GlStateManager.pushMatrix();
            GlStateManager.loadIdentity();
            GlStateManager.ortho(0.0, w, h, 0.0, 1000.0, 3000.0);
            GlStateManager.matrixMode(GL11.GL_MODELVIEW);
            try {
                drawOver(mc, cells, black, 0f, out);
                drawOver(mc, cells, white, 1f, null);
            } finally {
                GlStateManager.matrixMode(GL11.GL_PROJECTION);
                GlStateManager.popMatrix();
                GlStateManager.matrixMode(GL11.GL_MODELVIEW);
            }
            unmatte();
            mc.getFramebuffer().bindFramebuffer(true);
            out.nanos = System.nanoTime() - start;
            return picture.framebufferTexture;
        }

        private static Framebuffer sized(Framebuffer f, int w, int h) {
            if (f == null) return new Framebuffer(w, h, true);
            if (f.framebufferWidth != w || f.framebufferHeight != h) f.createBindFramebuffer(w, h);
            return f;
        }

        private void drawOver(Minecraft mc, List<Cell> cells, Framebuffer target, float clear, Drawn out) {
            target.setFramebufferColor(clear, clear, clear, 0f);
            target.framebufferClear();
            target.bindFramebuffer(true);
            for (Cell cell : cells) {
                draw(mc, cell);
                // Measured in the parity phase only: a glGet stalls a legacy context
                if (out != null && out.checkTarget && GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING) != target.framebufferObject) {
                    out.retargeted.add(cell.item().isEmpty() ? cell.kind().name() : cell.item());
                    target.bindFramebuffer(true);
                }
            }
        }

        // black and white into one premultiplied picture: a full-target pass of a GLSL 1.20 program
        private void unmatte() {
            if (program == 0) program = link();
            picture.bindFramebuffer(true);
            GlStateManager.disableBlend();
            GlStateManager.disableDepth();
            GlStateManager.disableAlpha();
            GlStateManager.disableCull();
            // Bound for the program, never enabled: an enabled unit textures every fixed-function draw after it
            GlStateManager.setActiveTexture(GL13.GL_TEXTURE2);
            GlStateManager.bindTexture(white.framebufferTexture);
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
            GlStateManager.enableTexture2D();
            GlStateManager.bindTexture(black.framebufferTexture);
            OpenGlHelper.glUseProgram(program);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "black"), 0);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "white"), 2);
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glTexCoord2f(0f, 0f);
            GL11.glVertex2f(-1f, -1f);
            GL11.glTexCoord2f(1f, 0f);
            GL11.glVertex2f(1f, -1f);
            GL11.glTexCoord2f(1f, 1f);
            GL11.glVertex2f(1f, 1f);
            GL11.glTexCoord2f(0f, 1f);
            GL11.glVertex2f(-1f, 1f);
            GL11.glEnd();
            OpenGlHelper.glUseProgram(0);
            GlStateManager.enableDepth();
            GlStateManager.enableAlpha();
        }

        private static int link() {
            int vs = shader(GL20.GL_VERTEX_SHADER, "#version 120\nvoid main() { gl_TexCoord[0] = gl_MultiTexCoord0; "
                    + "gl_Position = gl_Vertex; }");
            int fs = shader(GL20.GL_FRAGMENT_SHADER, "#version 120\nuniform sampler2D black; uniform sampler2D white;\n"
                    + "void main() { vec4 b = texture2D(black, gl_TexCoord[0].st); vec4 w = texture2D(white, gl_TexCoord[0].st);\n"
                    + "  float a = 1.0 - dot(w.rgb - b.rgb, vec3(1.0 / 3.0));\n"
                    + "  gl_FragColor = vec4(b.rgb, clamp(a, 0.0, 1.0)); }");
            int p = GL20.glCreateProgram();
            GL20.glAttachShader(p, vs);
            GL20.glAttachShader(p, fs);
            GL20.glLinkProgram(p);
            if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == 0) {
                throw new IllegalStateException("picture unmatte: " + GL20.glGetProgramInfoLog(p, 4096));
            }
            return p;
        }

        private static int shader(int type, String source) {
            int s = GL20.glCreateShader(type);
            GL20.glShaderSource(s, source);
            GL20.glCompileShader(s);
            if (GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) == 0) {
                throw new IllegalStateException("picture unmatte: " + GL20.glGetShaderInfoLog(s, 4096));
            }
            return s;
        }

        private void draw(Minecraft mc, Cell cell) {
            switch (cell.kind()) {
                case ITEM:
                case DECORATED:
                    RenderHelper.enableGUIStandardItemLighting();
                    mc.getRenderItem().renderItemAndEffectIntoGUI(stack(cell), cell.x(), cell.y());
                    if (cell.kind() == CgPicturesProbe.Kind.DECORATED) {
                        mc.getRenderItem().renderItemOverlays(mc.fontRenderer, stack(cell), cell.x(), cell.y());
                    }
                    RenderHelper.disableStandardItemLighting();
                    break;
                case PLAYER:
                    if (mc.player != null) {
                        // Depth on, as in the inventory screen: the HUD draws with it off, which shows a model's far side
                        GlStateManager.enableDepth();
                        GuiInventory.drawEntityOnScreen(cell.x(), cell.y(), cell.count(), CgPicturesProbe.LOOK_X,
                                CgPicturesProbe.LOOK_Y, mc.player);
                        GlStateManager.disableDepth();
                    }
                    break;
                case TOOLTIP:
                    if (tips == null) tips = new Tips();
                    ScaledResolution res = new ScaledResolution(mc);
                    tips.setWorldAndResolution(mc, res.getScaledWidth(), res.getScaledHeight());
                    tips.tip(stack(cell), cell.x(), cell.y());
                    RenderHelper.disableStandardItemLighting();
                    break;
                default:
            }
        }

        private ItemStack stack(Cell cell) {
            if (stacks.size() > 256) stacks.clear();   // the probe's cells change by phase
            ItemStack stack = stacks.get(cell);
            if (stack == null) {
                stack = new ItemStack(Item.getByNameOrId(cell.item()), Math.max(1, cell.count()));
                if (cell.damage() > 0) stack.setItemDamage(cell.damage());
                if (cell.glint()) stack.addEnchantment(Enchantments.SHARPNESS, 1);
                stacks.put(cell, stack);
            }
            return stack;
        }
    }
    //?}
}
