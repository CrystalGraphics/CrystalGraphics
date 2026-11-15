package com.crystalgraphics.animation.blend;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import javax.annotation.Nullable;

import com.crystalgraphics.api.animation.CgAdditive;
import com.crystalgraphics.api.animation.CgAnimPose;
import com.crystalgraphics.api.animation.CgAnimSequence;

/**
 * Clips placed on one or two axes, blended by where an input falls among them: Unreal's {@code UBlendSpace} (and
 * {@code UBlendSpace1D}, {@code UAimOffsetBlendSpace}). Speed on one axis turns an idle, a jog and a sprint into one
 * locomotion; speed and direction on two make strafing. A {@link CgBlendSpacePlayerNode} plays one.
 *
 * <pre>{@code
 * CgBlendSpace run = CgBlendSpace.oneD("mymod:hero_run", "speed", 0, 8)
 *         .sample(IDLE, 0).sample(JOG, 4).sample(SPRINT, 8)
 *         .weightSpeed(5)                                   // sample weights move 5 a second toward their targets
 *         .build();
 *
 * CgBlendSpace strafe = CgBlendSpace.twoD("mymod:hero_strafe", "direction", -180, 180, "speed", 0, 6)
 *         .sample(IDLE, 0, 0).sample(FORWARD, 0, 6).sample(LEFT, -90, 6).sample(RIGHT, 90, 6).sample(BACK, 180, 6)
 *         .smoothing(0, 0.2f, CgBlendFilter.Type.SPRING_DAMPER)
 *         .build();
 * }</pre>
 *
 * <p>Unreal's two engines differ here, and both are kept: release's defaults, and 4.21's by switches an importer
 * sets ({@link Builder#gridInterpolation}, {@link Builder#weightEase}, {@link Builder#legacyLength}).</p>
 *
 * <ul>
 *   <li>Each sample sits within its axes' range, or the build fails naming it.</li>
 *   <li>Additive samples (an aim offset's) must all be the same kind of additive.</li>
 *   <li>Its time is normalised: a player's 0..1 runs every sample through once, each at its own length.</li>
 *   <li>Immutable and shared by every player.</li>
 * </ul>
 */
public final class CgBlendSpace {

    /** Unreal's {@code ZERO_ANIMWEIGHT_THRESH}. */
    static final float ZERO = CgAnimPose.ZERO_WEIGHT;

    /** {@code FInterpolationParameter}: an axis's input smoothing; a time of 0 is none. */
    public record Smoothing(float time, CgBlendFilter.Type type, float damping, float maxSpeed) {
        public static final Smoothing NONE = new Smoothing(0f, CgBlendFilter.Type.SPRING_DAMPER, 1f, 0f);
    }

    /** {@code FBlendParameter}: an axis's name, range, grid divisions, smoothing and whether it wraps (a direction). */
    public record Axis(String name, float min, float max, int grid, Smoothing smoothing, boolean wrap) {
        public Axis {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(smoothing, "smoothing");
            if (!(max > min)) throw new IllegalArgumentException("axis " + name + ": max " + max + " is not above min " + min);
            if (grid < 1) throw new IllegalArgumentException("axis " + name + ": " + grid + " grid divisions");
        }

        float gridSize() {
            return (max - min) / grid;
        }

        float clamp(float v) {
            return wrap ? CgBlendFilter.wrap(v, min, max) : Math.max(min, Math.min(max, v));
        }
    }

    /** {@code FBlendSample}: a clip at a place, played at {@code rateScale} times its speed. */
    public record Sample(CgAnimSequence clip, String clipId, float x, float y, float rateScale) {
    }

    /** {@code EBlendSpaceAxis}: the axis whose overshoot plays the clips faster instead of clamping. */
    public enum ScaleAxis { NONE, X, Y }

