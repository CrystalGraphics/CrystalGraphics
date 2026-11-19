package com.crystalgraphics.render.ui.svg;

import java.util.Map;

/**
 * A linear or radial paint server, evaluated per point.
 *
 * <h3>Why one colour was not enough</h3>
 *
 * <p>The first pass collapsed every gradient to its midpoint stop. That is defensible for a two-stop
 * gradient and badly wrong for real artwork, because of one detail: {@code gradientUnits="userSpaceOnUse"}
 * states the axis in the <b>document's</b> coordinates, not the shape's, and an exported logo routinely
 * gives a shape an axis several times its own size. The shape then occupies a narrow slice of the ramp —
 * so its actual colours have nothing to do with the ramp's middle.</p>
 *
 * <p>The JetBrains mark is the case in point. Its four gradients all run orange-or-pink to blue over axes
 * up to 87 units long inside a 70-unit box, and every one of them has a deliberately desaturated stop near
 * {@code 0.5} to make that blend read smoothly. Sampling the middle picks exactly those stops: the whole
 * logo comes out brown and purple, which is a plausible-looking picture of the wrong thing.</p>
 *
 * <h3>Evaluated on the CPU, per triangle</h3>
 *
 * <p>A cell takes one colour ramp, so the gradient is realised by subdivision —
 * the fill is cut fine enough along the gradient's own direction that a flat colour per triangle is
 * indistinguishable from a ramp. That costs triangles and no shader work at all, which is the right trade
 * at icon sizes: a paint-server material would mean a second shader, a second bind, and a per-shape uniform
 * upload, to remove banding nobody can see.</p>
 *
 * <p>Cutting along the gradient's <em>direction</em> rather than uniformly is what keeps the count sane. A
 * horizontal gradient needs no horizontal cuts at all, so its shape stays at a handful of bands however
 * tall it is.</p>
 */
