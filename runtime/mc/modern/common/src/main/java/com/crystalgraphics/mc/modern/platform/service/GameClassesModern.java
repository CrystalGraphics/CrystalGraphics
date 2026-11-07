package com.crystalgraphics.mc.modern.platform.service;

import com.crystalgraphics.platform.service.CgGameClasses;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

//? if >=1.14 {
import net.minecraft.SharedConstants;
//?}

/**
 * {@link CgGameClasses} on 1.13-26.3. A class's bytes are read off the loader that runs ours, which on every modern
 * loader is the game's: Fabric's Knot remaps the jar before it is read, and Forge and NeoForge keep official class
 * names. No class is renamed between jar and runtime.
 */
public final class GameClassesModern implements CgGameClasses.Host {

    @Override
    public String minecraftVersion() {
        // 1.21.6 renamed WorldVersion's accessors; 1.13 has none, and its node runs 1.13.2 alone.
        //? if >=1.21.6 {
        /*return SharedConstants.getCurrentVersion().name();
        *///?} elif >=1.14 {
        return SharedConstants.getCurrentVersion().getName();
        //?} else {
        /*return "1.13.2";
        *///?}
    }

    @Override
    public byte[] bytes(String internalName) throws IOException {
        if (internalName == null || isPlatform(internalName)) return null;
        ClassLoader loader = GameClassesModern.class.getClassLoader();
        if (loader == null) return null;
        try (InputStream stream = loader.getResourceAsStream(internalName + ".class")) {
            if (stream == null) return null;
            ByteArrayOutputStream collected = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int read = stream.read(buffer); read > 0; read = stream.read(buffer)) collected.write(buffer, 0, read);
            return collected.toByteArray();
        }
    }

    // The JDK's own classes are not the game's: a module image answers them, never a jar
    private static boolean isPlatform(String internalName) {
        return internalName.startsWith("java/") || internalName.startsWith("javax/")
                || internalName.startsWith("jdk/") || internalName.startsWith("sun/");
    }
}
