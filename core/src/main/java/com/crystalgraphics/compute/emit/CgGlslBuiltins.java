package com.crystalgraphics.compute.emit;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GLSL builtins newer than 3.30, the oldest GLSL a kernel compiles at (gpu-compute §6.6): the version each arrived in,
 * and an exact polyfill in 3.30 where one exists. A kernel calling one its target's GLSL lacks has the call renamed to
 * {@code _cg_<name>} and the polyfill emitted; one with no polyfill is refused.
 *
 * <pre>{@code
 * String why = CgGlslBuiltins.refusal(kernel.builtins(), 330);   // null: every one is there or polyfilled
 * sb.append(CgGlslBuiltins.polyfills(kernel.builtins(), 330));    // before the kernel's code
 * String code = CgGlslBuiltins.rename(text, kernel.builtins(), 330);
 * }</pre>
 *
 * <p>Exact means the result GLSL's own definition gives: {@code packUnorm4x8} rounds as {@code round} does, and
 * {@code fma} is {@code a * b + c}, which GLSL permits outside a {@code precise} consumer. Half-floats round to
 * nearest even.</p>
 */
public final class CgGlslBuiltins {

    /** A builtin: the GLSL version it is core in, its polyfill's definitions or null, and the polyfills those call. */
    private record Builtin(int version, @Nullable String polyfill, List<String> needs) {}

    private static final Map<String, Builtin> BUILTINS = new HashMap<>();
    private static final String[] FLOATS = {"float", "vec2", "vec3", "vec4"};
    private static final String[] INTS = {"int", "ivec2", "ivec3", "ivec4"};
    private static final String[] UINTS = {"uint", "uvec2", "uvec3", "uvec4"};
    private static final String XYZW = "xyzw";

    private CgGlslBuiltins() {}

