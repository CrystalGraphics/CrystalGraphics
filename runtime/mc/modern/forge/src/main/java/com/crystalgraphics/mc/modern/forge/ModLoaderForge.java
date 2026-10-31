package com.crystalgraphics.mc.modern.forge;

import com.crystalgraphics.platform.service.CgModLoader;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.moddiscovery.ModFileInfo;
import net.minecraftforge.forgespi.language.IModInfo;
import net.minecraftforge.versions.forge.ForgeVersion;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Forge's mods, each by its mod file's open file system. Both sides. */
final class ModLoaderForge implements CgModLoader.Host {

    static final ModLoaderForge INSTANCE = new ModLoaderForge();

    private ModLoaderForge() {
    }

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
        //? if >=1.15.2 {
        return !FMLLoader.isProduction();
        //?} else {
        /*return !"srg".equals(FMLLoader.getNaming());
        *///?}
    }

    @Override
    public List<CgModLoader.Mod> mods() {
        List<CgModLoader.Mod> mods = new ArrayList<>();
        for (Object o : files()) {
            ModFileInfo file = (ModFileInfo) o;
            List<Path> roots = root(file);
            for (Object m : file.getMods()) {
                IModInfo info = (IModInfo) m;
                mods.add(new CgModLoader.Mod(info.getModId(), String.valueOf(info.getVersion()), info.getDisplayName(), roots));
            }
        }
        return mods;
    }

    @Override
    public List<Path> roots() {
        List<Path> roots = new ArrayList<>();
        for (Object o : files()) roots.addAll(root((ModFileInfo) o));
        return roots;
    }

    private static List<?> files() {
        //? if >=26.1 {
        /*return ModList.getModFiles();
        *///?} else {
        return ModList.get().getModFiles();
        //?}
    }

    /** Where the file's {@code assets/} sits, inside the file system its mod file holds open. */
    private static List<Path> root(ModFileInfo file) {
        Path assets = file.getFile().findResource("assets");
        return assets == null || assets.getParent() == null ? Collections.emptyList() : Collections.singletonList(assets.getParent());
    }
}