    private final String id;
    private final List<Axis> axes;
    private final List<Sample> samples;
    private final float weightSpeed;
    private final boolean weightEase, gridInterpolation, legacyLength;
    private final ScaleAxis scaleAxis;
    @Nullable
    private final float[][][] cookedGrid;
    private final CgAdditive.Type additive;
    private final int withMarkers;
    // The lookup: a grid's elements (3 samples each, -1 none), else 1D segments or 2D triangles in normalised space
    private final int dims, dimX, dimY;
    private final int[] gridSamples;
    private final float[] gridWeights;
    private final int gridX, gridY;
    private final float[] segments;
    private final int[] segmentSamples;
    private final float[] triangles;
    private final int[] triangleSamples;

    private CgBlendSpace(Builder b) {
        id = b.id;
        axes = List.copyOf(b.axes);
        samples = List.copyOf(b.samples);
        weightSpeed = b.weightSpeed;
        weightEase = b.weightEase;
        gridInterpolation = b.gridInterpolation;
        legacyLength = b.legacyLength;
        scaleAxis = b.scaleAxis;
        cookedGrid = b.cookedGrid;
        if (samples.isEmpty()) throw new IllegalArgumentException(id + ": a blend space with no samples");
        for (int i = 0; i < samples.size(); i++) {
            Sample s = samples.get(i);
            if (s.x < axes.get(0).min || s.x > axes.get(0).max || axes.size() > 1 && (s.y < axes.get(1).min || s.y > axes.get(1).max)) {
                throw new IllegalArgumentException(id + ": samples[" + i + "] (" + s.clipId + ") lies outside its axes");
            }
        }
        CgAdditive.Type type = samples.get(0).clip.settings().additive().type();
        for (Sample s : samples) {
            if (s.clip.settings().additive().type() != type) {
                throw new IllegalArgumentException(id + ": " + s.clipId + " is " + s.clip.settings().additive().type()
                        + ", unlike " + samples.get(0).clipId + "; a blend space's samples are all one kind");
            }
        }
        additive = type;
        int marked = -1;
        for (int i = 0; i < samples.size() && marked < 0; i++) if (samples.get(i).clip.uniqueMarkerNames() != null) marked = i;
        withMarkers = marked;

        // ResampleData: the axes the samples spread along, or the first where they sit on one point
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (Sample s : samples) {
            minX = Math.min(minX, s.x);
            maxX = Math.max(maxX, s.x);
            minY = Math.min(minY, s.y);
            maxY = Math.max(maxY, s.y);
        }
        int d = 0, dx = 0, dy = -1;
        if (cookedGrid != null || axes.size() == 1 && !(maxX > minX)) {
            d = axes.size();
            dy = axes.size() > 1 ? 1 : -1;
        } else {
            if (maxX > minX) d++;
            if (axes.size() > 1 && maxY > minY) {
                if (d == 0) dx = 1;
                else dy = 1;
                d++;
            }
            if (d == 0) d = 1;
        }
        dims = d;
        dimX = dx;
        dimY = dy;

        if (cookedGrid != null || gridInterpolation) {
            gridX = axes.get(dimX).grid;
            gridY = dims == 2 ? axes.get(dimY).grid : 0;
            int points = (gridX + 1) * (gridY + 1);
            gridSamples = new int[points * 3];
            gridWeights = new float[points * 3];
            Arrays.fill(gridSamples, -1);
            if (cookedGrid != null) readGrid(cookedGrid, points);
            else if (dims == 1) bakeLine();
            else bakeTriangles(triangulate());
            segments = triangles = null;
            segmentSamples = triangleSamples = null;
        } else {
            gridX = gridY = 0;
            gridSamples = null;
            gridWeights = null;
            if (dims == 1) {
                Integer[] order = sortedAlong();
                int n = Math.max(1, order.length - 1);
                segments = new float[n * 2];
                segmentSamples = new int[n * 2];
                if (order.length == 1) {
                    segments[1] = 1f;
                    segmentSamples[0] = segmentSamples[1] = order[0];
                } else {
                    for (int i = 0; i < n; i++) {
                        segments[i * 2] = normalised(dimX, at(order[i], dimX));
                        segments[i * 2 + 1] = normalised(dimX, at(order[i + 1], dimX));
                        segmentSamples[i * 2] = order[i];
                        segmentSamples[i * 2 + 1] = order[i + 1];
                    }
                }
                triangles = null;
                triangleSamples = null;
            } else {
                segments = null;
                segmentSamples = null;
                triangleSamples = triangulate();
                if (triangleSamples.length == 0) {
                    throw new IllegalArgumentException(id + ": its samples make no triangle; place three off one line");
                }
                triangles = new float[triangleSamples.length * 2];
                for (int i = 0; i < triangleSamples.length; i++) {
                    triangles[i * 2] = normalised(dimX, at(triangleSamples[i], dimX));
                    triangles[i * 2 + 1] = normalised(dimY, at(triangleSamples[i], dimY));
                }
            }
        }
    }

