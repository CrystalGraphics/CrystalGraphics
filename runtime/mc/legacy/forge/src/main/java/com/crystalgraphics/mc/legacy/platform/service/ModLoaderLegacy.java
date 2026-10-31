package com.crystalgraphics.mc.legacy.platform.service;

import com.crystalgraphics.platform.service.CgModLoader;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;
import net.minecraft.launchwrapper.Launch;
import net.minecraftforge.common.ForgeVersion;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Legacy Forge's mods, each by its source: a jar the engine opens, or a directory. Both sides. */
public final class ModLoaderLegacy implements CgModLoader.Host {

    @Override
    public String name() {
        return "forge";
    }

    @Override
    public String version() {
        return ForgeVersion.getVersion();
    }

    @Override
    public boolean development() {
        return Boolean.TRUE.equals(Launch.blackboard.get("fml.deobfuscatedEnvironment"));
    }

    @Override
    public List<CgModLoader.Mod> mods() {
        List<CgModLoader.Mod> mods = new ArrayList<>();
        for (ModContainer mod : Loader.instance().getModList()) {
            File source = mod.getSource();
            mods.add(new CgModLoader.Mod(mod.getModId(), String.valueOf(mod.getVersion()), mod.getName(),
                    source == null ? Collections.emptyList() : Collections.singletonList(source.toPath())));
        }
        return mods;
    }
}
