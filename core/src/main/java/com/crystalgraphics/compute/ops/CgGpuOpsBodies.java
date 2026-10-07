package com.crystalgraphics.compute.ops;

import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.compute.cpu.CgCpuBuffer;
import com.crystalgraphics.compute.cpu.CgCpuDispatch;
import com.crystalgraphics.compute.cpu.CgCpuImage;

/**
 * The CPU tier's twin of each {@link CgGpuOps} kernel, line for line: the same blocks of sixteen, the same order, and
 * GLSL's own {@code min}/{@code max} (the second operand only when strictly better), so every tier answers in the same
 * bits.
 */
final class CgGpuOpsBodies {

    private static final int SUM = 0, MIN = 1, MAX = 2;
    private static final int UINT = 0, INT = 1, FLOAT = 2;

    private CgGpuOpsBodies() {}

    static CgCompute fill(CgCompute file) {
        file.kernel("Fill").cpu(d -> {
            CgCpuBuffer dst = d.buffer("DST");
            int n = count(d), value = d.propertyInt("_Value");
            for (int e = d.first(); e < d.end(); e++) if (below(e, n)) dst.setInt(e, value);
        });
        file.kernel("Iota").cpu(d -> {
            CgCpuBuffer dst = d.buffer("DST");
            int n = count(d), first = d.propertyInt("_Value"), step = d.propertyInt("_Step");
            for (int e = d.first(); e < d.end(); e++) if (below(e, n)) dst.setInt(e, first + e * step);
        });
        file.kernel("Copy").cpu(d -> {
            CgCpuBuffer src = d.buffer("SRC"), dst = d.buffer("DST");
            int n = count(d);
            for (int e = d.first(); e < d.end(); e++) if (below(e, n)) dst.setInt(e, src.getInt(e));
        });
        file.kernel("FillAt").cpu(d -> {
            CgCpuBuffer dst = d.buffer("DST");
            int n = count(d), at = d.propertyInt("_At"), value = d.propertyInt("_Value");
            for (int e = d.first(); e < d.end(); e++) if (below(e, n)) dst.setInt(at + e, value);
        });
        file.kernel("DispatchArgs").cpu(d -> {
            CgCpuBuffer dst = d.buffer("DST");
            int n = count(d), group = d.propertyInt("_Group"), at = d.propertyInt("_At");
            for (int e = d.first(); e < d.end() && e < 3; e++) {
                dst.setInt(at + e, e == 0 ? Integer.divideUnsigned(n + group - 1, group) : 1);
            }
        });
        return file;
    }

    static CgCompute expand(CgCompute file) {
        file.kernel("ExpandTotal").cpu(d -> {
            if (d.first() != 0 || d.end() == 0) return;
            CgCpuBuffer lengths = d.buffer("LENGTHS"), starts = d.buffer("STARTS");
            int n = count(d);
            d.buffer("TOTAL").setInt(d.propertyInt("_TotalAt"), n == 0 ? 0 : starts.getInt(n - 1) + lengths.getInt(n - 1));
        });
        file.kernel("Expand").cpu(d -> {
            CgCpuBuffer starts = d.buffer("STARTS"), out = d.buffer("OUT");
            int n = count(d), total = d.buffer("TOTAL").getInt(d.propertyInt("_TotalAt")), elements = d.propertyInt("_Elements");
            if (!below(total, elements)) total = elements;
            for (int j = d.first(); j < d.end(); j++) {
                if (!below(j, total)) continue;
                int lo = 0, hi = n;
                while (hi - lo > 1) {
                    int mid = (lo + hi) >>> 1;
                    if (Integer.compareUnsigned(starts.getInt(mid), j) <= 0) lo = mid;
                    else hi = mid;
                }
                out.setInt(j, 0, lo);
                out.setInt(j, 1, j - starts.getInt(lo));
            }
        });
        return file;
    }

