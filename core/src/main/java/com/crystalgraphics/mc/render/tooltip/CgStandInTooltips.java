package com.crystalgraphics.mc.render.tooltip;

import java.util.ArrayList;
import java.util.List;

import com.crystalgraphics.api.canvas.CgCanvas;
import com.crystalgraphics.api.text.CgStyledText;
import com.crystalgraphics.mc.item.CgItemStack;
import com.crystalgraphics.mc.render.custom.CgHostDrawContext;

/**
 * The host of {@link CgTooltips} where no Minecraft runs (the GL debug harness, a test): the item's id and count as
 * lines in a dark box with a purple border, sized as vanilla sizes its own, so measuring, bleed and refresh run as in a
 * game.
 *
 * <pre>{@code
 * CgPlatform.provide(CgTooltips.HOST, new CgStandInTooltips());
 * }</pre>
 */
public final class CgStandInTooltips implements CgTooltips.Host {

    /** Units a character takes: Minecraft's font is about this wide. */
    private static final int CHAR = 6;

    @Override
    public String absent() {
        return null;
    }

    @Override
    public int[] measure(Object hostStack) {
        List<CgStyledText> lines = lines(hostStack);
        int w = 0;
        for (CgStyledText l : lines) w = Math.max(w, l.plainText().length() * CHAR);
        // Vanilla's box: 4 units around the text, 10 a line, 2 more under the first
        return new int[] {w + 8, lines.size() * 10 + (lines.size() > 1 ? 2 : 0) - 2 + 8};
    }

    @Override
    public void draw(CgHostDrawContext ctx, Object hostStack, int x, int y) {
        int[] box = measure(hostStack);
        CgCanvas canvas = (CgCanvas) ctx.gui();
        canvas.fill(x, y, box[0], box[1], 0xF0100010);
        canvas.fill(x + 1, y + 1, box[0] - 2, 1f, 0x505000FF);
        canvas.fill(x + 1, y + box[1] - 2, box[0] - 2, 1f, 0x5028007F);
        List<CgStyledText> lines = lines(hostStack);
        for (int i = 0; i < lines.size(); i++) {
            int ly = y + 4 + i * 10 + (i > 0 ? 2 : 0);
            canvas.fill(x + 4, ly, lines.get(i).plainText().length() * CHAR, 7f, i == 0 ? 0xFFFFFFFF : 0xFFA0A0A0);
        }
    }

    @Override
    public List<CgStyledText> lines(Object hostStack) {
        CgItemStack s = (CgItemStack) hostStack;
        List<CgStyledText> out = new ArrayList<>();
        out.add(CgStyledText.plain(s.item()));
        if (s.count() > 1) out.add(CgStyledText.plain("Count: " + s.count()));
        for (String e : s.enchantments().keySet()) out.add(CgStyledText.plain(e));
        return out;
    }

    /** The stack itself: the stand-in draws from the value. */
    @Override
    public Object toHost(CgItemStack stack) {
        return stack;
    }
}
