package com.crystalgraphics.settings;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgGameDirectory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
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
 * A file of {@link CgSetting}s a player can change: {@code config/<name>.toml} under the game directory, written in a
 * small subset of TOML ({@code [section]}, {@code key = value}, {@code #} comments) with each setting's description beside
 * it, read when a setting is first asked for and again whenever the file changes on disk.
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
 *       version does not know are kept as written.</li>
 * </ul>
 */
public final class CgSettings {

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");
    private static final long POLL_NANOS = 1_000_000_000L;
    private static final char NL = (char) 10;
    private static final List<CgSettings> FILES = new CopyOnWriteArrayList<>();

    private final String name;
    private final List<CgSetting> settings = new ArrayList<>();
    /** Entries the file holds and nothing declares, by {@code section.key}, as written. */
    private final Map<String, String> unknown = new LinkedHashMap<>();
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
        String raw = unknown.remove(setting.id());
        if (raw != null) readInto(setting, raw);
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
            Map<String, String> entries = parse(Files.readAllLines(file, StandardCharsets.UTF_8));
            seen = modified();
            unknown.clear();
            for (CgSetting setting : settings) {
                String raw = entries.remove(setting.id());
                if (raw != null) readInto(setting, raw);
                else dirty = true;   // a key the file lacks is written back in
            }
            unknown.putAll(entries);
        } catch (IOException e) {
            warnOnce("could not read " + file, e);
        }
    }

    private void readInto(CgSetting setting, String raw) {
        if (!setting.read(unquote(raw))) {
            LOGGER.warn("[settings] {} = {} in {} is not a value it takes; keeping {}", setting.id(), raw, name, setting.write());
            dirty = true;
        }
    }

    private void save() {
        dirty = false;
        Path file = path();
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(temp, render().getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            seen = modified();
        } catch (IOException e) {
            warnOnce("could not write " + file, e);
        }
    }

    /**
     * The whole file as it is written. A key nothing declares stays in its own section, so no table is opened twice:
     * a top-level one before the first table, one in a section this version no longer has after every declared one.
     */
    String render() {
        StringBuilder out = new StringBuilder();
        out.append("# ").append(name).append(" settings: edited here or in the settings screen, read while the game runs.").append(NL);
        out.append("# A value that cannot be read keeps its default and is rewritten.").append(NL);
        List<String> sections = new ArrayList<>();
        for (CgSetting setting : settings) {
            if (!sections.contains(setting.section())) sections.add(setting.section());
        }
        for (String key : unknown.keySet()) {
            String section = sectionOf(key);
            if (!sections.contains(section)) sections.add(section.isEmpty() ? 0 : sections.size(), section);
        }
        for (String section : sections) {
            if (!section.isEmpty()) out.append(NL).append('[').append(section).append(']').append(NL);
            for (CgSetting setting : settings) {
                if (!setting.section().equals(section)) continue;
                out.append("# ").append(setting.label()).append(": ").append(setting.description()).append(NL);
                out.append("# ").append(setting.hint()).append(NL);
                out.append(setting.key()).append(" = ").append(setting.write()).append(NL);
            }
            boolean first = true;
            for (Map.Entry<String, String> entry : unknown.entrySet()) {
                if (!sectionOf(entry.getKey()).equals(section)) continue;
                if (first) out.append("# Not used by this version, kept as written.").append(NL);
                first = false;
                out.append(entry.getKey().substring(section.isEmpty() ? 0 : section.length() + 1))
                        .append(" = ").append(entry.getValue()).append(NL);
            }
        }
        return out.toString();
    }

    private static String sectionOf(String id) {
        int dot = id.indexOf('.');
        return dot < 0 ? "" : id.substring(0, dot);
    }

    /** {@code section.key} to the value as written, quotes and all. */
    static Map<String, String> parse(List<String> lines) {
        Map<String, String> entries = new LinkedHashMap<>();
        String section = "";
        for (String line : lines) {
            String text = stripComment(line).trim();
            if (text.isEmpty()) continue;
            if (text.startsWith("[") && text.endsWith("]")) {
                section = text.substring(1, text.length() - 1).trim();
                continue;
            }
            int eq = text.indexOf('=');
            if (eq <= 0) continue;
            String key = text.substring(0, eq).trim();
            entries.put(section.isEmpty() ? key : section + "." + key, text.substring(eq + 1).trim());
        }
        return entries;
    }

    private static String stripComment(String line) {
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\' && quoted) i++;
            else if (c == '"') quoted = !quoted;
            else if (c == '#' && !quoted) return line.substring(0, i);
        }
        return line;
    }

    private static String unquote(String raw) {
        if (raw.length() < 2 || raw.charAt(0) != '"' || raw.charAt(raw.length() - 1) != '"') return raw;
        StringBuilder s = new StringBuilder();
        for (int i = 1; i < raw.length() - 1; i++) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length() - 1) c = raw.charAt(++i);
            s.append(c);
        }
        return s.toString();
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
}
