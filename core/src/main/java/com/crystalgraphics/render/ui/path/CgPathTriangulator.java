package com.crystalgraphics.render.ui.path;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a set of closed contours into triangles, so a filled shape can be drawn.
 *
 * <h3>Trapezoid decomposition, not ear clipping</h3>
 *
 * <p>Ear clipping is the textbook answer and it is the wrong one here. It triangulates a <b>single simple
 * polygon</b> — and real artwork is neither single nor simple. A logo is several contours at once, its
 * counters and windows are holes that have to be <em>subtracted</em>, and exported paths self-intersect
 * routinely. Ear clipping fills a hole solid, which turns a ring into a disc and an "O" into a blob.</p>
 *
 * <p>So: slice the shape into horizontal bands at every vertex {@code y}, and within each band find where
 * the edges cross, sort those crossings, and apply the fill rule to decide which spans are inside. Each
 * inside span becomes a trapezoid — ONE cell, drawn as one instance that knows all four of its edges.</p>
 *
 * <h3>One cell per span, not two triangles</h3>
 *
 * <p>A cell's two walls are contour edges and get antialiased; its top and bottom are cuts shared with
 * the bands beside it and must stay a hard step. Split on its diagonal, each triangle knew one wall, and a
 * pixel on a cut within reach of the other wall was claimed at full coverage by the half that owned the
 * far one — a bright row across every band boundary, at every fractional scale. The cell reading of
 * {@code CgCurveBatch} takes all four corners, so there is no half that does not know a wall.</p>
 *
 * <p>Which edges are on the outline is decided here, once: the walls always are; a top or bottom is
 * only where the shape does not continue past it, which {@link Silhouettes} works out from the cells on
 * the other side of each cut — splitting a cell where the answer changes along its edge.</p>
 *
 * <p><b>This is exact, not an approximation.</b> The tempting objection is that horizontal bands must
 * stair-step a diagonal edge. They do not, because the bands are cut at <em>every</em> vertex: no vertex
 * can then fall inside a band, so within one band every edge is a straight run with no bend, and the
 * trapezoid's slanted sides lie exactly on it. Cutting at arbitrary intervals instead is what produces
 * stair-steps, and is the version of this algorithm people remember.</p>
 *
 * <h3>Both fill rules, for free</h3>
 *
 * <p>Once the crossings in a band are sorted, {@code evenodd} is "every other span" and {@code nonzero}
 * is "spans where the accumulated winding is not zero", run together across a crossing that keeps it non-zero
 * so a cell's walls are only ever the outline. They differ only in that walk, so supporting SVG's
 * actual default ({@code nonzero}) costs nothing — and getting it wrong inverts every hole in a file
 * authored with overlapping contours.</p>
 *
 * <h3>Self-intersection is handled, and it had to be</h3>
 *
 * <p>A crossing point is not a vertex, so nothing cuts a band there by default — and the band containing it
 * has its left and right walls trade places partway down. The trapezoid built from them is a bowtie: it
 * covers a wedge the shape does not occupy and leaves a matching wedge bare. On stacked artwork that reads
 * as smears of whatever is <em>underneath</em>, which looks like a paint bug rather than a geometry one.</p>
 *
 * <p>{@link Edges#selfIntersections} finds every crossing and adds its {@code y} to the band cuts, which is
 * all it takes: below the crossing the edges are in one order and above it the other, so no band ever
 * contains a swap. Not an edge case worth skipping — the JetBrains mark has four polygons that each fold
 * back over themselves.</p>
 *
 * <h3>Why both sweeps carry an active set — the cost that was actually being paid</h3>
 *
 * <p>Bands are cut at every vertex, so <b>a shape has about as many bands as edges</b>. The obvious way to
 * write either sweep is a nested loop — for each band, ask every edge whether it crosses; for each edge,
 * ask every other edge whether it meets it — and both are then quadratic in the vertex count. That is not a
 * pathological case, it is the normal one: a curve-heavy icon flattens to a few hundred edges and pays a
 * few hundred <em>squared</em>, which is where 54% of a document's load time was going.</p>
 *
 * <p>The fix is the same in both places and is the oldest trick in scanline rendering: an <b>active edge
 * table</b>. Sort the edges once by their top {@code y}; as the sweep descends, admit each edge when it
 * starts and retire it when it ends. Both boundaries are monotone in the sweep position, so every edge is
 * admitted once and retired once, and a band only ever touches the edges genuinely spanning it. That takes
 * the band sweep from {@code O(bands × edges)} to {@code O(n log n + crossings)} — and the crossing count
 * is the size of the output, so it cannot be beaten.</p>
 *
 * <p>{@link Edges#selfIntersections} gets the same treatment with the same argument: two edges can only
 * meet where their {@code y} ranges <em>overlap</em>, so the pairs worth testing are exactly the pairs
 * simultaneously live in the sweep. A bounding-box reject on {@code x} sits inside that, because the
 * intersection maths costs two divides and a box test costs two comparisons.</p>
 *
 * <p><b>Both prunes are exact, not heuristic</b>, and that distinction is the whole safety argument. An
 * intersection strictly interior to both edges lies at a single {@code y}, which must be strictly inside
 * both edges' {@code y} spans — so a pair whose spans do not overlap could not have contributed a crossing
 * to begin with, and skipping it changes nothing. The same holds for the box reject. This rewrite is
 * therefore a pure performance change: it emits <b>the same cells, in the same order, bit for bit</b>,
 * which is the property to check first if it is ever suspected of a visual regression.</p>
 *
 * <h3>Flat arrays, and why they are not premature</h3>
 *
 * <p>Edges and output cells are flat primitive arrays rather than {@code List<float[]>}. A moderately
 * complex icon reaches tens of thousands of cells, and the list form costs a {@code float[8]} and a boxed
 * {@code Integer} for each of them — allocation that outweighs the arithmetic it accompanies. The
 * parallel-array note on {@link #sortByX} already made this argument for the innermost loop; this is the
 * same argument applied to the two structures around it.</p>
 *
 * <p>Ported from CrystalGUI's {@code SvgTriangulator}, unchanged but for its names; {@link CgPathCells} draws its
 * cells.</p>
 */
public final class CgPathTriangulator {

    private CgPathTriangulator() {
    }

    /** Contours below this many points enclose no area and are skipped rather than fed to the sweep. */
    private static final int MINIMUM_POINTS = 3;

    private static final float EPSILON = 1e-5f;

    /**
     * Ceilings on gradient subdivision, per shape.
     *
     * <p>Not defensive padding — a bound on what one document can cost the draw loop. The spacing comes
     * from the gradient's axis, and a file is free to state an axis a thousandth the size of the shape it
     * paints; without a cap that is a shape asking for a million triangles, every frame, for a ramp with
     * two visible colours in it.</p>
     */
    private static final int MAX_SLICES = 64;

    /**
     * The most cells one fill may be cut into, shared out across however many bands it has.
     *
     * <h3>Why the cap has to know the band count</h3>
     *
     * <p>The cuts are axis-aligned, so a gradient running <b>diagonally</b> is approximated by a grid and
     * asking for N bands of quality costs N² cells rather than N. Only O(N) of them carry new colour.</p>
     *
     * <p>Capping the two axes independently does not bound that. A curve-heavy outline already arrives
     * with hundreds of natural bands — one per flattened vertex — and giving each of them {@link
     * #MAX_SLICES} slices is what took {@code htaccess}'s feather to <b>30,406 triangles for one 16px
     * icon</b>. Dividing the budget by the bands that actually exist is the only place the product is
     * visible, which is why this lives here and not with the spacing that requested it.</p>
     *
     * <h3>Why 900, and why it is a ceiling rather than an answer</h3>
     *
     * <p>A mesh is built once and cached <em>scale-free</em>, so the number has to be chosen for the size
     * the artwork is actually drawn at. <b>A 16px icon can display at most sixteen bands</b> — anything
     * finer is subdivision no display can resolve. 900 cells is a 30×30 grid: smooth well past the sizes
     * a file tree or a toolbar uses, and enough headroom that a moderate zoom does not fall apart.</p>
     *
     * <p>It does fall apart eventually. Zoomed to several hundred pixels, a diagonal ramp facets into a
     * visible mosaic, and no CPU subdivision fixes that without a mesh per zoom level. The real answer
     * there is a paint-server shader — the instance record already carries {@code color0}/{@code color1}
     * and the fragment already mixes them, but a fill forces {@code t = 0} and there is nowhere in the
     * record to put a gradient axis. Widening it is a backend change, and one worth making only if
     * something needs large gradient artwork.</p>
     */
    private static final int MAX_CELLS = 3000;

    private static final int MAX_EXTRA_BANDS = 192;

    /**
     * Above this edge count the crossing search is skipped entirely.
     *
     * <p>The search is no longer quadratic in the general case — see the class note on the active set — but
     * its worst case still is, and the worst case is reachable: a shape whose every edge spans its whole
     * height leaves every pair simultaneously live and no prune can help. The cap bounds that.</p>
     *
     * <p>It is deliberately unchanged from the value the quadratic version used. Raising it would find
     * crossings that are currently missed and would therefore <em>change</em> the mesh on exactly the
     * documents most likely to be sensitive to it — a separate decision, to be made on its own evidence,
     * and not something to fold into a rewrite whose whole claim is that the output is identical.</p>
     */
    private static final int MAX_INTERSECTION_EDGES = 1200;

    /**
     * Edge bits of {@link Fill#edges}: the cell's top ({@code p0->p1}), right, bottom and left edges.
     * The same values as {@code CgCurveBatch.CELL_*}, which the draw hands them to untranslated;
     * {@link CgPathCells} hands them to the cell primitive untranslated.
     */
    public static final int TOP = 1, RIGHT = 2, BOTTOM = 4, LEFT = 8;

    /**
     * Fills a set of contours.
     *
     * <p>Every contour is implicitly closed, whether or not the path said {@code Z} — that is SVG's own
     * rule for filling, and it is why an unclosed subpath still paints a solid shape.</p>
     *
     * @param evenOdd {@code true} for {@code fill-rule: evenodd}, {@code false} for {@code nonzero}
     * @return eight floats per cell — see {@link Fill#cells}; empty for anything with no area
     */
    public static float[] fill(List<List<float[]>> contours, boolean evenOdd) {
        return fill(contours, evenOdd, 0f, 0f).cells();
    }

    /**
     * A filled mesh: one cell per span, each knowing which of its edges are on the outline.
     *
     * @param cells eight floats per cell — {@code x0,y0} top-left, {@code x1,y1} top-right, {@code x2,y2}
     *              bottom-right, {@code x3,y3} bottom-left, in the space the contours came in. A cell at
     *              a tip has a collapsed top or bottom, which is legal: a zero-length edge constrains
     *              nothing
     * @param slice {@code slice[i]} is the index of the trapezoid slice cell {@code i} came from, so a
     *              caller colouring per slice gives every piece of a split cell the same colour
     * @param edges {@code edges[i]} is a mask of {@link #TOP}, {@link #RIGHT}, {@link #BOTTOM},
     *              {@link #LEFT} — the edges of cell {@code i} that lie on the shape's outline. The rest
     *              are seams shared with a neighbouring cell
     */
    public record Fill(float[] cells, int[] slice, int[] edges) {

        public int count() {
            return slice.length;
        }
    }

    /**
     * Fills a set of contours, cut fine enough that a colour ramp per cell passes for a gradient.
     *
     * <p>The two spacings are what keep that affordable. A gradient's colour varies along <em>one</em>
     * direction, so cutting uniformly wastes almost all of the cells: a horizontal ramp passes
     * {@code stepY = 0} and a shape of any height stays at its handful of natural bands. See
     * a gradient's sample spacing.</p>
     *
     * @param stepX how far apart, in the contours' own units, two samples may sit across the shape before
     *              the step between them shows; {@code 0} for no horizontal subdivision
     * @param stepY the same down the shape; {@code 0} for no extra bands beyond the vertex cuts
     */
    public static Fill fill(List<List<float[]>> contours, boolean evenOdd,
                            float stepX, float stepY) {
        return fill(contours, evenOdd, stepX, stepY, null);
    }

    /**
     * @param extraCuts additional band boundaries in {@code y}, on top of the vertex cuts. A caller that
     *                  needs a band to stop at a particular line — a gradient stop, say — states it here
     *                  rather than approximating it with a fine {@code stepY}, which would cut everywhere
     *                  to get one cut in the right place
     */
    public static Fill fill(List<List<float[]>> contours, boolean evenOdd,
                            float stepX, float stepY, float[] extraCuts) {
        return Silhouettes.mark(cells(contours, evenOdd, stepX, stepY, extraCuts));
    }

    /**
     * The sweep alone: every wall soft, every cut hard, nothing split. What {@link Silhouettes#mark}
     * refines.
     */
    static Fill cells(List<List<float[]>> contours, boolean evenOdd,
                      float stepX, float stepY, float[] extraCuts) {
        Edges edges = Edges.of(contours);
        if (edges.count == 0) return empty();

        float[] bands = edges.bandBoundaries(stepY, extraCuts);
        if (bands.length < 2) return empty();

        return edges.sweep(bands, evenOdd, stepX);
    }

    private static Fill empty() {
        return new Fill(new float[0], new int[0], new int[0]);
    }

    /**
     * The shape reduced to non-horizontal edges, in flat arrays, plus the sweep that consumes them.
     *
     * <p>Horizontal edges are dropped at build time — they can never cross a band's midline, so keeping
     * them only costs a test per band per edge. {@code lo}/{@code hi} are precomputed for the same reason:
     * the sweep asks for them far more often than there are edges.</p>
     */
    private static final class Edges {

        final float[] x0, y0, x1, y1;
        /** Per edge, its {@code y} span — {@code lo} is where it enters the sweep, {@code hi} where it leaves. */
        final float[] lo, hi;
        final int count;
        /** Edge indices ordered by {@link #lo}, so both sweeps can admit edges as they reach them. */
        final int[] byLo;

        private Edges(float[] x0, float[] y0, float[] x1, float[] y1, int count) {
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
            this.count = count;
            this.lo = new float[count];
            this.hi = new float[count];
            for (int i = 0; i < count; i++) {
                lo[i] = Math.min(y0[i], y1[i]);
                hi[i] = Math.max(y0[i], y1[i]);
            }
            this.byLo = orderBy(lo, count);
        }

        static Edges of(List<List<float[]>> contours) {
            int total = 0;
            for (List<float[]> contour : contours) {
                int n = contour.size();
                if (n < MINIMUM_POINTS) continue;
                for (int i = 0; i < n; i++) {
                    if (Math.abs(contour.get(i)[1] - contour.get((i + 1) % n)[1]) < EPSILON) continue;
                    total++;
                }
            }
            float[] x0 = new float[total], y0 = new float[total];
            float[] x1 = new float[total], y1 = new float[total];
            int at = 0;
            for (List<float[]> contour : contours) {
                int n = contour.size();
                if (n < MINIMUM_POINTS) continue;
                for (int i = 0; i < n; i++) {
                    float[] a = contour.get(i);
                    float[] b = contour.get((i + 1) % n);
                    if (Math.abs(a[1] - b[1]) < EPSILON) continue;
                    x0[at] = a[0];
                    y0[at] = a[1];
                    x1[at] = b[0];
                    y1[at] = b[1];
                    at++;
                }
            }
            return new Edges(x0, y0, x1, y1, total);
        }

        /**
         * The band sweep, carrying an active edge table.
         *
         * <p>Bands ascend, so an edge's admission ({@code lo <= middle}) and its retirement
         * ({@code hi <= middle}) are each crossed once and never uncrossed. {@code next} walks
         * {@link #byLo} for the first; the compaction pass that gathers crossings performs the second, for
         * free, since it is already visiting every active edge.</p>
         *
         * <p>A band skipped for being thinner than {@link #EPSILON} does not disturb that: neither pointer
         * moves, and the following band's midpoint is strictly larger, so the {@code while} catches up.</p>
         */
        Fill sweep(float[] bands, boolean evenOdd, float stepX) {
            Sink sink = new Sink();
            int sliceIndex = 0;
            // Shared out now that the bands are known. A shape whose outline already supplies three hundred
            // bands gets a handful of slices each; a rectangle with two bands may have the lot.
            int sliceAllowance = Math.max(1, MAX_CELLS / Math.max(1, bands.length - 1));

            float[] crossX = new float[count];
            int[] crossDir = new int[count];
            int[] crossEdge = new int[count];
            int[] active = new int[count];
            int activeCount = 0;
            int next = 0;

            for (int band = 0; band + 1 < bands.length; band++) {
                float top = bands[band];
                float bottom = bands[band + 1];
                if (bottom - top < EPSILON) continue;
                float middle = (top + bottom) * 0.5f;

                while (next < count && lo[byLo[next]] <= middle) active[activeCount++] = byLo[next++];

                int found = 0;
                int keep = 0;
                for (int a = 0; a < activeCount; a++) {
                    int e = active[a];
                    if (hi[e] <= middle) continue;
                    active[keep++] = e;
                    crossX[found] = xAt(e, middle);
                    crossDir[found] = y1[e] > y0[e] ? 1 : -1;
                    crossEdge[found] = e;
                    found++;
                }
                activeCount = keep;
                if (found < 2) continue;
                sortByX(crossX, crossDir, crossEdge, found);

                int winding = 0;
                for (int i = 0; i + 1 < found; i++) {
                    winding += evenOdd ? 1 : crossDir[i];
                    boolean inside = evenOdd ? (winding & 1) == 1 : winding != 0;
                    if (!inside) continue;

                    // Under nonzero a crossing that leaves the winding non-zero is inside the shape (contours
                    // overlapping, a stroke's outline at a joint), so the span runs on to the next crossing
                    // that leaves it: a wall there would be antialiased from both sides into a hairline.
                    int j = i + 1;
                    if (!evenOdd) {
                        while (j + 1 < found && winding + crossDir[j] != 0) winding += crossDir[j++];
                    }
                    int left = crossEdge[i];
                    int right = crossEdge[j];
                    i = j - 1;
                    float lt = xAt(left, top), lb = xAt(left, bottom);
                    float rt = xAt(right, top), rb = xAt(right, bottom);
                    if (Math.abs(rt - lt) < EPSILON && Math.abs(rb - lb) < EPSILON) continue;

                    // Sliced by interpolating BETWEEN the two walls rather than by cutting at fixed x. A
                    // vertical cut through a slanted trapezoid produces pieces that are no longer trapezoids
                    // wherever the cut leaves the shape partway down the band; lerping the walls keeps every
                    // piece the same shape as the whole, so the slicing needs no clipping and no special case.
                    float widest = Math.max(Math.abs(rt - lt), Math.abs(rb - lb));
                    int slices = stepX > 0f
                            ? Math.max(1, Math.min(Math.min(MAX_SLICES, sliceAllowance),
                                    (int) Math.ceil(widest / stepX)))
                            : 1;
                    for (int s = 0; s < slices; s++) {
                        // A band sliced into N cells has one contour edge at each end and N-1 seams in
                        // between; only the contour may be antialiased. Feathering a seam fades it from
                        // one side against a neighbour that steps hard there, and the coverage never
                        // reaches 1 -- a line down every slice boundary.
                        int edges = (s == 0 ? LEFT : 0) | (s == slices - 1 ? RIGHT : 0);
                        float a = (float) s / slices, b = (float) (s + 1) / slices;
                        float at = lt + (rt - lt) * a, ab = lb + (rb - lb) * a;
                        float bt = lt + (rt - lt) * b, bb = lb + (rb - lb) * b;
                        sink.add(sliceIndex, edges, at, top, bt, top, bb, bottom, ab, bottom);
                        sliceIndex++;
                    }
                }
            }
            return sink.toFill();
        }

        /**
         * Every {@code y} at which the shape's edge ordering can change, ascending.
         *
         * <p>Three sources, and all three are load-bearing:</p>
         *
         * <ol>
         *   <li><b>Vertex {@code y}s</b> — what makes the decomposition exact rather than stair-stepped. No
         *       vertex may fall inside a band, or an edge bends where the trapezoid cannot follow it.</li>
         *   <li><b>Self-intersection {@code y}s</b> — see {@link #selfIntersections}.</li>
         *   <li><b>Extra cuts at {@code stepY}</b>, for gradient subdivision. <b>Added to</b> the first two,
         *       never substituted for them.</li>
         * </ol>
         */
        float[] bandBoundaries(float stepY, float[] extraCuts) {
            int extra = 0;
            float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            if (stepY > 0f) {
                for (int i = 0; i < count; i++) {
                    minY = Math.min(minY, lo[i]);
                    maxY = Math.max(maxY, hi[i]);
                }
                extra = Math.min(MAX_EXTRA_BANDS, (int) Math.ceil((maxY - minY) / stepY));
            }

            float[] crossings = selfIntersections();
            int explicit = extraCuts == null ? 0 : extraCuts.length;
            float[] all = new float[count * 2 + Math.max(0, extra) + crossings.length + explicit];
            for (int i = 0; i < count; i++) {
                all[i * 2] = y0[i];
                all[i * 2 + 1] = y1[i];
            }
            int at = count * 2;
            for (int i = 0; i < extra; i++) all[at++] = minY + (maxY - minY) * (i + 0.5f) / extra;
            for (float y : crossings) all[at++] = y;
            for (int i = 0; i < explicit; i++) all[at++] = extraCuts[i];

            Arrays.sort(all);
            float[] unique = new float[all.length];
            int unifiedCount = 0;
            for (float value : all) {
                if (unifiedCount == 0 || value - unique[unifiedCount - 1] > EPSILON) {
                    unique[unifiedCount++] = value;
                }
            }
            return Arrays.copyOf(unique, unifiedCount);
        }

        /**
         * Every {@code y} where two edges of the shape cross each other.
         *
         * <p><b>This is what makes a self-intersecting contour come out right, and it is not an edge
         * case.</b> Exported logo artwork is full of them: the JetBrains mark has four polygons of seven and
         * eight points that each fold back over themselves. Without a cut at the crossing, one band contains
         * the swap — its left and right walls trade places partway down — and the trapezoid built from them
         * is a bowtie covering a wedge of space the shape does not occupy, while leaving a matching wedge
         * that it does bare. On a stack of overlapping polygons that reads as smears of the colour
         * <em>underneath</em> showing through, which looks like a paint bug rather than a geometry one.</p>
         *
         * <p>Cutting there is enough. Below the crossing the two edges are in one order and above it the
         * other; within each band they never swap, so the ordinary sweep is exact again. There is no need to
         * split the edges themselves or to know where the crossing is in {@code x} — the per-band midpoint
         * sort finds that on its own.</p>
         *
         * <h3>Which pairs are worth testing</h3>
         *
         * <p>An intersection strictly interior to both edges sits at one {@code y}, and each edge is
         * non-horizontal, so its parameter is monotone in {@code y} — the crossing's {@code y} therefore
         * lies strictly inside <em>both</em> spans. Two edges whose spans do not overlap cannot produce one.
         * Sweeping by {@code lo} and keeping an active set tests exactly the overlapping pairs and no
         * others, which is why this is a prune rather than an approximation.</p>
         *
         * <p>The lower-indexed edge is always taken as {@code a}. The intersection is symmetric in exact
         * arithmetic and not in floats, and the answer feeds a list that is deduplicated by
         * {@link #EPSILON} — so fixing the roles keeps the output identical to the naive {@code i < j}
         * order rather than merely equivalent to it.</p>
         */
        float[] selfIntersections() {
            if (count < 2 || count > MAX_INTERSECTION_EDGES) return new float[0];

            float[] found = new float[64];
            int size = 0;
            int[] active = new int[count];
            int activeCount = 0;

            for (int k = 0; k < count; k++) {
                int current = byLo[k];
                float enters = lo[current];
                float currentMinX = Math.min(x0[current], x1[current]);
                float currentMaxX = Math.max(x0[current], x1[current]);

                int keep = 0;
                for (int a = 0; a < activeCount; a++) {
                    int other = active[a];
                    if (hi[other] <= enters) continue;
                    active[keep++] = other;

                    // Boxes first: two comparisons against two divides.
                    if (currentMaxX < Math.min(x0[other], x1[other])
                            || Math.max(x0[other], x1[other]) < currentMinX) continue;

                    int i = Math.min(current, other), j = Math.max(current, other);
                    float ax = x1[i] - x0[i], ay = y1[i] - y0[i];
                    float bx = x1[j] - x0[j], by = y1[j] - y0[j];
                    float denominator = ax * by - ay * bx;
                    if (Math.abs(denominator) < 1e-9f) continue;      // parallel, or both degenerate

                    float dx = x0[j] - x0[i], dy = y0[j] - y0[i];
                    float t = (dx * by - dy * bx) / denominator;
                    float u = (dx * ay - dy * ax) / denominator;
                    // Strictly interior on both: edges that merely share an endpoint are every adjacent pair
                    // in the contour, and their shared vertex is already a band boundary.
                    if (t <= EPSILON || t >= 1f - EPSILON || u <= EPSILON || u >= 1f - EPSILON) continue;

                    if (size == found.length) found = Arrays.copyOf(found, size * 2);
                    found[size++] = y0[i] + ay * t;
                }
                activeCount = keep;
                active[activeCount++] = current;
            }
            return Arrays.copyOf(found, size);
        }

        private float xAt(int edge, float y) {
            float dy = y1[edge] - y0[edge];
            // Guarded even though horizontal edges never reach here: a denormal dy would otherwise produce
            // an infinity that propagates into the vertex buffer and takes the whole draw with it.
            if (Math.abs(dy) < 1e-9f) return x0[edge];
            // The endpoint itself at a cut through it (to the cut merge's EPSILON): the cells either side
            // reach it through different edges, and a seam whose two copies differ by an ulp drops every
            // pixel centre lying on it, which a 45-degree seam at a whole scale does along its length.
            if (Math.abs(y - y0[edge]) < EPSILON) return x0[edge];
            if (Math.abs(y - y1[edge]) < EPSILON) return x1[edge];
            return x0[edge] + (x1[edge] - x0[edge]) * (y - y0[edge]) / dy;
        }
    }

    /**
     * The growing mesh.
     *
     * <p>Doubling flat arrays rather than a {@code List<float[]>} plus boxed tags. A gradient-heavy icon
     * reaches tens of thousands of triangles and the list form allocates four objects for each of them,
     * which costs more than the arithmetic that produced it.</p>
     */
    private static final class Sink {

        private float[] cells = new float[8 * 64];
        private int[] slice = new int[64];
        private int[] edges = new int[64];
        private int count;

        /** Appends one cell, degenerate or not: a tip is a cell with a collapsed top or bottom. */
        void add(int sliceIndex, int edgeMask,
                 float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3) {
            if (count == slice.length) {
                cells = Arrays.copyOf(cells, cells.length * 2);
                slice = Arrays.copyOf(slice, slice.length * 2);
                edges = Arrays.copyOf(edges, edges.length * 2);
            }
            int at = count * 8;
            cells[at] = x0;
            cells[at + 1] = y0;
            cells[at + 2] = x1;
            cells[at + 3] = y1;
            cells[at + 4] = x2;
            cells[at + 5] = y2;
            cells[at + 6] = x3;
            cells[at + 7] = y3;
            slice[count] = sliceIndex;
            edges[count] = edgeMask;
            count++;
        }

        Fill toFill() {
            return new Fill(Arrays.copyOf(cells, count * 8),
                    Arrays.copyOf(slice, count), Arrays.copyOf(edges, count));
        }
    }

    /**
     * Decides which cell tops and bottoms are on the outline, and splits a cell where the answer changes
     * along its edge.
     *
     * <p>A cut is a horizontal line the whole shape is sliced at. Where the shape continues across it the
     * cut is a seam between two cells and must stay a hard step — softening it fades the boundary from
     * both sides into a visible line. Where the shape stops at it — a horizontal contour edge — it is a
     * silhouette and wants the same antialiasing as a wall, or a horizontal edge sitting between pixel
     * rows draws as a full row of ink at every fractional scale.</p>
     *
     * <p>One cell's top can be both: a step in the outline leaves the shape continuing above part of the
     * edge and not the rest. The cell is cut where the coverage changes, along a line to the same fraction
     * of its bottom edge — the slicing rule, which keeps every piece a trapezoid — and each piece gets the
     * one answer that holds along its whole edge.</p>
     */
    static final class Silhouettes {

        private Silhouettes() {
        }

        static Fill mark(Fill cells) {
            int count = cells.count();
            if (count == 0) return cells;
            float[] q = cells.cells();

            // The spans each cut is covered by, from the cells above it (their bottoms) and below it (their
            // tops). Keyed on the exact float: every cell on a cut took its y from the same bands[] entry.
            Map<Float, List<float[]>> bottomsAt = new HashMap<>();
            Map<Float, List<float[]>> topsAt = new HashMap<>();
            for (int i = 0; i < count; i++) {
                int at = i * 8;
                topsAt.computeIfAbsent(q[at + 1], k -> new ArrayList<>())
                        .add(new float[]{Math.min(q[at], q[at + 2]), Math.max(q[at], q[at + 2])});
                bottomsAt.computeIfAbsent(q[at + 5], k -> new ArrayList<>())
                        .add(new float[]{Math.min(q[at + 6], q[at + 4]), Math.max(q[at + 6], q[at + 4])});
            }

            Sink out = new Sink();
            float[] fractions = new float[16];
            for (int i = 0; i < count; i++) {
                int at = i * 8;
                float x0 = q[at], top = q[at + 1], x1 = q[at + 2];
                float x2 = q[at + 4], bottom = q[at + 5], x3 = q[at + 6];
                int walls = cells.edges()[i];

                List<float[]> above = bottomsAt.get(top);
                List<float[]> below = topsAt.get(bottom);

                // Where the coverage changes along the top and along the bottom, as fractions across the
                // cell, merged and sorted. Most cells produce none.
                int capacity = 2 + 2 * ((above == null ? 0 : above.size()) + (below == null ? 0 : below.size()));
                if (fractions.length < capacity) fractions = new float[capacity];
                int n = 0;
                fractions[n++] = 0f;
                fractions[n++] = 1f;
                n = boundaries(above, x0, x1, fractions, n);
                n = boundaries(below, x3, x2, fractions, n);
                Arrays.sort(fractions, 0, n);

                for (int k = 0; k + 1 < n; k++) {
                    float a = fractions[k], b = fractions[k + 1];
                    if (b - a < EPSILON) continue;
                    float at0 = x0 + (x1 - x0) * a, bt0 = x0 + (x1 - x0) * b;
                    float ab0 = x3 + (x2 - x3) * a, bb0 = x3 + (x2 - x3) * b;
                    int edges = walls;
                    if (a > 0f) edges &= ~LEFT;
                    if (b < 1f) edges &= ~RIGHT;
                    if (!covered(above, (at0 + bt0) * 0.5f)) edges |= TOP;
                    if (!covered(below, (ab0 + bb0) * 0.5f)) edges |= BOTTOM;
                    out.add(cells.slice()[i], edges, at0, top, bt0, top, bb0, bottom, ab0, bottom);
                }
            }
            return out.toFill();
        }

        /** Whether any span in {@code spans} contains {@code x}. */
        private static boolean covered(List<float[]> spans, float x) {
            if (spans == null) return false;
            for (float[] span : spans) {
                if (x > span[0] - EPSILON && x < span[1] + EPSILON) return true;
            }
            return false;
        }

        /**
         * Appends, as fractions of {@code [from, to]}, every span endpoint strictly inside it where the
         * coverage genuinely changes — the boundary between two abutting spans is a seam between two
         * cells on the far side, not a change.
         */
        private static int boundaries(List<float[]> spans, float from, float to, float[] out, int n) {
            if (spans == null) return n;
            float width = to - from;
            if (Math.abs(width) < EPSILON) return n;
            for (float[] span : spans) {
                for (float x : span) {
                    float f = (x - from) / width;
                    if (f <= EPSILON || f >= 1f - EPSILON) continue;
                    if (coveredOutside(spans, span, x)) continue;
                    out[n++] = f;
                }
            }
            return n;
        }

        /** Whether a span other than {@code self} covers {@code x}. */
        private static boolean coveredOutside(List<float[]> spans, float[] self, float x) {
            for (float[] span : spans) {
                if (span == self) continue;
                if (x > span[0] - EPSILON && x < span[1] + EPSILON) return true;
            }
            return false;
        }
    }

    /**
     * Indices of {@code keys[0..count)}, ascending by key, ties broken by index.
     *
     * <p>Packed into a {@code long} — sortable key high, index low — so this is one {@code Arrays.sort}
     * over primitives rather than a boxed comparator. {@link #sortableBits} is the standard IEEE-754
     * total-order transform: it leaves non-negative floats alone and flips the magnitude bits of negative
     * ones, which is what makes descending negatives compare as ascending ints.</p>
     */
    private static int[] orderBy(float[] keys, int count) {
        long[] packed = new long[count];
        for (int i = 0; i < count; i++) {
            packed[i] = ((long) sortableBits(keys[i]) << 32) | (i & 0xFFFFFFFFL);
        }
        Arrays.sort(packed);
        int[] order = new int[count];
        for (int i = 0; i < count; i++) order[i] = (int) packed[i];
        return order;
    }

    private static int sortableBits(float value) {
        int bits = Float.floatToIntBits(value);
        return bits ^ ((bits >> 31) & 0x7FFFFFFF);
    }

    /**
     * Insertion sort over the three parallel arrays, ascending by {@code x} and then by edge index.
     *
     * <p>Parallel primitive arrays rather than a list of crossing objects because this runs once per band
     * per fill, which is the only part of the whole loader that is genuinely hot — a logo with a few
     * hundred flattened vertices is a few hundred bands, and allocating a crossing object for each edge in
     * each of them is the difference between a load you notice and one you do not. Insertion sort because
     * a band typically has two crossings and rarely more than a dozen.</p>
     *
     * <p><b>The index tiebreak is not cosmetic.</b> Crossings used to be gathered by walking every edge in
     * order, so a stable sort left equal {@code x} in index order for free. They now arrive in active-set
     * order, which is admission order — so the tiebreak is what keeps two coincident crossings in the same
     * relation as before, and with them the winding walk that reads them.</p>
     */
    private static void sortByX(float[] x, int[] dir, int[] edge, int count) {
        for (int i = 1; i < count; i++) {
            float keyX = x[i];
            int keyDir = dir[i], keyEdge = edge[i];
            int j = i - 1;
            while (j >= 0 && (x[j] > keyX || (x[j] == keyX && edge[j] > keyEdge))) {
                x[j + 1] = x[j];
                dir[j + 1] = dir[j];
                edge[j + 1] = edge[j];
                j--;
            }
            x[j + 1] = keyX;
            dir[j + 1] = keyDir;
            edge[j + 1] = keyEdge;
        }
    }
}
