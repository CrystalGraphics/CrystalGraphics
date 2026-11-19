package com.crystalgraphics.render.ui.svg;

import javax.annotation.Nullable;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns scanned tags into a {@link CgSvgScene} — the resolution half of the pipeline, ported from
 * <b>usvg</b> (Apache-2.0/MIT).
 *
 * <p>This is where every SVG-shaped question is answered and none is passed on: inheritance, {@code use}
 * expansion, shape-to-path conversion, transform flattening, paint-server lookup and viewBox origin. What
 * comes out is geometry and colour. See {@link CgSvgScene} for the contract that buys.</p>
 *
 * <p>A class rather than a pile of static methods with a dozen parameters: the walk carries an
 * accumulating output, an id index and a gradient table through every level of recursion, and threading
 * those by hand is how a {@code <use>} ends up appending to the wrong list.</p>
 *
 * <h3>Deliberately NOT a general SVG resolver</h3>
 *
 * <p>usvg also resolves clip paths, masks, filters, patterns, text and nested {@code svg} viewports. Of
 * those only one filter is resolved, the drop shadow ({@link #shadowOf}); a filter that is anything else is
 * ignored, and the rest are not implemented. None is needed to draw an icon — the shipped set of 51
 * IntelliJ icons and the Feather chrome set use exactly the subset below. The gaps are recorded in
 * {@code ICONS.md} rather than left to be discovered.</p>
 */
public final class CgSvgResolver {

    /** Elements whose content is a definition, not a picture — skipped unless reached through {@code use}. */
    private static final Set<String> DEFINITIONS =
            Set.of("defs", "symbol", "clipPath", "mask", "pattern", "marker", "linearGradient",
                    "radialGradient", "filter", "style", "title", "desc", "metadata");

    private static final Set<String> SHAPES =
            Set.of("path", "rect", "circle", "ellipse", "line", "polyline", "polygon");

    private static final Set<String> CONTAINERS = Set.of("svg", "g", "a", "switch");

    /**
     * How deep {@code <use>} may nest before the walk gives up.
     *
     * <p>A file may reference itself, directly or through a chain, and that must not be how we find out.
     * Eight is far past anything real artwork nests and cheap to carry.</p>
     */
    private static final int MAX_USE_DEPTH = 8;

    private final List<CgSvgScanner.Tag> tags;
    private final Map<String, Integer> idIndex = new HashMap<>();
    private final Map<String, CgSvgGradient> servers = new HashMap<>();
    /** The same servers reduced to one colour each — what {@link CgSvgStyle} needs for strokes. */
    private final Map<String, Integer> gradientColours = new HashMap<>();
    private final List<CgSvgScene.Node> nodes = new ArrayList<>();
    /** Each drop-shadow filter by id, in its own user units; a filter that is not one is absent. */
    private final Map<String, ShadowPrototype> filters = new HashMap<>();
    /** Numbers the filtered elements, so two with equal shadows stay two groups. */
    private int filteredElements;

    /** Curve-flattening resolution, carried down to every shape — see {@link CgSvgPath#parse(String, int)}. */
    private final int steps;

    private CgSvgResolver(List<CgSvgScanner.Tag> tags, int steps) {
        this.tags = tags;
        this.steps = steps;
    }

    /** The whole picture at curve resolution {@code steps}. */
    public static CgSvgScene resolve(List<CgSvgScanner.Tag> tags, int steps) {
        return new CgSvgResolver(tags, steps).run(null);
    }

    /**
     * The {@code <symbol>} (or any element) with id {@code id} alone, in its own {@code viewBox}: a sprite sheet's
     * entry. Null when the file has no such id.
     */
    @Nullable
    public static CgSvgScene resolve(List<CgSvgScanner.Tag> tags, int steps, String id) {
        return new CgSvgResolver(tags, steps).run(id);
    }

    /** Whether the file names element {@code id}. */
    public static boolean hasId(List<CgSvgScanner.Tag> tags, String id) {
        for (CgSvgScanner.Tag tag : tags) {
            if (tag.kind() != CgSvgScanner.Kind.CLOSE && tag.get("id").equals(id)) return true;
        }
        return false;
    }

    @Nullable
    private CgSvgScene run(@Nullable String symbol) {
        indexIds();
        Integer symbolAt = null;
        if (symbol != null) {
            symbolAt = idIndex.get(symbol);
            if (symbolAt == null) return null;
        }
        float boxWidth = 24f, boxHeight = 24f, originX = 0f, originY = 0f;
        for (int t = symbolAt == null ? 0 : symbolAt; t < tags.size(); t++) {
            CgSvgScanner.Tag tag = tags.get(t);
            // A symbol's own box, or the root svg's
            if (symbolAt == null && !tag.name().equals("svg")) continue;
            String box = tag.get("viewBox");
            if (!box.isBlank()) {
                // Scanned, not split. `String.split` compiles its character class on every call, and this
                // runs once per document to read four numbers -- measured at 2.4 ms across the shipped set,
                // more than every rect in it. Same fix, and the same reason, as CgSvgGeometry#addPoints.
                float[] parts = new float[5];
                int found = 0;
                CgSvgPath.Cursor cursor = new CgSvgPath.Cursor(box);
                while (found < parts.length && cursor.hasNumber()) {
                    int before = cursor.position();
                    parts[found] = cursor.number();
                    if (cursor.position() == before) {
                        cursor.skip();
                        continue;
                    }
                    found++;
                }
                // Exactly four, as the split-based version required: a viewBox with more or fewer numbers
                // is malformed and is ignored rather than half-applied.
                if (found == 4) {
                    // min-x and min-y, NOT ignored. A viewBox states an origin as well as a size, and
                    // artwork authored as "-2 -2 28 28" -- which is how a set gives itself padding --
                    // draws offset by exactly that origin otherwise.
                    originX = parts[0];
                    originY = parts[1];
                    boxWidth = parts[2];
                    boxHeight = parts[3];
                }
            } else {
                boxWidth = number(tag.get("width"), boxWidth);
                boxHeight = number(tag.get("height"), boxHeight);
            }
            break;
        }

        collectGradients();
        collectFilters();

        // The origin lands in the root transform rather than in a fix-up pass over the points, so nothing
        // downstream ever sees coordinates that are not already in the icon's own 0,0 space.
        CgSvgTransform root = new CgSvgTransform(1, 0, 0, 1, -originX, -originY);
        if (symbolAt != null) {
            resolveNode(symbolAt, CgSvgStyle.ROOT, root, 0, true, null);
        } else {
            resolveRange(0, tags.size(), CgSvgStyle.ROOT, root, 0, null);
        }
        return new CgSvgScene(nodes, boxWidth, boxHeight);
    }

    private void indexIds() {
        for (int i = 0; i < tags.size(); i++) {
            CgSvgScanner.Tag tag = tags.get(i);
            if (tag.kind() == CgSvgScanner.Kind.CLOSE) continue;
            String id = tag.get("id");
            if (!id.isEmpty()) idIndex.putIfAbsent(id, i);
        }
    }

    // ── Paint servers ───────────────────────────────────────────────────────────────────────────────

    /**
     * Reads every paint server in the file, keeping the whole ramp and not only a colour.
     *
     * <p>Two passes over the same tags: the first builds each gradient from its own attributes and stops,
     * the second resolves {@code href} inheritance. Bounded iteration rather than recursion — a file can
     * reference itself in a cycle, and that must not be how we find out.</p>
     */
    private void collectGradients() {
        Map<String, String> inherits = new HashMap<>();
        for (int i = 0; i < tags.size(); i++) {
            CgSvgScanner.Tag tag = tags.get(i);
            if (tag.kind() == CgSvgScanner.Kind.CLOSE) continue;
            boolean linear = tag.name().equals("linearGradient");
            if (!linear && !tag.name().equals("radialGradient")) continue;
            String id = tag.get("id");
            if (id.isEmpty()) continue;

            String href = tag.has("href") ? tag.get("href") : tag.get("xlink:href");
            if (href.startsWith("#")) inherits.put(id, href.substring(1));

            Map<String, String> attributes = new HashMap<>(tag.attributes());
            // CgSvgGradient reads the element kind out of the same map as everything else, so that its
            // factory takes one argument instead of a boolean nobody can read at the call site.
            attributes.put("__tag__", tag.name());
            int end = tag.kind() == CgSvgScanner.Kind.OPEN ? matchingClose(i) : i;
            List<Stop> stops = readStops(i, end);
            float[] offsets = new float[stops.size()];
            int[] colours = new int[stops.size()];
            for (int s = 0; s < stops.size(); s++) {
                offsets[s] = stops.get(s).offset();
                colours[s] = stops.get(s).argb();
            }
            servers.put(id, CgSvgGradient.of(attributes, offsets, colours, null));
        }
        for (int pass = 0; pass < 4; pass++) {
            for (Map.Entry<String, String> entry : inherits.entrySet()) {
                CgSvgGradient self = servers.get(entry.getKey());
                CgSvgGradient source = servers.get(entry.getValue());
                if (self == null || source == null || self.colours().length > 0) continue;
                servers.put(entry.getKey(), new CgSvgGradient(self.radial(), self.userSpace(),
                        self.transform(), self.x1(), self.y1(), self.x2(), self.y2(),
                        source.offsets(), source.colours(), self.spread()));
            }
        }
        for (Map.Entry<String, CgSvgGradient> entry : servers.entrySet()) {
            if (entry.getValue().colours().length == 0) continue;
            gradientColours.put(entry.getKey(), entry.getValue().representativeColour());
        }
    }

    /**
     * One gradient stop.
     *
     * <p>A record rather than a {@code float[2]} because the second value is an ARGB int, and a float
     * cannot hold one: 24 bits of mantissa against 32 bits of colour. Opaque colours happen to survive the
     * round trip — {@code 0xFF000000} is exactly {@code -2^24} — and anything with a partial alpha does
     * not, so packing them would work on every stop in the shipped set and quietly shift the colour of the
     * first translucent one anybody adds.</p>
     */
    private record Stop(float offset, int argb) {
    }

    private List<Stop> readStops(int from, int to) {
        List<Stop> out = new ArrayList<>();
        for (int i = from + 1; i <= to && i < tags.size(); i++) {
            CgSvgScanner.Tag stop = tags.get(i);
            if (!stop.name().equals("stop") || stop.kind() == CgSvgScanner.Kind.CLOSE) continue;

            // stop-color lives in an attribute OR inside style="", and Illustrator exports use the second
            // exclusively -- reading only the attribute finds no stops at all in a file that is full of
            // them, and every gradient then falls back to grey.
            Map<String, String> declarations = new HashMap<>(stop.attributes());
            declarations.putAll(styleDeclarations(stop.get("style")));

            Integer colour = CgSvgColor.parseColor(declarations.get("stop-color"));
            if (colour == null) colour = 0xFF000000;
            String opacity = declarations.get("stop-opacity");
            if (opacity != null) colour = CgSvgColor.withOpacity(colour, number(opacity, 1f));

            String rawOffset = declarations.getOrDefault("offset", "0").trim();
            float offset = rawOffset.endsWith("%")
                    ? number(rawOffset.substring(0, rawOffset.length() - 1), 0f) / 100f
                    : number(rawOffset, 0f);
            out.add(new Stop(Math.max(0f, Math.min(1f, offset)), colour));
        }
        return out;
    }

    // ── Filters ─────────────────────────────────────────────────────────────────────────────────────

    /** A drop shadow as its {@code <filter>} states it, in the referencing element's user units. */
    private record ShadowPrototype(float dx, float dy, float sigmaX, float sigmaY, int argb) {
    }

    private void collectFilters() {
        for (int i = 0; i < tags.size(); i++) {
            CgSvgScanner.Tag tag = tags.get(i);
            if (!tag.name().equals("filter") || tag.kind() != CgSvgScanner.Kind.OPEN) continue;
            String id = tag.get("id");
            if (id.isEmpty()) continue;
            ShadowPrototype shadow = shadowOf(i, matchingClose(i));
            if (shadow != null) filters.put(id, shadow);
        }
    }

    /**
     * The drop shadow the filter at {@code open} draws, or null when it draws anything else.
     *
     * <p>Two spellings: {@code feDropShadow} on its own, with the spec's defaults (offset 2, deviation 2,
     * black), and the chain authoring tools export for one — a blur of the alpha, an offset, a flood, an
     * {@code in} composite of the flood with the blur, and a merge ending in {@code SourceGraphic}, in any
     * order. Both are the shadow of the element's alpha painted under it, which is all that is drawn.</p>
     */
    @Nullable
    private ShadowPrototype shadowOf(int open, int close) {
        List<Map<String, String>> primitives = new ArrayList<>();
        List<String> names = new ArrayList<>();
        String mergeLast = null;
        for (int i = open + 1; i < close; i++) {
            CgSvgScanner.Tag tag = tags.get(i);
            if (tag.kind() == CgSvgScanner.Kind.CLOSE || !tag.name().startsWith("fe")) continue;
            if (tag.name().equals("feMergeNode")) {
                mergeLast = tag.get("in");
                continue;
            }
            Map<String, String> declarations = new HashMap<>(tag.attributes());
            declarations.putAll(styleDeclarations(tag.get("style")));
            primitives.add(declarations);
            names.add(tag.name());
        }

        if (names.equals(List.of("feDropShadow"))) {
            Map<String, String> drop = primitives.get(0);
            float[] sigma = deviation(drop.get("stdDeviation"), 2f);
            return new ShadowPrototype(number(drop.get("dx"), 2f), number(drop.get("dy"), 2f),
                    sigma[0], sigma[1], flood(drop));
        }

        if (names.size() != 5 || !"SourceGraphic".equals(mergeLast)) return null;
        Map<String, String> blur = null, offset = null, flood = null, composite = null;
        for (int i = 0; i < names.size(); i++) {
            Map<String, String> primitive = primitives.get(i);
            switch (names.get(i)) {
                case "feGaussianBlur" -> blur = primitive;
                case "feOffset" -> offset = primitive;
                case "feFlood" -> flood = primitive;
                case "feComposite" -> composite = primitive;
                case "feMerge" -> { }
                default -> { return null; }
            }
        }
        if (blur == null || offset == null || flood == null || composite == null) return null;
        if (!"in".equals(composite.getOrDefault("operator", "over"))) return null;
        // SourceGraphic blurred has the same alpha as SourceAlpha, and the flood replaces its colour
        String source = blur.getOrDefault("in", "SourceGraphic");
        if (!source.equals("SourceAlpha") && !source.equals("SourceGraphic")) return null;
        float[] sigma = deviation(blur.get("stdDeviation"), 0f);
        return new ShadowPrototype(number(offset.get("dx"), 0f), number(offset.get("dy"), 0f),
                sigma[0], sigma[1], flood(flood));
    }

    /** {@code stdDeviation}: one number for both axes, or two. */
    private static float[] deviation(@Nullable String raw, float fallback) {
        if (raw == null || raw.isBlank()) return new float[]{fallback, fallback};
        String[] parts = raw.trim().split("[\\s,]+");
        float x = Math.max(0f, number(parts[0], fallback));
        float y = parts.length > 1 ? Math.max(0f, number(parts[1], x)) : x;
        return new float[]{x, y};
    }

    /** {@code flood-color} with {@code flood-opacity} folded in; black by default, as the spec says. */
    private static int flood(Map<String, String> primitive) {
        Integer colour = CgSvgColor.parseColor(primitive.get("flood-color"));
        int argb = colour == null ? 0xFF000000 : colour;
        String opacity = primitive.get("flood-opacity");
        return opacity == null ? argb : CgSvgColor.withOpacity(argb, number(opacity, 1f));
    }

    /**
     * The shadow an element's own {@code filter} names, in absolute space; {@code inherited} when it names none.
     * A filter nested inside a filtered group replaces the group's for its subtree rather than compounding it.
     */
    @Nullable
    private CgSvgScene.DropShadow shadowFor(CgSvgScanner.Tag tag, CgSvgTransform transform,
                                          @Nullable CgSvgScene.DropShadow inherited) {
        String raw = tag.has("filter") ? tag.get("filter") : styleDeclarations(tag.get("style")).get("filter");
        if (raw == null) return inherited;
        raw = raw.trim();
        if (!raw.startsWith("url(#") || !raw.endsWith(")")) return inherited;
        ShadowPrototype prototype = filters.get(raw.substring(5, raw.length() - 1).trim());
        if (prototype == null) return inherited;
        // An offset and a length, so the transform's linear part only
        float dx = transform.applyX(prototype.dx(), prototype.dy()) - transform.applyX(0f, 0f);
        float dy = transform.applyY(prototype.dx(), prototype.dy()) - transform.applyY(0f, 0f);
        float scale = transform.lengthScale();
        return new CgSvgScene.DropShadow(dx, dy, prototype.sigmaX() * scale, prototype.sigmaY() * scale,
                prototype.argb(), filteredElements++);
    }

    // ── The walk ────────────────────────────────────────────────────────────────────────────────────

    private void resolveRange(int from, int to, CgSvgStyle style, CgSvgTransform transform, int depth,
                              @Nullable CgSvgScene.DropShadow shadow) {
        int i = from;
        while (i < to) {
            i = resolveNode(i, style, transform, depth, false, shadow) + 1;
        }
    }

    /**
     * Resolves one node and everything under it.
     *
     * @param forced the node was reached through {@code <use>}, so a {@code <symbol>} or a member of
     *               {@code <defs>} is now genuinely part of the picture
     * @param shadow the drop shadow of the filtered element this node is inside, if any
     * @return the index of the last token this node consumed
     */
    private int resolveNode(int index, CgSvgStyle style, CgSvgTransform transform, int depth, boolean forced,
                            @Nullable CgSvgScene.DropShadow shadow) {
        CgSvgScanner.Tag tag = tags.get(index);
        if (tag.kind() == CgSvgScanner.Kind.CLOSE) return index;

        int end = tag.kind() == CgSvgScanner.Kind.OPEN ? matchingClose(index) : index;
        String name = tag.name();
        if (DEFINITIONS.contains(name) && !forced) return end;

        CgSvgStyle childStyle = style.inherit(tag.attributes(), gradientColours);
        CgSvgTransform childTransform = CgSvgTransform.parse(tag.get("transform")).then(transform);
        CgSvgScene.DropShadow childShadow = shadowFor(tag, childTransform, shadow);

        if (name.equals("use")) {
            resolveUse(tag, childStyle, childTransform, depth, childShadow);
            return end;
        }
        if (SHAPES.contains(name)) {
            resolveShape(tag, childStyle, childTransform, childShadow);
            return end;
        }
        if (tag.kind() == CgSvgScanner.Kind.OPEN
                && (CONTAINERS.contains(name) || forced || !DEFINITIONS.contains(name))) {
            // <symbol> and <defs> reached through <use> descend as ordinary groups, and so does any
            // element we do not recognise -- an unknown container that swallowed its children would lose
            // whole regions of a file for a tag we simply had not heard of.
            resolveRange(index + 1, end, childStyle, childTransform, depth, childShadow);
        }
        return end;
    }

    private void resolveUse(CgSvgScanner.Tag tag, CgSvgStyle style, CgSvgTransform transform, int depth,
                            @Nullable CgSvgScene.DropShadow shadow) {
        if (depth >= MAX_USE_DEPTH) return;
        String href = tag.has("href") ? tag.get("href") : tag.get("xlink:href");
        if (!href.startsWith("#")) return;
        Integer target = idIndex.get(href.substring(1));
        if (target == null) return;

        // x/y on a <use> are a translation, applied INSIDE its own transform -- a sprite sheet that places
        // twenty copies of one symbol depends on it, and dropping it stacks all twenty.
        float x = number(tag.get("x"), 0f);
        float y = number(tag.get("y"), 0f);
        CgSvgTransform placed = (x != 0f || y != 0f)
                ? new CgSvgTransform(1, 0, 0, 1, x, y).then(transform)
                : transform;
        resolveNode(target, style, placed, depth + 1, true, shadow);
    }

    /**
     * One shape becomes one {@link CgSvgScene.Node}, and a second when its stroke is a gradient: the stroke's
     * outline, filled.
     *
     * <p><b>Paint is resolved before the points move, geometry after.</b> An {@code objectBoundingBox}
     * gradient resolves against the shape's box in the space the gradient was <em>stated</em> in — which
     * is the space in effect where it is referenced, before this element's own transform — so the box has
     * to be measured while the contours are still local. The transform then survives on the paint (see
     * {@link CgSvgScene.Gradient}) and is flattened into the contours, which is exactly usvg's split.</p>
     */
    private void resolveShape(CgSvgScanner.Tag tag, CgSvgStyle style, CgSvgTransform transform,
                              @Nullable CgSvgScene.DropShadow shadow) {
        if (!style.fills() && !style.strokes()) return;
        List<CgSvgPath.Polyline> contours = CgSvgGeometry.of(tag, steps);
        if (contours.isEmpty()) return;

        CgSvgScene.Fill fill = null;
        if (style.fills()) {
            CgSvgScene.Gradient gradient = gradientOf(style.fill(), contours, transform,
                    style.fillOpacity() * style.opacity(), style.fillArgb());
            fill = new CgSvgScene.Fill(gradient != null ? gradient
                    : new CgSvgScene.Solid(style.fillArgb(), style.fill().currentColor()), style.evenOdd());
        }
        CgSvgScene.Gradient strokeGradient = style.strokes() ? gradientOf(style.stroke(), contours, transform,
                style.strokeOpacity() * style.opacity(), style.strokeArgb()) : null;

        for (CgSvgPath.Polyline contour : contours) {
            for (float[] point : contour.points()) {
                float px = transform.applyX(point[0], point[1]);
                float py = transform.applyY(point[0], point[1]);
                point[0] = px;
                point[1] = py;
            }
        }

        float halfWidth = style.strokeHalfWidth() * transform.lengthScale();
        CgSvgScene.Stroke stroke = null;
        if (style.strokes() && strokeGradient == null) {
            stroke = new CgSvgScene.Stroke(new CgSvgScene.Solid(style.strokeArgb(), style.stroke().currentColor()),
                    halfWidth, style.cap());
        }
        if (fill != null || stroke != null) nodes.add(new CgSvgScene.Node(contours, fill, stroke, shadow));
        if (strokeGradient != null) {
            // Painted as a fill of its own outline, after the element's fill: SVG's order for one element.
            List<CgSvgPath.Polyline> outline = CgSvgStroker.outline(contours, halfWidth, style.cap(), steps);
            if (!outline.isEmpty()) {
                nodes.add(new CgSvgScene.Node(outline, new CgSvgScene.Fill(strokeGradient, false), null, shadow));
            }
        }
    }

    /**
     * The gradient a paint names, resolved against the shape's local box; null for a colour, or for a
     * one-stop ramp, which the style's flat colour already paints.
     */
    @Nullable
    private CgSvgScene.Gradient gradientOf(CgSvgColor.Paint paint, List<CgSvgPath.Polyline> contours,
                                         CgSvgTransform transform, float alpha, int argb) {
        CgSvgGradient gradient = paint.reference() == null ? null : servers.get(paint.reference());
        if (gradient == null || gradient.colours().length < 2) return null;
        return new CgSvgScene.Gradient(gradient.resolvedAgainst(CgSvgGeometry.boundsOf(contours)),
                transform, alpha, argb, paint.currentColor());
    }

    // ── Tokens ──────────────────────────────────────────────────────────────────────────────────────

    /**
     * The index of the {@code </name>} closing the tag at {@code open}.
     *
     * <p>Matched <b>by name</b>, not by a bare depth counter: real files carry stray closing tags and
     * unclosed ones, and a counter walks straight past the true close of a group into its siblings — which
     * silently reparents them and applies the wrong transform to the rest of the file.</p>
     */
    private int matchingClose(int open) {
        String name = tags.get(open).name();
        int depth = 0;
        for (int i = open + 1; i < tags.size(); i++) {
            CgSvgScanner.Tag tag = tags.get(i);
            if (!tag.name().equals(name)) continue;
            if (tag.kind() == CgSvgScanner.Kind.OPEN) depth++;
            else if (tag.kind() == CgSvgScanner.Kind.CLOSE) {
                if (depth == 0) return i;
                depth--;
            }
        }
        return tags.size() - 1;
    }

    /** An inline {@code style}'s declarations, by lower-cased property name. */
    static Map<String, String> styleDeclarations(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null) return out;
        for (String part : raw.split(";")) {
            int colon = part.indexOf(':');
            if (colon <= 0) continue;
            out.put(part.substring(0, colon).trim().toLowerCase(), part.substring(colon + 1).trim());
        }
        return out;
    }

    /** An attribute's number, or {@code fallback} when it is missing or not one. */
    static float number(String raw, float fallback) {
        if (raw == null) return fallback;
        try {
            return Float.parseFloat(raw.trim());
        } catch (RuntimeException notANumber) {
            return fallback;
        }
    }
}
