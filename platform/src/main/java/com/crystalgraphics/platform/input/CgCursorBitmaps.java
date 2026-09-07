package com.crystalgraphics.platform.input;

/**
 * Procedurally drawn cursor bitmaps, 32×32 ARGB.
 *
 * <p><b>Lives in {@code platform/} despite containing no platform code at all</b> — it is integer pixel
 * maths and would compile anywhere. It sits here because of who needs it: every LWJGL2 loader (the
 * harness, MC 1.7.10) has to hand raw pixels to {@code Mouse.setNativeCursor}, and a loader implements
 * {@link com.crystalgraphics.platform.service.CgCursorService} against this module alone. Shipping the
 * artwork beside the interface that consumes it leaves each loader with only a ~90-line adapter to write
 * rather than the drawings as well.</p>
 *
 *
 * <p>Generated rather than shipped as PNGs. The shapes are a handful of arrows built from straight
 * runs and triangles — a few dozen lines of drawing code against binary assets that would need
 * authoring, packing, loading, and a hotspot table maintained alongside them. Doing it in code also
 * keeps the hotspot next to the geometry that defines it, which is the thing most likely to drift.</p>
 *
 * <p>Every shape is drawn as a <b>white body with a black outline</b>, for the same reason every OS
 * cursor is: a single-colour cursor disappears against a UI of that colour. The outline is generated
 * from the body rather than drawn separately, so the two can never disagree.</p>
 *
 * <p><b>Origin is top-left here.</b> LWJGL2 wants cursor images bottom-up with the hotspot measured
 * from the bottom, and that conversion happens once, in {@code Lwjgl2CursorService} — not in these
 * functions, which would otherwise all have to be read upside-down.</p>
 */
public final class CgCursorBitmaps {

    public static final int SIZE = 32;
    /** Centre pixel — the hotspot for every symmetric shape here. */
    public static final int HOTSPOT = SIZE / 2;

    /**
     * The fingertip of {@link #pointingHand()}, which is <b>not</b> the centre.
     *
     * <p>Every other bitmap here is a symmetric arrow whose hotspot genuinely is its middle. A hand is not:
     * it points, and the click has to land where the finger does. Kept near the top so the two numbers that
     * must agree — where the finger is drawn and where the platform anchors the image — sit together.</p>
     */
    public static final int HAND_HOTSPOT_X = 10;
    public static final int HAND_HOTSPOT_Y = 4;

    private static final int TRANSPARENT = 0x00000000;
    private static final int BODY = 0xFFFFFFFF;
    private static final int OUTLINE = 0xFF000000;

    private CgCursorBitmaps() {
    }

    /** ↔ — a horizontal double-headed arrow. {@code ew-resize}, and SplitView's divider. */
    public static int[] horizontalDoubleArrow() {
        boolean[] body = new boolean[SIZE * SIZE];
        shaft(body, true);
        arrowHead(body, 4, HOTSPOT, +1, true);
        arrowHead(body, SIZE - 5, HOTSPOT, -1, true);
        return outline(body);
    }

    /** ↕ — {@code ns-resize}. */
    public static int[] verticalDoubleArrow() {
        boolean[] body = new boolean[SIZE * SIZE];
        shaft(body, false);
        arrowHead(body, HOTSPOT, 4, +1, false);
        arrowHead(body, HOTSPOT, SIZE - 5, -1, false);
        return outline(body);
    }

    /** ↖↘ — {@code nwse-resize}. */
    public static int[] diagonalNwseArrow() {
        return diagonal(true);
    }

    /** ↗↙ — {@code nesw-resize}. */
    public static int[] diagonalNeswArrow() {
        return diagonal(false);
    }

