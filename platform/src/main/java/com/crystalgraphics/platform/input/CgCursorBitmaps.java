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
}
