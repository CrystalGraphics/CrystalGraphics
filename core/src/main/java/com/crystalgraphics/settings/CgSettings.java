package com.crystalgraphics.settings;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgGameDirectory;
import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecException;
import com.crystalgraphics.serialization.CgDynamicOps;
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
    private final FileCodec codec = new FileCodec();
    /** What the file holds and nothing declares, by section ({@code ""} its top) and key, as the format read it. */
    private final Map<String, Map<String, Object>> unknown = new LinkedHashMap<>();
    /** The format that read {@link #unknown}: only it can write those values back. */
    @Nullable
    private CgDynamicOps<Object> unknownOps;
    private Path path;
    private volatile boolean loaded;
    private boolean dirty, warned;
    private long lastPoll;
    private FileTime seen;

    private CgSettings(String name) {
        this.name = name;
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
        if (raw != null) readInto(setting, unknownOps, raw);
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

    private static String dotted(String section, String key) {
        return section.isEmpty() ? key : section + "." + key;
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
            Map<T, T> top = new LinkedHashMap<>();
            for (String section : sections()) {
                Map<T, T> keys = section.isEmpty() ? top : new LinkedHashMap<>();
                for (CgSetting setting : settings) {
                    if (setting.section().equals(section)) keys.put(ops.createString(setting.key()), setting.encode(ops));
                }
                kept(ops, unknown.get(section), keys);
                if (!section.isEmpty()) top.put(ops.createString(section), ops.createMap(keys));
            }
            kept(ops, unknown.get(""), top);
            return ops.createMap(top);
        }

        @SuppressWarnings("unchecked")
        private <T> void kept(CgDynamicOps<T> ops, @Nullable Map<String, Object> values, Map<T, T> into) {
            if (values == null || ops != (Object) unknownOps) return;
            for (Map.Entry<String, Object> e : values.entrySet()) {
                T key = ops.createString(e.getKey());
                if (!into.containsKey(key)) into.put(key, (T) e.getValue());
            }
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
            unknown.clear();
            unknownOps = (CgDynamicOps<Object>) ops;
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

        @Override
        public Map<String, String> comments() {
            Map<String, String> out = new LinkedHashMap<>();
            out.put("", name + " settings: edited here or in the settings screen, read while the game runs.\n"
                    + "A value that cannot be read keeps its default and is rewritten.");
            for (CgSetting setting : settings) {
                out.put(dotted(setting.section(), setting.key()),
                        setting.label() + ": " + setting.description() + "\n" + setting.hint());
            }
            for (Map.Entry<String, Map<String, Object>> section : unknown.entrySet()) {
                for (String key : section.getValue().keySet()) out.put(dotted(section.getKey(), key), UNKNOWN);
            }
            return out;
        }
    }
}
