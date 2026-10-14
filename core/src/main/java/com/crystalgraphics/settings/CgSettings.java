package com.crystalgraphics.settings;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgGameDirectory;
import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecException;
import com.crystalgraphics.serialization.CgCodecs;
import com.crystalgraphics.serialization.CgDynamicOps;
import com.crystalgraphics.serialization.CgPlainOps;
import com.crystalgraphics.serialization.format.CgFormats;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A file of {@link CgSetting}s a player can change: {@code config/<name>.toml} under the game directory, each setting
 * under its {@code [section]} with its description above it, read when a setting is first asked for and again whenever
 * the file changes on disk.
 *
 * <pre>{@code
 * public final class MySettings {
 *     static final CgSettings FILE = CgSettings.file("mymod");
 *     public static final CgSetting.Number DENSITY = FILE.number("vfx", "density", "Particle density",
 *             "Share of each effect's particles to spawn.", 1f, 0f, 1f, 0.05f);
 *     public static final CgSetting.Toggle TRAILS = FILE.toggle("vfx", "trails", "Trails",
 *             "Projectiles leave a trail.", true);
 *     public static final CgSetting.Choice<Quality> QUALITY = FILE.choice("vfx", "quality", "Quality",
 *             "How much each effect draws.", Quality.HIGH);
 * }
 * }</pre>
 *
 * <ul>
 *   <li>Declare a file's settings together, in one class, as static finals: the file is written in declaration order,
 *       and a setting declared after the first read still takes its value from the file.</li>
 *   <li>Render thread. {@code CgGraphicsLifecycle.tickFrame} saves a changed file and looks for edits on disk once a
 *       second; nothing here runs mid-frame but the first read.</li>
 *   <li>A missing file is written with every default; an unreadable value keeps its default and is rewritten. Keys this
 *       version does not know are kept as written, and so are a player's comments.</li>
 *   <li>A file that is not TOML is kept as {@code <name>.toml.bak} and written again from the values in force, as Forge
 *       does with a broken config.</li>
 * </ul>
 */
public final class CgSettings {

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");
    private static final long POLL_NANOS = 1_000_000_000L;
    private static final List<CgSettings> FILES = new CopyOnWriteArrayList<>();
    private static final String UNKNOWN = "Not used by this version, kept as written.";

    private final String name;
    private final List<CgSetting> settings = new ArrayList<>();
    private final CgCodec<CgSettings> codec;
    /** What the file holds and nothing declares, by section ({@code ""} its top) and key, as plain values. */
    private final Map<String, Map<String, Object>> unknown = new LinkedHashMap<>();
    private Path path;
    private volatile boolean loaded;
    private boolean dirty, warned;
    private long lastPoll;
    private FileTime seen;

    private CgSettings(String name) {
        this.name = name;
        this.codec = new FileCodec().comment(name + " settings: edited here or in the settings screen, read while the"
                + " game runs.\nA value that cannot be read keeps its default and is rewritten.");
    }

    /** The settings file {@code config/<name>.toml}. One per name; ask once and keep it. */
    public static synchronized CgSettings file(String name) {
        for (CgSettings file : FILES) {
            if (file.name.equals(name)) throw new IllegalStateException("settings file " + name + " already exists");
        }
        CgSettings file = new CgSettings(name);
        FILES.add(file);
        return file;
    }

    public CgSetting.Number number(String section, String key, String label, String description,
                                   float fallback, float min, float max, float step) {
        return add(new CgSetting.Number(this, section, key, label, description, fallback, min, max, step));
    }

    public CgSetting.Toggle toggle(String section, String key, String label, String description, boolean fallback) {
        return add(new CgSetting.Toggle(this, section, key, label, description, fallback));
    }

    public <E extends Enum<E>> CgSetting.Choice<E> choice(String section, String key, String label, String description,
                                                          E fallback) {
        return add(new CgSetting.Choice<>(this, section, key, label, description, fallback));
    }

