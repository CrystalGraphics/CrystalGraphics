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
        file.kernel("DispatchArgs").cpu(d -> {
            CgCpuBuffer dst = d.buffer("DST");
            int n = count(d), group = d.propertyInt("_Group"), at = d.propertyInt("_At");
            for (int e = d.first(); e < d.end() && e < 3; e++) {
                dst.setInt(at + e, e == 0 ? Integer.divideUnsigned(n + group - 1, group) : 1);
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
