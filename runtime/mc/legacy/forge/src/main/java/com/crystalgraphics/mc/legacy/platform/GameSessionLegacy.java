package com.crystalgraphics.mc.legacy.platform;

import java.io.File;
import java.lang.reflect.Constructor;

import javax.annotation.Nullable;

import com.crystalgraphics.platform.service.CgGameDirectory;
import com.crystalgraphics.platform.service.CgGameSession;
import com.crystalgraphics.platform.world.CgLevels;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;

/** {@link CgGameSession} on Forge 1.8.9-1.12.2: with no save it makes a flat creative world, as 1.7.10 does. */
public final class GameSessionLegacy implements CgGameSession.Host {

    public static final GameSessionLegacy INSTANCE = new GameSessionLegacy();

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");

    private GameSessionLegacy() {
    }

    @Override
    public boolean ready() {
        return Minecraft.getMinecraft().currentScreen instanceof GuiMainMenu || inWorld();
    }

    @Override
    public boolean inWorld() {
        // theWorld to 1.10.2, world from 1.11: the level slot spells it once.
        return CgLevels.clientLevel() != null;
    }

    @Override
    public boolean enterWorld(@Nullable String save) {
        Minecraft mc = Minecraft.getMinecraft();
        // The main menu itself: any screen includes Mojang's splash, before the context is in a state a launch survives.
        if (!(mc.currentScreen instanceof GuiMainMenu)) return false;
        File saves = new File(CgGameDirectory.game().toFile(), "saves");
        String found = CgGameSession.saveToEnter(saves.toPath(), save);
        String name = found == null ? CgGameSession.MADE_SAVE : found;
        boolean exists = new File(saves, name + "/level.dat").isFile();
        LOGGER.info("[cg] {} world '{}'", exists ? "loading" : "making", name);
        mc.launchIntegratedServer(name, name, exists ? null : creativeFlat());
        return true;
    }

    @Override
    public void quit() {
        Minecraft.getMinecraft().shutdown();
    }

    /**
     * {@code new WorldSettings(0, CREATIVE, false, false, FLAT)}, its game-mode type read off the constructor:
     * {@code WorldSettings.GameType} to 1.9.4, {@code world.GameType} from 1.10, inside one node's range.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static WorldSettings creativeFlat() {
        for (Constructor<?> constructor : WorldSettings.class.getConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length != 5 || !parameters[1].isEnum()) continue;
            try {
                Object creative = Enum.valueOf((Class) parameters[1], "CREATIVE");
                return (WorldSettings) constructor.newInstance(0L, creative, false, false, WorldType.FLAT);
            } catch (ReflectiveOperationException failed) {
                throw new IllegalStateException("cannot build WorldSettings", failed);
            }
        }
        throw new IllegalStateException("no WorldSettings(long, GameType, boolean, boolean, WorldType)");
    }
}
