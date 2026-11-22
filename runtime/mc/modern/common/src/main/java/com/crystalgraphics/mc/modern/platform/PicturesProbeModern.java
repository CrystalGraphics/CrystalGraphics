package com.crystalgraphics.mc.modern.platform;

import com.crystalgraphics.mc.item.CgItemStack;
import com.crystalgraphics.mc.item.CgItems;
import com.crystalgraphics.mc.render.custom.CgHostDrawContext;
import com.crystalgraphics.probe.CgPicturesProbe;
import com.crystalgraphics.probe.CgPicturesProbe.Cell;
import com.crystalgraphics.probe.CgPicturesProbe.Drawn;
import com.crystalgraphics.probe.CgPicturesProbe.Kind;
import com.crystalgraphics.serialization.CgCodecException;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
//? if <1.14 {
/*import java.util.HashMap;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ItemLike;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
*///?} elif <1.14.3 {
/*import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
*///?} elif <1.15 {
/*import com.mojang.blaze3d.platform.Lighting;
import net.minecraft.client.gui.GuiComponent;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
*///?} elif <1.16 {
/*import net.minecraft.client.gui.GuiComponent;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
*///?} elif <1.20 {
/*import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiComponent;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
*///?} elif <1.21.5 {
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
//?} elif <26.1 {
/*import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
*///?} else {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
*///?}

/**
 * The modern half of {@link CgPicturesProbe}: vanilla's own GUI calls, into the main target from the HUD hook and into
 * a picture through the draw context the draw point hands over.
 *
 * <ul>
 *   <li>1.13.2 - 1.19.4: {@code ItemRenderer}'s items and decorations; 1.13.2 has no GUI fill under Mojang's names, so
 *       no reference panel there.</li>
 *   <li>1.20.1 - 1.21.5 and 26.1 on: every kind, as {@code GuiGraphics} or {@code GuiGraphicsExtractor} draws it.</li>
 *   <li>1.21.6 - 1.21.11: items, decorations and the player.</li>
 * </ul>
 */
public final class PicturesProbeModern implements CgPicturesProbe.Host {

    private final Map<Cell, ItemStack> stacks = new IdentityHashMap<>();

    private PicturesProbeModern() {
    }

    public static CgPicturesProbe.Host create() {
        return new PicturesProbeModern();
    }

    @Override
    @Nullable
    public String absent() {
        return null;
    }

    @Override
    public boolean knows(String item) {
        //? if <1.14 {
        /*return VANILLA.containsKey(item);
        *///?} else {
        // An item the game lacks: null before 1.20.5, a codec's refusal after
        try {
            ItemStack stack = CgItems.toHost(CgItemStack.of(item, 1));
            return stack != null && !stack.isEmpty();
        } catch (CgCodecException e) {
            return false;
        }
        //?}
    }

    @Override
    public boolean draws(Kind kind) {
        //? if <1.20 {
        /*return kind == Kind.ITEM || kind == Kind.DECORATED;
        *///?} else {
        return true;
        //?}
    }

    @Override
    public void reference(@Nullable Object gui, List<Cell> cells, int x, int y, int w, int h, int bx, int by,
                          int background, Drawn out) {
        Minecraft mc = Minecraft.getInstance();
        long start = System.nanoTime();
        //? if <1.14.3 {
        /*// No GUI fill under Mojang's names here: no reference
        *///?} elif <1.15 {
        /*GuiComponent.fill(x, y, x + w, y + h, background);
        GuiComponent.fill(bx, by, bx + w, by + h, background);
        // As the hotbar lights its items: the GUI rests with lighting off before 1.15
        Lighting.turnOnGui();
        for (Cell cell : cells) drawItem(mc, cell, x, y);
        Lighting.turnOff();
        out.drew = true;
        *///?} elif <1.16 {
        /*GuiComponent.fill(x, y, x + w, y + h, background);
        GuiComponent.fill(bx, by, bx + w, by + h, background);
        for (Cell cell : cells) drawItem(mc, cell, x, y);
        out.drew = true;
        *///?} elif <1.20 {
        /*PoseStack pose = new PoseStack();
        GuiComponent.fill(pose, x, y, x + w, y + h, background);
        GuiComponent.fill(pose, bx, by, bx + w, by + h, background);
        for (Cell cell : cells) drawItem(mc, pose, cell, x, y);
        out.drew = true;
        *///?} elif <1.21.6 {
        GuiGraphics g = gui instanceof GuiGraphics given ? given : new GuiGraphics(mc, mc.renderBuffers().bufferSource());
        g.fill(x, y, x + w, y + h, background);
        g.fill(bx, by, bx + w, by + h, background);
        g.flush();
        for (Cell cell : cells) draw(mc, g, cell, x, y);
        out.drew = true;
        //?} elif <26.1 {
        /*if (!(gui instanceof GuiGraphics g)) return;
        g.fill(x, y, x + w, y + h, background);
        g.fill(bx, by, bx + w, by + h, background);
        for (Cell cell : cells) draw(mc, g, cell, x, y);
        out.drew = true;
        *///?} else {
        /*if (!(gui instanceof GuiGraphicsExtractor g)) return;
        g.fill(x, y, x + w, y + h, background);
        g.fill(bx, by, bx + w, by + h, background);
        for (Cell cell : cells) draw(mc, g, cell, x, y);
        out.drew = true;
        *///?}
        out.nanos = System.nanoTime() - start;
    }