    // ── building ─────────────────────────────────────────────────────────────────────────

    /** A blend space on one axis named {@code axis}, from {@code min} to {@code max}. */
    public static Builder oneD(String id, String axis, float min, float max) {
        return new Builder(id).axis(new Axis(axis, min, max, 4, Smoothing.NONE, false));
    }

    /** A blend space on two axes. */
    public static Builder twoD(String id, String x, float xMin, float xMax, String y, float yMin, float yMax) {
        return new Builder(id).axis(new Axis(x, xMin, xMax, 4, Smoothing.NONE, false)).axis(new Axis(y, yMin, yMax, 4, Smoothing.NONE, false));
    }

    /** Builds a {@link CgBlendSpace}; each setter answers itself. */
    public static final class Builder {
        private final String id;
        private final List<Axis> axes = new ArrayList<>();
        private final List<Sample> samples = new ArrayList<>();
        private float weightSpeed;
        private boolean weightEase = true, gridInterpolation, legacyLength;
        private ScaleAxis scaleAxis = ScaleAxis.NONE;
        @Nullable
        private float[][][] cookedGrid;

        Builder(String id) {
            this.id = Objects.requireNonNull(id, "id");
        }

        /** Another axis, whole: at most two. */
        public Builder axis(Axis axis) {
            if (axes.size() == 2) throw new IllegalArgumentException(id + ": a blend space has at most two axes");
            axes.add(Objects.requireNonNull(axis, "axis"));
            return this;
        }

        private Builder replace(int axis, Axis with) {
            axes.set(axis, with);
            return this;
        }

        /** Axis {@code axis}'s grid divisions: where 4.21 bakes its lookup. */
        public Builder grid(int axis, int divisions) {
            Axis a = axes.get(axis);
            return replace(axis, new Axis(a.name, a.min, a.max, divisions, a.smoothing, a.wrap));
        }

        /** Axis {@code axis}'s input smoothed over {@code time} seconds. */
        public Builder smoothing(int axis, float time, CgBlendFilter.Type type) {
            return smoothing(axis, new Smoothing(time, type, 1f, 0f));
        }

        public Builder smoothing(int axis, Smoothing smoothing) {
            Axis a = axes.get(axis);
            return replace(axis, new Axis(a.name, a.min, a.max, a.grid, smoothing, a.wrap));
        }

        /** Axis {@code axis} wraps: an angle, its ends the same place. */
        public Builder wrap(int axis) {
            Axis a = axes.get(axis);
            return replace(axis, new Axis(a.name, a.min, a.max, a.grid, a.smoothing, true));
        }

        /** A 1D sample. */
        public Builder sample(CgAnimSequence clip, float x) {
            return sample(clip, clip.name(), x, 0f, 1f);
        }

        /** A 2D sample. */
        public Builder sample(CgAnimSequence clip, float x, float y) {
            return sample(clip, clip.name(), x, y, 1f);
        }

