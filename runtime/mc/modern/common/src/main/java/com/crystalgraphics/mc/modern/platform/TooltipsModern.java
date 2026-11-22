package com.crystalgraphics.mc.modern.platform;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.crystalgraphics.api.text.CgStyledText;
import com.crystalgraphics.mc.render.custom.CgHostDrawContext;
import com.crystalgraphics.api.canvas.CgPicture;
import com.crystalgraphics.mc.render.tooltip.CgTooltipImage;
import com.crystalgraphics.mc.render.tooltip.CgTooltips;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
//? if >=1.14 {
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.screens.Screen;
//?}
//? if >=1.14 <1.16 {
/*import com.crystalgraphics.text.richtext.CgMinecraftColorCodeParser;
*///?}
//? if >=1.16 {
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import com.crystalgraphics.api.text.CgStyleSpan;
import com.crystalgraphics.api.text.CgTextDecoration;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
//?}
//? if >=1.17 {
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
//?}
//? if >=1.14 <1.19 {
/*import net.minecraft.network.chat.TextComponent;
*///?}
//? if >=1.16 <1.20 {
/*import com.mojang.blaze3d.vertex.PoseStack;
*///?}
//? if >=1.20 <26.1 {
import net.minecraft.client.gui.GuiGraphics;
//?}
//? if >=26.1 {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
*///?}

/**
 * {@link CgTooltips}' host on the modern tree: a stack's tooltip as a screen measures and draws it, through each
 * loader's tooltip events, so a tooltip mod's restyle shows; its lines as styled text.
 */
public final class TooltipsModern implements CgTooltips.Host {

    // Before 1.20 a tooltip's lines and its drawing are a screen's: one never shown
    //? if >=1.14 <1.16 {
    /*private static final class Tips extends Screen {
        Tips() {
            super(new TextComponent(""));
        }

        // Coloured strings here: each kept as a component, its codes read back by the parser
        List<Component> lines(ItemStack stack) {
            List<Component> out = new ArrayList<>();
            for (String line : getTooltipFromItem(stack)) out.add(new TextComponent(line));
            return out;
        }

        void tip(ItemStack stack, int x, int y) {
            renderTooltip(stack, x, y);
        }
    }
    *///?} elif >=1.16 <1.19 {
    /*private static final class Tips extends Screen {
        Tips() {
            super(new TextComponent(""));
        }

        List<Component> lines(ItemStack stack) {
            return getTooltipFromItem(stack);
        }

        void tip(PoseStack pose, ItemStack stack, int x, int y) {
            renderTooltip(pose, stack, x, y);
        }
    }
    *///?} elif >=1.19 <1.20 {
    /*private static final class Tips extends Screen {
        Tips() {
            super(Component.empty());
        }

        List<Component> lines(ItemStack stack) {
            return getTooltipFromItem(stack);
        }

        void tip(PoseStack pose, ItemStack stack, int x, int y) {
            renderTooltip(pose, stack, x, y);
        }
    }
    *///?}

    // The game's own GUI size, never the picture's: Forge wraps a tooltip to what the screen leaves it
    //? if >=1.14 <1.15 {
    /*private Tips tips;

    private Tips tips() {
        if (tips == null) tips = new Tips();
        Minecraft mc = Minecraft.getInstance();
        tips.init(mc, mc.window.getGuiScaledWidth(), mc.window.getGuiScaledHeight());
        return tips;
    }
    *///?} elif >=1.15 <1.20 {
    /*private Tips tips;

    private Tips tips() {
        if (tips == null) tips = new Tips();
        Minecraft mc = Minecraft.getInstance();
        tips.init(mc, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
        return tips;
    }
    *///?}

    @Override
    public String absent() {
        //? if <1.14 {
        /*return "1.13.2's item stacks cannot be made yet: CgItems writes no NBT there (NR7)";
        *///?} else {
        return null;
        //?}
    }