public record CgSvgGradient(boolean radial, boolean userSpace, CgSvgTransform transform,
                          float x1, float y1, float x2, float y2,
                          float[] offsets, int[] colours, int spread) {

    /** Clamp at both ends — SVG's default and the only one exported artwork uses in practice. */
    public static final int SPREAD_PAD = 0;
    public static final int SPREAD_REFLECT = 1;
    public static final int SPREAD_REPEAT = 2;

    /**
     * The largest per-band colour step, in 8-bit levels, before the banding is visible.
     *
     * <p>Two. Below that a step is inside the noise of an ordinary display and dithering nobody asked for
     * would cost more than it buys; above it, a flat band reads as a facet rather than as part of a ramp.
     * <b>This is a quality target, not a subdivision count</b> — see {@link #sampleSpacing}.</p>
     */
    private static final float LEVELS_PER_BAND = 2f;

    /** Floor and ceiling on bands across a shape, so neither a flat ramp nor a violent one runs away. */
    private static final int MIN_BANDS = 8;

    private static final int MAX_BANDS = 96;

    /**
     * The most cells one gradient fill may be cut into, before the spacing is relaxed to fit.
     *
     * <h3>Why a budget and not just a band count</h3>
     *
     * <p>The cuts are axis-aligned — horizontal bands, and slices across each band — so a gradient running
     * <b>diagonally</b> is approximated by a grid, and asking for N bands of quality costs N² cells rather
     * than N. Only O(N) of them carry new colour; the rest repeat a neighbour. Following the ramp's own
     * iso-lines would fix that properly and means cutting at angles the scanline does not produce.</p>
     *
     * <p>Left unbounded this bites hard: {@code htaccess}'s feather runs orange to crimson across its whole
     * diagonal, asked for the maximum in both axes, and came to <b>30,406 triangles for one 16px icon</b>.
     * The budget relaxes both spacings by the same factor until it fits, so the ramp stays smooth in the
     * direction that matters and simply gets coarser everywhere rather than failing in one place.</p>
     */
    public static final int CELL_BUDGET = 900;

    /**
     * Where {@code (x, y)} falls on the ramp, in {@code [0, 1]} after the spread method.
     *
     * @param box the shape's bounding box as {@code minX, minY, width, height} — only read when the
     *            gradient is in {@code objectBoundingBox} units, which is SVG's default
     */
    public float parameterAt(float x, float y, float[] box) {
        float ax1 = x1, ay1 = y1, ax2 = x2, ay2 = y2;
        if (!userSpace) {
            // objectBoundingBox: the axis is stated as a fraction of the shape's own box, so it has to be
            // resolved per shape rather than once at parse time -- the same gradient element legitimately
            // paints two shapes of different sizes.
            ax1 = box[0] + ax1 * box[2];
            ay1 = box[1] + ay1 * box[3];
            ax2 = box[0] + ax2 * box[2];
            ay2 = box[1] + ay2 * box[3];
        }
        // gradientTransform maps the gradient's space into the element's, so a sample point travels the
        // other way. Applied to the axis instead of inverting the matrix: for a linear gradient the two are
        // equivalent, and it avoids an inversion that a degenerate matrix would make undefined.
        if (transform != CgSvgTransform.IDENTITY) {
            float tx1 = transform.applyX(ax1, ay1), ty1 = transform.applyY(ax1, ay1);
            float tx2 = transform.applyX(ax2, ay2), ty2 = transform.applyY(ax2, ay2);
            ax1 = tx1;
            ay1 = ty1;
            ax2 = tx2;
            ay2 = ty2;
        }

        float t;
        if (radial) {
            // x1,y1 is the centre and x2 carries the radius; a focal point is ignored, which shifts the
            // highlight and never changes which colours appear.
            float radius = Math.max(1e-6f, ax2 - ax1);
            float dx = x - ax1, dy = y - ay1;
            t = (float) Math.sqrt(dx * dx + dy * dy) / radius;
        } else {
            float dx = ax2 - ax1, dy = ay2 - ay1;
            float lengthSq = dx * dx + dy * dy;
            if (lengthSq < 1e-9f) return 0f;
            t = ((x - ax1) * dx + (y - ay1) * dy) / lengthSq;
        }
        return spread(t);
    }

    /** {@link #spread} for a caller that has computed the raw projection itself. */
    public float spreadPublic(float t) {
        return spread(t);
    }

    private float spread(float t) {
        return switch (spread) {
            case SPREAD_REPEAT -> t - (float) Math.floor(t);
            case SPREAD_REFLECT -> {
                float wrapped = Math.abs(t) % 2f;
                yield wrapped > 1f ? 2f - wrapped : wrapped;
            }
            default -> Math.max(0f, Math.min(1f, t));
        };
    }

    /** The colour at a ramp position, interpolating between the two stops that bracket it. */
    public int colourAt(float t) {
        if (colours.length == 0) return 0xFF000000;
        if (colours.length == 1 || t <= offsets[0]) return colours[0];
        if (t >= offsets[offsets.length - 1]) return colours[colours.length - 1];
        for (int i = 0; i + 1 < offsets.length; i++) {
            if (t < offsets[i] || t > offsets[i + 1]) continue;
            float span = offsets[i + 1] - offsets[i];
            float local = span < 1e-6f ? 0f : (t - offsets[i]) / span;
            return CgSvgColor.mix(colours[i], colours[i + 1], local);
        }
        return colours[colours.length - 1];
    }

    public int colourAt(float x, float y, float[] box) {
        return colourAt(parameterAt(x, y, box));
    }

    /**
     * The axis in the shape's own space, as {@code x1, y1, x2, y2}, after units and
     * {@code gradientTransform} are resolved.
     *
     * <p>Exposed because a caller that wants to cut ALONG the ramp needs the direction, not just the
     * ability to sample it — see {@code CgSvgPainter}.</p>
     */
    public float[] effectiveAxis(float[] box) {
        float ax1 = x1, ay1 = y1, ax2 = x2, ay2 = y2;
        if (!userSpace) {
            ax1 = box[0] + ax1 * box[2];
            ay1 = box[1] + ay1 * box[3];
            ax2 = box[0] + ax2 * box[2];
            ay2 = box[1] + ay2 * box[3];
        }
        if (transform != CgSvgTransform.IDENTITY) {
            float tx1 = transform.applyX(ax1, ay1), ty1 = transform.applyY(ax1, ay1);
            float tx2 = transform.applyX(ax2, ay2), ty2 = transform.applyY(ax2, ay2);
            ax1 = tx1;
            ay1 = ty1;
            ax2 = tx2;
            ay2 = ty2;
        }
        return new float[]{ax1, ay1, ax2, ay2};
    }

    /**
     * This gradient restated in <b>plain user space</b>, against a shape's box.
     *
     * <p>usvg's conversion, and the reason {@link CgSvgScene} can promise that nothing downstream needs a
     * bounding box: {@code objectBoundingBox} units and {@code gradientTransform} are both folded into the
     * axis here, once, leaving a gradient whose numbers mean what they say. The same paint server
     * legitimately paints two shapes of different sizes, which is why this takes a box rather than being
     * done at parse time — the result belongs to the <em>use</em>, not to the element.</p>
     *
     * <p>Exact for both kinds. A radial gradient stores its centre in {@code (x1, y1)} and its radius as
     * {@code x2 - x1}, so mapping the two points maps the circle — and under a non-uniform transform that
     * circle is genuinely an ellipse, which this representation cannot hold and which
     * {@link #parameterAt} could not have expressed either. That is a pre-existing limit being carried
     * forward unchanged, not one introduced here.</p>
     */
    public CgSvgGradient resolvedAgainst(float[] box) {
        if (userSpace && transform == CgSvgTransform.IDENTITY) return this;
        float[] axis = effectiveAxis(box);
        return new CgSvgGradient(radial, true, CgSvgTransform.IDENTITY,
                axis[0], axis[1], axis[2], axis[3], offsets, colours, spread);
    }

    /**
     * How many bands a shape spanning {@code [t0, t1]} needs to approximate the ramp with FLAT cells.
     *
     * <p>Only the radial path still asks. A linear gradient is evaluated per pixel now, so its band count
     * comes from the stop offsets alone — see {@code CgSvgPainter}.</p>
     */
    public int bandCountFor(float t0, float t1) {
        return bandsFor(t0, t1);
    }

    /** The single colour this reduces to when it has to be one — used for strokes and as a fallback. */
    public int representativeColour() {
        return colourAt(0.5f);
    }

    /**
     * How far apart, in user units, two samples may sit before the step between them shows.
     *
     * <p>Returned per axis, and a zero means "this axis does not change the colour at all" — which is the
     * whole reason the caller asks: a horizontal gradient returns {@code 0} for y, so a tall shape is not
     * cut into a hundred bands to render a ramp that does not vary down it.</p>
     */
    public float[] sampleSpacing(float[] box) {
        float ax1 = x1, ay1 = y1, ax2 = x2, ay2 = y2;
        if (!userSpace) {
            ax1 = box[0] + ax1 * box[2];
            ay1 = box[1] + ay1 * box[3];
            ax2 = box[0] + ax2 * box[2];
            ay2 = box[1] + ay2 * box[3];
        }

        // How much of the ramp this shape actually spans, and how much colour moves across it. Both are
        // needed: a fixed band count is wrong at both ends -- a shape sitting in 10% of a long axis gets a
        // tenth of the bands and facets visibly, while a shape spanning a near-flat ramp gets hundreds it
        // cannot show.
        float[] range = parameterRange(box);
        float target = (range[1] - range[0]) / bandsFor(range[0], range[1]);
        if (target <= 0f) return new float[]{0f, 0f};

        if (radial) {
            float radius = Math.max(1e-6f, ax2 - ax1);
            float step = radius * target;
            return new float[]{step, step};
        }
        float dx = ax2 - ax1, dy = ay2 - ay1;
        float lengthSq = dx * dx + dy * dy;
        if (lengthSq < 1e-9f) return new float[]{0f, 0f};
        // dt/dx and dt/dy of the projection, inverted into "units per band".
        float perX = Math.abs(dx) / lengthSq;
        float perY = Math.abs(dy) / lengthSq;
        return new float[]{perX < 1e-9f ? 0f : target / perX, perY < 1e-9f ? 0f : target / perY};
    }

    /** The ramp positions of the shape's four bounding-box corners, as {@code [min, max]}. */
    private float[] parameterRange(float[] box) {
        float minT = Float.MAX_VALUE;
        float maxT = -Float.MAX_VALUE;
        for (int corner = 0; corner < 4; corner++) {
            float px = box[0] + ((corner & 1) == 0 ? 0f : box[2]);
            float py = box[1] + ((corner & 2) == 0 ? 0f : box[3]);
            float t = parameterAt(px, py, box);
            minT = Math.min(minT, t);
            maxT = Math.max(maxT, t);
        }
        // A shape entirely past one end of a `pad` ramp is one flat colour, and subdividing it would be
        // pure cost. The floor keeps a degenerate range from dividing by zero.
        return new float[]{minT, Math.max(maxT, minT + 1e-4f)};
    }

    /**
     * How many bands this shape needs, from the colour it actually traverses.
     *
     * <p>Walks the ramp over the shape's own span, sums the per-channel change, and asks for enough bands
     * that each carries at most {@link #LEVELS_PER_BAND}. That makes the cost track the visible problem:
     * a subtle blend gets the minimum, an orange-to-crimson feather gets what it needs, and neither is
     * decided by a constant that was right for one icon.</p>
     */
    private int bandsFor(float t0, float t1) {
        final int probes = 32;
        float total = 0f;
        int previous = colourAt(spread(t0));
        for (int i = 1; i <= probes; i++) {
            int current = colourAt(spread(t0 + (t1 - t0) * i / probes));
            total += Math.abs(((current >>> 16) & 0xFF) - ((previous >>> 16) & 0xFF))
                    + Math.abs(((current >>> 8) & 0xFF) - ((previous >>> 8) & 0xFF))
                    + Math.abs((current & 0xFF) - (previous & 0xFF));
            previous = current;
        }
        int bands = (int) Math.ceil(total / LEVELS_PER_BAND);
        return Math.max(MIN_BANDS, Math.min(MAX_BANDS, bands));
    }

    // ── Parsing ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * Builds a gradient from a tag's attributes and its already-collected stops.
     *
     * @param inherited the gradient this one points at with {@code href}, for geometry it does not state
     *                  itself; null when there is none
     */
    public static CgSvgGradient of(Map<String, String> attributes, float[] offsets, int[] colours,
                                 CgSvgGradient inherited) {
        boolean radial = "radialGradient".equals(attributes.get("__tag__"));
        String units = attributes.get("gradientUnits");
        boolean userSpace = units != null
                ? units.trim().equals("userSpaceOnUse")
                : inherited != null && inherited.userSpace();

        CgSvgTransform transform = attributes.containsKey("gradientTransform")
                ? CgSvgTransform.parse(attributes.get("gradientTransform"))
                : inherited != null ? inherited.transform() : CgSvgTransform.IDENTITY;

        float x1, y1, x2, y2;
        if (radial) {
            // Packed into the same four fields as a linear axis: centre in the first pair, radius in x2.
            // Two record shapes for what is one evaluation with two formulas would be a hierarchy earning
            // its keep only in the type checker.
            x1 = coordinate(attributes, "cx", inherited != null ? inherited.x1() : 0.5f, userSpace);
            y1 = coordinate(attributes, "cy", inherited != null ? inherited.y1() : 0.5f, userSpace);
            x2 = x1 + coordinate(attributes, "r", inherited != null ? inherited.x2() - inherited.x1() : 0.5f,
                    userSpace);
            y2 = y1;
        } else {
            x1 = coordinate(attributes, "x1", inherited != null ? inherited.x1() : 0f, userSpace);
            y1 = coordinate(attributes, "y1", inherited != null ? inherited.y1() : 0f, userSpace);
            x2 = coordinate(attributes, "x2", inherited != null ? inherited.x2() : 1f, userSpace);
            y2 = coordinate(attributes, "y2", inherited != null ? inherited.y2() : 0f, userSpace);
        }

        if (colours.length == 0 && inherited != null) {
            offsets = inherited.offsets();
            colours = inherited.colours();
        }
        String spreadRaw = attributes.get("spreadMethod");
        int spread = spreadRaw == null ? (inherited != null ? inherited.spread() : SPREAD_PAD)
                : switch (spreadRaw.trim()) {
                    case "reflect" -> SPREAD_REFLECT;
                    case "repeat" -> SPREAD_REPEAT;
                    default -> SPREAD_PAD;
                };
        return new CgSvgGradient(radial, userSpace, transform, x1, y1, x2, y2, offsets, colours, spread);
    }

    private static float coordinate(Map<String, String> attributes, String name,
                                    float fallback, boolean userSpace) {
        String raw = attributes.get(name);
        if (raw == null || raw.isBlank()) return fallback;
        String value = raw.trim();
        try {
            if (value.endsWith("%")) {
                float percent = Float.parseFloat(value.substring(0, value.length() - 1)) / 100f;
                // A percentage in objectBoundingBox units IS the fraction; in user space it is a percentage
                // of the viewport, which we do not track -- treating it as the fraction keeps it in range.
                return percent;
            }
            return Float.parseFloat(value);
        } catch (NumberFormatException malformed) {
            return fallback;
        }
    }
}