    /** Every setting, in declaration order: what a screen lists. */
    public List<CgSetting> settings() {
        return Collections.unmodifiableList(settings);
    }

    public String name() {
        return name;
    }

    /** Where it is kept. */
    public synchronized Path path() {
        if (path == null) {
            path = CgPlatform.get(CgGameDirectory.SERVICE).get().toAbsolutePath().resolve("config").resolve(name + ".toml");
        }
        return path;
    }

    /** Every file's frame step: saves what changed, and once a second reloads what was edited on disk. */
    public static void tickFrame() {
        long now = System.nanoTime();
        for (CgSettings file : FILES) file.poll(now);
    }

    /** Keeps it at {@code path} instead of the game directory: tests. */
    synchronized CgSettings at(Path path) {
        this.path = path;
        this.loaded = false;
        return this;
    }

    private synchronized <S extends CgSetting> S add(S setting) {
        for (CgSetting s : settings) {
            if (s.id().equals(setting.id())) throw new IllegalArgumentException("setting " + setting.id() + " already exists");
        }
        settings.add(setting);
        Map<String, Object> section = unknown.get(setting.section());
        Object raw = section == null ? null : section.remove(setting.key());
        if (raw != null) readInto(setting, CgPlainOps.INSTANCE, raw);
        return setting;
    }

    void ensureLoaded() {
        if (!loaded) load();
    }

    void changed() {
        dirty = true;
    }

    synchronized void poll(long now) {
        if (!loaded) return;
        if (dirty) {
            save();
            return;
        }
        if (now - lastPoll < POLL_NANOS) return;
        lastPoll = now;
        FileTime time = modified();
        if (time != null && !time.equals(seen)) {
            loaded = false;
            load();
        }
    }

    private synchronized void load() {
        if (loaded) return;
        loaded = true;
        Path file = path();
        if (!Files.isRegularFile(file)) {
            dirty = true;
            return;
        }
        try {
            CgFormats.TOML.read(file, codec, this);
        } catch (CgCodecException notToml) {
            keepBroken(file, notToml);
        } catch (IOException e) {
            warnOnce("could not read " + file, e);
        }
        seen = modified();
    }