    static CgCompute scan(CgCompute file) {
        file.kernel("ReduceStep").cpu(d -> {
            Fold f = new Fold(d);
            CgCpuBuffer dst = d.buffer("DST");
            int level = d.propertyInt("_Level"), above = levelCount(f.n, level + 1), here = levelCount(f.n, level);
            for (int j = d.first(); j < d.end(); j++) if (below(j, above)) dst.setInt(j, f.fold(j * 16, here));
        });
        file.kernel("ReduceLast").cpu(d -> {
            Fold f = new Fold(d);
            if (d.first() == 0 && d.end() > 0) {
                d.buffer("DST").setInt(d.propertyInt("_At"), f.fold(0, levelCount(f.n, d.propertyInt("_Level"))));
            }
        });
        file.kernel("ScanBlock").cpu(d -> {
            Fold f = new Fold(d);
            CgCpuBuffer dst = d.buffer("DST"), prefix = d.buffer("PREFIX");
            int here = levelCount(f.n, d.propertyInt("_Level"));
            boolean hasPrefix = d.propertyInt("_HasPrefix") != 0, inclusive = d.keyword("INCLUSIVE");
            for (int i = d.first(); i < d.end(); i++) {
                if (!below(i, here)) continue;
                int acc = hasPrefix ? prefix.getInt(i >>> 4) : f.identity();
                int first = i & ~15, end = inclusive ? i + 1 : i;
                for (int e = first; e < first + 16; e++) if (below(e, end)) acc = f.combine(acc, f.element(e));
                dst.setInt(i, acc);
            }
        });
        file.kernel("CompactIndices").cpu(d -> {
            CgCpuBuffer flags = d.buffer("SRC"), prefix = d.buffer("PREFIX"), dst = d.buffer("DST");
            int n = count(d);
            for (int i = d.first(); i < d.end(); i++) {
                if (below(i, n) && flags.getInt(i) != 0) dst.setInt(prefix.getInt(i), i);
            }
        });
        file.kernel("CompactValues").cpu(d -> {
            CgCpuBuffer flags = d.buffer("SRC"), prefix = d.buffer("PREFIX"), values = d.buffer("VALUES"),
                    dst = d.buffer("DST");
            int n = count(d);
            for (int i = d.first(); i < d.end(); i++) {
                if (below(i, n) && flags.getInt(i) != 0) dst.setInt(prefix.getInt(i), values.getInt(i));
            }
        });
        file.kernel("CompactCount").cpu(d -> {
            if (d.first() != 0 || d.end() == 0) return;
            CgCpuBuffer flags = d.buffer("SRC");
            int n = count(d);
            int kept = n == 0 ? 0 : d.buffer("PREFIX").getInt(n - 1) + (flags.getInt(n - 1) != 0 ? 1 : 0);
            d.buffer("DST").setInt(d.propertyInt("_At"), kept);
        });
        return file;
    }

    static CgCompute sort(CgCompute file) {
        file.kernel("RadixCount").cpu(d -> {
            Radix r = new Radix(d, false);
            CgCpuBuffer cells = d.buffer("CELLS");
            for (int cell = d.first(); cell < d.end(); cell++) {
                if (!below(cell, 16 * r.blocks)) continue;
                int digit = cell / r.blocks, first = (cell - digit * r.blocks) * 32, end = Math.min(first + 32, r.n), n = 0;
                for (int e = first; e < end; e++) if (r.digit(e) == digit) n++;
                cells.setInt(cell, n);
            }
        });
        file.kernel("RadixScatter").cpu(d -> {
            Radix r = new Radix(d, true);
            CgCpuBuffer out = d.buffer("KEYS_OUT"), values = d.buffer("VALUES_IN"), valuesOut = d.buffer("VALUES_OUT");
            for (int i = d.first(); i < d.end(); i++) {
                if (!below(i, r.n)) continue;
                int at = r.place(i);
                out.setInt(at, r.keys.getInt(i));
                valuesOut.setInt(at, values.getInt(i));
            }
        });
        file.kernel("RadixScatterKeys").cpu(d -> {
            Radix r = new Radix(d, true);
            CgCpuBuffer out = d.buffer("KEYS_OUT");
            for (int i = d.first(); i < d.end(); i++) if (below(i, r.n)) out.setInt(r.place(i), r.keys.getInt(i));
        });
        return file;
    }

