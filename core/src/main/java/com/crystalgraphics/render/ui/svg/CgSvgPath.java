package com.crystalgraphics.render.ui.svg;

import java.util.ArrayList;
import java.util.List;

/**
 * An SVG {@code <path d="…">} flattened into polylines.
 *
 * <h3>Scope: path data, and nothing else</h3>
 *
 * <p>The {@code d} grammar — {@code M L H V C S Q T A Z}, absolute and relative — and no strokes,
 * gradients, transforms, groups or text. That is the whole of what an icon needs: every vector editor
 * exports artwork as filled paths, and the rest of SVG is a document format we are not implementing.</p>
 *
 * <h3>Flattened, not kept as curves</h3>
 *
 * <p>This produces points. Keeping the curves would matter for a distance-field bake, where segment type
 * is the input msdfgen wants — see {@code ICONS.md}. For getting a shape on screen through
 * {@code ctx.curve()}, which strokes straight segments, points are the useful form and flattening here
 * means the consumer needs no curve maths at all.</p>
 *
 * <p>Fixed subdivision rather than adaptive: an icon is a few dozen segments and the difference is
 * invisible. Adaptive flattening is worth it when the curve count is unbounded, which is exactly the case
 * this is not.</p>
 */
public final class CgSvgPath {

    /** Points per curve segment. 16 is smooth at any icon size and trivial to draw. */
    private static final int STEPS = 16;

    private CgSvgPath() {
    }

    /** One closed or open run of points, in the path's own coordinate space. */
    public record Polyline(List<float[]> points, boolean closed) {
    }

    /** Flattens at the default resolution — see {@link #parse(String, int)}. */
    public static List<Polyline> parse(String d) {
        return parse(d, STEPS);
    }

