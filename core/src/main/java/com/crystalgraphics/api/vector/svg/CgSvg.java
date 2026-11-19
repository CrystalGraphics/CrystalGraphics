package com.crystalgraphics.api.vector.svg;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import javax.annotation.Nullable;

import com.crystalgraphics.job.CgJob;
import com.crystalgraphics.job.CgJobLane;
import com.crystalgraphics.render.ui.svg.CgSvgOps;
import com.crystalgraphics.render.ui.svg.CgSvgPath;
import com.crystalgraphics.render.ui.svg.CgSvgResolver;
import com.crystalgraphics.render.ui.svg.CgSvgScanner;
import com.crystalgraphics.render.ui.svg.CgSvgScene;
import com.crystalgraphics.util.io.CgIO;
import com.crystalgraphics.util.trace.CgChannels;
import com.crystalgraphics.trace.CgTrace;

/**
 * An SVG document, parsed: immutable, shared, usable from any thread. Drawn by the canvas through a brush, always
 * sharp — rasterised at the size shown when it is icon-sized, drawn as vectors when larger.
 *
 * <pre>{@code
 * static final CgSvg SAVE = CgSvg.of("mymod:ui/icons/save.svg");
 *
 * canvas.svg(SAVE, x, y, 16, 16);                                         // its own colours, fitted and centred
 * canvas.image(CgBrush.svg(SAVE).color(0xFFE0E0E0), x, y, 16, 16);        // currentColor bound to a theme colour
 * canvas.image(CgBrush.svg(SAVE).color(text).tint(0x80FFFFFF), x, y, 16, 16);  // and everything at half alpha
 * }</pre>
 *
 * <p>A sprite sheet's entries are its {@code <symbol>}s:</p>
 *
 * <pre>{@code
 * CgSvg arrow = CgSvg.of("mymod:ui/icons/sheet.svg#arrow");   // or CgSvg.of("mymod:ui/icons/sheet.svg").symbol("arrow")
 * }</pre>
 *
 * <h3>What it reads</h3>
 *
 * <ul>
 *   <li>Shapes: {@code path} (the whole {@code d} grammar), {@code rect} with rounded corners, {@code circle},
 *       {@code ellipse}, {@code line}, {@code polyline}, {@code polygon}.</li>
 *   <li>Structure: nested {@code g}, {@code svg}, {@code a}, {@code switch}; {@code defs} and {@code symbol} held back
 *       from the picture and brought in by {@code use}.</li>
 *   <li>Transforms, composed down the tree, stroke widths scaled with them.</li>
 *   <li>Paint: colours as CSS reads them ({@link com.crystalgraphics.api.paint.CgCssColors}), {@code currentColor},
 *       linear and radial gradients in either unit system with {@code gradientTransform}, {@code spreadMethod} and
 *       {@code href} stop inheritance; {@code fill-rule}, {@code stroke-width}, {@code stroke-linecap} and the three
 *       opacities, each overridable by an inline {@code style}.</li>
 * </ul>
 *
 * <ul>
 *   <li><b>A {@code <style>} block or a {@code class=} attribute fails the load</b>, naming the file: export with
 *       presentation attributes (Illustrator's "Presentation Attributes", SVGO's {@code inlineStyles}).</li>
 *   <li>Not drawn: text, {@code clipPath}, masks, patterns (flat), dashes, filters other than a drop shadow. A clipped
 *       shape draws unclipped. Group {@code opacity} multiplies into its children.</li>
 *   <li>A gradient on a stroke paints the stroke's outline as a gradient fill; a gradient is cut into bands fine
 *       enough that each moves the colour by at most two levels.</li>
 * </ul>
 */
public final class CgSvg {

    private static final Logger LOGGER = Logger.getLogger(CgSvg.class.getName());

    /** Path to document. Threads: any; a racing double load parses twice and keeps one. */
    private static final Map<String, CgSvg> CACHE = new ConcurrentHashMap<>();

    /** Moves whenever a cached document is dropped. @see #generation */
    private static volatile int generation;

    /** The curve resolution {@link #parse} resolves at: the cheapest mesh, drawn only until a tier is built. */
    private static final int PARSE_STEPS = 3;

