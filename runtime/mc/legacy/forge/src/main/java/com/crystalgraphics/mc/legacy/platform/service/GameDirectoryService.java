package com.crystalgraphics.mc.legacy.platform.service;

import com.crystalgraphics.platform.service.CgGameDirectory;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;

/** The client's game directory on Forge 1.8–1.12.2: {@code mcDataDir}, renamed {@code gameDir} in 1.12's MCP names. */
public final class GameDirectoryService implements CgGameDirectory {

    @Override
    public Path get() {
        //? if >=1.12 {
        return Minecraft.getMinecraft().gameDir.toPath();
        //?} else {
        /*return Minecraft.getMinecraft().mcDataDir.toPath();
        *///?}
    }
}