    /**
     * @param steps segments per cubic, and per QUARTER TURN of an arc — so a corner and a full circle
     *              are sampled at the same density. {@link #STEPS} is the resolution a large draw needs; a
     *              caller that knows the artwork will be small can ask for fewer and get a proportionally
     *              smaller mesh, since every flattened vertex becomes a band cut downstream
     */
    public static List<Polyline> parse(String d, int steps) {
        List<Polyline> out = new ArrayList<>();
        if (d == null || d.isBlank()) return out;

        Cursor cursor = new Cursor(d);
        List<float[]> current = new ArrayList<>();
        float x = 0, y = 0, startX = 0, startY = 0;
        // The reflected control point for S/T, per the spec: the previous one mirrored about the
        // current point, or the current point itself when the previous command was not a curve.
        float lastCx = 0, lastCy = 0;
        char previous = ' ';
        char command = ' ';

        while (cursor.hasMore()) {
            char next = cursor.peekCommand();
            if (next != 0) {
                command = cursor.readCommand();
            } else if (command == 'M') {
                command = 'L';          // implicit lineto after a moveto, per the spec
            } else if (command == 'm') {
                command = 'l';
            }
            boolean relative = Character.isLowerCase(command);
            char op = Character.toUpperCase(command);

            switch (op) {
                case 'M': {
                    float nx = cursor.number(), ny = cursor.number();
                    x = relative ? x + nx : nx;
                    y = relative ? y + ny : ny;
                    if (current.size() > 1) out.add(new Polyline(current, false));
                    current = new ArrayList<>();
                    current.add(new float[]{x, y});
                    startX = x;
                    startY = y;
                    break;
                }
                case 'L': {
                    float nx = cursor.number(), ny = cursor.number();
                    x = relative ? x + nx : nx;
                    y = relative ? y + ny : ny;
                    current.add(new float[]{x, y});
                    break;
                }
                case 'H': {
                    float nx = cursor.number();
                    x = relative ? x + nx : nx;
                    current.add(new float[]{x, y});
                    break;
                }
                case 'V': {
                    float ny = cursor.number();
                    y = relative ? y + ny : ny;
                    current.add(new float[]{x, y});
                    break;
                }
                case 'C': case 'S': {
                    float c1x, c1y;
                    if (op == 'C') {
                        c1x = value(cursor.number(), x, relative);
                        c1y = value(cursor.number(), y, relative);
                    } else {
                        boolean smooth = previous == 'C' || previous == 'S';
                        c1x = smooth ? 2 * x - lastCx : x;
                        c1y = smooth ? 2 * y - lastCy : y;
                    }
                    float c2x = value(cursor.number(), x, relative);
                    float c2y = value(cursor.number(), y, relative);
                    float ex = value(cursor.number(), x, relative);
                    float ey = value(cursor.number(), y, relative);
                    cubic(steps, current, x, y, c1x, c1y, c2x, c2y, ex, ey);
                    lastCx = c2x;
                    lastCy = c2y;
                    x = ex;
                    y = ey;
                    break;
                }
                case 'Q': case 'T': {
                    float cx, cy;
                    if (op == 'Q') {
                        cx = value(cursor.number(), x, relative);
                        cy = value(cursor.number(), y, relative);
                    } else {
                        boolean smooth = previous == 'Q' || previous == 'T';
                        cx = smooth ? 2 * x - lastCx : x;
                        cy = smooth ? 2 * y - lastCy : y;
                    }
                    float ex = value(cursor.number(), x, relative);
                    float ey = value(cursor.number(), y, relative);
                    // A quadratic IS a cubic with both controls at 2/3 of the way to the single one.
                    cubic(steps, current, x, y, x + 2f / 3f * (cx - x), y + 2f / 3f * (cy - y),
                            ex + 2f / 3f * (cx - ex), ey + 2f / 3f * (cy - ey), ex, ey);
                    lastCx = cx;
                    lastCy = cy;
                    x = ex;
                    y = ey;
                    break;
                }
                case 'A': {
                    float rx = cursor.number(), ry = cursor.number();
                    float rotation = cursor.number();
                    boolean largeArc = cursor.number() != 0f;
                    boolean sweep = cursor.number() != 0f;
                    float ex = value(cursor.number(), x, relative);
                    float ey = value(cursor.number(), y, relative);
                    arc(steps, current, x, y, rx, ry, rotation, largeArc, sweep, ex, ey);
                    x = ex;
                    y = ey;
                    break;
                }
                case 'Z': {
                    if (!current.isEmpty()) {
                        out.add(new Polyline(current, true));
                        current = new ArrayList<>();
                        current.add(new float[]{startX, startY});
                    }
                    x = startX;
                    y = startY;
                    break;
                }
                default:
                    // An unknown command would otherwise spin forever on the same character.
                    cursor.skip();
            }
            previous = op;
        }
        if (current.size() > 1) out.add(new Polyline(current, false));
        return out;
    }

    private static float value(float raw, float origin, boolean relative) {
        return relative ? origin + raw : raw;
    }

    /**
     * A rounded rectangle, built directly rather than via a {@code d} string.
     *
     * <h3>Why this exists</h3>
     *
     * <p>{@code CgSvgGeometry.addRect} used to compose the equivalent path as text — {@code "M" + (x + rx) +
     * " " + y + " H" + …} — and hand it back to {@link #parse}. That is a lovely way to guarantee the corner
     * arcs go through one arc implementation, and it was <b>39% of the entire parse</b>: nineteen rects in
     * the shipped set cost 11.9 ms against 2.5 ms for eighty-two real paths, or <b>21× per shape</b>. Nearly
     * all of it is {@code Float.toString}, which is expensive by construction — it has to find the shortest
     * decimal that round-trips — and it was being called ten times per rect only for {@link Cursor#number}
     * to parse every one of them straight back.</p>
     *
     * <p>The original reason for the round-trip is preserved: this still calls the same {@link #arc} the
     * {@code A} command does, in the same order, with the same arguments. It is the string that has gone,
     * not the shared implementation.</p>
     *
     * <p><b>The geometry is bit-identical</b>, and not merely close. {@code Float.toString} emits the
     * shortest decimal that parses back to the same {@code float}, so the round-trip was always exactly the
     * identity on these coordinates — which means computing them in place cannot differ.
     * {@code SvgRoundedRectTest} pins that against the old text form.</p>
     */
    static List<Polyline> roundedRect(float x, float y, float w, float h, float rx, float ry, int steps) {
        List<float[]> points = new ArrayList<>();
        points.add(new float[]{x + rx, y});
        points.add(new float[]{x + w - rx, y});
        arc(steps, points, x + w - rx, y, rx, ry, 0f, false, true, x + w, y + ry);
        points.add(new float[]{x + w, y + h - ry});
        arc(steps, points, x + w, y + h - ry, rx, ry, 0f, false, true, x + w - rx, y + h);
        points.add(new float[]{x + rx, y + h});
        arc(steps, points, x + rx, y + h, rx, ry, 0f, false, true, x, y + h - ry);
        points.add(new float[]{x, y + ry});
        arc(steps, points, x, y + ry, rx, ry, 0f, false, true, x + rx, y);
        List<Polyline> out = new ArrayList<>(1);
        out.add(new Polyline(points, true));
        return out;
    }