    static CgCompute histogram(CgCompute file) {
        file.kernel("Histogram").cpu(d -> {
            CgCpuBuffer keys = d.buffer("KEYS"), bins = d.buffer("BINS");
            int n = count(d), shift = d.propertyInt("_Shift"), last = d.propertyInt("_Bins") - 1;
            for (int i = d.first(); i < d.end(); i++) {
                if (!below(i, n)) continue;
                int bin = keys.getInt(i) >>> shift;
                bins.addInt(below(bin, last) ? bin : last, 1);
            }
        });
        return file;
    }

    /** {@code cull.compute}'s kernels, step for step: the same tests in the same order, then the same record. */
    static CgCompute cull(CgCompute file) {
        file.kernel("Cull").cpu(d -> {
            Culling c = new Culling(d, false).fromProperties();
            CgCpuBuffer out = d.appended("OUT");
            int n = count(d), keep = d.propertyInt("_Level");
            for (int e = d.first(); e < d.end(); e++) {
                if (!below(e, n)) continue;
                c.read(c.first + e);
                if (c.level() == keep) c.write(out, d.append("OUT"));
            }
        });
        file.kernel("CullFlags").cpu(d -> {
            Culling c = new Culling(d, false).fromProperties();
            CgCpuBuffer flags = d.buffer("FLAGS");
            int n = count(d), keep = d.propertyInt("_Level");
            for (int e = d.first(); e < d.end(); e++) {
                boolean kept = false;
                if (below(e, n)) {
                    c.read(c.first + e);
                    kept = c.level() == keep;
                }
                flags.setInt(e, 0, kept ? 1 : 0);
            }
        });
        file.kernel("CullPlace").cpu(d -> {
            Culling c = new Culling(d, false).fromProperties();
            CgCpuBuffer kept = d.buffer("KEPT"), placed = d.buffer("PLACED");
            int n = count(d);
            for (int e = d.first(); e < d.end(); e++) {
                if (!below(e, n)) continue;
                c.read(c.first + kept.getInt(e, 0));
                c.write(placed, e);
            }
        });
        file.kernel("CullKey").cpu(d -> {
            Culling c = new Culling(d, true);
            CgCpuBuffer keys = d.buffer("KEYS"), indices = d.buffer("INDICES");
            int culled = d.propertyInt("_Culled");
            for (int e = d.first(); e < d.end(); e++) {
                int row = c.row(e, 1), key = culled, index = 0;
                c.fromRow(row);
                int rank = e - c.keyAt;
                if (rank < c.capacity && rank < c.count()) {
                    index = c.first + rank;
                    c.read(index);
                    int level = c.level();
                    if (level >= 0) key = row * CgCull.MAX_LEVELS + level;
                }
                keys.setInt(e, 0, key);
                indices.setInt(e, 0, index);
            }
        });
        file.kernel("CullGather").cpu(d -> {
            Culling c = new Culling(d, true);
            CgCpuBuffer counts = d.buffer("COUNTS"), starts = d.buffer("STARTS"), sorted = d.buffer("SORTED");
            CgCpuBuffer placed = d.buffer("PLACED");
            for (int e = d.first(); e < d.end(); e++) {
                int row = c.row(e, 2);
                c.fromRow(row);
                int region = (c.capacity + 3) & ~3, local = e - c.outAt;
                int level = local / region, rank = local - level * region, bin = row * CgCull.MAX_LEVELS + level;
                if (Integer.compareUnsigned(rank, counts.getInt(bin, 0)) >= 0) continue;
                c.read(sorted.getInt(starts.getInt(bin, 0) + rank, 0));
                c.write(placed, e);
            }
        });
        return file;
    }