    static {
        // ── GLSL 4.00 ─────────────────────────────────────────────────────────
        StringBuilder fma = new StringBuilder();
        for (String t : FLOATS) fma.append(t).append(" _cg_fma(").append(t).append(" a, ").append(t).append(" b, ").append(t)
                .append(" c) { return a * b + c; }\n");
        polyfill("fma", 400, fma.toString());

        polyfill("frexp", 400, """
                float _cg_frexp(float x, out int e) {
                    uint b = floatBitsToUint(x);
                    uint be = (b >> 23) & 0xFFu;
                    if (x == 0.0 || be == 0xFFu) { e = 0; return x; }
                    int bias = 126;
                    if (be == 0u) { b = floatBitsToUint(x * 16777216.0); be = (b >> 23) & 0xFFu; bias = 150; }
                    e = int(be) - bias;
                    return uintBitsToFloat((b & 0x807FFFFFu) | 0x3F000000u);
                }
                """ + componentwiseOut("frexp", FLOATS, FLOATS, INTS));
        polyfill("ldexp", 400, """
                float _cg_pow2(int e) { return uintBitsToFloat(uint(clamp(e, -126, 127) + 127) << 23); }
                float _cg_ldexp(float x, int e) { int h = e / 2; return x * _cg_pow2(h) * _cg_pow2(e - h); }
                """ + componentwise2("ldexp", FLOATS, FLOATS, INTS));

        polyfill("bitfieldExtract", 400, """
                uint _cg_bitfieldExtract(uint v, int offset, int bits) {
                    if (bits == 0) return 0u;
                    return (v >> uint(offset)) & (bits == 32 ? 0xFFFFFFFFu : (1u << uint(bits)) - 1u);
                }
                int _cg_bitfieldExtract(int v, int offset, int bits) {
                    if (bits == 0) return 0;
                    return (v << (32 - offset - bits)) >> (32 - bits);
                }
                """ + componentwiseArgs("bitfieldExtract", UINTS, UINTS, ", int offset, int bits", ", offset, bits")
                + componentwiseArgs("bitfieldExtract", INTS, INTS, ", int offset, int bits", ", offset, bits"));
        polyfill("bitfieldInsert", 400, """
                uint _cg_bitfieldInsert(uint base, uint insert, int offset, int bits) {
                    if (bits == 0) return base;
                    uint mask = (bits == 32 ? 0xFFFFFFFFu : (1u << uint(bits)) - 1u) << uint(offset);
                    return (base & ~mask) | ((insert << uint(offset)) & mask);
                }
                int _cg_bitfieldInsert(int base, int insert, int offset, int bits) {
                    return int(_cg_bitfieldInsert(uint(base), uint(insert), offset, bits));
                }
                """ + insertComponentwise(UINTS) + insertComponentwise(INTS));
        polyfill("bitfieldReverse", 400, """
                uint _cg_bitfieldReverse(uint v) {
                    v = ((v >> 1) & 0x55555555u) | ((v & 0x55555555u) << 1);
                    v = ((v >> 2) & 0x33333333u) | ((v & 0x33333333u) << 2);
                    v = ((v >> 4) & 0x0F0F0F0Fu) | ((v & 0x0F0F0F0Fu) << 4);
                    v = ((v >> 8) & 0x00FF00FFu) | ((v & 0x00FF00FFu) << 8);
                    return (v >> 16) | (v << 16);
                }
                int _cg_bitfieldReverse(int v) { return int(_cg_bitfieldReverse(uint(v))); }
                """ + componentwise1("bitfieldReverse", UINTS, UINTS) + componentwise1("bitfieldReverse", INTS, INTS));
        polyfill("bitCount", 400, """
                int _cg_bitCount(uint v) {
                    v = v - ((v >> 1) & 0x55555555u);
                    v = (v & 0x33333333u) + ((v >> 2) & 0x33333333u);
                    return int((((v + (v >> 4)) & 0x0F0F0F0Fu) * 0x01010101u) >> 24);
                }
                int _cg_bitCount(int v) { return _cg_bitCount(uint(v)); }
                """ + componentwise1("bitCount", INTS, UINTS) + componentwise1("bitCount", INTS, INTS));
        polyfill("findLSB", 400, """
                int _cg_findLSB(uint v) { return v == 0u ? -1 : _cg_bitCount((v & (~v + 1u)) - 1u); }
                int _cg_findLSB(int v) { return _cg_findLSB(uint(v)); }
                """ + componentwise1("findLSB", INTS, UINTS) + componentwise1("findLSB", INTS, INTS), "bitCount");
        polyfill("findMSB", 400, """
                int _cg_findMSB(uint v) {
                    if (v == 0u) return -1;
                    int r = 0;
                    if ((v & 0xFFFF0000u) != 0u) { v >>= 16; r += 16; }
                    if ((v & 0xFF00u) != 0u) { v >>= 8; r += 8; }
                    if ((v & 0xF0u) != 0u) { v >>= 4; r += 4; }
                    if ((v & 0xCu) != 0u) { v >>= 2; r += 2; }
                    if ((v & 0x2u) != 0u) r += 1;
                    return r;
                }
                int _cg_findMSB(int v) { return _cg_findMSB(uint(v < 0 ? ~v : v)); }
                """ + componentwise1("findMSB", INTS, UINTS) + componentwise1("findMSB", INTS, INTS));
        polyfill("uaddCarry", 400, """
                uint _cg_uaddCarry(uint x, uint y, out uint carry) { uint s = x + y; carry = s < x ? 1u : 0u; return s; }
                """ + componentwiseOut2("uaddCarry", UINTS));
        polyfill("usubBorrow", 400, """
                uint _cg_usubBorrow(uint x, uint y, out uint borrow) { borrow = x < y ? 1u : 0u; return x - y; }
                """ + componentwiseOut2("usubBorrow", UINTS));
        polyfill("umulExtended", 400, """
                void _cg_umulExtended(uint x, uint y, out uint msb, out uint lsb) {
                    uint xl = x & 0xFFFFu, xh = x >> 16, yl = y & 0xFFFFu, yh = y >> 16;
                    uint lh = xl * yh, hl = xh * yl;
                    uint mid = ((xl * yl) >> 16) + (lh & 0xFFFFu) + (hl & 0xFFFFu);
                    msb = xh * yh + (lh >> 16) + (hl >> 16) + (mid >> 16);
                    lsb = x * y;
                }
                """ + mulComponentwise("umulExtended", UINTS));
        polyfill("imulExtended", 400, """
                void _cg_imulExtended(int x, int y, out int msb, out int lsb) {
                    uint m, l;
                    _cg_umulExtended(uint(x), uint(y), m, l);
                    if (x < 0) m -= uint(y);
                    if (y < 0) m -= uint(x);
                    msb = int(m);
                    lsb = int(l);
                }
                """ + mulComponentwise("imulExtended", INTS), "umulExtended");
        polyfill("packUnorm2x16", 400, """
                uint _cg_packUnorm2x16(vec2 v) { uvec2 b = uvec2(round(clamp(v, 0.0, 1.0) * 65535.0)); return b.x | (b.y << 16); }
                """);
        polyfill("unpackUnorm2x16", 400, """
                vec2 _cg_unpackUnorm2x16(uint p) { return vec2(uvec2(p, p >> 16) & 0xFFFFu) / 65535.0; }
                """);
        polyfill("packUnorm4x8", 400, """
                uint _cg_packUnorm4x8(vec4 v) {
                    uvec4 b = uvec4(round(clamp(v, 0.0, 1.0) * 255.0));
                    return b.x | (b.y << 8) | (b.z << 16) | (b.w << 24);
                }
                """);
        polyfill("packSnorm4x8", 400, """
                uint _cg_packSnorm4x8(vec4 v) {
                    uvec4 b = uvec4(ivec4(round(clamp(v, -1.0, 1.0) * 127.0))) & 0xFFu;
                    return b.x | (b.y << 8) | (b.z << 16) | (b.w << 24);
                }
                """);
        polyfill("unpackUnorm4x8", 400, """
                vec4 _cg_unpackUnorm4x8(uint p) { return vec4(uvec4(p, p >> 8, p >> 16, p >> 24) & 0xFFu) / 255.0; }
                """);
        polyfill("unpackSnorm4x8", 400, """
                vec4 _cg_unpackSnorm4x8(uint p) {
                    ivec4 b = ivec4(uvec4(p << 24, p << 16, p << 8, p)) >> 24;
                    return clamp(vec4(b) / 127.0, -1.0, 1.0);
                }
                """);
        for (String name : new String[]{"textureGather", "textureGatherOffset", "textureGatherOffsets", "textureQueryLod",
                "packDouble2x32", "unpackDouble2x32", "double", "dvec2", "dvec3", "dvec4", "dmat2", "dmat3", "dmat4",
                "dmat2x2", "dmat2x3", "dmat2x4", "dmat3x2", "dmat3x3", "dmat3x4", "dmat4x2", "dmat4x3", "dmat4x4"}) {
            BUILTINS.put(name, new Builtin(400, null, List.of()));
        }

        // ── GLSL 4.20 ─────────────────────────────────────────────────────────
        polyfill("packSnorm2x16", 420, """
                uint _cg_packSnorm2x16(vec2 v) {
                    uvec2 b = uvec2(ivec2(round(clamp(v, -1.0, 1.0) * 32767.0))) & 0xFFFFu;
                    return b.x | (b.y << 16);
                }
                """);
        polyfill("unpackSnorm2x16", 420, """
                vec2 _cg_unpackSnorm2x16(uint p) {
                    ivec2 b = ivec2(uvec2(p << 16, p)) >> 16;
                    return clamp(vec2(b) / 32767.0, -1.0, 1.0);
                }
                """);
        polyfill("packHalf2x16", 420, """
                uint _cg_halfBits(float f) {
                    uint x = floatBitsToUint(f);
                    uint sign = (x >> 16) & 0x8000u;
                    uint e = (x >> 23) & 0xFFu;
                    uint m = x & 0x7FFFFFu;
                    if (e == 0xFFu) return sign | 0x7C00u | (m != 0u ? 0x200u | (m >> 13) : 0u);
                    int ex = int(e) - 112;
                    if (ex >= 31) return sign | 0x7C00u;
                    if (ex <= 0) {
                        if (ex < -10) return sign;
                        m |= 0x800000u;
                        uint shift = uint(14 - ex);
                        uint h = m >> shift, rest = m & ((1u << shift) - 1u), mid = 1u << (shift - 1u);
                        if (rest > mid || (rest == mid && (h & 1u) != 0u)) h += 1u;
                        return sign | h;
                    }
                    uint h = (uint(ex) << 10) | (m >> 13), rest = m & 0x1FFFu;
                    if (rest > 0x1000u || (rest == 0x1000u && (h & 1u) != 0u)) h += 1u;
                    return sign | h;
                }
                uint _cg_packHalf2x16(vec2 v) { return _cg_halfBits(v.x) | (_cg_halfBits(v.y) << 16); }
                """);
        polyfill("unpackHalf2x16", 420, """
                float _cg_halfFloat(uint h) {
                    uint sign = (h & 0x8000u) << 16;
                    uint e = (h >> 10) & 0x1Fu;
                    uint m = h & 0x3FFu;
                    if (e == 0u) return m == 0u ? uintBitsToFloat(sign) : (sign != 0u ? -1.0 : 1.0) * float(m) * 5.9604644775390625e-8;
                    if (e == 31u) return uintBitsToFloat(sign | 0x7F800000u | (m << 13));
                    return uintBitsToFloat(sign | ((e + 112u) << 23) | (m << 13));
                }
                vec2 _cg_unpackHalf2x16(uint p) { return vec2(_cg_halfFloat(p & 0xFFFFu), _cg_halfFloat(p >> 16)); }
                """);

        // ── GLSL 4.30 and later ───────────────────────────────────────────────
        BUILTINS.put("textureQueryLevels", new Builtin(430, null, List.of()));
        for (String name : new String[]{"textureSamples", "imageSamples"}) BUILTINS.put(name, new Builtin(450, null, List.of()));
        for (String name : new String[]{"anyInvocation", "allInvocations", "allInvocationsEqual"}) {
            BUILTINS.put(name, new Builtin(460, null, List.of()));
        }
    }

