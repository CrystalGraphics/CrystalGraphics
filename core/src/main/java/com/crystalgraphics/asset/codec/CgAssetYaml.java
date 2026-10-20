package com.crystalgraphics.asset.codec;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.asset.tree.CgShape;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.object.CgValueType;
import com.crystalgraphics.serialization.format.file.YamlDocument;

/**
 * An asset file's text and its tree, by {@code spec/core.md} § How a file is written: the one writer every tool uses,
 * so the same tree always gives the same bytes and a file written here reads back to the tree that wrote it.
 *
 * <pre>{@code
 * Map<String, Object> tree = CgAssetYaml.read(text);                 // comments moved into `editor`
 * String bytes = CgAssetYaml.write(tree, CgParticleKinds.SYSTEM);    // keys in declared order, flow where it fits
 * String fixed = CgAssetYaml.canonical("vfx/systems/nova.yaml", text); // a file's kind is its folder
 * }</pre>
 *
 * <ul>
 *   <li>The tree is plain: {@link LinkedHashMap}, {@link List}, {@link String}, {@link Integer}/{@link Long},
 *       {@link Double}, {@link Boolean}, null. Maps keep their order.</li>
 *   <li>Key order is the declaration's; keys it does not order follow in the order held, and {@code editor} closes
 *       every map that has one. A dispatch member is its {@code id}, the family's key, then its own fields.</li>
 *   <li>A port of the format's reference writer, {@code X:/research/tools/scripts/aaa_write.py}; the two give the
 *       same bytes.</li>
 * </ul>
 */
public final class CgAssetYaml {

    /** Root files of a namespace: no kind, and no {@code editor}. */
    private static final List<String> ROOT_FILES = Collections.unmodifiableList(Arrays.asList(
            "units.yaml", "content.yaml", "tags.yaml"));
    private static final int WIDTH = 120;
    private static final Pattern PLAIN = Pattern.compile("[A-Za-z_][A-Za-z0-9_.:/@-]*");
    private static final String[] RESERVED = {"true", "false", "yes", "no", "on", "off", "y", "n", "null"};
    private static final String[] FORM_KEYS = {"range", "pick", "curve", "curves", "gradient", "array", "bind", "fn",
            "mesh", "texture", "field", "unit"};

    private CgAssetYaml() {
    }

    // ── Reading ──────────────────────────────────────────────────────────────────────────

    /**
     * A file's tree, its comments moved into {@code editor} ({@code core.md} § The writer's rules): the lines before
     * the first key to the asset's {@code editor.note}, a comment on or above an element to its {@code editor.note},
     * one on or above a key inside an element to its {@code editor.notes} under the key's path.
     */
    public static Object read(String text) {
        text = text.replace("\r\n", "\n");
        YamlDocument doc = YamlDocument.parse(text);
        Object root = doc.root() == null ? new LinkedHashMap<>() : doc.root();
        if (root instanceof Map) CgAssetComments.place(text, doc, map(root));
        return root;
    }

    // ── Writing ──────────────────────────────────────────────────────────────────────────

    /** {@code tree}'s canonical text; {@code kind} null for a file of no kind (units, content, tags). */
    public static String write(Object tree, @Nullable CgType kind) {
        List<String> out = new ArrayList<>();
        block(tree, kind == null ? null : CgValueType.struct(kind), 0, out);
        StringBuilder text = new StringBuilder();
        for (String line : out) text.append(line).append('\n');
        if (out.isEmpty()) text.append('\n');
        return text.toString();
    }

    /** A file's text, canonical: read, then written by the kind its folder names; a root file loses {@code editor}. */
    public static String canonical(String path, String text) {
        Object tree = read(text);
        String name = path.substring(path.replace('\\', '/').lastIndexOf('/') + 1);
        if (ROOT_FILES.contains(name) && tree instanceof Map) map(tree).remove(CgType.EDITOR);
        return write(tree, kindOf(path));
    }

    /** The kind a file's folder names ({@code spec/README.md} § Kinds), or null for a root file or none. */
    @Nullable
    public static CgType kindOf(String path) {
        String[] segs = path.replace('\\', '/').split("/");
        List<CgType> kinds = CgAssetKinds.all();
        for (int i = 0; i < segs.length; i++) {
            for (CgType kind : kinds) {
                String[] folder = kind.folder().split("/");
                if (segs.length <= i + folder.length) continue;
                boolean match = true;
                for (int j = 0; j < folder.length && match; j++) match = segs[i + j].equals(folder[j]);
                if (match) return kind;
            }
        }
        return null;
    }

    // ── The writer's rules ───────────────────────────────────────────────────────────────