    /**
     * {@code cull.compute}'s cull_model, cull_level and cull_record: the view from the dispatch's properties, the set
     * from them too ({@link #fromProperties}) or from a row of {@code SETS} ({@link #fromRow}).
     */
    private static final class Culling {
        private final CgCpuDispatch d;
        private final CgCpuBuffer in;
        private final float[] clip, planes = new float[24];
        private final float screenY;
        private final float[] place = new float[16], normal = new float[12], min = new float[3], max = new float[3];
        private final float[] heights = new float[8], stamps = new float[16], light = new float[3];
        private final float[] lo = new float[3], hi = new float[3], r = new float[48], m = new float[16];
        private float scale, unscale;
        private int levels, customs, countAt;
        int first, capacity, keyAt, outAt;
        private CgCpuBuffer sets;
        private int setFirst, setCount;

        /** {@code rows}: a batched kernel's, its sets read from SETS. */
        Culling(CgCpuDispatch d, boolean rows) {
            this.d = d;
            in = d.buffer("INSTANCES");
            clip = columns(d, "_Clip", 0);
            for (int i = 0; i < 6; i++) for (int c = 0; c < 4; c++) planes[i * 4 + c] = d.property("_Plane" + i, c);
            screenY = d.property("_ScreenY");
            if (rows) {
                sets = d.buffer("SETS");
                setFirst = d.propertyInt("_SetFirst");
                setCount = d.propertyInt("_Sets");
            }
        }

        /** The set a per-set kernel's properties describe. */
        Culling fromProperties() {
            float[] p = columns(d, "_Place", 4), n = columns(d, "_PlaceNormal", 3);
            System.arraycopy(p, 0, place, 0, 16);
            System.arraycopy(n, 0, normal, 0, 12);
            for (int a = 0; a < 3; a++) {
                min[a] = d.property("_Min", a);
                max[a] = d.property("_Max", a);
            }
            for (int c = 0; c < 4; c++) {
                heights[c] = d.property("_Heights0", c);
                heights[4 + c] = d.property("_Heights1", c);
            }
            for (int k = 0; k < 4; k++) for (int c = 0; c < 4; c++) stamps[k * 4 + c] = d.property("_Custom" + k, c);
            for (int c = 0; c < 3; c++) light[c] = d.property("_Light", c);
            scale = d.property("_Scale");
            unscale = d.property("_NormalScale");
            levels = d.propertyInt("_Levels");
            customs = d.propertyInt("_Customs");
            countAt = d.propertyInt("_CountAt");
            capacity = d.propertyInt("_Capacity");
            first = d.propertyInt("_First");
            return this;
        }

        /** Row {@code row} of SETS: CgCullSets' layout. */
        void fromRow(int row) {
            CgCpuBuffer t = sets;
            for (int w = 0; w < 16; w++) place[w] = t.getFloat(row, w);
            for (int w = 0; w < 12; w++) normal[w] = t.getFloat(row, 16 + w);
            for (int a = 0; a < 3; a++) {
                min[a] = t.getFloat(row, 28 + a);
                max[a] = t.getFloat(row, 32 + a);
            }
            scale = t.getFloat(row, 31);
            unscale = t.getFloat(row, 35);
            for (int w = 0; w < 8; w++) heights[w] = t.getFloat(row, 36 + w);
            for (int c = 0; c < 3; c++) light[c] = t.getFloat(row, 44 + c);
            for (int w = 0; w < 16; w++) stamps[w] = t.getFloat(row, 48 + w);
            levels = t.getInt(row, 64);
            customs = t.getInt(row, 65);
            countAt = t.getInt(row, 66);
            capacity = t.getInt(row, 67);
            first = t.getInt(row, 68);
            keyAt = t.getInt(row, 69);
            outAt = t.getInt(row, 70);
        }

