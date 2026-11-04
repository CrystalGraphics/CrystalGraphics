package com.crystalgraphics.api.shader;

import com.crystalgraphics.variable.CgVar;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GLSL as the driver needs it: comments, the {@code #if} branches the source's own macros decide against, the functions
 * nothing reaches from {@code main} and the macros nothing names, all removed, and the blank lines they leave. A
 * {@code #line} stands wherever lines went, so the driver's line numbers still name the source as it was given, and an
 * error printed beside that source reads true. {@link CgShaderProgram} hands every stage through it.
 *
 * <pre>{@code
 * CgGL.glShaderSource(shader, CgGlslStrip.forDriver(expanded));
 * }</pre>
 *
 * <ul>
 *   <li>A conditional it cannot decide stays whole, every branch and everything in them: one naming a macro the driver
 *       defines ({@code GL_*}, {@code __*}, shaderc's {@code VULKAN}), a function-like macro, or a macro defined inside another undecided one.</li>
 *   <li>Token pasting ({@code ##}) in any macro, or text it cannot scan, keeps every function and macro: the
 *       conditionals and comments still go.</li>
 *   <li>Off under {@code -Dcrystalgraphics.shader.strip=false}, for reading a capture's source with its comments.</li>
 * </ul>
 */
final class CgGlslStrip {

    private static final CgVar.Bool ENABLED = CgVar.bool("crystalgraphics.shader.strip", true, CgVar.DEV | CgVar.RESTART,
            "Strip comments, decided #if branches, unreached functions and unused macros from GLSL before the driver "
            + "compiles it; false hands it the source as written.");

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern FUNCTION = Pattern.compile(
            "(?:(?:precise|highp|mediump|lowp|invariant)\\s+)*([A-Za-z_]\\w*)(?:\\s*\\[[^\\]]*\\])?\\s+([A-Za-z_]\\w*)\\s*\\([^()]*\\)");
    private static final Pattern DEFINE = Pattern.compile("#\\s*define\\s+([A-Za-z_]\\w*)(\\([^)]*\\))?(.*)", Pattern.DOTALL);
    private static final Pattern UNDEF = Pattern.compile("#\\s*undef\\s+([A-Za-z_]\\w*).*");
    private static final Pattern CONDITIONAL = Pattern.compile("#\\s*(if|ifdef|ifndef|elif|else|endif)\\b(.*)", Pattern.DOTALL);

    private CgGlslStrip() {}

    /** {@link #strip}, unless {@code crystalgraphics.shader.strip} is off. */
    static String forDriver(String source) {
        return ENABLED.get() ? strip(source) : source;
    }

    static String strip(String source) {
        String[] lines = blankComments(source.replace("\r\n", "\n")).split("\n", -1);
        for (int i = 0; i < lines.length; i++) lines[i] = trim(lines[i]);
        String[] out;
        try {
            out = new Resolver(lines).resolve();
        } catch (RuntimeException unscannable) {
            return compact(String.join("\n", lines));
        }
        String resolved = String.join("\n", out);
        try {
            return compact(Reach.strip(resolved));
        } catch (RuntimeException unscannable) {
            return compact(resolved);
        }
    }

    private static final Pattern LINE = Pattern.compile("#\\s*line\\s+(\\d+)\\b.*");

    /**
     * Blank lines dropped, a {@code #line} written wherever the driver's count would leave the source's: an error names
     * the line it would have named in {@code text}, through any {@code #line} it holds already (GLSL 3.30's reading:
     * the line after the directive is the number given).
     */
    private static String compact(String text) {
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder(text.length());
        int source = 1, driver = 1;
        boolean continued = false;
        for (String line : lines) {
            Matcher directive = LINE.matcher(line);
            if (directive.matches()) {
                out.append(line).append('\n');
                source = driver = Integer.parseInt(directive.group(1));
                continued = false;
                continue;
            }
            if (!line.isBlank() || continued) {
                // Nothing may come before #version: the line after it takes the #line instead.
                if (source != driver && !line.startsWith("#version")) {
                    out.append("#line ").append(source).append('\n');
                    driver = source;
                }
                out.append(line).append('\n');
                driver++;
            }
            source++;
            continued = line.endsWith("\\");
        }
        return out.toString();
    }

    /** Comments as spaces, newlines kept, quoted text left alone. */
    static String blankComments(String text) {
        char[] out = text.toCharArray();
        int n = out.length;
        for (int i = 0; i < n; i++) {
            char c = out[i];
            if (c == '"') {
                while (++i < n && out[i] != '"' && out[i] != '\n') { }
            } else if (c == '/' && i + 1 < n && out[i + 1] == '/') {
                while (i < n && out[i] != '\n') out[i++] = ' ';
            } else if (c == '/' && i + 1 < n && out[i + 1] == '*') {
                out[i++] = ' ';
                out[i++] = ' ';
                while (i < n && !(out[i] == '*' && i + 1 < n && out[i + 1] == '/')) {
                    if (out[i] != '\n') out[i] = ' ';
                    i++;
                }
                if (i < n) out[i++] = ' ';
                if (i < n) out[i] = ' ';
            }
        }
        return new String(out);
    }

    /** Both ends' whitespace, unless dropping a trailing space would make a line continuation of a backslash. */
    private static String trim(String line) {
        String t = line.strip();
        return t.endsWith("\\") && !line.endsWith("\\") ? line.stripLeading() : t;
    }

    private static Set<String> identifiers(CharSequence text) {
        Set<String> out = new HashSet<>();
        Matcher m = IDENTIFIER.matcher(text);
        while (m.find()) {
            int start = m.start();
            if (start > 0 && Character.isDigit(text.charAt(start - 1))) continue;   // 1e5, 2u
            out.add(m.group());
        }
        return out;
    }

    // ── Conditionals ─────────────────────────────────────────────────────────────────────────────────────────

    /** A line, a directive and its continuation lines, or a conditional group. */
    private sealed interface Node permits Line, Group {}

    /** Lines {@code first} to {@code last}; {@code text} their logical line when a directive. */
    private record Line(int first, int last, String text) implements Node {}

    private record Branch(Line directive, String keyword, String expression, List<Node> body) {}

    private static final class Group implements Node {
        final List<Branch> branches = new ArrayList<>();
        Line endif;
    }

    /** A macro as defined: {@code params} null for an object-like one. */
    private record Macro(String params, String body) {}

    private static final Macro UNKNOWN = new Macro(null, null), UNDEFINED = new Macro(null, null);

    private static final class Resolver {
        private final String[] lines;
        private final String[] out;
        private final Map<String, Macro> macros = new HashMap<>();

        Resolver(String[] lines) {
            this.lines = lines;
            this.out = new String[lines.length];
            Arrays.fill(out, "");
        }

        String[] resolve() {
            emit(parse());
            return out;
        }

        private List<Node> parse() {
            List<Node> top = new ArrayList<>();
            ArrayDeque<List<Node>> bodies = new ArrayDeque<>();
            ArrayDeque<Group> groups = new ArrayDeque<>();
            bodies.push(top);
            for (int i = 0; i < lines.length; i++) {
                if (!lines[i].startsWith("#")) {
                    if (!lines[i].isEmpty()) bodies.peek().add(new Line(i, i, null));
                    continue;
                }
                int last = i;
                StringBuilder text = new StringBuilder(lines[i]);
                while (text.length() > 0 && text.charAt(text.length() - 1) == '\\' && last + 1 < lines.length) {
                    text.setLength(text.length() - 1);
                    text.append(' ').append(lines[++last]);
                }
                Line line = new Line(i, last, text.toString());
                i = last;
                Matcher c = CONDITIONAL.matcher(line.text());
                if (!c.matches()) {
                    bodies.peek().add(line);
                    continue;
                }
                String keyword = c.group(1), expression = c.group(2).trim();
                switch (keyword) {
                    case "if", "ifdef", "ifndef" -> {
                        Group group = new Group();
                        Branch branch = new Branch(line, keyword, expression, new ArrayList<>());
                        group.branches.add(branch);
                        bodies.peek().add(group);
                        groups.push(group);
                        bodies.push(branch.body());
                    }
                    case "elif", "else" -> {
                        if (groups.isEmpty()) throw new IllegalStateException("#" + keyword + " outside #if");
                        Branch branch = new Branch(line, keyword, expression, new ArrayList<>());
                        groups.peek().branches.add(branch);
                        bodies.pop();
                        bodies.push(branch.body());
                    }
                    default -> {
                        if (groups.isEmpty()) throw new IllegalStateException("#endif outside #if");
                        groups.pop().endif = line;
                        bodies.pop();
                    }
                }
            }
            if (!groups.isEmpty()) throw new IllegalStateException("#if never closed");
            return top;
        }

        private void emit(List<Node> nodes) {
            for (Node node : nodes) {
                if (node instanceof Line line) {
                    copy(line);
                    if (line.text() != null) define(line.text(), false);
                } else {
                    Group group = (Group) node;
                    Branch chosen = null;
                    boolean decided = true;
                    for (Branch b : group.branches) {
                        Boolean taken = b.keyword().equals("else") ? Boolean.TRUE : condition(b);
                        if (taken == null) {
                            decided = false;
                            break;
                        }
                        if (taken) {
                            chosen = b;
                            break;
                        }
                    }
                    if (decided) {
                        if (chosen != null) emit(chosen.body());
                    } else {
                        verbatim(group);
                    }
                }
            }
        }

        /** An undecided group, whole: every macro it touches is unknown from here on. */
        private void verbatim(Group group) {
            for (Branch b : group.branches) {
                copy(b.directive());
                for (Node node : b.body()) {
                    if (node instanceof Line line) {
                        copy(line);
                        if (line.text() != null) define(line.text(), true);
                    } else {
                        verbatim((Group) node);
                    }
                }
            }
            copy(group.endif);
        }

        private void copy(Line line) {
            for (int i = line.first(); i <= line.last(); i++) out[i] = lines[i];
        }

        private void define(String directive, boolean unknown) {
            Matcher d = DEFINE.matcher(directive);
            if (d.matches()) {
                macros.put(d.group(1), unknown ? UNKNOWN : new Macro(d.group(2), d.group(3).trim()));
                return;
            }
            Matcher u = UNDEF.matcher(directive);
            if (u.matches()) macros.put(u.group(1), unknown ? UNKNOWN : UNDEFINED);
        }

        private Boolean condition(Branch b) {
            if (b.keyword().equals("ifdef") || b.keyword().equals("ifndef")) {
                Boolean defined = defined(b.expression().split("\\s+")[0]);
                return defined == null ? null : defined == b.keyword().equals("ifdef");
            }
            try {
                Long v = new Expression(tokens(b.expression()), macros).evaluate();
                return v == null ? null : v != 0;
            } catch (RuntimeException malformed) {
                return null;
            }
        }

        private Boolean defined(String name) {
            return Expression.defined(name, macros);
        }
    }

    private static final Pattern TOKEN = Pattern.compile(
            "\\s*(0[xX][0-9a-fA-F]+[uU]?|\\d+[uU]?|[A-Za-z_]\\w*|<<|>>|<=|>=|==|!=|&&|\\|\\||[-+~!*/%<>&^|()])");

    private static List<String> tokens(String expression) {
        List<String> out = new ArrayList<>();
        Matcher m = TOKEN.matcher(expression);
        int at = 0;
        while (at < expression.length() && m.find(at) && m.start() == at) {
            out.add(m.group(1));
            at = m.end();
        }
        if (!expression.substring(at).isBlank()) throw new IllegalStateException("cannot read " + expression);
        return out;
    }

    /** A {@code #if} expression over the macros known so far; null where it depends on one that is not. */
    private static final class Expression {
        private final List<String> tokens;
        private final Map<String, Macro> macros;
        private int at;

        Expression(List<String> tokens, Map<String, Macro> macros) {
            this.macros = macros;
            this.tokens = expand(tokens, 0);
        }

        /**
         * A macro the compiler may define: GL's and the driver's, shaderc's {@code VULKAN} under Vulkan, and
         * {@code CG_BINDLESS}, which a device reading textures by index defines.
         */
        static boolean compilerDefines(String name) {
            return name.startsWith("GL_") || name.startsWith("__") || name.equals("VULKAN") || name.equals("CG_BINDLESS");
        }

        static Boolean defined(String name, Map<String, Macro> macros) {
            Macro m = macros.get(name);
            if (m == null) return compilerDefines(name) ? null : Boolean.FALSE;
            if (m == UNKNOWN) return null;
            return m != UNDEFINED;
        }

        /** Object-like macros replaced by their bodies, never the operand of {@code defined}. */
        private List<String> expand(List<String> in, int depth) {
            if (depth > 32) throw new IllegalStateException("macro recursion");
            List<String> out = new ArrayList<>(in.size());
            for (int i = 0; i < in.size(); i++) {
                String t = in.get(i);
                if (t.equals("defined")) {
                    out.add(t);
                    if (i + 1 < in.size() && in.get(i + 1).equals("(")) out.add(in.get(++i));
                    if (i + 1 < in.size()) out.add(in.get(++i));
                    continue;
                }
                Macro m = macros.get(t);
                if (m == null || m == UNDEFINED || !Character.isJavaIdentifierStart(t.charAt(0))) {
                    out.add(t);
                } else if (m == UNKNOWN || m.params() != null) {
                    out.add("\u0000");   // undecidable
                } else {
                    out.addAll(expand(tokens(m.body()), depth + 1));
                }
            }
            return out;
        }

        Long evaluate() {
            Long v = binary(0);
            if (at != tokens.size()) throw new IllegalStateException("trailing tokens");
            return v;
        }

        private static final String[][] LEVELS = {
                {"||"}, {"&&"}, {"|"}, {"^"}, {"&"}, {"==", "!="}, {"<", ">", "<=", ">="}, {"<<", ">>"}, {"+", "-"},
                {"*", "/", "%"}};

        private Long binary(int level) {
            if (level == LEVELS.length) return unary();
            Long left = binary(level + 1);
            while (at < tokens.size() && contains(LEVELS[level], tokens.get(at))) {
                String op = tokens.get(at++);
                Long right = binary(level + 1);
                left = apply(op, left, right);
            }
            return left;
        }

        private static boolean contains(String[] ops, String token) {
            for (String op : ops) if (op.equals(token)) return true;
            return false;
        }

        private static Long apply(String op, Long l, Long r) {
            // A known side settles && and || alone, as the preprocessor's short circuit does.
            if (op.equals("&&")) return l != null && l == 0 || r != null && r == 0 ? Long.valueOf(0) : l == null || r == null ? null : 1L;
            if (op.equals("||")) return l != null && l != 0 || r != null && r != 0 ? Long.valueOf(1) : l == null || r == null ? null : 0L;
            if (l == null || r == null) return null;
            return switch (op) {
                case "|" -> l | r;
                case "^" -> l ^ r;
                case "&" -> l & r;
                case "==" -> l.equals(r) ? 1L : 0L;
                case "!=" -> l.equals(r) ? 0L : 1L;
                case "<" -> l < r ? 1L : 0L;
                case ">" -> l > r ? 1L : 0L;
                case "<=" -> l <= r ? 1L : 0L;
                case ">=" -> l >= r ? 1L : 0L;
                case "<<" -> l << r;
                case ">>" -> l >> r;
                case "+" -> l + r;
                case "-" -> l - r;
                case "*" -> l * r;
                case "/" -> r == 0 ? null : l / r;
                default -> r == 0 ? null : l % r;
            };
        }

        private Long unary() {
            String t = tokens.get(at++);
            switch (t) {
                case "!" -> {
                    Long v = unary();
                    return v == null ? null : v == 0 ? 1L : 0L;
                }
                case "-" -> {
                    Long v = unary();
                    return v == null ? null : -v;
                }
                case "+" -> {
                    return unary();
                }
                case "~" -> {
                    Long v = unary();
                    return v == null ? null : ~v;
                }
                case "(" -> {
                    Long v = binary(0);
                    if (!tokens.get(at++).equals(")")) throw new IllegalStateException("unclosed (");
                    return v;
                }
                case "defined" -> {
                    boolean paren = tokens.get(at).equals("(");
                    if (paren) at++;
                    Boolean d = defined(tokens.get(at++), macros);
                    if (paren && !tokens.get(at++).equals(")")) throw new IllegalStateException("unclosed defined(");
                    return d == null ? null : d ? 1L : 0L;
                }
                default -> {
                    char c = t.charAt(0);
                    if (Character.isDigit(c)) {
                        String digits = t.endsWith("u") || t.endsWith("U") ? t.substring(0, t.length() - 1) : t;
                        if (digits.startsWith("0x") || digits.startsWith("0X")) return Long.parseLong(digits.substring(2), 16);
                        return digits.length() > 1 && digits.startsWith("0") ? Long.parseLong(digits, 8) : Long.parseLong(digits);
                    }
                    return null;   // undecidable, or an identifier no macro names: the driver's to judge
                }
            }
        }
    }

    // ── Reach ────────────────────────────────────────────────────────────────────────────────────────────────

    /** Functions not reached from {@code main} or file scope, and macros nothing names, removed from resolved text. */
    private static final class Reach {

        /** A function's definition or prototype, or a {@code #define}: its span, and what it names. */
        private record Item(String name, int start, int end, Set<String> names) {}

        static String strip(String text) {
            Map<String, List<Item>> functions = new HashMap<>();
            Map<String, List<Item>> defines = new HashMap<>();
            Set<String> roots = new HashSet<>();
            roots.add("main");
            int n = text.length();
            int start = -1;
            boolean lineStart = true;
            for (int i = 0; i < n; ) {
                char c = text.charAt(i);
                if (c == '\n') {
                    lineStart = true;
                    i++;
                    continue;
                }
                if (Character.isWhitespace(c)) {
                    i++;
                    continue;
                }
                if (lineStart && c == '#') {
                    int end = i;
                    do {
                        end = text.indexOf('\n', end + 1);
                        if (end < 0) end = n;
                    } while (end < n && end > 0 && text.charAt(end - 1) == '\\');
                    String directive = text.substring(i, end);
                    Matcher d = DEFINE.matcher(directive);
                    if (start < 0 && d.matches()) {
                        if (directive.contains("##")) return text;
                        defines.computeIfAbsent(d.group(1), k -> new ArrayList<>())
                                .add(new Item(d.group(1), i, end, identifiers(d.group(3))));
                    } else if (!directive.startsWith("#version") && !directive.startsWith("#extension")) {
                        roots.addAll(identifiers(directive));   // an undecided #if, a #pragma
                    }
                    i = end;
                    continue;
                }
                lineStart = false;
                if (start < 0) start = i;
                if (c == '{') {
                    int close = matchBrace(text, i);
                    String header = text.substring(start, i);
                    Matcher f = FUNCTION.matcher(header.replaceAll("\\s+", " ").trim());
                    if (f.matches() && header.indexOf('#') < 0) {
                        String body = text.substring(i, close + 1);
                        Set<String> names = identifiers(header);
                        names.addAll(identifiers(body));
                        // A macro inside a function, or one in its type's place, is not one Reach sees: keep it.
                        if (body.contains("#") || defines.containsKey(f.group(1))) roots.add(f.group(2));
                        functions.computeIfAbsent(f.group(2), k -> new ArrayList<>()).add(new Item(f.group(2), start, close + 1, names));
                        start = -1;
                    }
                    i = close + 1;
                    continue;
                }
                if (c == ';') {
                    String statement = text.substring(start, i);
                    Matcher f = FUNCTION.matcher(statement.replaceAll("\\s+", " ").trim());
                    if (f.matches() && statement.indexOf('#') < 0 && !defines.containsKey(f.group(1))) {
                        functions.computeIfAbsent(f.group(2), k -> new ArrayList<>())
                                .add(new Item(f.group(2), start, i + 1, identifiers(statement)));
                    } else {
                        roots.addAll(identifiers(statement));
                    }
                    start = -1;
                    i++;
                    continue;
                }
                i++;
            }
            if (start >= 0) roots.addAll(identifiers(text.substring(start)));

            Set<String> reached = new HashSet<>();
            ArrayDeque<String> queue = new ArrayDeque<>(roots);
            while (!queue.isEmpty()) {
                String name = queue.poll();
                if (!reached.add(name)) continue;
                for (Item item : functions.getOrDefault(name, List.of())) queue.addAll(item.names());
                for (Item item : defines.getOrDefault(name, List.of())) queue.addAll(item.names());
            }

            List<Item> dropped = new ArrayList<>();
            for (List<Item> items : functions.values()) if (!reached.contains(items.get(0).name())) dropped.addAll(items);
            for (List<Item> items : defines.values()) if (!reached.contains(items.get(0).name())) dropped.addAll(items);
            if (dropped.isEmpty()) return text;
            dropped.sort((a, b) -> Integer.compare(a.start(), b.start()));
            StringBuilder out = new StringBuilder(text.length());
            int at = 0;
            for (Item item : dropped) {
                out.append(text, at, item.start());
                for (int i = item.start(); i < item.end(); i++) if (text.charAt(i) == '\n') out.append('\n');
                at = item.end();
            }
            out.append(text, at, n);
            return out.toString();
        }

        private static int matchBrace(String text, int open) {
            int depth = 0;
            for (int i = open; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '{') depth++;
                else if (c == '}' && --depth == 0) return i;
            }
            throw new IllegalStateException("'{' never closed");
        }
    }
}