    /** A scalar, a list of scalars and lists, or a map naming a form: flow style on its key's line, always. */
    private static boolean isValue(Object v) {
        if (v instanceof String) return ((String) v).indexOf('\n') < 0;
        if (v == null || v instanceof Number || v instanceof Boolean) return true;
        if (v instanceof List) {
            List<?> list = (List<?>) v;
            boolean allStrings = !list.isEmpty();
            for (Object x : list) {
                if (x instanceof Map || !isValue(x)) return false;
                allStrings &= x instanceof String;
            }
            return !allStrings;   // a list of names is a list, not a value
        }
        if (v instanceof Map) {
            boolean form = false;
            for (String k : FORM_KEYS) form |= map(v).containsKey(k);
            return form && flowable(v);
        }
        return false;
    }

    private static boolean flowable(Object v) {
        if (v instanceof String) return ((String) v).indexOf('\n') < 0;
        if (v instanceof Map) {
            for (Map.Entry<Object, Object> e : map(v).entrySet()) {
                if (!flowable(e.getKey()) || !flowable(e.getValue())) return false;
            }
            return true;
        }
        if (v instanceof List) {
            for (Object x : (List<?>) v) {
                if (!flowable(x)) return false;
            }
        }
        return true;
    }

    private static List<Object> ordered(Map<Object, Object> m, CgShape s) {
        List<Object> keys = new ArrayList<>(m.size());
        for (String k : s.props().keySet()) {
            if (m.containsKey(k) && !k.equals(CgType.EDITOR)) keys.add(k);
        }
        for (Object k : m.keySet()) {
            if (!(k instanceof String && s.props().containsKey(k)) && !CgType.EDITOR.equals(k)) keys.add(k);
        }
        if (m.containsKey(CgType.EDITOR)) keys.add(CgType.EDITOR);
        return keys;
    }

    private static String flow(Object v, @Nullable CgValueType type) {
        return flow(v, type, null);
    }