        /** cull_row: the last of the dispatch's rows whose first (word {@code 68 + c}) is at or below {@code e}. */
        int row(int e, int c) {
            int low = setFirst, high = setFirst + setCount - 1;
            while (low < high) {
                int mid = (low + high + 1) >> 1;
                if (sets.getInt(mid, 68 + c) <= e) low = mid;
                else high = mid - 1;
            }
            return low;
        }

        /** cull_count, of the set loaded. */
        int count() {
            return countAt < 0 ? capacity : (int) Math.min(d.buffer("COUNT").getInt(countAt, 0) & 0xFFFFFFFFL, capacity);
        }

        /** Record {@code index} of INSTANCES read, and its model matrix placed: cull_model. */
        void read(int index) {
            for (int w = 0; w < 48; w++) r[w] = in.getFloat(index, w);
            for (int w = 0; w < 12; w++) r[w] *= scale;
            for (int c = 0; c < 4; c++) {
                for (int row = 0; row < 4; row++) {
                    m[c * 4 + row] = place[row] * r[c * 4] + place[4 + row] * r[c * 4 + 1]
                            + place[8 + row] * r[c * 4 + 2] + place[12 + row] * r[c * 4 + 3];
                }
            }
        }

        /** The level the instance read is kept at, or -1: cull_level. */
        int level() {
            for (int k = 0; k < 3; k++) lo[k] = hi[k] = m[12 + k];
            for (int a = 0; a < 3; a++) {
                for (int k = 0; k < 3; k++) {
                    float p = m[a * 4 + k] * min[a], q = m[a * 4 + k] * max[a];
                    lo[k] += Math.min(p, q);
                    hi[k] += Math.max(p, q);
                }
            }
            for (int i = 0; i < 6; i++) {
                float x = planes[i * 4], y = planes[i * 4 + 1], z = planes[i * 4 + 2];
                if (x * (x < 0f ? lo[0] : hi[0]) + y * (y < 0f ? lo[1] : hi[1]) + z * (z < 0f ? lo[2] : hi[2])
                        < -planes[i * 4 + 3]) return -1;
            }
            int level = 0;
            if (levels > 0) {
                float cx = (lo[0] + hi[0]) * 0.5f, cy = (lo[1] + hi[1]) * 0.5f, cz = (lo[2] + hi[2]) * 0.5f;
                float dx = hi[0] - lo[0], dy = hi[1] - lo[1], dz = hi[2] - lo[2];
                float radius = 0.5f * (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                float w = clip[12] * cx + clip[13] * cy + clip[14] * cz + clip[15];
                float screen = w <= radius ? 3.4e38f : radius * screenY / w;
                level = -1;
                for (int i = 0; i < levels; i++) {
                    if (screen >= heights[i]) {
                        level = i;
                        break;
                    }
                }
            }
            if (level < 0 || occluded(d, clip, lo, hi)) return -1;
            return level;
        }

        /** The record a draw reads for the instance read, into element {@code at} of {@code out}: cull_record. */
        void write(CgCpuBuffer out, int at) {
            for (int w = 0; w < 16; w++) out.setFloat(at, w, m[w]);
            for (int c = 0; c < 3; c++) {
                for (int row = 0; row < 3; row++) {
                    out.setFloat(at, 16 + c * 4 + row, (normal[row] * r[16 + c * 4] + normal[4 + row] * r[16 + c * 4 + 1]
                            + normal[8 + row] * r[16 + c * 4 + 2]) * unscale);
                }
                out.setFloat(at, 16 + c * 4 + 3, r[16 + c * 4 + 3]);
            }
            boolean stamp = light[2] > 0.5f;
            out.setFloat(at, 28, stamp ? light[0] : r[28]);
            out.setFloat(at, 29, stamp ? light[1] : r[29]);
            out.setFloat(at, 30, r[30]);
            out.setFloat(at, 31, r[31]);
            for (int k = 0; k < 4; k++) {
                boolean stamped = (customs & 1 << k) != 0;
                for (int c = 0; c < 4; c++) out.setFloat(at, 32 + k * 4 + c, stamped ? stamps[k * 4 + c] : r[32 + k * 4 + c]);
            }
        }
    }

