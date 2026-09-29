package com.crystalgraphics.platform.gl.tracked.glsl;

import com.crystalgraphics.platform.device.CgBindingLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A GL program's GLSL, rewritten at link for Vulkan (spec §6), with the table the tracked backend answers GL's
 * program queries from.
 *
 * <pre>{@code
 * CgGlslRewrite.Program p = CgGlslRewrite.rewrite(vertexGlsl, fragmentGlsl, Map.of("cg_Position", 0, "cg_TexCoord0", 1));
 * device.createShaderModule(VERTEX, p.vertexGlDepth(), label);       // our own passes: GL's clip depth
 * device.createShaderModule(VERTEX, p.vertexZeroToOne(), label);     // a pass into a zero-to-one host depth
 * device.createShaderModule(FRAGMENT, p.fragment(), label);
 * device.createBindingLayout(label, p.slots());
 * }</pre>
 *
 * <ul>
 *   <li>{@code #version} becomes 450; the {@code #if} family is evaluated first, from the source's own defines.</li>
 *   <li>Vertex inputs take the locations {@code glBindAttribLocation} gave them; varyings, blocks included, get
 *       matching locations by name across the stages; fragment outputs keep theirs, else declaration order.</li>
 *   <li>Uniform and storage blocks, samplers and texel buffers get {@code set = 0} and a binding; a name used in
 *       both stages gets one. Loose uniforms move into a generated std140 block per stage.</li>
 *   <li>{@code gl_InstanceID}/{@code gl_VertexID} become {@code gl_InstanceIndex}/{@code gl_VertexIndex}, and
 *       GLSL 1.x's {@code texture2D} family {@code texture}.</li>
 *   <li>For GL's clip depth the vertex {@code main} is wrapped and ends with
 *       {@code gl_Position.z = (gl_Position.z + gl_Position.w) * 0.5}.</li>
 *   <li>What cannot be carried — a struct uniform declared before its struct, an array sized by something that is
 *       not a constant — throws {@link GlslRewriteException}, which the link reports.</li>
 * </ul>
 */
public final class CgGlslRewrite {

    /** A vertex input and the location it reads. */
    public record Attribute(String name, int location, int glType) {}

    /** A uniform or storage block: its binding in the set, and the GL binding point it starts on. */
    public record Block(String name, int binding, int glBinding) {}

    /** A sampler or texel buffer: its binding, and the texture unit it starts on. */
    public record Sampler(String name, int binding, int glType, boolean texel, int unit) {}

    /**
     * One leaf of a loose uniform, in each stage's generated block: {@code -1} where the stage does not declare it.
     * Matrices are {@code columns} vectors of {@code rows} values, each column 16 bytes apart; array elements are
     * {@code stride} apart.
     *
     * @param initial the declaration's initialiser, flattened, or {@code null}
     */
    public record Uniform(String name, int glType, int count, int stride, int vertexOffset, int fragmentOffset,
                          int columns, int rows, boolean integer, float[] initial) {}

    /**
     * @param vertexUniformBinding   the vertex stage's loose-uniform block, or -1 without one
     * @param fragmentUniformBinding the fragment stage's, or -1
     */
    public record Program(String vertexGlDepth, String vertexZeroToOne, String fragment,
                          List<Attribute> attributes, List<Block> uniformBlocks, List<Block> storageBlocks,
                          List<Sampler> samplers, List<Uniform> uniforms,
                          int vertexUniformBinding, int vertexUniformSize,
                          int fragmentUniformBinding, int fragmentUniformSize,
                          List<CgBindingLayout.Slot> slots) {}

    private static final String L = "(?:layout\\s*\\(([^)]*)\\)\\s*)?";
    private static final Pattern BLOCK = Pattern.compile("(?s)^" + L
            + "((?:\\w+\\s+)*?)(uniform|buffer|in|out)\\s+(\\w+)\\s*\\{(.*)\\}\\s*(\\w+)?\\s*(\\[[^\\]]*\\])?\\s*;$");
    private static final Pattern VAR = Pattern.compile("(?s)^" + L
            + "((?:(?:flat|smooth|noperspective|centroid|sample|invariant|precise|highp|mediump|lowp)\\s+)*)"
            + "(uniform|in|out|attribute|varying)\\s+"
            + "((?:(?:highp|mediump|lowp|flat|smooth|noperspective|centroid)\\s+)*)(\\w+)\\s+([^{]+);$");
    private static final Pattern STRUCT = Pattern.compile("(?s)^struct\\s+(\\w+)\\s*\\{(.*)\\}\\s*;$");
    private static final Pattern DECLARATOR = Pattern.compile("(?s)^\\s*(\\w+)\\s*(?:\\[\\s*([^\\]]*)\\s*\\])?\\s*(?:=\\s*(.+))?$");
    private static final Pattern VERSION = Pattern.compile("(?m)^[ \\t]*#[ \\t]*version[ \\t]+(\\d+).*$");
    private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void)?\\s*\\)");
    private static final Pattern NUMBER = Pattern.compile("[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?");

    private CgGlslRewrite() {}

    /**
     * @param attribLocations what {@code glBindAttribLocation} set before the link
     * @throws GlslRewriteException naming what cannot be carried to Vulkan
     */
    public static Program rewrite(String vertexSource, String fragmentSource, Map<String, Integer> attribLocations) {
        Stage v = new Stage(vertexSource, true);
        Stage f = new Stage(fragmentSource, false);

        int next = 0;
        int vLoose = v.loose.isEmpty() ? -1 : next++;
        int fLoose = f.loose.isEmpty() ? -1 : next++;
        Map<String, Integer> uniformBlocks = new LinkedHashMap<>(), storageBlocks = new LinkedHashMap<>();
        Map<String, Integer> samplers = new LinkedHashMap<>();
        for (Stage s : new Stage[] {v, f}) {
            for (BlockDecl b : s.uniformBlocks) if (!uniformBlocks.containsKey(b.name)) uniformBlocks.put(b.name, next++);
        }
        for (Stage s : new Stage[] {v, f}) {
            for (BlockDecl b : s.storageBlocks) if (!storageBlocks.containsKey(b.name)) storageBlocks.put(b.name, next++);
        }
        for (Stage s : new Stage[] {v, f}) {
            for (VarDecl d : s.samplers) {
                for (Declarator dc : d.declarators) {
                    if (dc.arraySize != 0) throw new GlslRewriteException("Sampler arrays are not carried: " + dc.name);
                    if (!samplers.containsKey(dc.name)) samplers.put(dc.name, next++);
                }
            }
        }

        Map<String, Integer> varyings = new HashMap<>();
        int nextVarying = 0;
        for (Object o : v.interfaceOrder) {
            if (o instanceof VarDecl d && d.kind.equals("out")) {
                for (Declarator dc : d.declarators) {
                    int loc = explicitLocation(d.layout, nextVarying);
                    varyings.put("v:" + dc.name, loc);
                    nextVarying = Math.max(nextVarying, loc + v.locations(d.type, dc.arraySize));
                }
            } else if (o instanceof BlockDecl b && b.kind.equals("out")) {
                int loc = explicitLocation(b.layout, nextVarying);
                varyings.put("b:" + b.name, loc);
                nextVarying = Math.max(nextVarying, loc + v.blockLocations(b));
            }
        }

        StringBuilder vBlock = new StringBuilder(), fBlock = new StringBuilder();
        List<Leaf> vLeaves = new ArrayList<>(), fLeaves = new ArrayList<>();
        int vSize = v.looseLayout(vBlock, vLeaves), fSize = f.looseLayout(fBlock, fLeaves);

        List<Attribute> attributes = new ArrayList<>();
        v.emit(vLoose, vBlock.toString(), uniformBlocks, storageBlocks, samplers, varyings, attribLocations, attributes);
        f.emit(fLoose, fBlock.toString(), uniformBlocks, storageBlocks, samplers, varyings, attribLocations, null);

        String vertex = v.finish();
        Matcher main = MAIN.matcher(vertex);
        if (!main.find()) throw new GlslRewriteException("The vertex stage has no main()");
        String renamed = vertex.substring(0, main.start()) + "void cg_main_user()" + vertex.substring(main.end());
        String glDepth = renamed + "\nvoid main() {\n    cg_main_user();\n"
                + "    gl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;\n}\n";
        String zeroToOne = renamed + "\nvoid main() {\n    cg_main_user();\n}\n";

        List<Block> ub = new ArrayList<>(), sb = new ArrayList<>();
        List<CgBindingLayout.Slot> slots = new ArrayList<>();
        if (vLoose >= 0) slots.add(new CgBindingLayout.Slot(vLoose, CgBindingLayout.Type.UNIFORM_BUFFER));
        if (fLoose >= 0) slots.add(new CgBindingLayout.Slot(fLoose, CgBindingLayout.Type.UNIFORM_BUFFER));
        for (Map.Entry<String, Integer> e : uniformBlocks.entrySet()) {
            ub.add(new Block(e.getKey(), e.getValue(), glBinding(v, f, e.getKey(), false)));
            slots.add(new CgBindingLayout.Slot(e.getValue(), CgBindingLayout.Type.UNIFORM_BUFFER));
        }
        for (Map.Entry<String, Integer> e : storageBlocks.entrySet()) {
            sb.add(new Block(e.getKey(), e.getValue(), glBinding(v, f, e.getKey(), true)));
            slots.add(new CgBindingLayout.Slot(e.getValue(), CgBindingLayout.Type.STORAGE_BUFFER));
        }
        List<Sampler> smp = new ArrayList<>();
        for (Map.Entry<String, Integer> e : samplers.entrySet()) {
            String type = samplerType(v, f, e.getKey());
            GlslType t = GlslType.of(type);
            if (t == null) throw new GlslRewriteException("Unsupported opaque type " + type + " " + e.getKey());
            smp.add(new Sampler(e.getKey(), e.getValue(), t.glType, t.texel, samplerUnit(v, f, e.getKey())));
            slots.add(new CgBindingLayout.Slot(e.getValue(), t.texel ? CgBindingLayout.Type.TEXEL_BUFFER
                    : CgBindingLayout.Type.SAMPLED_TEXTURE));
        }
        slots.sort(Comparator.comparingInt(CgBindingLayout.Slot::binding));

        return new Program(glDepth, zeroToOne, f.finish(), attributes, ub, sb, smp, merge(vLeaves, fLeaves),
                vLoose, vSize, fLoose, fSize, slots);
    }

    // ── one stage ──────────────────────────────────────────────────────────────

    private record Stmt(int start, int end, String text) {}

    private record Declarator(String name, int arraySize, String init) {}

    private record VarDecl(Stmt stmt, String layout, String quals, String kind, String precision, String type,
                           List<Declarator> declarators) {}

    private record BlockDecl(Stmt stmt, int brace, String layout, String quals, String kind, String name,
                             String body) {}

    private record Member(String type, String name, int arraySize) {}

    private record Leaf(String name, GlslType type, int count, int stride, int offset, float[] initial) {}

    private record Edit(int start, int end, String text) {}

    private static final class Stage {
        final boolean vertex;
        final String text;
        final String code;
        final Map<String, Integer> defines = new HashMap<>();
        final List<BlockDecl> uniformBlocks = new ArrayList<>(), storageBlocks = new ArrayList<>();
        final List<VarDecl> samplers = new ArrayList<>(), loose = new ArrayList<>();
        final List<Object> interfaceOrder = new ArrayList<>();
        final Map<String, List<Member>> structs = new HashMap<>();
        final Map<String, Integer> structAt = new HashMap<>();
        final List<Edit> edits = new ArrayList<>();
        boolean fragColor;

        Stage(String source, boolean vertex) {
            this.vertex = vertex;
            Matcher m = VERSION.matcher(source);
            int version = m.find() ? Integer.parseInt(m.group(1)) : 110;
            this.text = GlslConditionals.evaluate(source, version);
            this.code = blank(text);
            collectDefines();
            for (Stmt s : statements(code)) classify(s);
        }

        private void collectDefines() {
            Matcher m = Pattern.compile("(?m)^[ \\t]*#[ \\t]*define[ \\t]+(\\w+)[ \\t]+(\\d+)[ \\t]*$").matcher(text);
            while (m.find()) defines.put(m.group(1), Integer.parseInt(m.group(2)));
        }

        private void classify(Stmt s) {
            String t = s.text.trim();
            Matcher m;
            if ((m = STRUCT.matcher(t)).matches()) {
                structs.put(m.group(1), members(m.group(2)));
                structAt.put(m.group(1), s.start);
                return;
            }
            if ((m = BLOCK.matcher(t)).matches()) {
                String name = m.group(4);
                if (name.startsWith("gl_")) return;
                int brace = s.start + s.text.indexOf('{');
                BlockDecl b = new BlockDecl(s, brace, nz(m.group(1)), m.group(2), m.group(3), name, m.group(5));
                switch (b.kind) {
                    case "uniform": uniformBlocks.add(b); break;
                    case "buffer": storageBlocks.add(b); break;
                    default: interfaceOrder.add(b);
                }
                return;
            }
            if ((m = VAR.matcher(t)).matches()) {
                String kind = m.group(3);
                if (kind.equals("attribute")) kind = "in";
                if (kind.equals("varying")) kind = vertex ? "out" : "in";
                VarDecl d = new VarDecl(s, nz(m.group(1)), m.group(2), kind, m.group(4), m.group(5),
                        declarators(m.group(6)));
                if (kind.equals("uniform")) {
                    if (GlslType.isOpaque(d.type)) samplers.add(d);
                    else loose.add(d);
                } else {
                    interfaceOrder.add(d);
                }
            }
        }

        private List<Declarator> declarators(String list) {
            List<Declarator> out = new ArrayList<>();
            for (String part : splitTopLevel(list, ',')) {
                Matcher m = DECLARATOR.matcher(part);
                if (!m.matches()) throw new GlslRewriteException("Cannot read the declaration '" + part.trim() + "'");
                int size = m.group(2) == null ? 0 : m.group(2).isEmpty() ? -1 : constant(m.group(2).trim());
                out.add(new Declarator(m.group(1), size, m.group(3)));
            }
            return out;
        }

        private List<Member> members(String body) {
            List<Member> out = new ArrayList<>();
            for (String decl : splitTopLevel(body, ';')) {
                String d = decl.trim().replaceAll("^layout\\s*\\([^)]*\\)\\s*", "")
                        .replaceAll("^((?:flat|smooth|noperspective|centroid|highp|mediump|lowp|readonly|writeonly)\\s+)*", "");
                if (d.isEmpty()) continue;
                Matcher m = Pattern.compile("(?s)^(\\w+)\\s+(.+)$").matcher(d);
                if (!m.matches()) throw new GlslRewriteException("Cannot read the member '" + d + "'");
                for (Declarator dc : declarators(m.group(2))) out.add(new Member(m.group(1), dc.name, dc.arraySize));
            }
            return out;
        }

        private int constant(String s) {
            try {
                return Integer.parseInt(s.replaceAll("[uU]$", ""));
            } catch (NumberFormatException e) {
                Integer v = defines.get(s);
                if (v == null) throw new GlslRewriteException("An array size that is not a constant: '" + s + "'");
                return v;
            }
        }

        int locations(String type, int arraySize) {
            GlslType t = GlslType.of(type);
            int per = t != null ? t.locations() : structLocations(type);
            return per * Math.max(1, arraySize);
        }

        private int structLocations(String type) {
            List<Member> ms = structs.get(type);
            if (ms == null) throw new GlslRewriteException("Unknown type " + type);
            int n = 0;
            for (Member mb : ms) n += locations(mb.type, mb.arraySize);
            return n;
        }

        int blockLocations(BlockDecl b) {
            int n = 0;
            for (Member mb : members(b.body)) n += locations(mb.type, mb.arraySize);
            return n;
        }

        // ── the loose-uniform block ────────────────────────────────────────────

        /** Lays out this stage's loose uniforms in std140, writing the block's members; the block's size. */
        int looseLayout(StringBuilder block, List<Leaf> leaves) {
            int offset = 0;
            for (VarDecl d : loose) {
                for (Declarator dc : d.declarators) {
                    if (dc.arraySize < 0) throw new GlslRewriteException("An unsized uniform array: " + dc.name);
                    int[] as = alignSize(d.type, dc.arraySize);
                    offset = GlslType.roundUp(offset, as[0]);
                    flatten(d.type, dc.name, dc.arraySize, offset, dc.init, leaves);
                    block.append("    ").append(d.type).append(' ').append(dc.name);
                    if (dc.arraySize > 0) block.append('[').append(dc.arraySize).append(']');
                    block.append(';');
                    offset += as[1];
                }
            }
            return GlslType.roundUp(offset, 16);
        }

        /** {std140 alignment, size} of {@code type}, or of an array of it. */
        private int[] alignSize(String type, int arraySize) {
            GlslType t = GlslType.of(type);
            int align, size;
            if (t != null) {
                align = t.align();
                size = t.size();
            } else {
                int[] s = structLayout(type, null, null, 0);
                align = s[0];
                size = s[1];
            }
            if (arraySize > 0) {
                int stride = GlslType.roundUp(size, 16);
                return new int[] {GlslType.roundUp(align, 16), stride * arraySize};
            }
            return new int[] {align, size};
        }

        /** Lays a struct out from {@code base}, adding its leaves under {@code prefix} when given; {align, size}. */
        private int[] structLayout(String type, String prefix, List<Leaf> leaves, int base) {
            List<Member> ms = structs.get(type);
            if (ms == null) throw new GlslRewriteException("Unknown uniform type " + type);
            int offset = 0, align = 16;
            for (Member mb : ms) {
                int[] as = alignSize(mb.type, mb.arraySize);
                offset = GlslType.roundUp(offset, as[0]);
                align = Math.max(align, as[0]);
                if (leaves != null) flatten(mb.type, prefix + mb.name, mb.arraySize, base + offset, null, leaves);
                offset += as[1];
            }
            return new int[] {align, GlslType.roundUp(offset, align)};
        }

        private void flatten(String type, String name, int arraySize, int offset, String init, List<Leaf> leaves) {
            GlslType t = GlslType.of(type);
            if (t != null) {
                int stride = arraySize > 0 ? GlslType.roundUp(t.size(), 16) : t.size();
                leaves.add(new Leaf(name, t, Math.max(1, arraySize), stride, offset, init == null ? null : numbers(init)));
                return;
            }
            Integer at = structAt.get(type);
            if (at != null && !loose.isEmpty() && at > loose.get(0).stmt.start)
                throw new GlslRewriteException("Struct " + type + " is declared after the first loose uniform");
            if (arraySize <= 0) {
                structLayout(type, name + ".", leaves, offset);
                return;
            }
            int stride = GlslType.roundUp(structLayout(type, null, null, 0)[1], 16);
            for (int i = 0; i < arraySize; i++) structLayout(type, name + "[" + i + "].", leaves, offset + i * stride);
        }

        private static float[] numbers(String init) {
            Matcher m = NUMBER.matcher(init.replaceAll("\\b\\w+\\s*\\(", "("));
            List<Float> out = new ArrayList<>();
            while (m.find()) out.add(Float.parseFloat(m.group()));
            float[] a = new float[out.size()];
            for (int i = 0; i < a.length; i++) a[i] = out.get(i);
            return a;
        }

        // ── emitting ───────────────────────────────────────────────────────────

        void emit(int looseBinding, String looseMembers, Map<String, Integer> uniformBlockBindings,
                  Map<String, Integer> storageBlockBindings, Map<String, Integer> samplerBindings,
                  Map<String, Integer> varyings, Map<String, Integer> attribLocations, List<Attribute> attributes) {
            for (BlockDecl b : uniformBlocks) {
                edits.add(new Edit(start(b.stmt), b.brace, "layout(" + blockLayout(b.layout, "std140",
                        "set = 0, binding = " + uniformBlockBindings.get(b.name)) + ") " + b.quals + "uniform " + b.name + " "));
            }
            for (BlockDecl b : storageBlocks) {
                edits.add(new Edit(start(b.stmt), b.brace, "layout(" + blockLayout(b.layout, "std430",
                        "set = 0, binding = " + storageBlockBindings.get(b.name)) + ") " + b.quals + "buffer " + b.name + " "));
            }
            for (VarDecl d : samplers) {
                StringBuilder s = new StringBuilder();
                for (Declarator dc : d.declarators) {
                    s.append(s.length() == 0 ? "" : " ").append("layout(set = 0, binding = ")
                            .append(samplerBindings.get(dc.name)).append(") uniform ").append(d.precision)
                            .append(d.type).append(' ').append(dc.name).append(';');
                }
                replace(d.stmt, s.toString());
            }
            for (int i = 0; i < loose.size(); i++) {
                String block = i > 0 ? "" : "layout(std140, set = 0, binding = " + looseBinding + ") uniform CgLooseUniforms"
                        + (vertex ? "Vertex" : "Fragment") + " {" + looseMembers + " };";
                replace(loose.get(i).stmt, block);
            }
            int nextIn = 0, nextOut = 0;
            for (Object o : interfaceOrder) {
                if (o instanceof BlockDecl b) {
                    Integer loc = varyings.get("b:" + b.name);
                    if (loc == null) {
                        if (vertex) throw new GlslRewriteException("A vertex input block is not carried: " + b.name);
                        loc = fresh(varyings);
                    }
                    edits.add(new Edit(start(b.stmt), b.brace, "layout(" + stripped(b.layout, "location")
                            + "location = " + loc + ") " + b.quals + b.kind + " " + b.name + " "));
                    continue;
                }
                VarDecl d = (VarDecl) o;
                StringBuilder s = new StringBuilder();
                for (Declarator dc : d.declarators) {
                    int loc;
                    if (vertex && d.kind.equals("in")) {
                        Integer bound = attribLocations.get(dc.name);
                        loc = param(d.layout, "location") >= 0 ? param(d.layout, "location") : bound != null ? bound : nextIn;
                        nextIn = Math.max(nextIn, loc + locations(d.type, dc.arraySize));
                        GlslType t = GlslType.of(d.type);
                        attributes.add(new Attribute(dc.name, loc, t == null ? 0 : t.glType));
                    } else if (vertex || d.kind.equals("in")) {
                        Integer known = varyings.get("v:" + dc.name);
                        loc = known != null ? known : fresh(varyings);
                    } else {
                        loc = explicitLocation(d.layout, nextOut);
                        nextOut = Math.max(nextOut, loc + locations(d.type, dc.arraySize));
                    }
                    s.append(s.length() == 0 ? "" : " ").append("layout(location = ").append(loc).append(") ")
                            .append(d.quals).append(d.kind).append(' ').append(d.precision).append(d.type).append(' ')
                            .append(dc.name);
                    if (dc.arraySize > 0) s.append('[').append(dc.arraySize).append(']');
                    s.append(';');
                }
                replace(d.stmt, s.toString());
            }
            if (!vertex && Pattern.compile("\\bgl_FragColor\\b").matcher(code).find()) fragColor = true;
            if (!vertex && Pattern.compile("\\bgl_FragData\\b").matcher(code).find())
                throw new GlslRewriteException("gl_FragData is not carried; declare outputs");
        }

        String finish() {
            StringBuilder out = new StringBuilder(text);
            edits.sort((a, b) -> Integer.compare(b.start, a.start));
            for (Edit e : edits) out.replace(e.start, e.end, e.text);
            String s = out.toString();
            s = VERSION.matcher(s).replaceFirst(fragColor
                    ? "#version 450\nlayout(location = 0) out vec4 cg_FragColor;" : "#version 450");
            s = s.replaceAll("(?m)^[ \\t]*#[ \\t]*extension[ \\t]+GL_ARB_(?!gpu_shader_int64)\\w+.*$", "");
            if (vertex) {
                s = s.replaceAll("\\bgl_InstanceID\\b", "gl_InstanceIndex").replaceAll("\\bgl_VertexID\\b", "gl_VertexIndex");
            }
            s = s.replaceAll("\\b(?:texture2D|texture3D|textureCube)(\\s*\\()", "texture$1");
            if (fragColor) s = s.replaceAll("\\bgl_FragColor\\b", "cg_FragColor");
            return s;
        }

        /** Where a statement's text starts, past the whitespace before it. */
        private int start(Stmt s) {
            int i = 0;
            while (i < s.text.length() && Character.isWhitespace(s.text.charAt(i))) i++;
            return s.start + i;
        }

        /** Replaces a whole statement, keeping its line count. */
        private void replace(Stmt s, String with) {
            int from = start(s);
            StringBuilder t = new StringBuilder(with);
            for (int i = from; i < s.end; i++) if (text.charAt(i) == '\n') t.append('\n');
            edits.add(new Edit(from, s.end, t.toString()));
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static int fresh(Map<String, Integer> varyings) {
        int max = -1;
        for (int v : varyings.values()) max = Math.max(max, v);
        varyings.put("fresh:" + varyings.size(), max + 1);
        return max + 1;
    }

    private static int explicitLocation(String layout, int otherwise) {
        int loc = param(layout, "location");
        return loc >= 0 ? loc : otherwise;
    }

    /** An integer layout parameter, or -1. */
    private static int param(String layout, String key) {
        Matcher m = Pattern.compile("\\b" + key + "\\s*=\\s*(\\d+)").matcher(layout);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /** {@code layout}'s parameters without {@code set}, {@code binding} and {@code drop}, each followed by ", ". */
    private static String stripped(String layout, String drop) {
        StringBuilder s = new StringBuilder();
        for (String p : layout.split(",")) {
            String q = p.trim();
            if (q.isEmpty() || q.startsWith("set") || q.startsWith("binding") || q.startsWith(drop)) continue;
            s.append(q).append(", ");
        }
        return s.toString();
    }

    private static String blockLayout(String layout, String packing, String binding) {
        StringBuilder s = new StringBuilder();
        boolean packed = false;
        for (String p : layout.split(",")) {
            String q = p.trim();
            if (q.isEmpty() || q.startsWith("set") || q.startsWith("binding")) continue;
            if (q.equals("shared") || q.equals("packed")) q = packing;
            if (q.equals("std140") || q.equals("std430")) packed = true;
            s.append(q).append(", ");
        }
        if (!packed) s.insert(0, packing + ", ");
        return s.append(binding).toString();
    }

    private static int glBinding(Stage v, Stage f, String name, boolean storage) {
        for (Stage s : new Stage[] {v, f}) {
            for (BlockDecl b : storage ? s.storageBlocks : s.uniformBlocks) {
                if (b.name.equals(name) && param(b.layout, "binding") >= 0) return param(b.layout, "binding");
            }
        }
        return 0;
    }

    private static String samplerType(Stage v, Stage f, String name) {
        for (Stage s : new Stage[] {v, f}) {
            for (VarDecl d : s.samplers) {
                for (Declarator dc : d.declarators) if (dc.name.equals(name)) return d.type;
            }
        }
        throw new IllegalStateException(name);
    }

    private static int samplerUnit(Stage v, Stage f, String name) {
        for (Stage s : new Stage[] {v, f}) {
            for (VarDecl d : s.samplers) {
                for (Declarator dc : d.declarators) {
                    if (dc.name.equals(name) && param(d.layout, "binding") >= 0) return param(d.layout, "binding");
                }
            }
        }
        return 0;
    }

    private static List<Uniform> merge(List<Leaf> vertex, List<Leaf> fragment) {
        Map<String, Uniform> out = new LinkedHashMap<>();
        for (Leaf l : vertex) out.put(l.name, uniform(l, l.offset, -1));
        for (Leaf l : fragment) {
            Uniform u = out.get(l.name);
            if (u == null) {
                out.put(l.name, uniform(l, -1, l.offset));
            } else if (u.glType() != l.type.glType || u.count() != l.count) {
                throw new GlslRewriteException("Uniform " + l.name + " differs between the stages");
            } else {
                out.put(l.name, new Uniform(u.name(), u.glType(), u.count(), u.stride(), u.vertexOffset(), l.offset,
                        u.columns(), u.rows(), u.integer(), u.initial() != null ? u.initial() : l.initial));
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(out.values()));
    }

    private static Uniform uniform(Leaf l, int vertexOffset, int fragmentOffset) {
        return new Uniform(l.name, l.type.glType, l.count, l.stride, vertexOffset, fragmentOffset,
                l.type.columns, l.type.rows, l.type.integer, l.initial);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** Comments and directive lines as spaces, newlines kept: the text declarations are read from. */
    static String blank(String text) {
        char[] c = text.toCharArray();
        int n = c.length;
        for (int i = 0; i < n; i++) {
            if (c[i] == '/' && i + 1 < n && c[i + 1] == '/') {
                while (i < n && c[i] != '\n') c[i++] = ' ';
            } else if (c[i] == '/' && i + 1 < n && c[i + 1] == '*') {
                c[i] = c[i + 1] = ' ';
                i += 2;
                while (i < n && !(c[i] == '*' && i + 1 < n && c[i + 1] == '/')) {
                    if (c[i] != '\n') c[i] = ' ';
                    i++;
                }
                if (i < n) c[i] = ' ';
                if (i + 1 < n) c[++i] = ' ';
            } else if (c[i] == '#' && lineStart(c, i)) {
                while (i < n && c[i] != '\n') c[i++] = ' ';
            }
        }
        return new String(c);
    }

    private static boolean lineStart(char[] c, int i) {
        for (int j = i - 1; j >= 0 && c[j] != '\n'; j--) if (!Character.isWhitespace(c[j])) return false;
        return true;
    }

    /** Top-level statements; a function's body is skipped whole. */
    private static List<Stmt> statements(String code) {
        List<Stmt> out = new ArrayList<>();
        int start = 0, paren = 0, n = code.length();
        for (int i = 0; i < n; i++) {
            char c = code.charAt(i);
            if (c == '(') paren++;
            else if (c == ')') paren--;
            else if (c == '{') {
                int close = matching(code, i);
                if (code.substring(start, i).trim().endsWith(")")) start = close + 1;
                i = close;
            } else if (c == ';' && paren == 0) {
                out.add(new Stmt(start, i + 1, code.substring(start, i + 1)));
                start = i + 1;
            }
        }
        return out;
    }

    private static int matching(String code, int open) {
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return i;
        }
        throw new GlslRewriteException("Unbalanced braces");
    }

    private static List<String> splitTopLevel(String s, char sep) {
        List<String> out = new ArrayList<>();
        int depth = 0, from = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth--;
            else if (c == sep && depth == 0) {
                out.add(s.substring(from, i));
                from = i + 1;
            }
        }
        if (from < s.length() && !s.substring(from).trim().isEmpty()) out.add(s.substring(from));
        return out;
    }
}