    /** ✛ — a four-way arrow. {@code move}, for a dialog's title bar. */
    public static int[] fourWayArrow() {
        boolean[] body = new boolean[SIZE * SIZE];
        shaft(body, true);
        shaft(body, false);
        arrowHead(body, 4, HOTSPOT, +1, true);
        arrowHead(body, SIZE - 5, HOTSPOT, -1, true);
        arrowHead(body, HOTSPOT, 4, +1, false);
        arrowHead(body, HOTSPOT, SIZE - 5, -1, false);
        return outline(body);
    }

    /**
     * ◄ ► — two opposed triangles with a clear gap between them. {@link CgCursor#SLIDE_ARROW}: this
     * value can be dragged sideways to change it.
     *
     * <h3>Deliberately no shaft, and that is the whole distinction</h3>
     * <p>{@link #horizontalDoubleArrow()} is the same two heads joined by a bar, and it means "this edge
     * moves" — a divider, a resize handle. Bare heads with a gap mean "this <em>value</em> moves". The two
     * gestures land on adjacent pixels in a node editor (a resizable panel containing scrubbable numbers),
     * so they have to be tellable apart at a glance, which a shared shape would not be.</p>
     *
     * <p>Kept off-centre from the middle by a wider gap than the arrow heads alone would give: the pointer
     * sits at the hotspot, in that gap, and a triangle drawn under the cursor tip reads as one wide blob
     * rather than as two directions.</p>
     */
    public static int[] slideArrow() {
        boolean[] body = new boolean[SIZE * SIZE];
        arrowHead(body, 7, HOTSPOT, +1, true);
        arrowHead(body, SIZE - 8, HOTSPOT, -1, true);
        return outline(body);
    }

    /** An I-beam. {@code text} — what {@code cursor: auto} resolves to over an editable element. */
    public static int[] textBeam() {
        boolean[] body = new boolean[SIZE * SIZE];
        for (int y = 6; y < SIZE - 6; y++) plot(body, HOTSPOT, y);
        for (int x = HOTSPOT - 3; x <= HOTSPOT + 3; x++) {
            plot(body, x, 6);
            plot(body, x, SIZE - 7);
        }
        return outline(body);
    }

    /**
     * A pointing hand drawn for <b>1-bit transparency</b> — blocks and a derived outline, no anti-aliasing.
     *
     * <p>Kept as the fallback for displays that cannot show cursor alpha, and kept <em>separately</em> rather
     * than being replaced, so the pixel-art look can be switched back to wholesale if it turns out to read
     * better in-game than {@link #pointingHand()} does. The two are the same shape; only the rasterisation
     * differs.</p>
     *
     * <p><b>Its hotspot is the fingertip, not the centre</b>, which every other bitmap here uses. That is
     * not a quirk of the drawing: a hand cursor points, and a user aims the fingertip. Centring it would put
     * the click point half a cursor below where the picture says it is. Hence {@link #HAND_HOTSPOT_X}/
     * {@link #HAND_HOTSPOT_Y} and a per-shape hotspot in the platform adapters.</p>
     *
     * <p>Built from blocks rather than an arrow-style outline pass, because a hand is a silhouette and not a
     * stroked shape. The index finger rises from a fist; the three folded fingers are bumps along the top so
     * the shape still reads as a hand at 1-bit transparency, where there is no shading to carry it.</p>
     */
    public static int[] pointingHandPixelArt() {
        boolean[] body = new boolean[SIZE * SIZE];

        final int tipX = HAND_HOTSPOT_X, tipY = HAND_HOTSPOT_Y;
        // Index finger: a 4px column from the tip down into the fist.
        block(body, tipX, tipY, 4, 13);
        // The fist, offset right so the finger sits on its left edge, as a real pointing hand does.
        block(body, tipX, tipY + 10, 13, 10);
        // Folded fingers: three bumps rising off the top of the fist, each a little shorter.
        block(body, tipX + 4, tipY + 7, 3, 4);
        block(body, tipX + 7, tipY + 8, 3, 3);
        block(body, tipX + 10, tipY + 9, 3, 2);
        // Thumb, on the left of the fist and below the finger.
        block(body, tipX - 3, tipY + 12, 3, 6);

        return outline(body);
    }

