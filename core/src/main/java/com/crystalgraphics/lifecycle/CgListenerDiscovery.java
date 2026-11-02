package com.crystalgraphics.lifecycle;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.crystalgraphics.platform.service.CgModLoader;

/**
 * The lifecycle listeners every mod declares in {@value #FILE}, found in each mod's root ({@link CgModLoader#roots()})
 * and on the engine's classpath, as Fabric finds a mod's entrypoints in its {@code fabric.mod.json}.
 *
 * <ul>
 *   <li>Classes load through the engine's own classloader, then the thread's where that one cannot see them (a NeoForge
 *       dev run's engine is a layer below the mods). The file is never found through {@code ServiceLoader}.</li>
 *   <li>A class declared twice (a dev run sees its root and its classpath) is one listener.</li>
 *   <li>A line that does not parse, or a class that does not load, is logged and skipped; the rest still register.</li>
 * </ul>
 */
final class CgListenerDiscovery {

    static final String FILE = "META-INF/crystalgraphics/listeners";

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");

    enum Side { COMMON, CLIENT }

    /** One declared listener: its side, its class, and the file that named it. */
    record Entry(Side side, String className, String from) {
    }

    private CgListenerDiscovery() {
    }

    /** Every declaration, mods' roots first, each class once. */
    static List<Entry> find() {
        Map<String, Entry> byClass = new LinkedHashMap<>();
        for (Path root : CgModLoader.roots()) {
            String text = read(root);
            if (text != null) addAll(byClass, parse(text, describe(root)));
        }
        try {
            Enumeration<URL> urls = CgListenerDiscovery.class.getClassLoader().getResources(FILE);
            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                try (InputStream in = url.openStream()) {
                    addAll(byClass, parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), url.toString()));
                }
            }
        } catch (IOException e) {
            LOGGER.warn("[crystalgraphics] listeners: the classpath's {} could not be read", FILE, e);
        }
        return Collections.unmodifiableList(new ArrayList<>(byClass.values()));
    }

    private static void addAll(Map<String, Entry> byClass, List<Entry> entries) {
        for (Entry e : entries) byClass.putIfAbsent(e.className(), e);
    }

    /**
     * A listeners file: one {@code <side> <class>} a line, side {@code common} or {@code client}; blank lines and
     * {@code #} comments ignored.
     */
    static List<Entry> parse(String text, String from) {
        List<Entry> out = new ArrayList<>();
        for (String raw : text.split("\n")) {
            int hash = raw.indexOf('#');
            String line = (hash < 0 ? raw : raw.substring(0, hash)).trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split("\\s+");
            Side side = parts.length == 2 ? side(parts[0]) : null;
            if (side == null) {
                LOGGER.warn("[crystalgraphics] listeners: '{}' in {} is not '<common|client> <class>'", line, from);
                continue;
            }
            out.add(new Entry(side, parts[1], from));
        }
        return out;
    }

    private static Side side(String word) {
        return switch (word) {
            case "common" -> Side.COMMON;
            case "client" -> Side.CLIENT;
            default -> null;
        };
    }

    /**
     * {@code entry}'s listener: the class's {@code public static final INSTANCE} when it has one of a listener type,
     * else a new one from its no-argument constructor. Null, logged, when it does not load or is no listener.
     */
    static CgLifecycleListener instantiate(Entry entry) {
        try {
            Class<?> type = load(entry.className());
            if (!CgLifecycleListener.class.isAssignableFrom(type)) {
                LOGGER.warn("[crystalgraphics] listeners: {} (from {}) is not a CgLifecycleListener", entry.className(),
                        entry.from());
                return null;
            }
            Field instance = instanceField(type);
            if (instance != null) return (CgLifecycleListener) instance.get(null);
            return (CgLifecycleListener) type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            LOGGER.error("[crystalgraphics] listeners: {} (from {}) could not be made", entry.className(), entry.from(), e);
            return null;
        }
    }

    /**
     * The engine's own classloader first, then the thread's: a NeoForge dev run puts the engine in a layer below the
     * game's ({@code MC-BOOTSTRAP}), which cannot see the mods, while mod loading runs with the game's loader there.
     */
    private static Class<?> load(String name) throws ClassNotFoundException {
        ClassLoader own = CgListenerDiscovery.class.getClassLoader();
        try {
            return Class.forName(name, true, own);
        } catch (ClassNotFoundException missing) {
            ClassLoader thread = Thread.currentThread().getContextClassLoader();
            if (thread == null || thread == own) throw missing;
            return Class.forName(name, true, thread);
        }
    }

    private static Field instanceField(Class<?> type) {
        try {
            Field f = type.getField("INSTANCE");
            int m = f.getModifiers();
            boolean usable = Modifier.isStatic(m) && Modifier.isFinal(m) && CgLifecycleListener.class.isAssignableFrom(f.getType());
            return usable ? f : null;
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    /** Where {@code root} is, for a log: a mod jar's union filesystem prints its root as an empty string. */
    private static String describe(Path root) {
        String text = root.toString();
        if (!text.isEmpty() && !text.equals("/")) return text;
        try {
            return root.toUri().toString();
        } catch (RuntimeException e) {
            return root.getFileSystem().toString();
        }
    }

    /** The listeners file under {@code root} (a directory, a path in an open jar, or a jar file), or null. */
    private static String read(Path root) {
        try {
            if (Files.isRegularFile(root)) {
                try (ZipFile jar = new ZipFile(root.toFile())) {
                    ZipEntry e = jar.getEntry(FILE);
                    if (e == null) return null;
                    try (InputStream in = jar.getInputStream(e)) {
                        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    }
                }
            }
            Path file = root.resolve(FILE);
            return Files.isRegularFile(file) ? new String(Files.readAllBytes(file), StandardCharsets.UTF_8) : null;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[crystalgraphics] listeners: {} under {} could not be read", FILE, root, e);
            return null;
        }
    }
}
