package com.crystalgraphics.serialization.format;

import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgDynamicOps;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.serialization.format.file.BinaryFileFormat;
import com.crystalgraphics.serialization.format.file.JsonFileFormat;
import com.crystalgraphics.serialization.format.file.JsoncFileFormat;
import com.crystalgraphics.serialization.format.file.TomlFileFormat;
import com.crystalgraphics.serialization.format.file.YamlFileFormat;
import com.crystalgraphics.serialization.format.nbt.CgHostFormats;
import com.crystalgraphics.serialization.format.nbt.HostTagOps;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Where a codec's value lives when it is not on the wire: a file of the mod's own, in CrystalGraphics' binary format,
 * JSON, TOML or YAML, or Minecraft's tags where Minecraft owns the container. A mod saves with the codecs it sends with, and
 * needs no NBT of its own.
 *
 * <pre>{@code
 * CgFormats.write(file, PlayerData.CODEC, data);                  // binary, compressed
 * PlayerData data = CgFormats.read(file, PlayerData.CODEC);       // null when there is no file yet
 * CgFormats.read(file, PlayerData.CODEC, live);                   // into a live object
 *
 * CgFormats.TOML.write(config, Settings.CODEC, settings);         // a config file people edit
 * Settings s = CgFormats.of(config).read(config, Settings.CODEC); // the format from the extension
 * }</pre>
 *
 * Inside an entity's, block entity's or item's saved data, which only takes Minecraft's tags:
 *
 * <pre>{@code
 * tag.put("character", CgFormats.toNbt(Character.CODEC, character));              // saving
 * Character character = CgFormats.fromNbt(Character.CODEC, tag.get("character")); // loading
 * }</pre>
 *
 * <ul>
 *   <li>A file is written whole beside itself then moved over the old one: a crash mid-save leaves the last save.</li>
 *   <li>Every format takes any codec. JSON, TOML, YAML and NBT take only text map keys; TOML's top is a map; NBT's lists hold one kind of value
 *       and its booleans are bytes.</li>
 *   <li>{@link #toNbt}, {@link #fromNbt} and {@link #nbt} need a host: in a test or the harness they throw, naming the
 *       {@link CgHostFormats} slot.</li>
 * </ul>
 */
public final class CgFormats {

    /** CrystalGraphics' binary format, gzipped: the wire's own, smallest and fastest. */
    public static final CgFormat BINARY = new BinaryFileFormat();

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

    private CgFormats() {
    }

    /**
     * The format a file's extension names: {@code .json}, {@code .jsonc}, {@code .yml}, {@code .yaml} or {@code .toml},
     * anything else binary.
     */
    public static CgFormat of(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".json")) return JSON;
        if (name.endsWith(".jsonc")) return JSONC;
        if (name.endsWith(".yml") || name.endsWith(".yaml")) return YAML;
        if (name.endsWith(".toml")) return TOML;
        return BINARY;
    }

    /** {@link #BINARY}'s {@link CgFormat#write}. */
    public static <V> void write(Path file, CgCodec<V> codec, V value) throws IOException {
        BINARY.write(file, codec, value);
    }

    /** {@link #BINARY}'s {@link CgFormat#read}: null when there is no file. */
    @Nullable
    public static <V> V read(Path file, CgCodec<V> codec) throws IOException {
        return BINARY.read(file, codec);
    }

    /** {@link #BINARY}'s {@link CgFormat#read(Path, CgCodec, Object)}: into {@code target}. */
    public static <V> V read(Path file, CgCodec<V> codec, V target) throws IOException {
        return BINARY.read(file, codec, target);
    }

    /**
     * {@code value} as the host's tags: a compound for a map, a string tag for a string, and so on. Typed as the caller
     * takes it ({@code Tag}, {@code CompoundTag}, {@code NBTBase}): asking for a kind it did not make is a
     * {@code ClassCastException} there.
     */
    @SuppressWarnings("unchecked")
    public static <V, N> N toNbt(CgCodec<V> codec, V value) {
        return (N) codec.encode(nbt(), value);
    }

    /** What {@link #toNbt} wrote, back through {@code codec}. */
    public static <V> V fromNbt(CgCodec<V> codec, Object tag) {
        return codec.decode(nbt(), tag);
    }

    /** What {@link #toNbt} wrote, decoded into {@code target}: an entity's live data refreshed from its tag. */
    public static <V> V fromNbt(CgCodec<V> codec, Object tag, V target) {
        return codec.decode(nbt(), tag, target);
    }

    /** The host's tags as a codec format, for a codec read or written by hand. */
    public static CgDynamicOps<Object> nbt() {
        CgHostFormats tags = CgPlatform.get(CgHostFormats.SERVICE);
        if (!tags.present()) {
            throw new IllegalStateException("no host tags here: crystalgraphics:host_formats has none (a test, the"
                    + " harness)");
        }
        return new HostTagOps(tags);
    }
}