    // A screen's box: the widest line, 10 units a line and 2 more under the first, an image below, 4 around the text
    @Override
    public int[] measure(Object hostStack) {
        //? if <1.14 {
        /*return null;
        *///?} else {
        ItemStack stack = (ItemStack) hostStack;
        Minecraft mc = Minecraft.getInstance();
        List<Component> lines = components(stack);
        if (lines.isEmpty()) return null;
        int[] box = {0, lines.size() * 10 + (lines.size() > 1 ? 2 : 0) - 2};
        for (Component line : lines) box[0] = Math.max(box[0], width(mc, line));
        addImage(mc, stack, box);
        box[0] += 8;
        box[1] += 8;
        return box;
        //?}
    }

    // A screen draws a tooltip's text 12 units right of and 12 above the pointer, its box 4 around the text: a box's
    // top-left at (x, y) is a pointer at (x - 8, y + 16)
    @Override
    public void draw(CgHostDrawContext ctx, Object hostStack, int x, int y) {
        ItemStack stack = (ItemStack) hostStack;
        Minecraft mc = Minecraft.getInstance();
        int mx = x - 8, my = y + 16;
        //? if >=1.14 <1.16 {
        /*tips().tip(stack, mx, my);
        *///?} elif >=1.16 <1.20 {
        /*tips().tip((PoseStack) ctx.poseStack(), stack, mx, my);
        *///?} elif >=1.20 <1.21.6 {
        GuiGraphics g = (GuiGraphics) ctx.gui();
        g.renderTooltip(mc.font, stack, mx, my);
        g.flush();
        //?} elif >=1.21.6 <1.21.10 {
        /*// Deferred to the GUI's end, which a picture's GUI never reaches: drawn now
        GuiGraphics g = (GuiGraphics) ctx.gui();
        g.setTooltipForNextFrame(mc.font, stack, mx, my);
        g.renderDeferredTooltip();
        *///?} elif >=1.21.10 <26.1 {
        /*GuiGraphics g = (GuiGraphics) ctx.gui();
        g.setTooltipForNextFrame(mc.font, stack, mx, my);
        g.renderDeferredElements();
        *///?} elif >=26.1 {
        /*GuiGraphicsExtractor g = (GuiGraphicsExtractor) ctx.gui();
        g.setTooltipForNextFrame(mc.font, stack, mx, my);
        g.extractDeferredElements(-1, -1, 0f);
        *///?}
    }

    @Override
    public List<CgStyledText> lines(Object hostStack) {
        List<CgStyledText> out = new ArrayList<>();
        //? if >=1.14 {
        for (Component line : components((ItemStack) hostStack)) out.add(styled(line));
        //?}
        return out;
    }

    // What a tooltip shows below its lines, each a picture Minecraft draws at its own size: from 1.20, whose image
    // drawing takes the GUI's own object
    //? if <1.20 {
    /*@Override
    public List<CgPicture> images(Object hostStack) {
        return Collections.emptyList();
    }
    *///?} elif >=1.20 <1.21.3 {
    @Override
    public List<CgPicture> images(Object hostStack) {
        ItemStack stack = (ItemStack) hostStack;
        Optional<TooltipComponent> image = stack.getTooltipImage();
        if (!image.isPresent()) return Collections.emptyList();
        Minecraft mc = Minecraft.getInstance();
        ClientTooltipComponent c = ClientTooltipComponent.create(image.get());
        return Collections.singletonList(new CgTooltipImage("tooltip image", c.getWidth(mc.font), c.getHeight(),
                ctx -> c.renderImage(Minecraft.getInstance().font, 0, 0, (GuiGraphics) ctx.gui())));
    }
    //?} elif >=1.21.3 <26.1 {
    /*@Override
    public List<CgPicture> images(Object hostStack) {
        ItemStack stack = (ItemStack) hostStack;
        Optional<TooltipComponent> image = stack.getTooltipImage();
        if (!image.isPresent()) return Collections.emptyList();
        Minecraft mc = Minecraft.getInstance();
        ClientTooltipComponent c = ClientTooltipComponent.create(image.get());
        int w = c.getWidth(mc.font), h = c.getHeight(mc.font);
        return Collections.singletonList(new CgTooltipImage("tooltip image", w, h,
                ctx -> c.renderImage(Minecraft.getInstance().font, 0, 0, w, h, (GuiGraphics) ctx.gui())));
    }
    *///?} elif >=26.1 {
    /*@Override
    public List<CgPicture> images(Object hostStack) {
        ItemStack stack = (ItemStack) hostStack;
        Optional<TooltipComponent> image = stack.getTooltipImage();
        if (!image.isPresent()) return Collections.emptyList();
        Minecraft mc = Minecraft.getInstance();
        ClientTooltipComponent c = ClientTooltipComponent.create(image.get());
        int w = c.getWidth(mc.font), h = c.getHeight(mc.font);
        return Collections.singletonList(new CgTooltipImage("tooltip image", w, h,
                ctx -> c.extractImage(Minecraft.getInstance().font, 0, 0, w, h, (GuiGraphicsExtractor) ctx.gui())));
    }
    *///?}

