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
//? if >=26.1 {
/*import com.mojang.serialization.Dynamic;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.filefix.FileFixerUpper;
import net.minecraft.util.worldupdate.UpgradeProgress;
import net.minecraft.world.level.storage.LevelStorageSource;
*///?}

/**
 * {@link CgGameSession} on 1.13+. It loads a save; with none on disk it loads one {@link CgGameSession#makeSave} wrote,
 * since making a level through Minecraft needs a {@code LevelSettings}, a {@code WorldOptions} and a dimensions function
 * whose signatures moved across versions.
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
        // Quick Play has not set the current server yet when a run first asks, so the dev run's property says it too.
        if (mc.getCurrentServer() != null || System.getProperty("crystalgraphics.join") != null) return true;
        Path saves = mc.gameDirectory.toPath().resolve("saves");
        String name = CgGameSession.saveToEnter(saves, save);
        if (name == null) name = CgGameSession.makeSave(saves);
        LOGGER.info("[cg] loading world '{}'", name);
        //? if >=26.1 {
        /*if (CgGameSession.MADE_SAVE.equals(name)) fixMadeSave(mc, name);
        *///?}
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

    //? if >=26.1 {
    /*// A save the file fixer has work for (any pre-26.3 one, on 26.3) opens behind two prompts: back it up, then join
    // now. The one this host made is fixed here instead, so a run never waits on a click; anyone else's keeps them.
    private static void fixMadeSave(Minecraft mc, String name) {
        try (LevelStorageSource.LevelStorageAccess access = mc.getLevelSource().createAccess(name)) {
            Dynamic<?> data = access.getUnfixedDataTag(false);
            FileFixerUpper fixer = DataFixers.getFileFixer();
            if (fixer.requiresFileFixing(NbtUtils.getDataVersion(data))) fixer.fix(access, data, new UpgradeProgress());
        } catch (Exception e) {
            LOGGER.warn("[cg] could not upgrade '{}' before loading it; Minecraft will ask", name, e);
        }
    }
    *///?}

    @Override
    public void quit() {
        // Minecraft's own shutdown, which the engine's teardown hangs off.
        Minecraft.getInstance().stop();
    }
}
