package com.crystalgraphics.serialization.format;

import com.crystalgraphics.serialization.CgCodec;

import com.crystalgraphics.platform.io.CgAtomicFile;
import com.crystalgraphics.serialization.format.file.BinaryFileFormat;
import com.crystalgraphics.serialization.format.file.JsonFileFormat;
import com.crystalgraphics.serialization.format.file.JsoncFileFormat;
import com.crystalgraphics.serialization.format.file.NbtFileFormat;
import com.crystalgraphics.serialization.format.file.SnbtFileFormat;
import com.crystalgraphics.serialization.format.file.TomlFileFormat;
import com.crystalgraphics.serialization.format.file.YamlFileFormat;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Where a codec's value lives when it is not on the wire: a file of the mod's own, in CrystalGraphics' binary format,
 * JSON, TOML, YAML, or NBT itself. A mod saves with the codecs it sends with; where Minecraft owns the container (an entity's, an item's
 * saved data) {@link com.crystalgraphics.serialization.nbt.CgNbt} carries the same codecs into its tags.
 *
 * <pre>{@code
 * CgFiles.write(file, PlayerData.CODEC, data);                  // binary, compressed
 * PlayerData data = CgFiles.read(file, PlayerData.CODEC);       // null when there is no file yet
 * CgFiles.read(file, PlayerData.CODEC, live);                   // into a live object
 *
 * CgFiles.TOML.write(config, Settings.CODEC, settings);         // a config file people edit
 * Settings s = CgFiles.of(config).read(config, Settings.CODEC); // the format from the extension
 * }</pre>
 *
 * <ul>
 *   <li>A file is written whole beside itself then moved over the old one: a crash mid-save leaves the last save.</li>
 *   <li>Every format takes any codec. JSON, TOML and YAML take only text map keys; TOML's top is a map.</li>
 * </ul>
 */
public final class CgFiles {

    /** CrystalGraphics' binary format, gzipped: the wire's own, smallest and fastest. */
    public static final CgFileFormat BINARY = new BinaryFileFormat();

    /** JSON, pretty-printed: Gson, which every Minecraft ships. */
    public static final CgTextFormat JSON = new LazyTextFormat("JSON", "Gson", () -> new JsonFileFormat());

    /** YAML in block style: SnakeYAML, carried by CrystalGraphics. */
    public static final CgTextFormat YAML = new LazyTextFormat("YAML", "SnakeYAML", () -> new YamlFileFormat());

    /** TOML 1.0, Forge's and NeoForge's config format: tables of keys, a value's text says its type. */
    public static final CgTextFormat TOML = new TomlFileFormat();

    /**
     * JSON with comments, as VS Code's {@code settings.json}: {@code //} comments kept like TOML's, a trailing comma
     * allowed. For a file people edit that is JSON in shape; {@link #JSON} stays strict, for files a tool reads.
     */
    public static final CgTextFormat JSONC = new JsoncFileFormat();

    /**
     * Minecraft's own binary NBT, gzipped: {@code level.dat}, structure files. Every tag keeps its exact type, so it is the
     * format to read and write tags in ({@code CgFiles.NBT.read(file, CgNbtCompound.CODEC)}). A value must encode to a compound.
     */
    public static final CgFileFormat NBT = new NbtFileFormat();

    /** NBT as Minecraft's text, SNBT: exact types ({@code 1b}, {@code 5L}), readable and editable. {@code .snbt}. */
    public static final CgTextFormat SNBT = new SnbtFileFormat();

    private CgFiles() {
    }

    /**
     * The format a file's extension names: {@code .json}, {@code .jsonc}, {@code .yml}, {@code .yaml}, {@code .toml},
     * {@code .nbt} or {@code .snbt}, anything else binary.
     */
    public static CgFileFormat of(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".json")) return JSON;
        if (name.endsWith(".jsonc")) return JSONC;
        if (name.endsWith(".yml") || name.endsWith(".yaml")) return YAML;
        if (name.endsWith(".toml")) return TOML;
        if (name.endsWith(".nbt")) return NBT;
        if (name.endsWith(".snbt")) return SNBT;
        return BINARY;
    }

    /**
     * Replaces {@code file} with {@code bytes}, making its folders: written beside it and moved over it, so a crash
     * leaves the last contents. Every format's {@code write} and every config store goes through it.
     */
    public static void writeAtomic(Path file, byte[] bytes) throws IOException {
        CgAtomicFile.write(file, bytes);
    }

    /** {@link #BINARY}'s {@link CgFileFormat#write}. */
    public static <V> void write(Path file, CgCodec<V> codec, V value) throws IOException {
        BINARY.write(file, codec, value);
    }

    /** {@link #BINARY}'s {@link CgFileFormat#read}: null when there is no file. */
    @Nullable
    public static <V> V read(Path file, CgCodec<V> codec) throws IOException {
        return BINARY.read(file, codec);
    }

    /** {@link #BINARY}'s {@link CgFileFormat#read(Path, CgCodec, Object)}: into {@code target}. */
    public static <V> V read(Path file, CgCodec<V> codec, V target) throws IOException {
        return BINARY.read(file, codec, target);
    }
}