    private static void cubic(int steps, List<float[]> out, float x0, float y0, float x1, float y1,
                              float x2, float y2, float x3, float y3) {
        for (int i = 1; i <= steps; i++) {
            float t = (float) i / steps;
            float u = 1 - t;
            float a = u * u * u, b = 3 * u * u * t, c = 3 * u * t * t, e = t * t * t;
            out.add(new float[]{a * x0 + b * x1 + c * x2 + e * x3,
                    a * y0 + b * y1 + c * y2 + e * y3});
        }
    }

    /**
     * An elliptical arc, sampled.
     *
     * <p>SVG states arcs by their <b>endpoint</b> — where to finish, which radii, and two flags choosing
     * between the four arcs that fit — so drawing one means converting to the centre parametrisation
     * first. That conversion is the appendix-F.6 formula from the SVG spec, and it is why arcs are the one
     * command worth calling out: every other command's data is already in the form it is drawn from.</p>
     *
     * <p>They are not optional in practice. Feather, Lucide and Material all round their corners with
     * {@code a}, so a parser without this handles almost no real icon.</p>
     */
    private static void arc(int steps, List<float[]> out, float x0, float y0, float rx, float ry,
                            float rotationDeg, boolean largeArc, boolean sweep, float x1, float y1) {
        if (rx == 0 || ry == 0) {
            out.add(new float[]{x1, y1});
            return;
        }
        rx = Math.abs(rx);
        ry = Math.abs(ry);
        double phi = Math.toRadians(rotationDeg);
        double cos = Math.cos(phi), sin = Math.sin(phi);

        double dx2 = (x0 - x1) / 2.0, dy2 = (y0 - y1) / 2.0;
        double x1p = cos * dx2 + sin * dy2;
        double y1p = -sin * dx2 + cos * dy2;

        // Radii too small to reach the endpoint are scaled up, per the spec, rather than refused.
        double lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry);
        if (lambda > 1) {
            double scale = Math.sqrt(lambda);
            rx *= (float) scale;
            ry *= (float) scale;
        }

        double sign = largeArc == sweep ? -1 : 1;
        double numerator = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p;
        double denominator = rx * rx * y1p * y1p + ry * ry * x1p * x1p;
        double coefficient = sign * Math.sqrt(Math.max(0, numerator / denominator));
        double cxp = coefficient * rx * y1p / ry;
        double cyp = -coefficient * ry * x1p / rx;

        double cx = cos * cxp - sin * cyp + (x0 + x1) / 2.0;
        double cy = sin * cxp + cos * cyp + (y0 + y1) / 2.0;

        double startAngle = angle(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry);
        double sweepAngle = angle((x1p - cxp) / rx, (y1p - cyp) / ry,
                (-x1p - cxp) / rx, (-y1p - cyp) / ry);
        if (!sweep && sweepAngle > 0) sweepAngle -= 2 * Math.PI;
        if (sweep && sweepAngle < 0) sweepAngle += 2 * Math.PI;