        /** A sample whose clip data names {@code clipId}, played at {@code rateScale}. */
        public Builder sample(CgAnimSequence clip, String clipId, float x, float y, float rateScale) {
            samples.add(new Sample(Objects.requireNonNull(clip, "clip"), Objects.requireNonNull(clipId, "clipId"), x, y, rateScale));
            return this;
        }

        /** {@code TargetWeightInterpolationSpeedPerSec}: sample weights move toward their targets this much a second; 0 jumps. */
        public Builder weightSpeed(float perSecond) {
            weightSpeed = perSecond;
            return this;
        }

        /** {@code bTargetWeightInterpolationEaseInOut}: eased by a critically damped spring (release), else constant (4.21). */
        public Builder weightEase(boolean ease) {
            weightEase = ease;
            return this;
        }

        /** {@code bInterpolateUsingGrid}: bilinear over a baked grid (4.21 always), else the triangulation. */
        public Builder gridInterpolation(boolean grid) {
            gridInterpolation = grid;
            return this;
        }

        /** 4.21's length: each sample's length over its rate summed by weight, the move scaled by target over blended length. */
        public Builder legacyLength(boolean legacy) {
            legacyLength = legacy;
            return this;
        }

        /** {@code AxisToScaleAnimation}. */
        public Builder scaleAxis(ScaleAxis axis) {
            scaleAxis = Objects.requireNonNull(axis, "axis");
            return this;
        }

        /**
         * The grid as cooked by the game's editor: per grid point ({@code y·(nx+1)+x}), its samples as
         * {@code {sample, weight}} pairs. Played as given, never re-baked.
         */
        public Builder cookedGrid(float[][][] grid) {
            cookedGrid = grid;
            gridInterpolation = true;
            return this;
        }

        /**
         * @throws IllegalArgumentException with no samples, a sample outside its axes, mixed additive kinds
         */
        public CgBlendSpace build() {
            if (axes.isEmpty()) throw new IllegalArgumentException(id + ": a blend space with no axis");
            return new CgBlendSpace(this);
        }
    }

    // ── what it is ───────────────────────────────────────────────────────────────────────

    public String id() {
        return id;
    }

    public List<Axis> axes() {
        return axes;
    }

    public List<Sample> samples() {
        return samples;
    }

    public float weightSpeed() {
        return weightSpeed;
    }

    public boolean weightEase() {
        return weightEase;
    }

    public boolean gridInterpolation() {
        return gridInterpolation;
    }

    public boolean legacyLength() {
        return legacyLength;
    }

    public ScaleAxis scaleAxis() {
        return scaleAxis;
    }

    @Nullable
    public float[][][] cookedGrid() {
        return cookedGrid;
    }

    /** Its samples' additive kind: {@code MESH_SPACE} for an aim offset's. */
    public CgAdditive.Type additive() {
        return additive;
    }

    /** {@code SampleIndexWithMarkers}: the first sample whose clip has sync markers, else -1. */
    int withMarkers() {
        return withMarkers;
    }

    /** {@code GetUniqueMarkerNames}: its marked sample's markers, or null. */
    @Nullable
    String[] uniqueMarkers() {
        return withMarkers < 0 ? null : samples.get(withMarkers).clip.uniqueMarkerNames();
    }

    // ── the lookup ───────────────────────────────────────────────────────────────────────

    /** The most samples a {@link #lookup} can answer before merging. */
    int scratchSize() {
        return Math.max(samples.size(), 12);
    }

