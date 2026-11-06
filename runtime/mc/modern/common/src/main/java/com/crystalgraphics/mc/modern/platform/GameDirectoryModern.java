package com.crystalgraphics.mc.modern.platform;

import com.crystalgraphics.platform.service.CgGameDirectory;
import com.crystalgraphics.platform.service.CgServers;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;
import java.nio.file.Paths;

/** The game directory on modern Minecraft. Client only. */
final class GameDirectoryModern implements CgGameDirectory.Host {

    /** Minecraft's, or before Minecraft exists (NeoForge constructs mods first) the launcher's working directory. */
    @Override
    public Path game() {
        Minecraft mc = Minecraft.getInstance();
        return mc != null ? mc.gameDirectory.toPath() : Paths.get("").toAbsolutePath();
    }

    @Override
    public Path world() {
        return CgServers.dedicated() ? null : CgServers.directory();
    }
}
