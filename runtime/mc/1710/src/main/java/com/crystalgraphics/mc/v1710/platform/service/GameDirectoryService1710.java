package com.crystalgraphics.mc.v1710.platform.service;

import com.crystalgraphics.platform.service.CgGameDirectory;
import com.crystalgraphics.platform.service.CgServers;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;

/** The client's game directory on 1.7.10. */
public final class GameDirectoryService1710 implements CgGameDirectory.Host {

    @Override
    public Path game() {
        return Minecraft.getMinecraft().mcDataDir.toPath();
    }

    @Override
    public Path world() {
        return CgServers.dedicated() ? null : CgServers.directory();
    }
}