    private static void polyfill(String name, int version, String text, String... needs) {
        BUILTINS.put(name, new Builtin(version, text, List.of(needs)));
    }

    // ── Asking ────────────────────────────────────────────────────────────────

    /** Whether {@code id} is a builtin newer than GLSL 3.30: what a kernel's identifiers are filtered by. */
    public static boolean versioned(String id) {
        return BUILTINS.containsKey(id);
    }

    /** The GLSL version {@code id} arrived in, or 0 for an identifier this table does not know. */
    public static int version(String id) {
        Builtin b = BUILTINS.get(id);
        return b == null ? 0 : b.version();
    }

    /** Those of {@code builtins} GLSL {@code glsl} lacks. */
    public static Set<String> missing(Set<String> builtins, int glsl) {
        Set<String> out = new LinkedHashSet<>();
        for (String id : builtins) {
            Builtin b = BUILTINS.get(id);
            if (b != null && b.version() > glsl) out.add(id);
        }
        return out.isEmpty() ? Collections.emptySet() : out;
    }

    /** The first of {@code builtins} GLSL {@code glsl} lacks and no polyfill gives, named with its version; or null. */
    @Nullable
    public static String refusal(Set<String> builtins, int glsl) {
        for (String id : missing(builtins, glsl)) {
            if (BUILTINS.get(id).polyfill() == null) {
                return "uses " + id + ", GLSL " + versionName(BUILTINS.get(id).version()) + ", which GLSL "
                        + versionName(glsl) + " lacks and no polyfill gives exactly";
            }
        }
        return null;
    }

