package com.crystalgraphics.mc.v1710.platform.service;

import com.crystalgraphics.platform.service.CgGameDirectory;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;

/** The client's game directory on 1.7.10. */
public final class GameDirectoryService1710 implements CgGameDirectory {

    @Override
    public Path get() {
        return Minecraft.getMinecraft().mcDataDir.toPath();
    }
}
