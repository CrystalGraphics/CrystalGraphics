package com.crystalgraphics.mc.v1710.platform.service;

import com.crystalgraphics.platform.service.CgGameClasses;

import net.minecraft.launchwrapper.IClassNameTransformer;
import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;

import java.util.List;

/**
 * {@link CgGameClasses} on 1.7.10: LaunchWrapper's bytes after its whole transformer chain, mixins included, and
 * its renamer's names. Production jars store Notch names and remap as they load, so the bytes are read under the
 * untransformed name, as {@code LaunchClassLoader.findClass} does.
 */
public final class GameClasses1710 implements CgGameClasses.Host {

    private volatile IClassNameTransformer renamer;

    @Override
    public String minecraftVersion() {
        return "1.7.10";
    }

    @Override
    public byte[] bytes(String internalName) {
        LaunchClassLoader loader = Launch.classLoader;
        if (loader == null || internalName == null) return null;
        String binaryName = internalName.replace('/', '.');
        List<IClassTransformer> transformers = loader.getTransformers();
        // Looked up per call: a mod may still be registering transformers
        IClassNameTransformer names = renamerIn(transformers);
        String untransformed = names == null ? null : names.unmapClassName(binaryName);
        String transformed = names == null ? null : names.remapClassName(binaryName);
        if (untransformed == null) untransformed = binaryName;
        if (transformed == null) transformed = binaryName;

        byte[] bytes;
        try {
            bytes = loader.getClassBytes(untransformed);
        } catch (Exception unreadable) {
            return null;
        }
        if (bytes == null || transformers == null) return bytes;
        for (IClassTransformer transformer : transformers) {
            try {
                byte[] next = transformer.transform(untransformed, transformed, bytes);
                if (next == null) return null;   // removed: the runtime has no such class
                bytes = next;
            } catch (Throwable refused) {
                // One mod's transformer failing on a class it did not expect leaves the class as it stands
            }
        }
        return bytes;
    }

    @Override
    public byte[] fileBytes(String internalName) {
        LaunchClassLoader loader = Launch.classLoader;
        if (loader == null || internalName == null) return null;
        String binaryName = internalName.replace('/', '.');
        IClassNameTransformer names = renamerIn(loader.getTransformers());
        String untransformed = names == null ? null : names.unmapClassName(binaryName);
        try {
            return loader.getClassBytes(untransformed == null ? binaryName : untransformed);
        } catch (Exception unreadable) {
            return null;
        }
    }

    @Override
    public String runtimeName(String onDiskName) {
        LaunchClassLoader loader = Launch.classLoader;
        if (loader == null) return onDiskName;
        IClassNameTransformer names = renamer;
        if (names == null) {
            names = renamerIn(loader.getTransformers());
            if (names == null) return onDiskName;
            renamer = names;
        }
        String renamed = names.remapClassName(onDiskName.replace('/', '.'));
        return renamed == null ? onDiskName : renamed.replace('.', '/');
    }

    // The loader keeps its renamer in a private field and also in the public transformer list
    private static IClassNameTransformer renamerIn(List<IClassTransformer> transformers) {
        if (transformers == null) return null;
        for (IClassTransformer transformer : transformers) {
            if (transformer instanceof IClassNameTransformer) return (IClassNameTransformer) transformer;
        }
        return null;
    }
}
