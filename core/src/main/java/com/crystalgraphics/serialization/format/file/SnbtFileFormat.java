package com.crystalgraphics.serialization.format.file;

import com.crystalgraphics.serialization.CgDynamicOps;
import com.crystalgraphics.serialization.format.CgFiles;
import com.crystalgraphics.serialization.nbt.CgNbt;
import com.crystalgraphics.serialization.nbt.CgNbtOps;
import com.crystalgraphics.serialization.nbt.CgNbtTag;

/**
 * {@link CgFiles#SNBT}: Minecraft's text form of NBT ({@code /data get}, a structure's {@code .snbt}). Every tag keeps its
 * exact type ({@code 1b}, {@code 5L}, {@code [I;1,2]}), and a person can read and edit the file.
 */
public final class SnbtFileFormat extends TextFileFormat<CgNbtTag> {

    @Override
    CgDynamicOps<CgNbtTag> ops() {
        return CgNbtOps.INSTANCE;
    }

    @Override
    String print(CgNbtTag tree) {
        return tree.toString();
    }

    @Override
    CgNbtTag parse(String text) {
        return CgNbt.parse(text);
    }
}