    /** The polyfills {@code builtins} need at GLSL {@code glsl}, each once, every one it calls before it. */
    public static String polyfills(Set<String> builtins, int glsl) {
        Set<String> missing = missing(builtins, glsl);
        if (missing.isEmpty()) return "";
        Set<String> order = new LinkedHashSet<>();
        for (String id : missing) collect(id, order);
        StringBuilder sb = new StringBuilder("// Builtins GLSL ").append(versionName(glsl)).append(" lacks\n");
        for (String id : order) sb.append(BUILTINS.get(id).polyfill());
        return sb.toString();
    }

    private static void collect(String id, Set<String> order) {
        Builtin b = BUILTINS.get(id);
        if (b == null || b.polyfill() == null || order.contains(id)) return;
        for (String need : b.needs()) collect(need, order);
        order.add(id);
    }

    /** {@code text} with every call of a builtin GLSL {@code glsl} lacks renamed to its polyfill. */
    public static String rename(String text, Set<String> builtins, int glsl) {
        Set<String> missing = missing(builtins, glsl);
        if (missing.isEmpty()) return text;
        Matcher m = Pattern.compile("(?<![A-Za-z0-9_.])(" + String.join("|", missing) + ")(?=\\s*\\()").matcher(text);
        return m.replaceAll("_cg_$1");
    }

