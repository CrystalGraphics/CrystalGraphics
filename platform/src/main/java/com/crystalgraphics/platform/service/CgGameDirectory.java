package com.crystalgraphics.platform.service;

import com.crystalgraphics.platform.CgService;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * The host's game directory, where its {@code config/} folder lives: what CrystalGraphics' settings file is kept under.
 *
 * <pre>{@code
 * Path config = CgPlatform.get(CgGameDirectory.SERVICE).get().resolve("config");
 *
 * // a host, once, on a client
 * CgPlatform.provide(CgGameDirectory.SERVICE, () -> Minecraft.getInstance().gameDirectory.toPath());
 * }</pre>
 *
 * <ul>
 *   <li>{@link #NONE} answers the working directory, which every launcher sets to the game directory; the harness and
 *       tests run with no host filling the slot.</li>
 *   <li>Asked when a file is opened, never cached by the host, so it may answer something only built after registration.</li>
 * </ul>
 */
@FunctionalInterface
public interface CgGameDirectory {

    CgGameDirectory NONE = () -> Paths.get("").toAbsolutePath();

    CgService<CgGameDirectory> SERVICE = CgService.of("crystalgraphics:game_directory", NONE);

    Path get();
}