    /** A file that will not parse is moved aside, the player's text whole, and written again from the values in force. */
    private void keepBroken(Path file, CgCodecException why) {
        Path kept = file.resolveSibling(file.getFileName() + ".bak");
        try {
            Files.move(file, kept, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.warn("[settings] {} could not be read ({}); kept as {} and written again", file, why.getMessage(),
                    kept.getFileName());
            dirty = true;
        } catch (IOException e) {
            warnOnce("could not move " + file + " aside", e);
        }
    }

    private <T> void readInto(CgSetting setting, CgDynamicOps<T> ops, T raw) {
        if (!setting.decode(ops, raw)) {
            LOGGER.warn("[settings] {} = {} in {} is not a value it takes; keeping {}", setting.id(), raw, name,
                    setting.savedText());
            dirty = true;
        }
    }

    private void save() {
        dirty = false;
        Path file = path();
        try {
            CgFormats.TOML.write(file, codec, this);
            seen = modified();
        } catch (IOException e) {
            warnOnce("could not write " + file, e);
        }
    }

    /** Every section a setting is declared in, in declaration order. */
    private List<String> sections() {
        List<String> out = new ArrayList<>();
        for (CgSetting setting : settings) {
            if (!out.contains(setting.section())) out.add(setting.section());
        }
        return out;
    }

    private FileTime modified() {
        try {
            return Files.getLastModifiedTime(path());
        } catch (IOException e) {
            return null;
        }
    }

    private void warnOnce(String what, IOException e) {
        if (warned) return;
        warned = true;
        LOGGER.warn("[settings] {}: {}", what, e.toString());
    }

    /** The file: a table per section and a key per setting, with what nothing declares kept beside them. */
    private final class FileCodec implements CgCodec<CgSettings> {

        @Override
        public <T> T encode(CgDynamicOps<T> ops, CgSettings file) {
            CgCodecs.MapCodecBuilder<T> top = CgCodecs.map(ops);
            for (String section : sections()) {
                CgCodecs.MapCodecBuilder<T> keys = section.isEmpty() ? top : CgCodecs.map(ops);
                for (CgSetting setting : settings) {
                    if (!setting.section().equals(section)) continue;
                    keys.comment(setting.label() + ": " + setting.description() + "\n" + setting.hint())
                            .raw(setting.key(), setting.encode(ops));
                }
                kept(ops, unknown.get(section), keys);
                if (!section.isEmpty()) top.raw(section, keys.build());
            }
            kept(ops, unknown.get(""), top);
            return top.build();
        }

        /** What the file held and nothing declares, back as it was read. */
        private <T> void kept(CgDynamicOps<T> ops, @Nullable Map<String, Object> values, CgCodecs.MapCodecBuilder<T> into) {
            if (values == null) return;
            for (Map.Entry<String, Object> e : values.entrySet()) into.comment(UNKNOWN).raw(e.getKey(), plain(ops, e.getValue()));
        }

        /** A plain value, as TOML reads one, in {@code ops}. */
        private <T> T plain(CgDynamicOps<T> ops, Object value) {
            if (value instanceof Map) {
                Map<T, T> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                    out.put(ops.createString(String.valueOf(e.getKey())), plain(ops, e.getValue()));
                }
                return ops.createMap(out);
            }
            if (value instanceof List) {
                List<T> out = new ArrayList<>();
                for (Object element : (List<?>) value) out.add(plain(ops, element));
                return ops.createList(out);
            }
            if (value instanceof String) return ops.createString((String) value);
            if (value instanceof Boolean) return ops.createBoolean((Boolean) value);
            if (value instanceof Number) return ops.createNumber((Number) value);
            if (value instanceof byte[]) return ops.createBytes((byte[]) value);
            throw new CgCodecException("not a value a settings file holds: " + value);
        }

        @Override
        public <T> CgSettings decode(CgDynamicOps<T> ops, T input) {
            throw new UnsupportedOperationException("a settings file is decoded into itself");
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> CgSettings decode(CgDynamicOps<T> ops, T input, @Nullable CgSettings target) {
            Map<String, T> top = byName(ops, input);
            Map<String, Map<String, T>> sections = new LinkedHashMap<>();
            for (String section : sections()) {
                if (section.isEmpty()) continue;
                T table = top.remove(section);
                if (table == null) continue;
                try {
                    sections.put(section, byName(ops, table));
                } catch (CgCodecException notATable) {
                    LOGGER.warn("[settings] [{}] in {} is not a table; its settings keep their values", section, name);
                }
            }
            for (CgSetting setting : settings) {
                Map<String, T> in = setting.section().isEmpty() ? top : sections.get(setting.section());
                T raw = in == null ? null : in.remove(setting.key());
                if (raw != null) readInto(setting, ops, raw);
                else dirty = true;   // a key the file lacks is written back in
            }
            // The file is TOML: its values are plain Java ones.
            unknown.clear();
            sections.put("", top);
            for (Map.Entry<String, Map<String, T>> e : sections.entrySet()) {
                if (!e.getValue().isEmpty()) unknown.put(e.getKey(), new LinkedHashMap<>((Map<String, Object>) e.getValue()));
            }
            return CgSettings.this;
        }

        private <T> Map<String, T> byName(CgDynamicOps<T> ops, T table) {
            Map<String, T> out = new LinkedHashMap<>();
            for (Map.Entry<T, T> e : ops.getMapValue(table).entrySet()) out.put(ops.getStringValue(e.getKey()), e.getValue());
            return out;
        }
    }
}