    /**
     * A pointing hand — {@code cursor: pointer}, by far the most-used keyword in any UI.
     *
     * <h3>Authored as pixel art, after four attempts at generating it</h3>
     * <p>Earlier versions built this from a signed distance field: tapered cones for the fingers, a rounded
     * box for the palm, smooth-unioned together. It was the wrong tool, and the reasons are worth keeping:</p>
     * <ul>
     *   <li>A 32&times;32 cursor is <b>about twenty pixels of usable shape</b>. At that size every native
     *       pointer is axis-aligned pixel art with a uniform one-pixel outline, because anything else turns
     *       to mush. An SDF rim follows a curve and rasterises two pixels thick on the diagonals against one
     *       on the flats, which reads as a ragged edge rather than a crisp one.</li>
     *   <li>Fingers have to <b>separate near their tips and merge into the palm</b>. That is one number per
     *       block to say here, and a blend radius to coax out of an SDF — the generated versions either
     *       fused the fingers into a slab or split them into detached sticks, with too narrow a band
     *       between those to hit reliably.</li>
     * </ul>
     *
     * <p>So the shape is declared directly and {@link #outline} derives the border, exactly as the arrows
     * do. The blocks below <em>are</em> the drawing: move a number and the shape moves, with no interaction
     * between parts to reason about.</p>
     */
    public static int[] pointingHand() {
        boolean[] body = new boolean[SIZE * SIZE];

        // The raised index finger, and three curled ones stepping down behind it. Each is separated from
        // its neighbour by a single empty column, which outline() then fills as a one-pixel black division
        // — precisely how the classic pointer draws them.
        block(body, 8, 4, 4, 12);    // index: tallest, and a pixel wider, being the one that points
        block(body, 13, 9, 3, 7);
        block(body, 17, 11, 3, 5);
        block(body, 21, 13, 3, 3);

        // The palm. Its top edge is where the fingers stop being separate, so the divisions above it are
        // short — running them to the bottom of the hand is what made an earlier version read as four
        // loose sticks rather than one hand.
        block(body, 8, 15, 16, 11);

        // Thumb: a step out to the left rather than a taper. At this size it is three pixels of silhouette.
        block(body, 5, 17, 3, 5);

        return outline(body);
    }

    // ── Drawing primitives ──────────────────────────────────────────────────

    /** A filled rectangle, clipped by {@link #plot}. */
    private static void block(boolean[] body, int x, int y, int width, int height) {
        for (int dy = 0; dy < height; dy++) {
            for (int dx = 0; dx < width; dx++) plot(body, x + dx, y + dy);
        }
    }

    /** A 2px-thick run through the centre, along one axis. */
    private static void shaft(boolean[] body, boolean horizontal) {
        for (int i = 5; i < SIZE - 5; i++) {
            if (horizontal) {
                plot(body, i, HOTSPOT - 1);
                plot(body, i, HOTSPOT);
            } else {
                plot(body, HOTSPOT - 1, i);
                plot(body, HOTSPOT, i);
            }
        }
    }

    /**
     * A solid triangular head at {@code (tipX, tipY)} opening in {@code dir} along one axis.
     *
     * <p>Widening by one pixel per step back from the tip is what gives the stepped, pixel-art look
     * of the shapes it imitates — no anti-aliasing, which a 1-bit-transparency cursor could not
     * display anyway.</p>
     */
    private static void arrowHead(boolean[] body, int tipX, int tipY, int dir, boolean horizontal) {
        // Five steps, not six: even widths add a pixel at every level, so keeping the old step count made
        // the head visibly fatter instead of just moving it. 5 steps of even width tops out at 10px against
        // the old 11 — the same head, re-centred.
        for (int step = 0; step < 5; step++) {
            // EVEN widths, spanning [-step-1, step] rather than the symmetric [-step, step].
            //
            // The shaft is two pixels wide, so its centre line falls between HOTSPOT-1 and HOTSPOT — at
            // x.5. An odd-width head is centred ON a pixel, half a pixel to the right of that, and the
            // arrow looked subtly lopsided with the shaft hugging the left of each head. Even widths put
            // the head's centre on the same half-pixel the shaft already uses, so the two line up exactly.
            for (int spread = -step - 1; spread <= step; spread++) {
                if (horizontal) plot(body, tipX + dir * step, tipY + spread);
                else plot(body, tipX + spread, tipY + dir * step);
            }
        }
    }