    /** {@code 4.20} for 420. */
    public static String versionName(int glsl) {
        return glsl / 100 + "." + glsl % 100 / 10 + glsl % 10;
    }

    // ── Vector overloads, each component through the scalar one ───────────────

    /** {@code R f(T v)} for the vector sizes. */
    private static String componentwise1(String name, String[] returns, String[] args) {
        return componentwiseArgs(name, returns, args, "", "");
    }

    /** {@code R f(T v<params>)}, the extra arguments passed on to each component's call as {@code <args>}. */
    private static String componentwiseArgs(String name, String[] returns, String[] args, String params, String passed) {
        StringBuilder sb = new StringBuilder();
        for (int n = 2; n <= 4; n++) {
            sb.append(returns[n - 1]).append(" _cg_").append(name).append('(').append(args[n - 1]).append(" v").append(params)
              .append(") { return ").append(returns[n - 1]).append('(');
            for (int c = 0; c < n; c++) {
                if (c > 0) sb.append(", ");
                sb.append("_cg_").append(name).append("(v.").append(XYZW.charAt(c)).append(passed).append(')');
            }
            sb.append("); }\n");
        }
        return sb.toString();
    }

    /** {@code R f(T a, U b)}, both component-wise. */
    private static String componentwise2(String name, String[] returns, String[] first, String[] second) {
        StringBuilder sb = new StringBuilder();
        for (int n = 2; n <= 4; n++) {
            sb.append(returns[n - 1]).append(" _cg_").append(name).append('(').append(first[n - 1]).append(" a, ")
              .append(second[n - 1]).append(" b) { return ").append(returns[n - 1]).append('(');
            for (int c = 0; c < n; c++) {
                if (c > 0) sb.append(", ");
                char k = XYZW.charAt(c);
                sb.append("_cg_").append(name).append("(a.").append(k).append(", b.").append(k).append(')');
            }
            sb.append("); }\n");
        }
        return sb.toString();
    }