    /**
     * {@code GetSamplesFromBlendInput} (BlendSpace.cpp:1360-1492): the samples at ({@code x}, {@code y}), merged, heaviest
     * first, those under the threshold dropped, normalised. Answers how many; each one's sample index, weight and play
     * rate in the arrays.
     */
    int lookup(float x, float y, int[] index, float[] weight, float[] rate) {
        int n = 0;
        if (gridSamples != null) {
            float gx = (axes.get(dimX).clamp(dimX == 0 ? x : y) - axes.get(dimX).min) / axes.get(dimX).gridSize();
            int ix = (int) gx;
            float rx = gx - ix;
            if (dims == 1) {
                n = element(ix, 0, 1f - rx, index, weight, n);
                n = element(ix + 1, 0, rx, index, weight, n);
            } else {
                float gy = (axes.get(dimY).clamp(y) - axes.get(dimY).min) / axes.get(dimY).gridSize();
                int iy = (int) gy;
                float ry = gy - iy;
                n = element(ix, iy + 1, (1f - rx) * ry, index, weight, n);
                n = element(ix + 1, iy + 1, rx * ry, index, weight, n);
                n = element(ix, iy, (1f - rx) * (1f - ry), index, weight, n);
                n = element(ix + 1, iy, rx * (1f - ry), index, weight, n);
            }
        } else if (dims == 1) {
            n = segment(normalised(dimX, dimX == 0 ? x : y), index, weight);
        } else {
            n = triangle(normalised(dimX, x), normalised(dimY, y), index, weight);
        }
        for (int i = 0; i < n; i++) rate[i] = samples.get(index[i]).rateScale;
        // Heaviest first; equal weights keep their order
        for (int i = 1; i < n; i++) {
            int si = index[i];
            float wi = weight[i], ri = rate[i];
            int j = i - 1;
            while (j >= 0 && weight[j] < wi) {
                index[j + 1] = index[j];
                weight[j + 1] = weight[j];
                rate[j + 1] = rate[j];
                j--;
            }
            index[j + 1] = si;
            weight[j + 1] = wi;
            rate[j + 1] = ri;
        }
        float total = 0f;
        for (int i = 0; i < n; i++) {
            if (weight[i] < ZERO) {
                n = i;
                break;
            }
            total += weight[i];
        }
        for (int i = 0; i < n; i++) weight[i] /= total;
        return n;
    }

    private int element(int ix, int iy, float w, int[] index, float[] weight, int n) {
        if (ix < 0 || ix > gridX || iy < 0 || iy > gridY) return n;
        int e = (iy * (gridX + 1) + ix) * 3;
        for (int k = 0; k < 3; k++) {
            int s = gridSamples[e + k];
            if (s < 0) continue;
            int at = 0;
            while (at < n && index[at] != s) at++;
            if (at == n) {
                index[n] = s;
                weight[n++] = 0f;
            }
            weight[at] += gridWeights[e + k] * w;
        }
        return n;
    }

    /** {@code GetSamples1D} (:2904-2980), by a scan instead of the cached walk: the same segment. */
    private int segment(float p, int[] index, float[] weight) {
        int count = segments.length / 2;
        if (count == 1 && segmentSamples[0] == segmentSamples[1]) {
            index[0] = segmentSamples[0];
            weight[0] = 1f;
            return 1;
        }
        if (p < segments[0]) {
            index[0] = segmentSamples[0];
            weight[0] = 1f;
            return 1;
        }
        for (int i = 0; i < count; i++) {
            float p0 = segments[i * 2], p1 = segments[i * 2 + 1];
            if (p <= p1) {
                float f = Math.max(0f, Math.min(1f, (p - p0) / (p1 - p0)));
                index[0] = segmentSamples[i * 2];
                weight[0] = 1f - f;
                index[1] = segmentSamples[i * 2 + 1];
                weight[1] = f;
                return 2;
            }
        }
        index[0] = segmentSamples[count * 2 - 1];
        weight[0] = 1f;
        return 1;
    }

    /** {@code GetSamples2D} (:2982-3160): the containing triangle's barycentric weights, else the nearest edge's. */
    private int triangle(float px, float py, int[] index, float[] weight) {
        return searchTriangles(triangleSamples, triangles, px, py, index, weight);
    }