    /** A double-headed arrow along one of the two diagonals. */
    private static int[] diagonal(boolean nwse) {
        boolean[] body = new boolean[SIZE * SIZE];
        // THREE pixels wide, not two. On a 45-degree line the perpendicular thickness is the horizontal
        // run divided by root two, so a 2px run is only ~1.4px thick and the diagonal arrows came out
        // visibly thinner than the axis-aligned ones sitting next to them. Three gives ~2.1px, matching
        // the 2px shafts of the horizontal and vertical arrows.
        for (int i = -7; i <= 7; i++) {
            int x = HOTSPOT + i;
            int y = nwse ? HOTSPOT + i : HOTSPOT - i;
            plot(body, x - 1, y);
            plot(body, x, y);
            plot(body, x + 1, y);
        }
        if (nwse) {
            diagonalHead(body, HOTSPOT - 8, HOTSPOT - 8, -1, -1);
            diagonalHead(body, HOTSPOT + 8, HOTSPOT + 8, +1, +1);
        } else {
            diagonalHead(body, HOTSPOT + 8, HOTSPOT - 8, +1, -1);
            diagonalHead(body, HOTSPOT - 8, HOTSPOT + 8, -1, +1);
        }
        fillLatticeGaps(body);
        return outline(body);
    }

    /**
     * A solid triangular head on the diagonal, pointing along {@code (dirX, dirY)}.
     *
     * <p>These used to be hollow L-brackets — two thin arms meeting at the corner — on the reasoning that a
     * rotated triangle would rasterise lumpily at this size. It does not, and the cost of believing it was
     * that the diagonal cursors read as line art while every other arrow here is a solid wedge. Sitting
     * next to each other on the same resize handles, the mismatch is the first thing you notice.</p>
     */
    /**
     * A solid triangular head on the diagonal, pointing along {@code (dirX, dirY)}.
     *
     * <p>Marched in the rotated basis — step back along the diagonal, spread along the perpendicular —
     * which gives the same stepped wedge {@link #arrowHead} produces head-on. It leaves <b>one-pixel holes
     * through the interior</b>, because both of those moves change {@code x + y} by an even amount and so
     * only ever land on pixels of the tip's parity; {@link #fillLatticeGaps} closes them afterwards.</p>
     *
     * <p>Filling by region test instead was tried and abandoned: it needs a half-angle, and at the 45
     * degrees the straight arrows use, a rotated wedge has axis-aligned edges and renders as a square
     * block. Narrowing it far enough to look like an arrow made the head a lozenge merging into the shaft.
     * Marching keeps the silhouette the straight arrows have; the holes are a lattice artifact, not a
     * shape problem, and are better fixed as one.</p>
     */
    private static void diagonalHead(boolean[] body, int tipX, int tipY, int dirX, int dirY) {
        final int perpX = dirY, perpY = -dirX;
        // FOUR steps, against the straight heads' five. Every unit here is a root-two pixel, so marching
        // the same count made the diagonal head half again as deep and wide as the ones it sits beside —
        // obvious as the cursor swapped between an edge handle and a corner one. 4 * root-two is about 5.7px,
        // which lands next to the straight head's 5.
        for (int step = 0; step < 4; step++) {
            for (int spread = -step; spread <= step; spread++) {
                plot(body, tipX - dirX * step + perpX * spread, tipY - dirY * step + perpY * spread);
            }
        }
    }