        // STEPS ARE PER QUARTER TURN, not per command. A rounded corner is one arc and a whole circle is
        // one arc, and they cannot both be `steps` segments: at the 8-step tier a C drawn as a single
        // 276-degree arc was a rough octagon while the tile's corners beside it were smooth. Normalising
        // on the quarter keeps every rounded rect exactly as it was (a quarter is `steps`, rounded rather
        // than ceiled so float error cannot add a segment) and gives a long arc what its length needs.
        int segments = Math.max(1, (int) Math.round(steps * Math.abs(sweepAngle) / (Math.PI / 2)));
        for (int i = 1; i <= segments; i++) {
            double t = startAngle + sweepAngle * i / segments;
            double px = cos * rx * Math.cos(t) - sin * ry * Math.sin(t) + cx;
            double py = sin * rx * Math.cos(t) + cos * ry * Math.sin(t) + cy;
            out.add(new float[]{(float) px, (float) py});
        }
    }

    private static double angle(double ux, double uy, double vx, double vy) {
        double dot = ux * vx + uy * vy;
        double len = Math.sqrt((ux * ux + uy * uy) * (vx * vx + vy * vy));
        double a = Math.acos(Math.max(-1, Math.min(1, dot / len)));
        return ux * vy - uy * vx < 0 ? -a : a;
    }

    /**
     * The largest integer a {@code float} holds exactly, {@code 2^24}.
     *
     * <p>Above it, consecutive integers stop being representable and the accumulate-then-divide path below
     * loses its exactness guarantee.</p>
     */
    private static final long EXACT_MANTISSA = 1L << 24;

    /**
     * The most fractional digits the divide-by-a-power-of-ten path may take.
     *
     * <p>{@code 10^7} is 10,000,000, still under {@link #EXACT_MANTISSA}; {@code 10^8} is not, so the
     * divisor itself would be rounded and the result would no longer be a single correctly-rounded
     * operation on exact inputs.</p>
     */
    private static final int EXACT_FRACTION_DIGITS = 7;

    private static final float[] POWERS_OF_TEN =
            {1f, 10f, 100f, 1000f, 10_000f, 100_000f, 1_000_000f, 10_000_000f};

    /**
     * Walks the {@code d} string. Commas and whitespace are both separators, and both are optional.
     *
     * <h3>Numbers are accumulated as integers, and it is exact rather than approximate</h3>
     *
     * <p>{@link Cursor#number()} used to hand every token to {@code Float.parseFloat(text.substring(…))},
     * which allocates a {@code String} per number. Path data is nothing but numbers — <b>8,928 of them
     * across the shipped icon set</b> — and tokenising was measured at <b>64% of the cost of flattening a
     * path</b>, well above the curve arithmetic it exists to feed.</p>
     *
     * <p>So the digits are accumulated into a {@code long} and the value is finished as
     * {@code mantissa / 10^k}. <b>That is not an approximation of what the library parser does, it is the
     * same answer</b>, and the two constants above are what make it so: when {@code mantissa} and
     * {@code 10^k} are each exactly representable as a {@code float}, IEEE-754 requires the division to be
     * correctly rounded — so the result is the correctly-rounded {@code float} nearest the true decimal
     * value, which is precisely {@code Float.parseFloat}'s contract. One rounding, not two.</p>
     *
     * <p><b>Anything that cannot clear that bar falls back to {@code Float.parseFloat}</b>: exponents, more
     * than 18 digits, a mantissa past {@code 2^24}, more than seven decimal places. The fallback is the
     * correctness story — the fast path is not a re-implementation of decimal parsing and must never grow
     * into one. {@code SvgPathNumberTest} checks the two against each other over the corpus and over
     * randomised input, which is the only reason to believe any of this.</p>
     *
     * <p>A hand-rolled float parser is normally a bad trade, and the reason this one is not is that it
     * refuses the hard cases instead of guessing at them.</p>
     */
    static final class Cursor {
        private final String text;
        private int at;

        Cursor(String text) {
            this.text = text;
        }

        /** How far the walk has got — a caller looping on {@link #number()} uses it to detect no progress. */
        int position() {
            return at;
        }

        /**
         * Whether a number token starts here, separators skipped.
         *
         * <p>{@link #number()} answers {@code 0} both for "the number zero" and for "there was no number",
         * which is fine inside {@code d} data where the command says how many to expect and a missing one
         * is malformed anyway. A {@code points} list has no such structure — it ends when the numbers do —
         * so it needs to ask before reading.</p>
         */
        boolean hasNumber() {
            skipSeparators();
            if (at >= text.length()) return false;
            char c = text.charAt(at);
            return c == '-' || c == '+' || c == '.' || (c >= '0' && c <= '9');
        }

        boolean hasMore() {
            skipSeparators();
            return at < text.length();
        }

        void skip() {
            at++;
        }

        private void skipSeparators() {
            while (at < text.length()) {
                char c = text.charAt(at);
                if (c == ',' || Character.isWhitespace(c)) at++;
                else break;
            }
        }

        /** The command letter here, or 0 when the next token is a number — an implicit repeat. */
        char peekCommand() {
            skipSeparators();
            if (at >= text.length()) return 0;
            char c = text.charAt(at);
            return Character.isLetter(c) ? c : 0;
        }

        char readCommand() {
            skipSeparators();
            return text.charAt(at++);
        }

        /**
         * One number, stopping where the next one begins.
         *
         * <h3>A SECOND DECIMAL POINT STARTS A NEW NUMBER</h3>
         *
         * <p>{@code 2.128.194} is two numbers — {@code 2.128} and {@code .194} — not one malformed one.
         * SVG's grammar allows it precisely so a separator can be dropped, and every minifier emits it:
         * {@code c.739 0 2.128.194 2.471.875} is six numbers in a real shipped icon.</p>
         *
         * <p>Consuming dots greedily instead made {@code Float.parseFloat} throw, and the {@code catch}
         * below turned that into {@code 0} — <b>silently</b>. The path did not fail to load; it loaded
         * with control points at the origin, so a glyph came out mostly right with one terminal collapsed
         * into a spike. That is the worst available failure mode, and the reason this reads as a
         * rendering bug rather than a parsing one.</p>
         *
         * <p>Same rule for the exponent: one {@code e} per number, so {@code 1e3e4} would end the first
         * number rather than run the two together.</p>
         */
        float number() {
            skipSeparators();
            int start = at;
            boolean negative = false;
            if (at < text.length() && (text.charAt(at) == '-' || text.charAt(at) == '+')) {
                negative = text.charAt(at) == '-';
                at++;
            }

            // Accumulated as an integer while that stays provably exact -- see the note below.
            long mantissa = 0;
            int digits = 0;
            int fractionDigits = 0;
            boolean seenDot = false;
            boolean seenExponent = false;
            boolean exact = true;
            while (at < text.length()) {
                char c = text.charAt(at);
                if (c >= '0' && c <= '9') {
                    if (digits < 18) {
                        mantissa = mantissa * 10 + (c - '0');
                        digits++;
                    } else {
                        exact = false;
                    }
                    if (seenDot) fractionDigits++;
                    at++;
                } else if (c == '.') {
                    // The dot after an exponent belongs to no number at all -- `1e2.5` is `1e2` then `.5`.
                    if (seenDot || seenExponent) break;
                    seenDot = true;
                    at++;
                } else if ((c == 'e' || c == 'E') && !seenExponent && at > start) {
                    seenExponent = true;
                    exact = false;
                    at++;
                    if (at < text.length() && (text.charAt(at) == '-' || text.charAt(at) == '+')) at++;
                } else {
                    break;
                }
            }
            if (start == at) return 0f;

            if (exact && digits > 0 && mantissa <= EXACT_MANTISSA
                    && fractionDigits <= EXACT_FRACTION_DIGITS) {
                float value = fractionDigits == 0
                        ? (float) mantissa
                        : (float) mantissa / POWERS_OF_TEN[fractionDigits];
                return negative ? -value : value;
            }
            try {
                return Float.parseFloat(text.substring(start, at));
            } catch (NumberFormatException malformed) {
                return 0f;
            }
        }
    }
}
