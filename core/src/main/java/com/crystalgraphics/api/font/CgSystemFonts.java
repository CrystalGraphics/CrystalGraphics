package com.crystalgraphics.api.font;

import com.crystalgraphics.text.font.ScriptFallbacks;
import com.crystalgraphics.text.font.Sfnt;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The fonts installed on this machine: find one by family name, resolve CSS's generic families,
 * and supply the per-script fallback an application uses for characters its own fonts cannot draw.
 *
 * <pre>{@code
 * CgSystemFonts fonts = CgSystemFonts.get();
 *
 * // a family by name, with CSS weight and italic matching
 * CgFont segoe = fonts.load(fonts.find("Segoe UI", CgFontStyle.BOLD), CgFontStyle.BOLD, 16);
 *
 * // what this platform means by monospace
 * CgSystemFontFace mono = fonts.generic(CgGenericFamily.MONOSPACE, CgFontStyle.REGULAR);
 *
 * // any text at all: 日本語, 한국어 and العربية drawn from whatever is installed
 * CgFontFamily family = CgFontFamily.of(bundled).withFallback(fonts.fallback(Locale.getDefault()));
 * }</pre>
 *
 * <p>A fallback for one character is chosen the way a browser chooses: the platform's table row
 * for its script, or for a symbol, math or emoji block, with Han drawn in the locale's convention
 * (Japanese, Simplified, Traditional or Korean) — the first installed face there that covers the
 * character; failing that, every installed face that covers it, the platform's broad fonts first.
 * The Windows table is Chromium's; see {@link ScriptFallbacks}.</p>
 *
 * <ul>
 *   <li>{@link #get()} returns at once and scans on a background thread. The first query waits for
 *       the scan — 129 ms for 571 faces in 520 files, measured on Windows 10. It is built once per
 *       process, so a font installed while the game runs is seen after a restart.</li>
 *   <li>{@link #find}, {@link #generic} and {@link #fallbackFor} answer {@code null} when nothing
 *       matches; {@link #load} refuses a {@code null} face.</li>
 *   <li>{@link #load} keeps what it loads and hands the same {@link CgFont} back each time;
 *       never dispose one.</li>
 *   <li>{@link #of} indexes exactly the directories given, synchronously: for tests, or a pack's
 *       own fonts folder.</li>
 * </ul>
 */
public final class CgSystemFonts {

    private static final Logger LOGGER = Logger.getLogger(CgSystemFonts.class.getName());
    private static final int MAX_DEPTH = 8;

    private static volatile CgSystemFonts installed;

    private final CompletableFuture<Index> scan;
    private final ScriptFallbacks.Platform platform;
    private volatile Index index;

    private final Map<LoadKey, CgFont> baseFonts = new ConcurrentHashMap<>();
    private final Map<Long, Optional<CgSystemFontFace>> fallbacks = new ConcurrentHashMap<>();
    private final Map<CgFontStyle, List<CgSystemFontFace>> searchOrders = new ConcurrentHashMap<>();

    private CgSystemFonts(CompletableFuture<Index> scan, ScriptFallbacks.Platform platform) {
        this.scan = scan;
        this.platform = platform;
    }

    /** The installed fonts. The first call starts a background scan and returns without waiting. */
    public static CgSystemFonts get() {
        CgSystemFonts local = installed;
        if (local == null) {
            synchronized (CgSystemFonts.class) {
                local = installed;
                if (local == null) {
                    List<Path> directories = platformDirectories();
                    CompletableFuture<Index> scan = new CompletableFuture<>();
                    Thread thread = new Thread(() -> {
                        try {
                            scan.complete(Index.scan(directories));
                        } catch (Throwable t) {
                            scan.completeExceptionally(t);
                        }
                    }, "CrystalGraphics font scan");
                    thread.setDaemon(true);
                    thread.start();
                    installed = local = new CgSystemFonts(scan, ScriptFallbacks.Platform.current());
                }
            }
        }
        return local;
    }

    /**
     * Only the fonts in {@code directories} and their subdirectories, scanned before this returns.
     *
     * <pre>{@code
     * CgSystemFonts pack = CgSystemFonts.of(List.of(Path.of("resourcepacks/fonts")));
     * }</pre>
     */
    public static CgSystemFonts of(List<Path> directories) {
        return new CgSystemFonts(CompletableFuture.completedFuture(Index.scan(directories)),
                ScriptFallbacks.Platform.current());
    }

    /** Where this platform keeps installed fonts, system-wide and per user. */
    public static List<Path> platformDirectories() {
        List<Path> directories = new ArrayList<>();
        String home = System.getProperty("user.home", "");
        switch (ScriptFallbacks.Platform.current()) {
            case WINDOWS: {
                String windows = System.getenv("WINDIR");
                addDirectory(directories, windows != null ? windows : "C:\\Windows", "Fonts");
                String local = System.getenv("LOCALAPPDATA");
                if (local != null) {
                    addDirectory(directories, local, "Microsoft", "Windows", "Fonts");
                }
                break;
            }
            case MAC:
                addDirectory(directories, "/System/Library/Fonts");
                addDirectory(directories, "/Library/Fonts");
                addDirectory(directories, home, "Library", "Fonts");
                break;
            default: {
                String dataHome = System.getenv("XDG_DATA_HOME");
                addDirectory(directories, dataHome != null && !dataHome.isEmpty() ? dataHome : home + "/.local/share",
                        "fonts");
                addDirectory(directories, home, ".fonts");
                String dataDirs = System.getenv("XDG_DATA_DIRS");
                for (String dir : (dataDirs != null && !dataDirs.isEmpty() ? dataDirs : "/usr/local/share:/usr/share")
                        .split(":")) {
                    if (!dir.isEmpty()) {
                        addDirectory(directories, dir, "fonts");
                    }
                }
                break;
            }
        }
        return directories;
    }

    /** Every indexed face. */
    public List<CgSystemFontFace> faces() {
        return index().faces;
    }

    /** Every family name, sorted. */
    public List<String> families() {
        return index().families;
    }

    /**
     * The face of {@code family} that best matches {@code style} by CSS's font-matching rules
     * (width, then italic, then nearest weight), or {@code null} when no such family is installed.
     * {@code family} is case-insensitive and may be the family ("Segoe UI"), the GDI name of one
     * weight ("Segoe UI Semibold") or the family in any language its font is named in ("メイリオ" is
     * Meiryo), as a browser matches it.
     */
    public CgSystemFontFace find(String family, CgFontStyle style) {
        if (family == null || style == null) {
            return null;
        }
        List<CgSystemFontFace> faces = index().byFamily.get(normalize(family));
        return faces == null ? null : bestMatch(faces, style);
    }

    /**
     * What this platform means by {@code generic}. Never {@code null} while any font that draws
     * Latin is installed: an absent generic falls to sans-serif, then to anything covering 'a'.
     */
    public CgSystemFontFace generic(CgGenericFamily generic, CgFontStyle style) {
        for (String name : ScriptFallbacks.generic(generic, platform)) {
            CgSystemFontFace face = find(name, style);
            if (face != null) {
                return face;
            }
        }
        if (generic != CgGenericFamily.SANS_SERIF) {
            for (String name : ScriptFallbacks.generic(CgGenericFamily.SANS_SERIF, platform)) {
                CgSystemFontFace face = find(name, style);
                if (face != null) {
                    return face;
                }
            }
        }
        return coverageSearch('a', style);
    }

    /**
     * The installed face an application would draw {@code codePoint} with when its own fonts lack
     * it, or {@code null} when nothing installed covers it. Remembered per character.
     */
    public CgSystemFontFace fallbackFor(int codePoint, CgFontStyle style, Locale locale) {
        if (style == null || !ScriptFallbacks.isFallbackCandidate(codePoint)) {
            return null;
        }
        ScriptFallbacks.HanScript han = ScriptFallbacks.hanScript(locale);
        Long key = ((long) codePoint) | ((long) style.ordinal() << 21) | ((long) han.ordinal() << 24);
        Optional<CgSystemFontFace> known = fallbacks.get(key);
        if (known == null) {
            known = Optional.ofNullable(resolveFallback(codePoint, style, han));
            fallbacks.put(key, known);
        }
        return known.orElse(null);
    }

    /**
     * {@code face}, size-bound at {@code targetPx}. A variable face is instanced at {@code style}'s
     * weight. The same face at the same weight is loaded once, and opened from its file, so no size of
     * it copies the font into native memory.
     *
     * @throws java.io.UncheckedIOException when the file can no longer be read
     */
    public CgFont load(CgSystemFontFace face, CgFontStyle style, int targetPx) {
        if (face == null || style == null) {
            throw new IllegalArgumentException("face and style must not be null");
        }
        int weight = face.getWeight();
        List<CgFontVariation> variations = Collections.emptyList();
        if (face.isVariableWeight()) {
            weight = Math.round(Math.max(face.getMinWeight(), Math.min(face.getMaxWeight(), weightOf(style))));
            if (weight != face.getWeight()) {
                variations = Collections.singletonList(new CgFontVariation("wght", weight));
            }
        }
        CgFontStyle nominal = styleOf(weight >= 600, face.isItalic());
        List<CgFontVariation> instance = variations;
        CgFont base = baseFonts.computeIfAbsent(new LoadKey(face, weight), key ->
                CgFont.loadInstalledFace(face.getPath(), face.getFaceIndex(), face.getPath().toString(),
                        nominal, instance));
        return base.atSize(targetPx);
    }

    /**
     * A {@link CgFontFallback} answering from the installed fonts. A Han character is drawn in the
     * convention its own text shows — Japanese beside kana, Korean beside Hangul — and in
     * {@code locale}'s when the text shows none.
     */
    public CgFontFallback fallback(Locale locale) {
        Locale fixed = locale != null ? locale : Locale.getDefault();
        return new CgFontFallback() {
            @Override
            public CgFont fontFor(int codePoint, CgFontStyle style, int targetPx) {
                return fontFor(codePoint, style, targetPx, null);
            }

            @Override
            public CgFont fontFor(int codePoint, CgFontStyle style, int targetPx, Locale language) {
                CgSystemFontFace face = fallbackFor(codePoint, style, language != null ? language : fixed);
                if (face == null) {
                    return null;
                }
                try {
                    return load(face, style, targetPx);
                } catch (RuntimeException e) {
                    LOGGER.log(Level.WARNING, "Cannot load fallback font " + face, e);
                    return null;
                }
            }
        };
    }

    // ── resolution ──────────────────────────────────────────────────────────

    private CgSystemFontFace resolveFallback(int codePoint, CgFontStyle style, ScriptFallbacks.HanScript han) {
        for (String name : ScriptFallbacks.candidates(codePoint, han, platform)) {
            CgSystemFontFace face = find(name, style);
            if (face != null && face.covers(codePoint)) {
                return face;
            }
        }
        return coverageSearch(codePoint, style);
    }

    private CgSystemFontFace coverageSearch(int codePoint, CgFontStyle style) {
        for (CgSystemFontFace face : searchOrder(style)) {
            if (face.covers(codePoint)) {
                return face;
            }
        }
        return null;
    }

    /** The platform's broad fonts first, then the closest style, then the widest coverage. */
    private List<CgSystemFontFace> searchOrder(CgFontStyle style) {
        return searchOrders.computeIfAbsent(style, s -> {
            List<String> preferred = new ArrayList<>();
            for (String name : ScriptFallbacks.lastResort(platform)) {
                preferred.add(normalize(name));
            }
            List<CgSystemFontFace> order = new ArrayList<>();
            for (CgSystemFontFace face : index().faces) {
                if (face.hasOutlines() && face.getCoverageSize() > 0
                        && !ScriptFallbacks.isPlaceholderFamily(face.getFamily())) {
                    order.add(face);
                }
            }
            order.sort(Comparator
                    .comparingInt((CgSystemFontFace face) -> rank(preferred, face))
                    .thenComparing(matchOrder(s))
                    .thenComparing(Comparator.comparingInt(CgSystemFontFace::getCoverageSize).reversed()));
            return Collections.unmodifiableList(order);
        });
    }

    private static int rank(List<String> preferred, CgSystemFontFace face) {
        int at = preferred.indexOf(normalize(face.getFamily()));
        return at >= 0 ? at : preferred.size();
    }

    private static CgSystemFontFace bestMatch(List<CgSystemFontFace> faces, CgFontStyle style) {
        CgSystemFontFace best = null;
        Comparator<CgSystemFontFace> order = matchOrder(style);
        for (CgSystemFontFace face : faces) {
            if (face.hasOutlines() && (best == null || order.compare(face, best) < 0)) {
                best = face;
            }
        }
        return best;
    }

    /** CSS Fonts 4 §5.2: width first, then italic, then weight; path and index to break ties. */
    private static Comparator<CgSystemFontFace> matchOrder(CgFontStyle style) {
        float weight = weightOf(style);
        boolean italic = style == CgFontStyle.ITALIC || style == CgFontStyle.BOLD_ITALIC;
        return Comparator
                .comparingInt((CgSystemFontFace face) -> Math.abs(face.getWidth() - 5))
                .thenComparingInt(face -> face.isItalic() == italic ? 0 : 1)
                .thenComparingDouble(face -> weightDistance(weight, face.getMinWeight(), face.getMaxWeight()))
                .thenComparing(face -> face.getPath().toString())
                .thenComparingInt(CgSystemFontFace::getFaceIndex);
    }

    /**
     * How far a face spanning {@code [min, max]} is from {@code desired}, ordered as CSS Fonts 4's
     * weight rule orders candidates: 400–500 looks heavier up to 500, then lighter, then heavier;
     * below 400 looks lighter first; above 500 heavier first.
     */
    static float weightDistance(float desired, float min, float max) {
        if (desired >= min && desired <= max) {
            return 0;
        }
        if (desired >= 400 && desired <= 500) {
            if (min > desired && min <= 500) {
                return min - desired;
            }
            if (max < desired) {
                return 1000 + (desired - max);
            }
            return 2000 + (min - desired);
        }
        if (desired < 400) {
            return max < desired ? desired - max : 1000 + (min - desired);
        }
        return min > desired ? min - desired : 1000 + (desired - max);
    }

    private static float weightOf(CgFontStyle style) {
        return style == CgFontStyle.BOLD || style == CgFontStyle.BOLD_ITALIC ? 700 : 400;
    }

    private static CgFontStyle styleOf(boolean bold, boolean italic) {
        if (bold) {
            return italic ? CgFontStyle.BOLD_ITALIC : CgFontStyle.BOLD;
        }
        return italic ? CgFontStyle.ITALIC : CgFontStyle.REGULAR;
    }

    private Index index() {
        Index local = index;
        if (local == null) {
            try {
                local = scan.join();
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "System font scan failed; no installed font is available", e);
                local = new Index(Collections.<CgSystemFontFace>emptyList());
            }
            index = local;
        }
        return local;
    }

    private static String normalize(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private static void addDirectory(List<Path> directories, String first, String... more) {
        try {
            directories.add(Paths.get(first, more));
        } catch (InvalidPathException ignored) {
            // an environment variable holding something that is not a path names no fonts
        }
    }

    private record LoadKey(CgSystemFontFace face, int weight) {
    }

    private static final class Index {

        final List<CgSystemFontFace> faces;
        final Map<String, List<CgSystemFontFace>> byFamily = new HashMap<>();
        final List<String> families;

        Index(List<CgSystemFontFace> faces) {
            this.faces = Collections.unmodifiableList(faces);
            Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (CgSystemFontFace face : faces) {
                for (String name : face.getFamilyNames()) {
                    byFamily.computeIfAbsent(normalize(name), k -> new ArrayList<>()).add(face);
                }
                names.add(face.getFamily());
            }
            this.families = Collections.unmodifiableList(new ArrayList<>(names));
        }

        static Index scan(List<Path> directories) {
            long start = System.nanoTime();
            List<CgSystemFontFace> faces = new ArrayList<>();
            Set<Path> seen = new HashSet<>();
            int files = 0;
            for (Path directory : directories) {
                if (directory == null || !Files.isDirectory(directory)) {
                    continue;
                }
                for (Path file : fontFiles(directory)) {
                    Path absolute = file.toAbsolutePath().normalize();
                    if (!seen.add(absolute)) {
                        continue;
                    }
                    files++;
                    try {
                        for (Sfnt.Face face : Sfnt.readFaces(absolute)) {
                            faces.add(new CgSystemFontFace(absolute, face));
                        }
                    } catch (IOException | RuntimeException e) {
                        LOGGER.log(Level.FINE, "Skipping unreadable font " + absolute, e);
                    }
                }
            }
            LOGGER.info("Indexed " + faces.size() + " font faces in " + files + " files in "
                    + (System.nanoTime() - start) / 1_000_000L + " ms");
            return new Index(faces);
        }

        private static List<Path> fontFiles(Path directory) {
            List<Path> files = new ArrayList<>();
            try {
                Files.walkFileTree(directory, EnumSet.noneOf(FileVisitOption.class), MAX_DEPTH,
                        new SimpleFileVisitor<Path>() {
                            @Override
                            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                                if (attributes.isRegularFile() && (name.endsWith(".ttf") || name.endsWith(".otf")
                                        || name.endsWith(".ttc") || name.endsWith(".otc"))) {
                                    files.add(file);
                                }
                                return FileVisitResult.CONTINUE;
                            }

                            @Override
                            public FileVisitResult visitFileFailed(Path file, IOException e) {
                                return FileVisitResult.CONTINUE;
                            }
                        });
            } catch (IOException e) {
                LOGGER.log(Level.FINE, "Cannot list fonts in " + directory, e);
            }
            return files;
        }
    }
}