    /** The finest tier, for a draw past every threshold in {@link #TIER_MAX_DEVICE_PX}. */
    private static final int REFERENCE_STEPS = 16;

    /**
     * Device sizes each tier serves, and its curve steps. Steps count per curve segment, so the polygon is fixed while
     * its error in device pixels grows with the size; eight is the least at which a 1 px stroked circle reads round.
     */
    private static final int[] TIER_MAX_DEVICE_PX = {24, 64, 160, Integer.MAX_VALUE};
    private static final int[] TIER_STEPS = {8, 10, 12, REFERENCE_STEPS};

    private final CgSvgScene scene;
    @Nullable
    private volatile List<CgSvgOps.Op> ops;
    private final List<CgSvgPath.Polyline> outline;
    /** {@code minX, minY, maxX, maxY} of everything drawn, unioned with the viewBox. */
    private final float[] bounds;
    private final boolean usesCurrentColor;
    /** The scanned file, kept so tiers and symbols resolve on demand; null for a tier. */
    @Nullable
    private List<CgSvgScanner.Tag> tags;
    private final Map<Integer, CgSvg> tiers = new ConcurrentHashMap<>();
    @Nullable
    private String source;
    private long fingerprint;
    /** For a symbol: the document it was resolved from, and its id, which tiers resolve again. */
    @Nullable
    private CgSvg symbolOf;
    @Nullable
    private String symbolId;

    private CgSvg(CgSvgScene scene) {
        this.scene = scene;
        List<CgSvgPath.Polyline> contours = new ArrayList<>();
        for (CgSvgScene.Node node : scene.nodes()) contours.addAll(node.contours());
        this.outline = Collections.unmodifiableList(contours);
        this.bounds = boundsOf(scene);
        this.usesCurrentColor = anyCurrentColor(scene);
    }

    // ── Loading ──────────────────────────────────────────────────────────

    /**
     * The shared document at {@code path} ({@code "namespace:path/file.svg"}, or {@code "...svg#id"} for a symbol),
     * loaded once; null when it cannot be read.
     *
     * @throws IllegalArgumentException naming the file, for a {@code <style>} block, a {@code class=} or a missing symbol
     */
    @Nullable
    public static CgSvg of(String path) {
        CgSvg cached = CACHE.get(path);
        if (cached != null) return cached;
        int hash = path.indexOf('#');
        CgSvg loaded;
        if (hash >= 0) {
            CgSvg sheet = of(path.substring(0, hash));
            loaded = sheet == null ? null : sheet.symbol(path.substring(hash + 1));
        } else {
            loaded = load(path);
        }
        if (loaded != null) CACHE.put(path, loaded);
        return loaded;
    }