    /** Vec4 properties {@code prefix0} on as one array of columns; 0 columns reads {@code _ClipX} to {@code _ClipW}. */
    private static float[] columns(CgCpuDispatch d, String prefix, int columns) {
        String[] names = columns == 0 ? new String[]{"_ClipX", "_ClipY", "_ClipZ", "_ClipW"} : new String[columns];
        for (int i = 0; i < columns; i++) names[i] = prefix + i;
        float[] out = new float[names.length * 4];
        for (int i = 0; i < names.length; i++) for (int c = 0; c < 4; c++) out[i * 4 + c] = d.property(names[i], c);
        return out;
    }

    /** {@code cull.compute}'s {@code occluded}: the box's nearest point beyond the farthest depth its rect covers. */
    private static boolean occluded(CgCpuDispatch d, float[] clip, float[] lo, float[] hi) {
        int levels = (int) d.property("_PyramidSize", 2);
        if (levels <= 0) return false;
        float x0 = 3.4e38f, y0 = 3.4e38f, x1 = -3.4e38f, y1 = -3.4e38f, nearest = 3.4e38f;
        for (int c = 0; c < 8; c++) {
            float px = (c & 1) == 0 ? lo[0] : hi[0], py = (c & 2) == 0 ? lo[1] : hi[1], pz = (c & 4) == 0 ? lo[2] : hi[2];
            float w = clip[12] * px + clip[13] * py + clip[14] * pz + clip[15];
            if (w < 0.01f) return false;
            float nx = (clip[0] * px + clip[1] * py + clip[2] * pz + clip[3]) / w;
            float ny = (clip[4] * px + clip[5] * py + clip[6] * pz + clip[7]) / w;
            x0 = Math.min(x0, nx);
            x1 = Math.max(x1, nx);
            y0 = Math.min(y0, ny);
            y1 = Math.max(y1, ny);
            nearest = Math.min(nearest, eyeDepth(d, (clip[8] * px + clip[9] * py + clip[10] * pz + clip[11]) / w));
        }
        int width = (int) d.property("_PyramidSize", 0), height = (int) d.property("_PyramidSize", 1);
        int tx0 = texel(x0, width), tx1 = texel(x1, width), ty0 = texel(y0, height), ty1 = texel(y1, height);
        int level = 0;
        while (level < levels - 1 && ((tx1 >> level) - (tx0 >> level) > 1 || (ty1 >> level) - (ty0 >> level) > 1)) level++;
        int lastX = Math.max(1, width >> level) - 1, lastY = Math.max(1, height >> level) - 1;
        int ax = Math.min(tx0 >> level, lastX), bx = Math.min(tx1 >> level, lastX);
        int ay = Math.min(ty0 >> level, lastY), by = Math.min(ty1 >> level, lastY);
        CgCpuImage depth = d.texture("_Pyramid", level);
        float farthest = Math.max(Math.max(depth.loadFloat(ax, ay, 0, 0), depth.loadFloat(bx, ay, 0, 0)),
                Math.max(depth.loadFloat(ax, by, 0, 0), depth.loadFloat(bx, by, 0, 0)));
        return nearest > farthest;
    }

    private static float eyeDepth(CgCpuDispatch d, float ndc) {
        float p22 = d.property("_Eye", 0), p23 = d.property("_Eye", 1), p32 = d.property("_Eye", 2), p33 = d.property("_Eye", 3);
        if (p23 == 0f) return (p32 - ndc) / p22;
        float a = -p22 / p23;
        float b = p32 + a * p33;
        return b / (ndc + a);
    }