    /** {@code v} in flow style; {@code key} is the key it sits under, which a colour's {@code argb} needs. */
    private static String flow(Object v, @Nullable CgValueType type, @Nullable Object key) {
        if (v instanceof Map) {
            Map<Object, Object> m = map(v);
            if (m.isEmpty()) return "{}";
            CgShape s = CgShape.of(type, v);
            StringBuilder b = new StringBuilder("{ ");
            boolean first = true;
            for (Object k : ordered(m, s)) {
                if (!first) b.append(", ");
                first = false;
                b.append(scalar(k)).append(": ").append(flow(m.get(k), s.child(k), k));
            }
            return b.append(" }").toString();
        }
        if (v instanceof List) {
            List<?> list = (List<?>) v;
            if (list.isEmpty()) return "[]";
            CgValueType items = CgShape.of(type, v).items();
            StringBuilder b = new StringBuilder();
            boolean maps = false;
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) b.append(", ");
                b.append(flow(list.get(i), items));
                maps |= list.get(i) instanceof Map;
            }
            return maps ? "[ " + b + " ]" : "[" + b + "]";
        }
        return scalar(v, key);
    }

    private static void entry(@Nullable Object key, Object v, @Nullable CgValueType type, int indent, List<String> out) {
        String pad = spaces(indent);
        String lead = pad + (key == null ? "" : scalar(key) + ": ");
        if (v instanceof String && ((String) v).indexOf('\n') >= 0) {
            String s = (String) v;
            boolean last = s.endsWith("\n");
            out.add(lead + (last ? "|" : "|-"));
            String body = last ? s.substring(0, s.length() - 1) : s;
            String inner = spaces(indent + 2);
            for (String l : body.split("\n", -1)) out.add(l.isEmpty() ? "" : inner + l);
            return;
        }
        if (!(v instanceof Map || v instanceof List) || isValue(v) || empty(v)) {
            out.add(lead + flow(v, type, key));
            return;
        }
        if (flowable(v)) {
            String line = lead + flow(v, type);
            boolean idList = v instanceof List && CgShape.of(type, v).idItems();
            if (width(line) <= WIDTH && !idList) {
                out.add(line);
                return;
            }
        }
        if (key != null) out.add(pad + scalar(key) + ":");
        block(v, type, indent + (key == null ? 0 : 2), out);
    }

    private static void block(Object v, @Nullable CgValueType type, int indent, List<String> out) {
        if (v instanceof Map) {
            Map<Object, Object> m = map(v);
            CgShape s = CgShape.of(type, v);
            for (Object k : ordered(m, s)) entry(k, m.get(k), s.child(k), indent, out);
            return;
        }
        String pad = spaces(indent);
        CgShape s = CgShape.of(type, v);
        CgValueType items = s.items();
        for (Object x : (List<?>) v) {
            if (x instanceof Map && !map(x).isEmpty() && !isValue(x)) {
                String line = pad + "- " + flow(x, items);
                if (flowable(x) && width(line) <= WIDTH) {
                    out.add(line);
                    continue;
                }
                hang(pad, indent, x, items, out);
            } else if (x instanceof List && !((List<?>) x).isEmpty() && !isValue(x)) {
                hang(pad, indent, x, items, out);
            } else {
                List<String> lines = new ArrayList<>();
                entry(null, x, items, indent + 2, lines);
                for (int i = 0; i < lines.size(); i++) {
                    out.add(i == 0 ? pad + "- " + lines.get(0).substring(indent + 2) : lines.get(i));
                }
            }
        }
    }

    /** A block map or list as a list item: its first line on the {@code - } line, the rest under it. */
    private static void hang(String pad, int indent, Object x, @Nullable CgValueType items, List<String> out) {
        List<String> body = new ArrayList<>();
        block(x, items, indent + 2, body);
        out.add(pad + "- " + body.get(0).substring(indent + 2));
        for (int i = 1; i < body.size(); i++) out.add(body.get(i));
    }

    // ── Scalars ──────────────────────────────────────────────────────────────────────────

    /** A scalar as the writer's rules spell it; also a key. */
    static String scalar(@Nullable Object v) {
        return scalar(v, null);
    }

    /** A scalar under {@code key}: a colour's {@code argb} as {@code 0x} and eight upper-case hex digits. */
    static String scalar(@Nullable Object v, @Nullable Object key) {
        if ("argb".equals(key) && v instanceof Number && !(v instanceof Double) && !(v instanceof Float)) return hex(v);
        if (v == null) return "null";
        if (v instanceof Boolean) return (Boolean) v ? "true" : "false";
        if (v instanceof Double || v instanceof Float) return floating(((Number) v).doubleValue());
        if (v instanceof Number) return v.toString();
        String s = v.toString();
        if (PLAIN.matcher(s).matches() && !reserved(s)) return s;
        return quoted(s);
    }

    private static boolean reserved(String s) {
        for (String r : RESERVED) {
            if (r.equalsIgnoreCase(s)) return true;
        }
        return false;
    }

    /** Python's {@code '0x%08X' % v}: a negative keeps its sign inside the zero padding. */
    private static String hex(Object v) {
        BigInteger n = new BigInteger(v.toString());
        String digits = n.abs().toString(16).toUpperCase(Locale.ROOT);
        if (n.signum() < 0) return "0x-" + zeros(7 - digits.length()) + digits;
        return "0x" + zeros(8 - digits.length()) + digits;
    }

    /** JSON's string, non-ASCII kept as is: Python's {@code json.dumps(s, ensure_ascii=False)}. */
    private static String quoted(String s) {
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                case '\b': b.append("\\b"); break;
                case '\f': b.append("\\f"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    /**
     * A double in the shortest form that reads back to it, Python's {@code repr}: positional for 1e-4 ≤ |x| < 1e16,
     * else an exponent with a sign and two digits at least; always with a {@code .}.
     */
    static String floating(double x) {
        if (Double.isNaN(x)) return ".nan";
        if (Double.isInfinite(x)) return x > 0 ? ".inf" : "-.inf";
        String sign = (Double.doubleToRawLongBits(x) < 0) ? "-" : "";
        double a = Math.abs(x);
        if (a == 0) return sign + "0.0";
        BigDecimal shortest = shortest(a);
        String digits = shortest.unscaledValue().toString();
        int point = digits.length() - shortest.scale();   // the value is 0.<digits> × 10^point
        if (point <= -4 || point > 16) {
            String mantissa = digits.length() == 1 ? digits + ".0" : digits.charAt(0) + "." + digits.substring(1);
            int exp = point - 1;
            String e = Integer.toString(Math.abs(exp));
            return sign + mantissa + "e" + (exp < 0 ? "-" : "+") + (e.length() < 2 ? "0" + e : e);
        }
        if (point <= 0) return sign + "0." + zeros(-point) + digits;
        if (point >= digits.length()) return sign + digits + zeros(point - digits.length()) + ".0";
        return sign + digits.substring(0, point) + "." + digits.substring(point);
    }

    /** The fewest significant digits that round-trip, correctly rounded: Python's shortest repr. */
    private static BigDecimal shortest(double a) {
        BigDecimal exact = new BigDecimal(a);
        int lo = 1, hi = 17;
        BigDecimal best = exact.round(new MathContext(17, RoundingMode.HALF_EVEN));
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            BigDecimal r = exact.round(new MathContext(mid, RoundingMode.HALF_EVEN));
            if (r.doubleValue() == a) {
                best = r;
                hi = mid - 1;
            } else {
                lo = mid + 1;
            }
        }
        return best.stripTrailingZeros();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    static Map<Object, Object> map(Object o) {
        return (Map<Object, Object>) o;
    }

    private static boolean empty(Object v) {
        return v instanceof Map ? ((Map<?, ?>) v).isEmpty() : ((List<?>) v).isEmpty();
    }

    private static int width(String line) {
        return line.codePointCount(0, line.length());
    }

    private static String spaces(int n) {
        StringBuilder b = new StringBuilder(n);
        for (int i = 0; i < n; i++) b.append(' ');
        return b.toString();
    }

    private static String zeros(int n) {
        StringBuilder b = new StringBuilder(n);
        for (int i = 0; i < n; i++) b.append('0');
        return b.toString();
    }
}