    /**
     * {@code GetAnimationLengthFromSampleData} (:2035-2095): 4.21's sum of each sample's length over its rate by weight
     * ({@code legacy_length}), else release's inverse of the summed rate over length.
     */
    float length(int[] index, float[] weight, float[] rate, int n) {
        float sum = 0f;
        for (int i = 0; i < n; i++) {
            float len = samples.get(index[i]).clip.duration(), w = Math.max(0f, Math.min(1f, weight[i])), r = rate[i];
            if (legacyLength) sum += len / (r != 0f ? Math.abs(r) : 1f) * w;
            else sum += (len != 0f ? r / len : 0f) * w;
        }
        if (legacyLength) return sum;
        return sum == 0f ? 0f : 1f / sum;
    }

    /** {@code ComputeAxisScaleFactor} (:459-515): how much faster the clips play for an input the filter or the range held back. */
    float axisScale(float rawX, float rawY, float filteredX, float filteredY) {
        if (scaleAxis == ScaleAxis.NONE) return 1f;
        int axis = scaleAxis == ScaleAxis.X ? 0 : 1;
        if (axis >= axes.size()) return 1f;
        float raw = axis == 0 ? rawX : rawY, filtered = axis == 0 ? filteredX : filteredY;
        float m = 1f;
        if (raw != filtered && filtered != 0f) m = raw / filtered;
        Axis a = axes.get(axis);
        float clamped = a.wrap ? filtered : Math.max(a.min, Math.min(a.max, filtered));
        if (clamped != filtered && clamped != 0f && !a.wrap) m *= filtered / clamped;
        return m;
    }

    // ── baking ───────────────────────────────────────────────────────────────────────────

    private float at(int sample, int axis) {
        return axis == 0 ? samples.get(sample).x : samples.get(sample).y;
    }

    private float normalised(int axis, float v) {
        Axis a = axes.get(axis);
        return (a.clamp(v) - a.min) / (a.max - a.min);
    }

    private Integer[] sortedAlong() {
        Integer[] order = new Integer[samples.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, (p, q) -> Float.compare(at(p, dimX), at(q, dimX)));
        return order;
    }

    private void readGrid(float[][][] grid, int points) {
        if (grid.length != points) throw new IllegalArgumentException(id + ": its grid has " + grid.length + " points, not " + points);
        for (int p = 0; p < points; p++) {
            if (grid[p].length > 3) throw new IllegalArgumentException(id + ": grid[" + p + "] has more than three samples");
            for (int k = 0; k < grid[p].length; k++) {
                int s = (int) grid[p][k][0];
                if (s < 0 || s >= samples.size()) throw new IllegalArgumentException(id + ": grid[" + p + "] names sample " + s);
                gridSamples[p * 3 + k] = s;
                gridWeights[p * 3 + k] = grid[p][k][1];
            }
        }
    }

    /** {@code FLineElementGenerator::CalculateEditorElements} (BlendSpace1DHelpers.cpp:101-160). */
    private void bakeLine() {
        Integer[] order = sortedAlong();
        Axis a = axes.get(dimX);
        float step = a.gridSize();
        for (int e = 0; e <= gridX; e++) {
            float pos = a.min + step * e;
            if (order.length == 1) {
                gridSamples[e * 3] = 0;
                gridWeights[e * 3] = 1f;
                continue;
            }
            for (int i = 0; i + 1 < order.length; i++) {
                float start = at(order[i], dimX), end = at(order[i + 1], dimX);
                if (pos < start) {
                    if (i != 0) continue;
                    gridSamples[e * 3] = order[i];
                    gridWeights[e * 3] = 1f;
                } else if (pos > end) {
                    if (i + 2 != order.length) continue;
                    gridSamples[e * 3] = order[i + 1];
                    gridWeights[e * 3] = 1f;
                } else {
                    float w = (pos - start) / (end - start);
                    gridSamples[e * 3] = order[i + 1];
                    gridWeights[e * 3] = w;
                    gridSamples[e * 3 + 1] = order[i];
                    gridWeights[e * 3 + 1] = 1f - w;
                }
                break;
            }
        }
    }

