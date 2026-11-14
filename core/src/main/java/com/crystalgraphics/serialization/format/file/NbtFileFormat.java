package com.crystalgraphics.serialization.format.file;

import com.crystalgraphics.serialization.CgCodecException;
import com.crystalgraphics.serialization.CgDynamicOps;
import com.crystalgraphics.serialization.format.CgFiles;
import com.crystalgraphics.serialization.nbt.CgNbt;
import com.crystalgraphics.serialization.nbt.CgNbtCompound;
import com.crystalgraphics.serialization.nbt.CgNbtOps;
import com.crystalgraphics.serialization.nbt.CgNbtTag;

import java.io.IOException;

/**
 * {@link CgFiles#NBT}: Minecraft's own binary NBT, gzipped, the format of {@code level.dat} and structure files. Every
 * tag keeps its exact type. A codec's value must encode to a compound (a record, an object, a map), as the format's
 * root always is.
 */
public final class NbtFileFormat extends FileFormat<CgNbtTag> {

    @Override
    CgDynamicOps<CgNbtTag> ops() {
        return CgNbtOps.INSTANCE;
    }

    @Override
    byte[] toBytes(CgNbtTag tree) throws IOException {
        if (!(tree instanceof CgNbtCompound compound)) {
            throw new CgCodecException("an NBT file's root is a compound; this value is a " + tree.type() + " tag");
        }
        return CgNbt.toBytes(compound);
    }

    @Override
    CgNbtTag fromBytes(byte[] bytes) throws IOException {
        return CgNbt.fromBytes(bytes);
    }
}
