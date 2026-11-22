package com.crystalgraphics.mc.modern.neoforge;

//? if >=1.20 <26.1 {
import com.crystalgraphics.probe.CgPicturesProbe;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.IItemDecorator;

// The pictures probe's item decorator: a red mark on its stick's corner while the test runs, so the probe proves a
// mod's decorator draws in an item's overlay as in vanilla's slot. Draws nothing otherwise.
final class ProbeDecorator implements IItemDecorator {
    @Override
    public boolean render(GuiGraphics g, Font font, ItemStack stack, int x, int y) {
        if (CgPicturesProbe.running()) g.fill(x, y, x + 4, y + 4, 0xFFE02020);
        return false;
    }
}
//?} elif >=26.1 {
/*import com.crystalgraphics.probe.CgPicturesProbe;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.IItemDecorator;

// The pictures probe's item decorator: a red mark on its stick's corner while the test runs, so the probe proves a
// mod's decorator draws in an item's overlay as in vanilla's slot. Draws nothing otherwise.
final class ProbeDecorator implements IItemDecorator {
    @Override
    public boolean render(GuiGraphicsExtractor g, Font font, ItemStack stack, int x, int y) {
        if (CgPicturesProbe.running()) g.fill(x, y, x + 4, y + 4, 0xFFE02020);
        return false;
    }
}
*///?} else {
/*// No decorator before 1.20: the probe's decorated cell shows the stick alone, as vanilla's slot does
final class ProbeDecorator {
}
*///?}