    /**
     * {@code path} read and parsed now, uncached; null when it cannot be read.
     *
     * @throws IllegalArgumentException naming the file, for a {@code <style>} block or a {@code class=}
     */
    @Nullable
    public static CgSvg load(String path) {
        String text;
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.MISC, "svg.load")) {
            text = CgIO.loadSource(path);
        }
        if (text == null) {
            LOGGER.warning("SVG " + path + " could not be read");
            return null;
        }
        CgSvg document = parse(text, path);
        document.fingerprint = fingerprint(text);
        return document;
    }

    /**
     * SVG text parsed: the first frame's mesh now, the drawn tiers when first asked for.
     *
     * @throws IllegalArgumentException for a {@code <style>} block or a {@code class=}
     */
    public static CgSvg parse(String svg) {
        return parse(svg, (String) null);
    }

    private static CgSvg parse(String svg, @Nullable String path) {
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.MISC, "svg.parse")) {
            List<CgSvgScanner.Tag> tags = CgSvgScanner.scan(svg);
            refuseStyles(tags, path);
            CgSvg document = new CgSvg(CgSvgResolver.resolve(tags, PARSE_STEPS));
            document.tags = tags;
            document.source = path;
            document.tiers.put(PARSE_STEPS, document);
            return document;
        }
    }

    /** The document at a chosen curve resolution and nothing else: judging artwork at the tier a size draws. */
    static CgSvg parse(String svg, int steps) {
        return new CgSvg(CgSvgResolver.resolve(CgSvgScanner.scan(svg), steps));
    }

    private static void refuseStyles(List<CgSvgScanner.Tag> tags, @Nullable String path) {
        for (CgSvgScanner.Tag tag : tags) {
            if (tag.kind() == CgSvgScanner.Kind.CLOSE) continue;
            String what = tag.name().equals("style") ? "a <style> block" : tag.has("class") ? "class=\"" + tag.get("class") + "\"" : null;
            if (what == null) continue;
            throw new IllegalArgumentException("SVG " + (path == null ? "(parsed text)" : path) + " uses " + what
                    + ", which is not read: export it with presentation attributes (Illustrator: Styling: Presentation"
                    + " Attributes; SVGO: inlineStyles)");
        }
    }

    /**
     * This file's {@code <symbol>} (or any element) {@code id} alone, in its own {@code viewBox}.
     *
     * @throws IllegalArgumentException naming the file and id when there is none
     */
    public CgSvg symbol(String id) {
        List<CgSvgScanner.Tag> scanned = tags;
        if (scanned == null) throw new IllegalStateException("a tier of a document has no symbols; ask its document");
        CgSvgScene found = CgSvgResolver.resolve(scanned, PARSE_STEPS, id);
        if (found == null) throw new IllegalArgumentException("SVG " + (source == null ? "(parsed text)" : source) + " has no element id=\"" + id + "\"");
        CgSvg symbol = new CgSvg(found);
        symbol.symbolOf = this;
        symbol.symbolId = id;
        symbol.source = source == null ? null : source + "#" + id;
        symbol.tiers.put(PARSE_STEPS, symbol);
        return symbol;
    }

    /** Every cached document dropped. A resource reload wants {@link #revalidate} instead. */
    public static void invalidateCache() {
        CACHE.clear();
        generation++;
    }

    /**
     * A counter that moves whenever a cached document is dropped, so a holder can tell its document may be stale.
     *
     * <pre>{@code
     * if (seen != CgSvg.generation()) { seen = CgSvg.generation(); icon = CgSvg.of(path); }
     * }</pre>
     */
    public static int generation() {
        return generation;
    }

    /**
     * Drops the cached documents whose file now reads differently and keeps the rest, with their identity: what a
     * resource reload calls. A path can read other bytes with no file edited (a resource pack), so each is re-read.
     *
     * @return how many were dropped
     */
    public static int revalidate() {
        int dropped = 0;
        for (Map.Entry<String, CgSvg> entry : CACHE.entrySet()) {
            String path = entry.getKey();
            int hash = path.indexOf('#');
            String now = CgIO.loadSource(hash < 0 ? path : path.substring(0, hash));
            CgSvg document = entry.getValue();
            long held = document.symbolOf != null ? document.symbolOf.fingerprint : document.fingerprint;
            if (now == null || fingerprint(now) != held) {
                CACHE.remove(path, document);
                dropped++;
            }
        }
        if (dropped > 0) generation++;
        return dropped;
    }

    /**
     * Loads and parses {@code paths} on the job pool, so the first frame that draws them finds them ready. Parsing
     * touches no device; drawing stays on the frame that draws.
     *
     * @return completes when every path has been tried; one that fails is logged and left out, as {@link #of} would
     */
    public static CompletableFuture<Void> preload(Collection<String> paths) {
        List<CompletableFuture<Void>> pending = new ArrayList<>(paths.size());
        for (String path : paths) {
            if (CACHE.containsKey(path)) continue;
            pending.add(CompletableFuture.runAsync(() -> of(path), CgJob.executor(CgJobLane.LATENCY)));
        }
        return CompletableFuture.allOf(pending.toArray(new CompletableFuture[0]));
    }

    private static long fingerprint(String text) {
        return ((long) text.length() << 32) | (text.hashCode() & 0xFFFFFFFFL);
    }

    // ── Tiers ────────────────────────────────────────────────────────────

    /**
     * The document as drawn at {@code devicePx}, its longer side in device pixels: the tier whose curve faceting that
     * size cannot resolve, built now on first use and kept.
     */
    public CgSvg tier(float devicePx) {
        if (tags == null && symbolOf == null) return this;
        for (int i = 0; i < TIER_MAX_DEVICE_PX.length; i++) {
            if (devicePx > TIER_MAX_DEVICE_PX[i]) continue;
            int steps = TIER_STEPS[i];
            CgSvg built = tiers.get(steps);
            if (built != null) return built;
            return tiers.computeIfAbsent(steps, this::resolveAt);
        }
        return this;
    }

    private CgSvg resolveAt(int steps) {
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.MISC, "svg.tier")) {
            CgSvgScene resolved = symbolOf != null
                    ? CgSvgResolver.resolve(symbolOf.tags, steps, symbolId)
                    : CgSvgResolver.resolve(tags, steps);
            CgSvg tier = new CgSvg(resolved);
            tier.source = source;
            tier.ops();
            return tier;
        }
    }

    // ── Queries ──────────────────────────────────────────────────────────

    /**
     * The draw operations at this document's resolution, in document order, built on first request. Shared: never
     * modify what it answers.
     */
    public List<CgSvgOps.Op> ops() {
        List<CgSvgOps.Op> built = ops;
        if (built != null) return built;
        synchronized (this) {
            if (ops == null) ops = Collections.unmodifiableList(CgSvgOps.build(scene));
            return ops;
        }
    }

    /** The viewBox's width, in the document's units. */
    public float width() {
        return scene.width();
    }

    /** The viewBox's height, in the document's units. */
    public float height() {
        return scene.height();
    }

    /** The left of everything the document draws, in its units: measured, never inside the viewBox. */
    public float minX() {
        return bounds[0];
    }

    public float minY() {
        return bounds[1];
    }

    public float maxX() {
        return bounds[2];
    }

    public float maxY() {
        return bounds[3];
    }

    /**
     * Whether any paint is {@code currentColor}: a mark meant to be tinted, as opposed to artwork in its own colours.
     */
    public boolean usesCurrentColor() {
        return usesCurrentColor;
    }

    /** Whether it draws nothing. */
    public boolean isEmpty() {
        return scene.nodes().isEmpty();
    }

    /** Every contour, in document order, transformed into the document's units. */
    public List<CgSvgPath.Polyline> outline() {
        return outline;
    }

    /** Where it was loaded from, {@code "...svg#id"} for a symbol; null for parsed text. */
    @Nullable
    public String source() {
        return source;
    }

    /** Whether any element carries a drop shadow ({@code feDropShadow} or the chain tools export). */
    public boolean hasShadow() {
        for (CgSvgScene.Node node : scene.nodes()) {
            if (node.shadow() != null) return true;
        }
        return false;
    }

    /** Fill cells over every op. */
    public int cellCount() {
        int total = 0;
        for (CgSvgOps.Op op : ops()) if (op.fill()) total += op.data().length / 8;
        return total;
    }

    /** Stroke segments over every op. */
    public int segmentCount() {
        int total = 0;
        for (CgSvgOps.Op op : ops()) if (!op.fill()) total += op.data().length / 4;
        return total;
    }

    @Override
    public String toString() {
        return "CgSvg(" + (source == null ? "parsed text" : source) + ", " + width() + "x" + height() + ")";
    }

    private static boolean anyCurrentColor(CgSvgScene scene) {
        for (CgSvgScene.Node node : scene.nodes()) {
            if (node.fill() != null && node.fill().paint().currentColor()) return true;
            if (node.stroke() != null && node.stroke().paint().currentColor()) return true;
        }
        return false;
    }

    /**
     * The box every op occupies: measured, since artwork draws past its own viewBox (a stroke centred on the edge),
     * and unioned with the viewBox, since contours flattened coarsely sit inside the curves they approximate.
     */
    private static float[] boundsOf(CgSvgScene scene) {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (CgSvgScene.Node node : scene.nodes()) {
            float pad = node.stroke() == null ? 0f : node.stroke().halfWidth();
            for (CgSvgPath.Polyline contour : node.contours()) {
                for (float[] point : contour.points()) {
                    minX = Math.min(minX, point[0] - pad);
                    minY = Math.min(minY, point[1] - pad);
                    maxX = Math.max(maxX, point[0] + pad);
                    maxY = Math.max(maxY, point[1] + pad);
                }
            }
        }
        if (minX > maxX) return new float[]{0f, 0f, 0f, 0f};
        return new float[]{Math.min(minX, 0f), Math.min(minY, 0f), Math.max(maxX, scene.width()), Math.max(maxY, scene.height())};
    }
}
