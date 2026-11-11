package com.crystalgraphics.mc.modern.platform;

import com.crystalgraphics.platform.service.CgGameSession;

import java.nio.file.Path;

import javax.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.client.Minecraft;
//? if >=1.19 {
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
//?}

/**
 * {@link CgGameSession} on 1.13+. It loads a save and never makes one: a new level needs a {@code LevelSettings}, a
 * {@code WorldOptions} and a dimensions function whose signatures moved across versions, which a run avoids by being
 * given a save.
 */
public final class GameSessionModern implements CgGameSession.Host {

    public static final GameSessionModern INSTANCE = new GameSessionModern();

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");

    private GameSessionModern() {
    }

    @Override
    public boolean ready() {
        Minecraft mc = Minecraft.getInstance();
        // A client tick fires under Mojang's loading overlay: a request then races its splash.
        return mc != null && !CgMinecraftClient.overlayUp(mc);
    }

    @Override
    public boolean inWorld() {
        Minecraft mc = Minecraft.getInstance();
        return mc != null && mc.level != null;
    }

    @Override
    public boolean enterWorld(@Nullable String save) {
        Minecraft mc = Minecraft.getInstance();
        if (!ready()) return false;
        // Pointed at a server (-PcgJoin): already on its way, and there is no save to load.
        if (mc.getCurrentServer() != null) return true;
        Path saves = mc.gameDirectory.toPath().resolve("saves");
        String name = CgGameSession.saveToEnter(saves, save);
        if (name == null) throw new IllegalStateException("no save under " + saves + ", and this version cannot make one");
        LOGGER.info("[cg] loading world '{}'", name);
        // Compiled on each side of the break, never reflective: each thin jar is remapped as it is built, and a lookup by
        // Mojang name is a string no remapper rewrites. The Runnable is the give-up path, not a completion callback.
        //? if >=1.20.5 {
        /*mc.createWorldOpenFlows().openWorld(name, () -> { });
        *///?} elif >=1.20.3 {
        /*mc.createWorldOpenFlows().checkForBackupAndLoad(name, () -> { });
        *///?} elif >=1.19 {
        WorldOpenFlows flows = mc.createWorldOpenFlows();
        flows.loadLevel(CgMinecraftClient.screen(mc), name);
        //?} elif >=1.16 {
        /*mc.loadLevel(name);
        *///?} else {
        /*mc.selectLevel(name, name, null);
        *///?}
        return true;
    }

    @Override
    public void quit() {
        // Minecraft's own shutdown, which the engine's teardown hangs off.
        Minecraft.getInstance().stop();
    }
}