    @Override
    public void cells(CgHostDrawContext ctx, List<Cell> cells, Drawn out) {
        Minecraft mc = Minecraft.getInstance();
        //? if <1.21.5 {
        int bound = out.checkTarget ? GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING) : 0;
        //?}
        for (Cell cell : cells) {
            //? if <1.16 {
            /*drawItem(mc, cell, 0, 0);
            *///?} elif <1.20 {
            /*drawItem(mc, (PoseStack) ctx.poseStack(), cell, 0, 0);
            *///?} elif <26.1 {
            draw(mc, (GuiGraphics) ctx.gui(), cell, 0, 0);
            //?} else {
            /*draw(mc, (GuiGraphicsExtractor) ctx.gui(), cell, 0, 0);
            *///?}
            //? if <1.21.5 {
            if (out.checkTarget && GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING) != bound) {
                out.retargeted.add(cell.item().isEmpty() ? cell.kind().name() : cell.item());
            }
            //?}
        }
    }

    //? if <1.16 {
    /*// ItemRenderer drawing at once into the bound target
    private void drawItem(Minecraft mc, Cell cell, int ox, int oy) {
        ItemStack stack = stack(cell);
        mc.getItemRenderer().renderAndDecorateItem(stack, cell.x() + ox, cell.y() + oy);
        if (cell.kind() == Kind.DECORATED) mc.getItemRenderer().renderGuiItemDecorations(mc.font, stack, cell.x() + ox, cell.y() + oy);
    }
    *///?} elif <1.19.4 {
    /*private void drawItem(Minecraft mc, PoseStack pose, Cell cell, int ox, int oy) {
        ItemStack stack = stack(cell);
        mc.getItemRenderer().renderAndDecorateItem(stack, cell.x() + ox, cell.y() + oy);
        if (cell.kind() == Kind.DECORATED) mc.getItemRenderer().renderGuiItemDecorations(mc.font, stack, cell.x() + ox, cell.y() + oy);
    }
    *///?} elif <1.20 {
    /*private void drawItem(Minecraft mc, PoseStack pose, Cell cell, int ox, int oy) {
        ItemStack stack = stack(cell);
        mc.getItemRenderer().renderAndDecorateItem(pose, stack, cell.x() + ox, cell.y() + oy);
        if (cell.kind() == Kind.DECORATED) mc.getItemRenderer().renderGuiItemDecorations(pose, mc.font, stack, cell.x() + ox, cell.y() + oy);
    }
    *///?} elif <1.20.2 {
    // GuiGraphics drawing at once into whatever target is bound
    private void draw(Minecraft mc, GuiGraphics g, Cell cell, int ox, int oy) {
        int x = cell.x() + ox, y = cell.y() + oy;
        switch (cell.kind()) {
            case ITEM -> g.renderItem(stack(cell), x, y);
            case DECORATED -> {
                g.renderItem(stack(cell), x, y);
                g.renderItemDecorations(mc.font, stack(cell), x, y);
            }
            case PLAYER -> {
                if (mc.player != null) {
                    InventoryScreen.renderEntityInInventoryFollowsMouse(g, x, y, cell.count(), CgPicturesProbe.LOOK_X,
                            CgPicturesProbe.LOOK_Y, mc.player);
                }
            }
            case TOOLTIP -> g.renderTooltip(mc.font, stack(cell), x, y);
        }
        g.flush();
    }
    //?} elif <1.21.6 {
    /*// The same; the player by its box from 1.20.2
    private void draw(Minecraft mc, GuiGraphics g, Cell cell, int ox, int oy) {
        int x = cell.x() + ox, y = cell.y() + oy;
        switch (cell.kind()) {
            case ITEM -> g.renderItem(stack(cell), x, y);
            case DECORATED -> {
                g.renderItem(stack(cell), x, y);
                g.renderItemDecorations(mc.font, stack(cell), x, y);
            }
            case PLAYER -> {
                if (mc.player != null) {
                    InventoryScreen.renderEntityInInventoryFollowsMouse(g, x - 25, y - 70, x + 25, y, cell.count(), 0.0625f,
                            x - CgPicturesProbe.LOOK_X, y - 35 - CgPicturesProbe.LOOK_Y, mc.player);
                }
            }
            case TOOLTIP -> g.renderTooltip(mc.font, stack(cell), x, y);
        }
        g.flush();
    }
    *///?} elif <1.21.10 {
    /*// The recorded GUI: an entity's picture-in-picture is placed by its box, so offsets, never a pose
    private void draw(Minecraft mc, GuiGraphics g, Cell cell, int ox, int oy) {
        int x = cell.x() + ox, y = cell.y() + oy;
        switch (cell.kind()) {
            case ITEM -> g.renderItem(stack(cell), x, y);
            case DECORATED -> {
                g.renderItem(stack(cell), x, y);
                g.renderItemDecorations(mc.font, stack(cell), x, y);
            }
            case PLAYER -> {
                if (mc.player != null) {
                    InventoryScreen.renderEntityInInventoryFollowsMouse(g, x - 25, y - 70, x + 25, y, cell.count(), 0.0625f,
                            x - CgPicturesProbe.LOOK_X, y - 35 - CgPicturesProbe.LOOK_Y, mc.player);
                }
            }
            case TOOLTIP -> {
                // Deferred to the GUI's end, which holds one: drawn now, under this cell's pose
                g.setTooltipForNextFrame(mc.font, stack(cell), x, y);
                g.renderDeferredTooltip();
            }
        }
    }
    *///?} elif <26.1 {
    /*// The recorded GUI: an entity's picture-in-picture is placed by its box, so offsets, never a pose
    private void draw(Minecraft mc, GuiGraphics g, Cell cell, int ox, int oy) {
        int x = cell.x() + ox, y = cell.y() + oy;
        switch (cell.kind()) {
            case ITEM -> g.renderItem(stack(cell), x, y);
            case DECORATED -> {
                g.renderItem(stack(cell), x, y);
                g.renderItemDecorations(mc.font, stack(cell), x, y);
            }
            case PLAYER -> {
                if (mc.player != null) {
                    InventoryScreen.renderEntityInInventoryFollowsMouse(g, x - 25, y - 70, x + 25, y, cell.count(), 0.0625f,
                            x - CgPicturesProbe.LOOK_X, y - 35 - CgPicturesProbe.LOOK_Y, mc.player);
                }
            }
            case TOOLTIP -> {
                g.setTooltipForNextFrame(mc.font, stack(cell), x, y);
                g.renderDeferredElements();
            }
        }
    }
    *///?} else {
    /*// The recorded GUI: an entity's picture-in-picture is placed by its box, so offsets, never a pose
    private void draw(Minecraft mc, GuiGraphicsExtractor g, Cell cell, int ox, int oy) {
        int x = cell.x() + ox, y = cell.y() + oy;
        switch (cell.kind()) {
            case ITEM -> g.item(stack(cell), x, y);
            case DECORATED -> {
                g.item(stack(cell), x, y);
                g.itemDecorations(mc.font, stack(cell), x, y);
            }
            case PLAYER -> {
                if (mc.player != null) {
                    InventoryScreen.extractEntityInInventoryFollowsMouse(g, x - 25, y - 70, x + 25, y, cell.count(), 0.0625f,
                            x - CgPicturesProbe.LOOK_X, y - 35 - CgPicturesProbe.LOOK_Y, mc.player);
                }
            }
            case TOOLTIP -> {
                // Deferred to the frame's end by the GUI; emitted here, under this cell's pose
                g.setTooltipForNextFrame(mc.font, stack(cell), x, y);
                g.extractDeferredElements(-1, -1, 0f);
            }
        }
    }
    *///?}

    @Override
    public void cooldown(String item) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        //? if >=1.14 {
        ItemStack stack = CgItems.toHost(CgItemStack.of(item, 1));
        if (stack == null || stack.isEmpty()) return;
        //?}
        //? if >=1.14 <1.21.3 {
        if (!mc.player.getCooldowns().isOnCooldown(stack.getItem())) mc.player.getCooldowns().addCooldown(stack.getItem(), 200);
        //?} elif >=1.21.3 {
        /*if (!mc.player.getCooldowns().isOnCooldown(stack)) mc.player.getCooldowns().addCooldown(stack, 200);
        *///?}
    }

    private ItemStack stack(Cell cell) {
        if (stacks.size() > 256) stacks.clear();   // the probe's cells change by phase
        return stacks.computeIfAbsent(cell, c -> {
            //? if <1.14 {
            /*ItemStack s = new ItemStack(VANILLA.get(c.item()), Math.max(1, c.count()));
            if (c.damage() > 0) s.setDamageValue(c.damage());
            if (c.glint()) s.enchant(Enchantments.SHARPNESS, 1);
            return s;
            *///?} else {
            return CgItems.toHost(CgPicturesProbe.stackOf(c));
            //?}
        });
    }

    //? if <1.14 {
    /*// 1.13.2's CgItems cannot write NBT (CompoundTag.put has no carried-back name until the stub database is
    // regenerated: NR7), so the probe's items come from Minecraft's own constants there
    private static final Map<String, ItemLike> VANILLA = new HashMap<>();
    static {
        VANILLA.put("minecraft:diamond_sword", Items.DIAMOND_SWORD);
        VANILLA.put("minecraft:diamond_pickaxe", Items.DIAMOND_PICKAXE);
        VANILLA.put("minecraft:clock", Items.CLOCK);
        VANILLA.put("minecraft:compass", Items.COMPASS);
        VANILLA.put("minecraft:shield", Items.SHIELD);
        VANILLA.put("minecraft:book", Items.BOOK);
    }
    *///?}
}