    private int[] triangulate() {
        float[] xy = new float[samples.size() * 2];
        for (int i = 0; i < samples.size(); i++) {
            xy[i * 2] = normalised(dimX, at(i, dimX));
            xy[i * 2 + 1] = normalised(dimY, at(i, dimY));
        }
        return CgDelaunay.triangulate(xy);
    }

    /** {@code FBlendSpaceGrid::GenerateGridElements} (BlendSpaceHelpers.cpp:657-738). */
    private void bakeTriangles(int[] tris) {
        if (tris.length == 0) throw new IllegalArgumentException(id + ": its samples make no triangle; place three off one line");
        float[] pos = new float[tris.length * 2];
        for (int i = 0; i < tris.length; i++) {
            pos[i * 2] = normalised(dimX, at(tris[i], dimX));
            pos[i * 2 + 1] = normalised(dimY, at(tris[i], dimY));
        }
        int[] index = new int[3];
        float[] weight = new float[3];
        for (int gy = 0; gy <= gridY; gy++) {
            for (int gx = 0; gx <= gridX; gx++) {
                int n = searchTriangles(tris, pos, (float) gx / gridX, (float) gy / gridY, index, weight);
                int e = (gy * (gridX + 1) + gx) * 3;
                for (int k = 0; k < n; k++) {
                    gridSamples[e + k] = index[k];
                    gridWeights[e + k] = weight[k];
                }
            }
        }
    }

    /** A point's samples among triangles {@code tris} at {@code pos}: barycentric in one, else the closest edge's two. */
    private static int searchTriangles(int[] tris, float[] pos, float px, float py, int[] index, float[] weight) {
        int count = tris.length / 3;
        for (int t = 0; t < count; t++) {
            int o = t * 6;
            float ax = pos[o], ay = pos[o + 1], bx = pos[o + 2], by = pos[o + 3], cx = pos[o + 4], cy = pos[o + 5];
            float det = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy);
            if (det == 0f) continue;
            float u = ((by - cy) * (px - cx) + (cx - bx) * (py - cy)) / det;
            float v = ((cy - ay) * (px - cx) + (ax - cx) * (py - cy)) / det;
            float w = 1f - u - v;
            if (Math.abs(w) < 1e-4f) w = 0f;
            if (u >= 0f && u <= 1f && v >= 0f && v <= 1f && w >= 0f && w <= 1f) {
                index[0] = tris[t * 3];
                weight[0] = u;
                index[1] = tris[t * 3 + 1];
                weight[1] = v;
                index[2] = tris[t * 3 + 2];
                weight[2] = w;
                return 3;
            }
        }
        float best = Float.MAX_VALUE, bestT = 0f;
        int bestA = 0, bestB = 0;
        for (int t = 0; t < count; t++) {
            for (int e = 0; e < 3; e++) {
                int a = t * 3 + e, b = t * 3 + (e + 1) % 3;
                float ax = pos[a * 2], ay = pos[a * 2 + 1], bx = pos[b * 2], by = pos[b * 2 + 1];
                float sx = bx - ax, sy = by - ay, len2 = sx * sx + sy * sy;
                float dot = (px - ax) * sx + (py - ay) * sy;
                float tt = dot <= 0f ? 0f : len2 <= dot ? 1f : dot / len2;
                float qx = ax + sx * tt - px, qy = ay + sy * tt - py, d = qx * qx + qy * qy;
                if (d < best) {
                    best = d;
                    bestT = tt;
                    bestA = tris[a];
                    bestB = tris[b];
                }
            }
        }
        index[0] = bestA;
        weight[0] = 1f - bestT;
        index[1] = bestB;
        weight[1] = bestT;
        return 2;
    }

    @Override
    public String toString() {
        return "CgBlendSpace(" + id + ")";
    }
}