    /** {@code R f(T v, out O o)}: each component's {@code o} gathered into the vector. */
    private static String componentwiseOut(String name, String[] returns, String[] args, String[] outs) {
        StringBuilder sb = new StringBuilder();
        for (int n = 2; n <= 4; n++) {
            sb.append(returns[n - 1]).append(" _cg_").append(name).append('(').append(args[n - 1]).append(" v, out ")
              .append(outs[n - 1]).append(" o) { ").append(outs[0]).append(' ');
            for (int c = 0; c < n; c++) sb.append(c > 0 ? ", " : "").append('o').append(c);
            sb.append("; ").append(returns[n - 1]).append(" r = ").append(returns[n - 1]).append('(');
            for (int c = 0; c < n; c++) {
                sb.append(c > 0 ? ", " : "").append("_cg_").append(name).append("(v.").append(XYZW.charAt(c)).append(", o")
                  .append(c).append(')');
            }
            sb.append("); o = ").append(outs[n - 1]).append('(');
            for (int c = 0; c < n; c++) sb.append(c > 0 ? ", " : "").append('o').append(c);
            sb.append("); return r; }\n");
        }
        return sb.toString();
    }

    /** {@code T f(T x, T y, out T o)} for the vector sizes of {@code types}. */
    private static String componentwiseOut2(String name, String[] types) {
        StringBuilder sb = new StringBuilder();
        for (int n = 2; n <= 4; n++) {
            String t = types[n - 1];
            sb.append(t).append(" _cg_").append(name).append('(').append(t).append(" x, ").append(t).append(" y, out ")
              .append(t).append(" o) { ").append(types[0]).append(' ');
            for (int c = 0; c < n; c++) sb.append(c > 0 ? ", " : "").append('o').append(c);
            sb.append("; ").append(t).append(" r = ").append(t).append('(');
            for (int c = 0; c < n; c++) {
                char k = XYZW.charAt(c);
                sb.append(c > 0 ? ", " : "").append("_cg_").append(name).append("(x.").append(k).append(", y.").append(k)
                  .append(", o").append(c).append(')');
            }
            sb.append("); o = ").append(t).append('(');
            for (int c = 0; c < n; c++) sb.append(c > 0 ? ", " : "").append('o').append(c);
            sb.append("); return r; }\n");
        }
        return sb.toString();
    }

    /** {@code void f(T x, T y, out T msb, out T lsb)} for the vector sizes of {@code types}. */
    private static String mulComponentwise(String name, String[] types) {
        StringBuilder sb = new StringBuilder();
        for (int n = 2; n <= 4; n++) {
            String t = types[n - 1];
            sb.append("void _cg_").append(name).append('(').append(t).append(" x, ").append(t).append(" y, out ").append(t)
              .append(" msb, out ").append(t).append(" lsb) {");
            for (int c = 0; c < n; c++) {
                char k = XYZW.charAt(c);
                sb.append(' ').append(types[0]).append(" m").append(c).append(", l").append(c).append("; _cg_").append(name)
                  .append("(x.").append(k).append(", y.").append(k).append(", m").append(c).append(", l").append(c).append(");");
            }
            sb.append(" msb = ").append(t).append('(');
            for (int c = 0; c < n; c++) sb.append(c > 0 ? ", " : "").append('m').append(c);
            sb.append("); lsb = ").append(t).append('(');
            for (int c = 0; c < n; c++) sb.append(c > 0 ? ", " : "").append('l').append(c);
            sb.append("); }\n");
        }
        return sb.toString();
    }

    /** {@code bitfieldInsert}'s vector overloads. */
    private static String insertComponentwise(String[] types) {
        StringBuilder sb = new StringBuilder();
        for (int n = 2; n <= 4; n++) {
            String t = types[n - 1];
            sb.append(t).append(" _cg_bitfieldInsert(").append(t).append(" base, ").append(t)
              .append(" insert, int offset, int bits) { return ").append(t).append('(');
            for (int c = 0; c < n; c++) {
                char k = XYZW.charAt(c);
                sb.append(c > 0 ? ", " : "").append("_cg_bitfieldInsert(base.").append(k).append(", insert.").append(k)
                  .append(", offset, bits)");
            }
            sb.append("); }\n");
        }
        return sb.toString();
    }

    /** Every builtin this table knows, for a test. */
    static List<String> names() {
        return new ArrayList<>(BUILTINS.keySet());
    }
}