    // The lines a screen draws, each loader's tooltip event included
    //? if >=1.14 <1.20 {
    /*private List<Component> components(ItemStack stack) {
        return tips().lines(stack);
    }
    *///?} elif >=1.20 {
    private List<Component> components(ItemStack stack) {
        return Screen.getTooltipFromItem(Minecraft.getInstance(), stack);
    }
    //?}

    //? if >=1.14 <1.16 {
    /*private static int width(Minecraft mc, Component line) {
        return mc.font.width(line.getColoredString());
    }
    *///?} elif >=1.16 {
    private static int width(Minecraft mc, Component line) {
        return mc.font.width(line);
    }
    //?}

    // An image under the lines (a bundle's contents) widens and lengthens the box
    //? if >=1.14 <1.17 {
    /*private static void addImage(Minecraft mc, ItemStack stack, int[] box) {
    }
    *///?} elif >=1.17 <1.21.3 {
    private static void addImage(Minecraft mc, ItemStack stack, int[] box) {
        Optional<TooltipComponent> image = stack.getTooltipImage();
        if (!image.isPresent()) return;
        ClientTooltipComponent c = ClientTooltipComponent.create(image.get());
        box[0] = Math.max(box[0], c.getWidth(mc.font));
        box[1] += c.getHeight();
    }
    //?} elif >=1.21.3 {
    /*private static void addImage(Minecraft mc, ItemStack stack, int[] box) {
        Optional<TooltipComponent> image = stack.getTooltipImage();
        if (!image.isPresent()) return;
        ClientTooltipComponent c = ClientTooltipComponent.create(image.get());
        box[0] = Math.max(box[0], c.getWidth(mc.font));
        box[1] += c.getHeight(mc.font);
    }
    *///?}

    // Each run of one style a span: its colour, bold, italic, underline and strikethrough
    //? if >=1.14 <1.16 {
    /*private static CgStyledText styled(Component line) {
        return new CgMinecraftColorCodeParser().parse(line.getColoredString());
    }
    *///?} elif >=1.16 {
    private static CgStyledText styled(Component line) {
        StringBuilder text = new StringBuilder();
        List<CgStyleSpan> spans = new ArrayList<>();
        line.visit((style, run) -> {
            int start = text.length();
            text.append(run);
            if (run.isEmpty() || style.isEmpty()) return Optional.empty();
            Set<CgTextDecoration> decorations = EnumSet.noneOf(CgTextDecoration.class);
            if (style.isUnderlined()) decorations.add(CgTextDecoration.UNDERLINE);
            if (style.isStrikethrough()) decorations.add(CgTextDecoration.STRIKETHROUGH);
            TextColor colour = style.getColor();
            spans.add(CgStyleSpan.builder().start(start).end(text.length()).bold(style.isBold()).italic(style.isItalic())
                    .decorations(decorations).argbColor(colour == null ? 0 : 0xFF000000 | colour.getValue()).build());
            return Optional.empty();
        }, Style.EMPTY);
        return new CgStyledText(text.toString(), spans);
    }
    //?}
}