    private static int texel(float ndc, int size) {
        return (int) Math.max(0.0, Math.min(size - 1, Math.floor((ndc * 0.5f + 0.5f) * size)));
    }

    static CgCompute image(CgCompute file) {
        for (int f = 0; f < CgGpuOps.SOURCES.length; f++) {
            String source = CgGpuOps.SOURCES[f], target = CgGpuOps.TARGETS[f];
            file.kernel(CgGpuOps.DOWNSAMPLE_KERNELS[f]).cpu(d -> downsample(d, d.image(source), d.image(target)));
            file.kernel(CgGpuOps.BLUR_KERNELS[f]).cpu(d -> blur(d, d.image(source), d.image(target)));
        }
        return file;
    }

    private static void downsample(CgCpuDispatch d, CgCpuImage src, CgCpuImage dst) {
        int fold = d.keyword("MIN") ? MIN : d.keyword("MAX") ? MAX : SUM;
        float[] wx = new float[3], wy = new float[3], acc = new float[4];
        for (int e = d.first(); e < d.end(); e++) {
            int x = d.x(e), y = d.y(e);
            boxWeights(x, src.width(), wx);
            boxWeights(y, src.height(), wy);
            for (int j = 0; j < 3; j++) {
                for (int i = 0; i < 3; i++) {
                    float w = wx[i] * wy[j];
                    if (i + j > 0 && !(w > 0f)) continue;
                    for (int c = 0; c < 4; c++) {
                        float v = src.loadFloat(2 * x + i, 2 * y + j, 0, c);
                        acc[c] = i + j == 0 ? (fold == SUM ? v * w : v)
                                : fold == MIN ? Math.min(acc[c], v) : fold == MAX ? Math.max(acc[c], v) : acc[c] + v * w;
                    }
                }
            }
            dst.store(x, y, 0, acc[0], acc[1], acc[2], acc[3]);
        }
    }

    /** image.compute's {@code boxWeights}: the shares of source texels 2t, 2t+1 and 2t+2 in target texel t. */
    private static void boxWeights(int t, int n, float[] w) {
        if (n == 1) {
            w[0] = 1f;
            w[1] = w[2] = 0f;
        } else if ((n & 1) == 0) {
            w[0] = w[1] = 0.5f;
            w[2] = 0f;
        } else {
            int half = n >> 1;
            w[0] = (float) (half - t) / n;
            w[1] = (float) half / n;
            w[2] = (float) (t + 1) / n;
        }
    }

    private static void blur(CgCpuDispatch d, CgCpuImage src, CgCpuImage dst) {
        int radius = d.propertyInt("_Radius");
        float sigma = d.property("_Sigma");
        boolean vertical = d.keyword("VERTICAL");
        float[] sum = new float[4];
        for (int e = d.first(); e < d.end(); e++) {
            int x = d.x(e), y = d.y(e);
            for (int c = 0; c < 4; c++) sum[c] = src.loadFloat(x, y, 0, c);
            float total = 1f;
            for (int k = 1; k <= radius; k++) {
                float w = (float) Math.exp(-0.5f * (k * k) / (sigma * sigma));
                int ax = vertical ? x : Math.max(0, x - k), ay = vertical ? Math.max(0, y - k) : y;
                int bx = vertical ? x : Math.min(src.width() - 1, x + k), by = vertical ? Math.min(src.height() - 1, y + k) : y;
                for (int c = 0; c < 4; c++) sum[c] += (src.loadFloat(ax, ay, 0, c) + src.loadFloat(bx, by, 0, c)) * w;
                total += 2f * w;
            }
            dst.store(x, y, 0, sum[0] / total, sum[1] / total, sum[2] / total, sum[3] / total);
        }
    }