    /**
     * Fills pixels that are enclosed left-and-right or above-and-below — the holes a diagonal lattice walk
     * leaves behind.
     *
     * <p>Deliberately a hole fill rather than a thicker brush: it can only ever add a pixel that already
     * has body on both sides, so the <b>silhouette cannot move</b>. Painting a 2&times;2 block per step
     * would also close the holes, and would grow the shape by a pixel on two sides while doing it.</p>
     */
    private static void fillLatticeGaps(boolean[] body) {
        boolean[] before = body.clone();
        for (int y = 1; y < SIZE - 1; y++) {
            for (int x = 1; x < SIZE - 1; x++) {
                int i = y * SIZE + x;
                if (before[i]) continue;
                boolean horizontallyEnclosed = before[i - 1] && before[i + 1];
                boolean verticallyEnclosed = before[i - SIZE] && before[i + SIZE];
                if (horizontallyEnclosed || verticallyEnclosed) body[i] = true;
            }
        }
    }

    private static void plot(boolean[] body, int x, int y) {
        if (x < 0 || y < 0 || x >= SIZE || y >= SIZE) return;
        body[y * SIZE + x] = true;
    }

    /**
     * Turns a boolean body mask into ARGB, adding a black outline in every pixel adjacent to the body.
     *
     * <p>Deriving the outline from the body is the point: hand-drawing both would let them fall out of
     * step the first time a shape changed, and the failure mode — a cursor with a gap in its
     * silhouette — is subtle enough to survive review.</p>
     */
    private static int[] outline(boolean[] body) {
        int[] argb = new int[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int i = y * SIZE + x;
                if (body[i]) {
                    argb[i] = BODY;
                } else if (adjacentToBody(body, x, y)) {
                    argb[i] = OUTLINE;
                } else {
                    argb[i] = TRANSPARENT;
                }
            }
        }
        return argb;
    }

    private static boolean adjacentToBody(boolean[] body, int x, int y) {
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int nx = x + dx, ny = y + dy;
                if (nx < 0 || ny < 0 || nx >= SIZE || ny >= SIZE) continue;
                if (body[ny * SIZE + nx]) return true;
            }
        }
        return false;
    }

    // ── Anti-aliased shapes ─────────────────────────────────────────────────
    //
    // The arrows above are 1-bit masks: a boolean body, an outline generated from it, every edge hard.
    // That is right for an arrow, whose edges are axis-aligned or exactly diagonal and so alias into
    // clean stair-steps. It is wrong for a curve -- an arc drawn as a mask reads as a chain of blocks,
    // which is the one dated thing about Photoshop's own rotate cursor. So the three below are
    // rasterised from signed distance fields, with the outline dilated from the same field. Same
    // white-body-black-outline convention, same 32x32, smooth edges.

    /**
     * A curved double-headed arrow bending around the <b>top-right</b> corner: drag to rotate.
     *
     * <p>Presented just outside a corner of the Free Transform box, which is where every editor puts the
     * rotate zone and the only affordance it has: nothing is drawn there, so the cursor IS the
     * advertisement. Each corner gets the bend that hugs it — see {@link #rotateNw()} and the other two,
     * which are this one MIRRORED rather than redrawn, so all four are the same artwork by construction
     * and tuning one tunes them all.</p>
     */
    public static int[] rotateNe() {
        // EXACTLY A QUARTER, and that is what buys the heads their definition. The tangent is horizontal
        // at the top of a circle and vertical at its right, so this one sweep is the only one whose ends
        // are both on an axis -- which means the heads can be the SAME artwork the resize arrows use,
        // plotted on the pixel grid, instead of arbitrary-angle triangles that rasterise soft on all four
        // edges and read as mush at 32 pixels.
        //
        // So this shape is drawn two ways at once: the arc from a distance field, because a curve has no
        // orientation that aliases cleanly, and the heads from a boolean mask, because an axis-aligned
        // arrowhead has nothing but orientations that do. Neither half looks right drawn the other way.
        final float cx = 11f;
        final float cy = 20f;
        // BIGGER THAN A HEAD IS WIDE. Two ten-pixel heads set on a small arc simply meet, and the mark
        // comes out a solid wedge with no turn visible in it.
        final float radius = 11f;
        final float half = 1.8f;

        boolean[] heads = new boolean[SIZE * SIZE];
        // Each head's BASE row lands on the arc's end, which is what makes the join a clean step rather
        // than something needing a fillet to hide it. The lower head is set two pixels IN from the arc's
        // end rather than centred on it: the arc arrives from the left there, so a centred head leaves
        // the outer half of its base hanging off the curve with nothing behind it.
        arrowHead(heads, 6, 9, 1, true);
        arrowHead(heads, 22, 25, -1, false);

        return rasterise((x, y) -> sdArc(x, y, cx, cy, radius, half, 260f, 370f), heads, 1f);
    }

    /** {@link #rotateNe()} mirrored across the vertical: the bend hugs the top-LEFT corner. */
    public static int[] rotateNw() {
        return mirror(rotateNe(), true, false);
    }

    /** {@link #rotateNe()} mirrored across the horizontal: the bend hugs the bottom-RIGHT corner. */
    public static int[] rotateSe() {
        return mirror(rotateNe(), false, true);
    }

    /** {@link #rotateNe()} mirrored both ways: the bend hugs the bottom-LEFT corner. */
    public static int[] rotateSw() {
        return mirror(rotateNe(), true, true);
    }

    /**
     * A copy flipped about the canvas centre on either axis.
     *
     * <p>Pixels, not geometry. Re-deriving each corner from its own angles would give four shapes that
     * drift apart the moment one is tuned, and a mirror of a symmetric-by-eye arrowhead is exactly the
     * same arrowhead — there is nothing in this mark whose handedness carries meaning.</p>
     */
    private static int[] mirror(int[] art, boolean flipX, boolean flipY) {
        int[] out = new int[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int sourceX = flipX ? SIZE - 1 - x : x;
                int sourceY = flipY ? SIZE - 1 - y : y;
                out[y * SIZE + x] = art[sourceY * SIZE + sourceX];
            }
        }
        return out;
    }

    /**
     * Two opposed arrows on parallel rails: <b>drag to lean this edge.</b>
     *
     * <p>Deliberately not {@link #horizontalDoubleArrow()}: a skew slides one edge PAST the other, and
     * one shaft with two heads says the whole thing travels. Two offset rails going opposite ways is the
     * shear itself, drawn.</p>
     */
    public static int[] skew() {
        float[] upper = head(23f, 11f, 1f, 0f, 5f, 3.4f, 1.2f);
        float[] lower = head(9f, 21f, -1f, 0f, 5f, 3.4f, 1.2f);
        return rasterise((x, y) -> min(
                sdSegment(x, y, 9f, 11f, 23f, 11f, 1.3f),
                sdSegment(x, y, 9f, 21f, 23f, 21f, 1.3f),
                sdTriangle(x, y, upper),
                sdTriangle(x, y, lower)), 1.15f);
    }

    /**
     * A ring inside a crosshair: <b>this is the point everything else turns about.</b>
     *
     * <p>After Effects' anchor-point tool, and the same mark the transform box draws for its pivot, so
     * the cursor and the thing under it agree.</p>
     */
    public static int[] pivot() {
        return rasterise((x, y) -> min(
                sdRing(x, y, 16f, 16f, 5.5f, 1.2f),
                sdSegment(x, y, 16f, 3.5f, 16f, 9f, 1.1f),
                sdSegment(x, y, 16f, 23f, 16f, 28.5f, 1.1f),
                sdSegment(x, y, 3.5f, 16f, 9f, 16f, 1.1f),
                sdSegment(x, y, 23f, 16f, 28.5f, 16f, 1.1f),
                sdDisc(x, y, 16f, 16f, 1.3f)), 1.1f);
    }

    /** A signed distance in pixels, negative inside the shape. */
    private interface Sdf {
        float at(float x, float y);
    }

    /**
     * Paints a field into ARGB, black outline under white body.
     *
     * <p>Composited rather than thresholded twice: the outline is the same field dilated, so the two can
     * no more disagree than the mask-based pair above can. White over black gives
     * {@code alpha = body + outline*(1-body)} and a grey level of {@code body/alpha}, which is the only
     * arithmetic here and is what keeps a curve smooth instead of stepped.</p>
     */
    /**
     * As {@link #rasterise(Sdf, float)}, plus a 1-bit mask unioned in at full coverage.
     *
     * <p>For a shape that is part curve and part axis-aligned arrow: the field half is anti-aliased
     * because a curve has no orientation that aliases cleanly, the mask half is not because an
     * arrowhead has nothing but orientations that do. The mask brings its own outline from its own
     * neighbours, so its stair-steps keep a crisp one-pixel edge instead of the field's soft ramp.</p>
     */
    private static int[] rasterise(Sdf shape, boolean[] mask, float outlineWidth) {
        int[] pixels = new int[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int i = y * SIZE + x;
                float distance = shape.at(x + 0.5f, y + 0.5f);
                float body = mask[i] ? 1f : coverage(distance);
                float outline = mask[i] || adjacentToBody(mask, x, y)
                        ? 1f : Math.max(coverage(distance - outlineWidth), body);
                float alpha = body + outline * (1f - body);
                if (alpha <= 0.004f) {
                    pixels[i] = TRANSPARENT;
                    continue;
                }
                int level = Math.round(255f * clamp(body / alpha));
                pixels[i] = (Math.round(255f * clamp(alpha)) << 24) | (level << 16) | (level << 8) | level;
            }
        }
        return pixels;
    }

    private static int[] rasterise(Sdf shape, float outlineWidth) {
        int[] pixels = new int[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                float distance = shape.at(x + 0.5f, y + 0.5f);
                float body = coverage(distance);
                float outline = coverage(distance - outlineWidth);
                float alpha = body + outline * (1f - body);
                if (alpha <= 0.004f) {
                    pixels[y * SIZE + x] = TRANSPARENT;
                    continue;
                }
                int level = Math.round(255f * clamp(body / alpha));
                pixels[y * SIZE + x] = (Math.round(255f * clamp(alpha)) << 24)
                        | (level << 16) | (level << 8) | level;
            }
        }
        return pixels;
    }

    /** One pixel of linear ramp across the edge, which is as sharp as a curve gets without stepping. */
    private static float coverage(float distance) {
        return clamp(0.5f - distance);
    }

    private static float clamp(float value) {
        return value < 0f ? 0f : (value > 1f ? 1f : value);
    }

    private static float min(float... values) {
        float best = values[0];
        for (float value : values) best = Math.min(best, value);
        return best;
    }

    private static float[] onCircle(float cx, float cy, float radius, float degrees) {
        double radians = Math.toRadians(degrees);
        return new float[]{cx + radius * (float) Math.cos(radians),
                cy + radius * (float) Math.sin(radians)};
    }

    /** The unit tangent at an angle, with {@code sign} choosing which way round. */
    private static float[] tangent(float degrees, float sign) {
        double radians = Math.toRadians(degrees);
        return new float[]{sign * -(float) Math.sin(radians), sign * (float) Math.cos(radians)};
    }

    /**
     * An arrowhead at a point aimed along a direction, as {tipX, tipY, ax, ay, bx, by}.
     *
     * <p>{@code backset} pulls the base BACK along whatever the head caps, and it has to be enough to
     * bury the join: on a curve the stroke falls away from the base line on both sides, leaving a pixel
     * that is inside neither shape but inside both outlines — a black notch in the middle of the mark.
     * Sized against the thing being capped, not against the head.</p>
     */
    private static float[] head(float x, float y, float dx, float dy,
                                float length, float width, float backset) {
        float baseX = x - dx * backset;
        float baseY = y - dy * backset;
        return new float[]{x + dx * length, y + dy * length,
                baseX - dy * width, baseY + dx * width,
                baseX + dy * width, baseY - dx * width};
    }

    private static float sdDisc(float x, float y, float cx, float cy, float radius) {
        return length(x - cx, y - cy) - radius;
    }

    private static float sdRing(float x, float y, float cx, float cy, float radius, float half) {
        return Math.abs(length(x - cx, y - cy) - radius) - half;
    }

    /** A capsule: the distance to a segment, less its half-width. */
    private static float sdSegment(float x, float y, float x0, float y0, float x1, float y1, float half) {
        float ex = x1 - x0;
        float ey = y1 - y0;
        float t = ((x - x0) * ex + (y - y0) * ey) / Math.max(1e-6f, ex * ex + ey * ey);
        t = clamp(t);
        return length(x - (x0 + ex * t), y - (y0 + ey * t)) - half;
    }

    /**
     * An arc of a ring, capped at both ends.
     *
     * <p>Outside the sweep it falls back to the nearer cap, which is what makes the join with an
     * arrowhead continuous: a bare angular test leaves the ends square, and a head planted on a square
     * end shows the corner.</p>
     */
    private static float sdArc(float x, float y, float cx, float cy, float radius, float half,
                               float fromDegrees, float toDegrees) {
        float angle = (float) Math.toDegrees(Math.atan2(y - cy, x - cx));
        if (angle < 0f) angle += 360f;
        // A SWEEP MAY CROSS THE SEAM: `to` is allowed past 360, so a quarter turn either side of the
        // right-hand extreme can be written as one range instead of two arcs meeting at a join.
        if (angle < fromDegrees) angle += 360f;
        if (angle >= fromDegrees && angle <= toDegrees) {
            return Math.abs(length(x - cx, y - cy) - radius) - half;
        }
        float[] from = onCircle(cx, cy, radius, fromDegrees);
        float[] to = onCircle(cx, cy, radius, toDegrees);
        return Math.min(length(x - from[0], y - from[1]), length(x - to[0], y - to[1])) - half;
    }

    /**
     * A triangle, as the farthest of its three edge half-planes.
     *
     * <p>Exact in sign and close enough in magnitude near an edge, which is all a one-pixel coverage ramp
     * reads. Outward normals are taken from the centroid, so the winding never has to be stated.</p>
     */
    private static float sdTriangle(float x, float y, float[] triangle) {
        float gx = (triangle[0] + triangle[2] + triangle[4]) / 3f;
        float gy = (triangle[1] + triangle[3] + triangle[5]) / 3f;
        return Math.max(edge(x, y, triangle, 0, 2, gx, gy),
                Math.max(edge(x, y, triangle, 2, 4, gx, gy), edge(x, y, triangle, 4, 0, gx, gy)));
    }

    private static float edge(float x, float y, float[] t, int a, int b, float gx, float gy) {
        float x0 = t[a];
        float y0 = t[a + 1];
        float nx = -(t[b + 1] - y0);
        float ny = t[b] - x0;
        float len = length(nx, ny);
        if (len < 1e-6f) return -1e9f;
        nx /= len;
        ny /= len;
        if ((gx - x0) * nx + (gy - y0) * ny > 0f) {
            nx = -nx;
            ny = -ny;
        }
        return (x - x0) * nx + (y - y0) * ny;
    }

    private static float length(float x, float y) {
        return (float) Math.sqrt(x * x + y * y);
    }
}
