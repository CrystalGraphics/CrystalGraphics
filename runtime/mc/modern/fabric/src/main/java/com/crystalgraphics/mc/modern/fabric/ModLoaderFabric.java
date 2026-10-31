package com.crystalgraphics.mc.modern.fabric;

import com.crystalgraphics.platform.service.CgModLoader;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;

import java.util.ArrayList;
import java.util.List;

/** Fabric's mods, each by the roots the loader holds open inside its jar. Both sides. */
final class ModLoaderFabric implements CgModLoader.Host {

    static final ModLoaderFabric INSTANCE = new ModLoaderFabric();

    private ModLoaderFabric() {
    }

    @Override
    public String name() {
        return "fabric";
    }

    @Override
    public String version() {
        return FabricLoader.getInstance().getModContainer("fabricloader")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("");
    }

    @Override
    public boolean development() {
        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }

    @Override
    public List<CgModLoader.Mod> mods() {
        List<CgModLoader.Mod> mods = new ArrayList<>();
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            ModMetadata meta = mod.getMetadata();
            mods.add(new CgModLoader.Mod(meta.getId(), meta.getVersion().getFriendlyString(), meta.getName(),
                    new ArrayList<>(mod.getRootPaths())));
        }
        return mods;
    }
}
