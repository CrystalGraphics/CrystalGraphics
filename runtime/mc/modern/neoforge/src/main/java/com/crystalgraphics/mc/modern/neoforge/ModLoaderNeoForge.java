package com.crystalgraphics.mc.modern.neoforge;

import com.crystalgraphics.platform.service.CgModLoader;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.loading.moddiscovery.ModFileInfo;
//? if >=1.20.4 {
import net.neoforged.neoforgespi.language.IModInfo;
//?}

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * NeoForge's mods: each by the file system its mod file holds open, or from 1.21.9, where a mod file names no path
 * inside itself, by its jar, which the engine opens. On 1.20.2 and 1.20.3 no mod file type is on the classpath (each
 * implements forgespi's, absent): mods are their ids alone, the mods folder's jars the roots. Both sides.
 */
final class ModLoaderNeoForge implements CgModLoader.Host {

    static final ModLoaderNeoForge INSTANCE = new ModLoaderNeoForge();

    private ModLoaderNeoForge() {
    }

    @Override
    public String name() {
        return "neoforge";
    }

    @Override
    public String version() {
        for (CgModLoader.Mod m : mods()) if (m.id().equals("neoforge")) return m.version();
        return "";
    }

    @Override
    public boolean development() {
        //? if >=1.21.9 {
        /*return !FMLEnvironment.isProduction();
        *///?} else {
        return !FMLEnvironment.production;
        //?}
    }

    @Override
    public List<CgModLoader.Mod> mods() {
        List<CgModLoader.Mod> mods = new ArrayList<>();
        //? if <1.20.4 {
        /*ModList.get().forEachModContainer((id, container) -> mods.add(new CgModLoader.Mod(id, "", id, Collections.emptyList())));
        *///?} else {
        for (Object o : ModList.get().getModFiles()) {
            ModFileInfo file = (ModFileInfo) o;
            List<Path> roots = root(file);
            for (Object m : file.getMods()) {
                IModInfo info = (IModInfo) m;
                mods.add(new CgModLoader.Mod(info.getModId(), String.valueOf(info.getVersion()), info.getDisplayName(), roots));
            }
        }
        //?}
        return mods;
    }

    @Override
    public List<Path> roots() {
        List<Path> roots = new ArrayList<>();
        //? if <1.20.4 {
        /*try (DirectoryStream<Path> jars = Files.newDirectoryStream(FMLPaths.MODSDIR.get(), "*.jar")) {
            for (Path jar : jars) roots.add(jar);
        } catch (IOException e) {
            throw new UncheckedIOException("listing " + FMLPaths.MODSDIR.get(), e);
        }
        *///?} else {
        for (Object o : ModList.get().getModFiles()) roots.addAll(root((ModFileInfo) o));
        //?}
        return roots;
    }

    //? if >=1.20.4 {
    private static List<Path> root(ModFileInfo file) {
        //? if >=1.21.9 {
        /*return Collections.singletonList(file.getFile().getFilePath());
        *///?} else {
        Path assets = file.getFile().findResource("assets");
        return assets == null || assets.getParent() == null ? Collections.emptyList() : Collections.singletonList(assets.getParent());
        //?}
    }
    //?}
}
