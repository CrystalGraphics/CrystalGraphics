package com.crystalgraphics.mc.v1710.platform;

import java.io.File;

import javax.annotation.Nullable;

import com.crystalgraphics.platform.service.CgGameSession;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;

/** {@link CgGameSession} on 1.7.10: with no save it makes a flat creative world, so a run needs nobody to have made one. */
public final class GameSession1710 implements CgGameSession.Host {

    public static final GameSession1710 INSTANCE = new GameSession1710();

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");

    private GameSession1710() {
    }

    @Override
    public boolean ready() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc.currentScreen instanceof GuiMainMenu || mc.theWorld != null;
    }

    @Override
    public boolean inWorld() {
        return Minecraft.getMinecraft().theWorld != null;
    }

    @Override
    public boolean enterWorld(@Nullable String save) {
        Minecraft mc = Minecraft.getMinecraft();
        // The main menu itself: any screen includes Mojang's splash, before the context is in a state a launch survives.
        if (!(mc.currentScreen instanceof GuiMainMenu)) return false;
        String found = CgGameSession.saveToEnter(new File(mc.mcDataDir, "saves").toPath(), save);
        String name = found == null ? CgGameSession.MADE_SAVE : found;
        boolean exists = new File(mc.mcDataDir, "saves/" + name + "/level.dat").isFile();
        LOGGER.info("[cg] {} world '{}'", exists ? "loading" : "making", name);
        mc.launchIntegratedServer(name, name, exists ? null
                : new WorldSettings(0L, WorldSettings.GameType.CREATIVE, false, false, WorldType.FLAT));
        return true;
    }

    @Override
    public void quit() {
        Minecraft.getMinecraft().shutdown();
    }
}