    /** The capacity when fixed ({@code _CountAt} below 0), else {@code min(COUNT(_CountAt), uint(_Capacity))}. */
    private static int count(CgCpuDispatch d) {
        int word = d.propertyInt("_CountAt"), capacity = d.propertyInt("_Capacity");
        if (word < 0) return capacity;
        int at = d.buffer("COUNT").getInt(word);
        return Integer.compareUnsigned(at, capacity) < 0 ? at : capacity;
    }

    private static boolean below(int a, int b) {
        return Integer.compareUnsigned(a, b) < 0;
    }

    /** {@code ceil(n / 16^level)} in uint arithmetic, as scan.compute's {@code levelCount}. */
    private static int levelCount(int n, int level) {
        int shift = 4 * level;
        return (n + (1 << shift) - 1) >>> shift;
    }

    /** One sort pass's digits, from its keywords, as sort.compute reads them. */
    private static final class Radix {
        final CgCpuBuffer keys, offsets;
        final int n, shift, blocks;
        final boolean isFloat, isInt, descending;

        Radix(CgCpuDispatch d, boolean placing) {
            keys = d.buffer("KEYS");
            offsets = placing ? d.buffer("OFFSETS") : null;
            n = count(d);
            shift = d.propertyInt("_Shift");
            blocks = d.propertyInt("_Blocks");
            isFloat = d.keyword("FLOAT");
            isInt = d.keyword("INT");
            descending = d.keyword("DESCENDING");
        }

        int digit(int e) {
            int k = keys.getInt(e);
            if (isFloat) k = k < 0 ? ~k : k ^ 0x80000000;
            else if (isInt) k ^= 0x80000000;
            if (descending) k = ~k;
            return (k >>> shift) & 15;
        }

        int place(int i) {
            int digit = digit(i), rank = 0;
            for (int e = i & ~31; e < i; e++) if (digit(e) == digit) rank++;
            return offsets.getInt(digit * blocks + (i >>> 5)) + rank;
        }
    }

    /** One dispatch's fold, from its keywords. */
    private static final class Fold {
        final int fold, type, n, stride, offset;
        final boolean flags;
        final CgCpuBuffer src;

        Fold(CgCpuDispatch d) {
            fold = d.keyword("MIN") ? MIN : d.keyword("MAX") ? MAX : SUM;
            type = d.keyword("FLOAT") ? FLOAT : d.keyword("INT") ? INT : UINT;
            flags = d.keyword("FLAGS");
            src = d.buffer("SRC");
            n = count(d);
            stride = d.propertyInt("_Stride");
            offset = d.propertyInt("_Offset");
        }

        int element(int e) {
            int bits = src.getInt(e * stride + offset);
            return flags ? (bits != 0 ? 1 : 0) : bits;
        }

        int fold(int first, int end) {
            int acc = identity();
            for (int e = first; e < first + 16; e++) if (below(e, end)) acc = combine(acc, element(e));
            return acc;
        }

        int identity() {
            if (fold == SUM) return 0;
            boolean min = fold == MIN;
            return switch (type) {
                case FLOAT -> min ? 0x7F800000 : 0xFF800000;
                case INT -> min ? Integer.MAX_VALUE : Integer.MIN_VALUE;
                default -> min ? 0xFFFFFFFF : 0;
            };
        }

        /** GLSL's {@code a + b}, {@code min(a, b)} or {@code max(a, b)} on the bits. */
        int combine(int a, int b) {
            if (type == FLOAT) {
                float x = Float.intBitsToFloat(a), y = Float.intBitsToFloat(b);
                return switch (fold) {
                    case MIN -> y < x ? b : a;
                    case MAX -> x < y ? b : a;
                    default -> Float.floatToRawIntBits(x + y);
                };
            }
            int order = type == INT ? Integer.compare(a, b) : Integer.compareUnsigned(a, b);
            return switch (fold) {
                case MIN -> order > 0 ? b : a;
                case MAX -> order < 0 ? b : a;
                default -> a + b;
            };
        }
    }
}
