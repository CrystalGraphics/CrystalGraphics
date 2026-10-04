package com.crystalgraphics.compute.parse;

import com.crystalgraphics.api.shader.CgShaderPreprocessor;
import com.crystalgraphics.compute.emit.CgGlslBuiltins;
import com.crystalgraphics.compute.source.CgBufferAccess;
import com.crystalgraphics.compute.source.CgBufferAccessor;
import com.crystalgraphics.compute.source.CgBufferDecl;
import com.crystalgraphics.compute.source.CgComputeSource;
import com.crystalgraphics.compute.source.CgImageAccess;
import com.crystalgraphics.compute.source.CgImageAccessor;
import com.crystalgraphics.compute.source.CgImageDecl;
import com.crystalgraphics.compute.source.CgImageDimension;
import com.crystalgraphics.compute.source.CgImageFormat;
import com.crystalgraphics.compute.source.CgKernelDecl;
import com.crystalgraphics.compute.source.CgKernelShape;
import com.crystalgraphics.compute.source.CgSourcePart;
import com.crystalgraphics.gl.buffer.shader.CgEngineBufferRegistry;
import com.crystalgraphics.gl.material.CgMaterialProperty;
import com.crystalgraphics.gl.material.parse.CgShaderParseException;
import com.crystalgraphics.gl.material.parse.CgShaderParser;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a {@code .compute} into a {@link CgComputeSource}, refusing what its kernels may not do: a kernel reaching
 * past its shape, an accessor its buffer's access does not give, an element a lowerable kernel's tier cannot hold.
 * GL-free; includes are expanded to see what a kernel calls, and kept as {@code #include} lines in what it emits.
 *
 * <pre>{@code
 * CgComputeSource source = CgComputeParser.parse(CgIO.loadSource(path), path);
 * }</pre>
 *
 * @throws CgShaderParseException naming the file, the kernel and the construct, placed at its line where it can be
 */
public final class CgComputeParser {

    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern MACRO_NAME = Pattern.compile("[A-Z][A-Z0-9_]*");
    private static final Pattern DECLARATION = Pattern.compile(
            "([A-Za-z_]\\w*)\\s*\\(\\s*\"([^\"]*)\"\\s*,\\s*([A-Za-z_]\\w*)\\s*,\\s*([A-Za-z_]\\w*)\\s*(?:,\\s*([A-Za-z0-9_]+)\\s*)?\\)\\s*;?");
    private static final Pattern DEFINE = Pattern.compile("#\\s*define\\s+([A-Za-z_]\\w*)(\\([^)]*\\))?\\s*(.*)");
    private static final Set<String> LOWERABLE_ELEMENTS =
            Set.of("float", "int", "uint", "vec2", "ivec2", "uvec2", "vec4", "ivec4", "uvec4");
    private static final Set<String> SIXTEEN_BYTES = Set.of("vec4", "ivec4", "uvec4");
    private static final Set<String> SCALARS = Set.of("float", "int", "uint");

    /** What only a general kernel may name, and why. */
    private static final Map<String, String> GENERAL_ONLY = new HashMap<>();
    private static final String[][] GENERAL_ONLY_PREFIXES = {
            {"imageAtomic", "is an image atomic: a general kernel's NAME_ADD"},
            {"subgroup", "is a subgroup operation"},
            {"gl_Subgroup", "is a subgroup's"},
            {"CG_SUBGROUP_", "is a subgroup operation"},
            {"CG_ATOMIC_", "is an atomic"},
    };

    static {
        for (String s : new String[]{"barrier", "memoryBarrier", "memoryBarrierShared", "memoryBarrierBuffer",
                "memoryBarrierImage", "memoryBarrierAtomicCounter", "groupMemoryBarrier"}) {
            GENERAL_ONLY.put(s, "synchronises a work group");
        }
        for (String s : new String[]{"atomicAdd", "atomicMin", "atomicMax", "atomicAnd", "atomicOr", "atomicXor",
                "atomicExchange", "atomicCompSwap", "atomicCounter", "atomicCounterIncrement", "atomicCounterDecrement"}) {
            GENERAL_ONLY.put(s, "is a raw atomic: a scatter kernel takes NAME_ADD, NAME_MIN, NAME_MAX or NAME_INC");
        }
        for (String s : new String[]{"imageLoad", "imageStore", "imageSize", "imageSamples"}) {
            GENERAL_ONLY.put(s, "reaches an image directly: Images { } gives NAME_LOAD, NAME_WRITE and NAME_SIZE");
        }
        for (String s : new String[]{"gl_GlobalInvocationID", "gl_LocalInvocationID", "gl_WorkGroupID", "gl_NumWorkGroups",
                "gl_LocalInvocationIndex", "gl_WorkGroupSize", "gl_NumSubgroups", "CG_LOCAL_ID", "CG_GROUP_ID",
                "CG_LOCAL_INDEX"}) {
            GENERAL_ONLY.put(s, "names a work group, which a kernel run without compute does not have: "
                    + "CG_ELEMENT and CG_DISPATCH_ID say where it is");
        }
    }

    private final String source;
    private final String path;
    private final String blanked;

    private final List<KernelPragma> kernelPragmas = new ArrayList<>();
    private final Map<String, String> fallbacks = new LinkedHashMap<>();
    private final Map<String, Integer> computeOnly = new LinkedHashMap<>();
    private final List<String> features = new ArrayList<>();
    private final List<String> uses = new ArrayList<>();
    private final List<String> extensions = new ArrayList<>();
    private final Map<String, TopLevel.Item> structs = new LinkedHashMap<>();
    private final Map<String, String> structBodies = new HashMap<>();
    private List<CgMaterialProperty> properties = Collections.emptyList();
    private final List<CgBufferDecl> buffers = new ArrayList<>();
    private final List<CgImageDecl> images = new ArrayList<>();

    /** Each generated name, and what it reaches. */
    private final Map<String, Reach> generated = new LinkedHashMap<>();
    /** The file and its includes: each function's bodies, every shared variable, every define. */
    private final Map<String, List<String>> functionBodies = new HashMap<>();
    private final Map<String, TopLevel.Item> sharedItems = new LinkedHashMap<>();
    private final Map<String, String> defines = new HashMap<>();
    private final Map<String, String> constantDefines = new HashMap<>();

    private record KernelPragma(String name, int x, int y, int z, int dimensions, CgKernelShape shape, int at) {}

    /** A generated name: an accessor on a buffer, or on an image. */
    private record Reach(CgBufferDecl buffer, CgBufferAccessor onBuffer, CgImageDecl image, CgImageAccessor onImage) {
        String refusal(CgKernelShape shape) {
            return buffer != null ? onBuffer.refusal(shape, buffer) : onImage.refusal(shape, image);
        }
    }

    private CgComputeParser(String source, String path) {
        this.source = source.replace("\r\n", "\n");
        this.path = path;
        this.blanked = GlslText.blankComments(this.source);
    }

    public static CgComputeSource parse(String source, String path) {
        CgComputeParser parser = new CgComputeParser(source, path);
        try {
            return parser.run();
        } catch (CgShaderParseException e) {
            throw e.locate(parser.source, quoted(e.getMessage()));
        }
    }

    private CgComputeSource run() {
        indexExpanded();
        List<TopLevel.Item> items = TopLevel.scan(blanked, path);
        for (TopLevel.Item item : items) {
            if (item.kind() != TopLevel.Kind.STRUCT) continue;
            structs.put(item.name(), item);
            structBodies.put(item.name(), item.body());
        }
        for (TopLevel.Item item : items) {
            switch (item.kind()) {
                case DIRECTIVE -> directive(item);
                case BLOCK -> block(item);
                case FUNCTION -> {
                    if (item.name().equals("main")) throw fail(item.start(), "defines main(): a kernel is its own "
                            + "function, which the compiler calls");
                }
                default -> { }
            }
        }
        if (kernelPragmas.isEmpty()) throw fail(0, "declares no kernel: '#pragma kernel Name [x [y [z]]] shape'");

        indexGenerated();
        List<String> engineBuffers = engineBuffers();

        for (Map.Entry<String, Integer> e : computeOnly.entrySet()) {
            if (kernelPragmas.stream().noneMatch(k -> k.name().equals(e.getKey()))) {
                throw fail(e.getValue(), "#pragma compute_only names no kernel '" + e.getKey() + "'");
            }
            if (fallbacks.containsKey(e.getKey())) {
                throw fail(e.getValue(), "kernel " + e.getKey() + " is compute_only and names a #pragma fallback: one "
                        + "runs below compute, the other says it never does");
            }
        }
        List<CgKernelDecl> kernels = new ArrayList<>();
        for (KernelPragma k : kernelPragmas) kernels.add(analyze(k, items));
        checkFallbacks(kernels);

        return new CgComputeSource(path, List.copyOf(kernels), List.copyOf(buffers), List.copyOf(images), properties,
                List.copyOf(features), engineBuffers, List.copyOf(extensions), parts(items));
    }

    // ── Directives ────────────────────────────────────────────────────────────

    private void directive(TopLevel.Item item) {
        String line = GlslText.squash(item.header());
        if (line.startsWith("#version")) {
            throw fail(item.start(), "declares #version: the compiler writes each kernel's for the device");
        }
        if (line.startsWith("#extension")) {
            extensions.add(source.substring(item.start(), item.end()).trim());
        } else if (line.startsWith("#pragma kernel")) {
            kernelPragma(line.substring("#pragma kernel".length()).trim(), item.start());
        } else if (line.startsWith("#pragma fallback")) {
            String[] t = line.substring("#pragma fallback".length()).trim().split(" ");
            if (t.length != 2 || t[0].isEmpty()) throw fail(item.start(), "#pragma fallback takes a kernel and the kernel "
                    + "a tier without compute runs instead: '#pragma fallback Bin BinScatter'");
            if (fallbacks.put(t[0], t[1]) != null) throw fail(item.start(), "names a second fallback for " + t[0]);
        } else if (line.startsWith("#pragma compute_only")) {
            String rest = line.substring("#pragma compute_only".length()).trim();
            if (rest.isEmpty()) throw fail(item.start(), "#pragma compute_only takes the kernels that run only where compute "
                    + "does: '#pragma compute_only Sort Scan'");
            for (String name : rest.split(" ")) {
                if (computeOnly.put(name, item.start()) != null) throw fail(item.start(), "#pragma compute_only names " + name + " twice");
            }
        } else if (line.startsWith("#pragma cg_feature")) {
            String name = line.substring("#pragma cg_feature".length()).trim();
            if (!NAME.matcher(name).matches()) throw fail(item.start(), "#pragma cg_feature: '" + name + "' is no name");
            if (features.contains(name)) throw fail(item.start(), "#pragma cg_feature: '" + name + "' twice");
            if (features.size() == 8) throw fail(item.start(), "#pragma cg_feature: more than 8 keywords");
            features.add(name);
        } else if (line.startsWith("#pragma cg_use")) {
            String token = line.substring("#pragma cg_use".length()).trim();
            if (!NAME.matcher(token).matches()) throw fail(item.start(), "#pragma cg_use: '" + token + "' is no token");
            if (uses.contains(token)) throw fail(item.start(), "#pragma cg_use: '" + token + "' twice");
            uses.add(token);
        }
    }

    private void kernelPragma(String rest, int at) {
        String[] t = rest.isEmpty() ? new String[0] : rest.split(" ");
        String usage = "'#pragma kernel Name [x [y [z]]] shape', shape one of map, gather, append, scatter, image, general";
        if (t.length < 2 || t.length > 5) throw fail(at, "#pragma kernel " + rest + ": " + usage);
        String name = t[0];
        if (!NAME.matcher(name).matches() || reserved(name)) throw fail(at, "#pragma kernel: '" + name + "' cannot "
                + "name a kernel");
        CgKernelShape shape = CgKernelShape.of(t[t.length - 1]);
        if (shape == null) throw fail(at, "#pragma kernel " + name + " needs a shape last: " + usage);
        int[] size = {CgKernelDecl.DEFAULT_SIZE, 1, 1};
        int dimensions = Math.max(1, t.length - 2);
        for (int i = 1; i < t.length - 1; i++) {
            try {
                size[i - 1] = Integer.parseInt(t[i]);
            } catch (NumberFormatException e) {
                throw fail(at, "#pragma kernel " + name + ": '" + t[i] + "' is no size; " + usage);
            }
            if (size[i - 1] < 1) throw fail(at, "#pragma kernel " + name + ": a size is at least 1");
        }
        for (KernelPragma k : kernelPragmas) {
            if (k.name().equals(name)) throw fail(at, "declares kernel " + name + " twice");
        }
        kernelPragmas.add(new KernelPragma(name, size[0], size[1], size[2], dimensions, shape, at));
    }

    // ── Blocks ────────────────────────────────────────────────────────────────

    private void block(TopLevel.Item item) {
        switch (item.name()) {
            case "Properties" -> {
                if (!properties.isEmpty()) throw fail(item.start(), "declares Properties twice");
                properties = CgShaderParser.parseProperties(blanked.substring(item.start(), item.end()), path);
            }
            case "Buffers" -> {
                if (!buffers.isEmpty()) throw fail(item.start(), "declares Buffers twice");
                declarations(item, true);
            }
            default -> {
                if (!images.isEmpty()) throw fail(item.start(), "declares Images twice");
                declarations(item, false);
            }
        }
    }

    private void declarations(TopLevel.Item block, boolean isBuffers) {
        int bodyStart = blanked.indexOf('{', block.start()) + 1;
        int offset = 0;
        for (String raw : block.body().split("\n", -1)) {
            int at = bodyStart + offset;
            offset += raw.length() + 1;
            String line = raw.trim();
            if (line.isEmpty()) continue;
            Matcher m = DECLARATION.matcher(line);
            if (!m.matches()) {
                throw fail(at, isBuffers
                        ? "Buffers: '" + line + "' is not 'NAME (\"Display\", element, access)'"
                        : "Images: '" + line + "' is not 'NAME (\"Display\", format, access [, 2d|3d|2darray|cube])'");
            }
            String name = m.group(1);
            if (!MACRO_NAME.matcher(name).matches() || name.startsWith("CG_")) {
                throw fail(at, "'" + name + "' cannot name a " + (isBuffers ? "buffer" : "image")
                        + ": upper case, digits and '_', not starting CG_");
            }
            if (buffer(name) != null || image(name) != null) throw fail(at, "declares " + name + " twice");
            if (isBuffers) buffers.add(buffer(m, at, block.start()));
            else images.add(image(m, at));
        }
    }

    private CgBufferDecl buffer(Matcher m, int at, int blockStart) {
        String name = m.group(1);
        String element = m.group(3);
        CgBufferAccess access = CgBufferAccess.of(m.group(4));
        if (access == null) throw fail(at, name + ": '" + m.group(4) + "' is no access: readonly, writeonly, readwrite, "
                + "append or counter");
        if (m.group(5) != null) throw fail(at, name + ": a buffer takes a name, an element and an access");
        TopLevel.Item struct = structs.get(element);
        if (struct != null && struct.start() > blockStart) {
            throw fail(at, name + "'s element " + element + " is declared after Buffers { }: declare it before");
        }
        Std430 std430 = new Std430(structBodies, e -> ConstantInt.eval(e, constantDefines));
        Std430.Layout layout = std430.of(element);
        if (layout == null) throw fail(at, name + ": '" + element + "' is no type this compiler lays out: a scalar, "
                + "vector, matrix or struct declared above, its arrays sized by integers");
        if (access == CgBufferAccess.COUNTER && !element.equals("uint") && !element.equals("int")) {
            throw fail(at, name + " is a counter buffer, whose element is uint or int");
        }
        return new CgBufferDecl(name, m.group(2), element, access, layout.stride(), lowerable(element, structBodies),
                SCALARS.contains(element), buffers.size(), List.copyOf(std430.fieldsOf(element)));
    }

    /**
     * A {@code .shader}'s {@code Buffers { }}: the buffers a material reads of those kernels write, in a kernel's
     * grammar. Each is {@code readonly}, of an element every tier holds. {@code structs} holds the body of each struct
     * declared before the block, by name.
     *
     * <pre>{@code
     * List<CgBufferDecl> read = CgComputeParser.materialBuffers("SPARKS (\"Sparks\", Spark, readonly)",
     *         Map.of("Spark", "vec4 positionLife; vec4 velocitySeed;"), path);
     * }</pre>
     */
    public static List<CgBufferDecl> materialBuffers(String block, Map<String, String> structs, String path) {
        List<CgBufferDecl> read = new ArrayList<>();
        Std430 std430 = new Std430(structs, e -> ConstantInt.eval(e, Map.of()));
        for (String raw : block.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            Matcher m = DECLARATION.matcher(line);
            if (!m.matches() || m.group(5) != null) {
                throw new CgShaderParseException("[" + path + "] Buffers: '" + line + "' is not 'NAME (\"Display\", element, readonly)'");
            }
            String name = m.group(1);
            String element = m.group(3);
            if (!MACRO_NAME.matcher(name).matches() || name.startsWith("CG_")) {
                throw new CgShaderParseException("[" + path + "] '" + name + "' cannot name a buffer: upper case, digits and '_', not starting CG_");
            }
            for (CgBufferDecl b : read) {
                if (b.name().equals(name)) throw new CgShaderParseException("[" + path + "] declares buffer '" + name + "' twice");
            }
            if (CgBufferAccess.of(m.group(4)) != CgBufferAccess.READONLY) {
                throw new CgShaderParseException("[" + path + "] buffer '" + name + "' is " + m.group(4)
                        + ": a material only reads a buffer, so it is readonly");
            }
            Std430.Layout layout = std430.of(element);
            if (layout == null) throw new CgShaderParseException("[" + path + "] buffer " + name + ": '" + element
                    + "' is no type this compiler lays out: a scalar, vector or struct declared before Buffers { }");
            if (!lowerable(element, structs)) {
                throw new CgShaderParseException("[" + path + "] buffer " + name + "'s element '" + element + "' is not one "
                        + "every tier holds: float, int or uint, their 2- and 4-vectors, or a struct of vec4, ivec4 and uvec4 only");
            }
            read.add(new CgBufferDecl(name, m.group(2), element, CgBufferAccess.READONLY, layout.stride(), true,
                    SCALARS.contains(element), read.size(), List.copyOf(std430.fieldsOf(element))));
        }
        return List.copyOf(read);
    }

    private CgImageDecl image(Matcher m, int at) {
        String name = m.group(1);
        CgImageFormat format = CgImageFormat.of(m.group(3));
        if (format == null) throw fail(at, name + ": '" + m.group(3) + "' is no image format (rgba8, r32f, r32ui...)");
        CgImageAccess access = CgImageAccess.of(m.group(4));
        if (access == null) throw fail(at, name + ": '" + m.group(4) + "' is no access: readonly, writeonly or readwrite");
        CgImageDimension dimension = m.group(5) == null ? CgImageDimension.D2 : CgImageDimension.of(m.group(5));
        if (dimension == null) throw fail(at, name + ": '" + m.group(5) + "' is no dimension: 2d, 3d, 2darray or cube");
        return new CgImageDecl(name, m.group(2), format, access, dimension, images.size());
    }

    /** A lower tier's texel or capture holds it: a 4-, 8- or 16-byte scalar or vector, or 16-byte fields only. */
    private static boolean lowerable(String element, Map<String, String> structBodies) {
        if (LOWERABLE_ELEMENTS.contains(element)) return true;
        String body = structBodies.get(element);
        String[][] fields = body == null ? null : Std430.fields(body);
        if (fields == null || fields.length == 0) return false;
        for (String[] field : fields) if (!SIXTEEN_BYTES.contains(field[0]) || !field[1].isEmpty()) return false;
        return true;
    }

    // ── What the code reaches ─────────────────────────────────────────────────

    private void indexGenerated() {
        Set<String> taken = new LinkedHashSet<>();
        for (CgMaterialProperty p : properties) taken.add(p.getName());
        for (KernelPragma k : kernelPragmas) taken.add(k.name());
        taken.addAll(structs.keySet());
        for (CgBufferDecl b : buffers) {
            for (CgBufferAccessor a : CgBufferAccessor.values()) generate(b.name() + a.suffix, new Reach(b, a, null, null), taken);
        }
        for (CgImageDecl i : images) {
            for (CgImageAccessor a : CgImageAccessor.values()) generate(i.name() + a.suffix, new Reach(null, null, i, a), taken);
        }
    }

    private void generate(String name, Reach target, Set<String> taken) {
        if (!taken.add(name)) throw fail(0, "'" + name + "' is both a generated accessor and another name in this file");
        generated.put(name, target);
    }

    /** Functions, shared variables and defines across the file and everything it includes. */
    private void indexExpanded() {
        String expanded = GlslText.blankComments(new CgShaderPreprocessor().process(source, path).replace("\r\n", "\n"));
        for (TopLevel.Item item : TopLevel.scan(expanded, path)) {
            switch (item.kind()) {
                case FUNCTION -> functionBodies.computeIfAbsent(item.name(), n -> new ArrayList<>()).add(item.body());
                case SHARED -> sharedItems.put(item.name(), item);
                case DIRECTIVE -> {
                    Matcher d = DEFINE.matcher(GlslText.squash(item.header()));
                    if (d.matches()) {
                        defines.put(d.group(1), d.group(3));
                        if (d.group(2) == null) constantDefines.put(d.group(1), d.group(3));
                    }
                }
                default -> { }
            }
        }
    }

    private CgKernelDecl analyze(KernelPragma k, List<TopLevel.Item> items) {
        boolean defined = false;
        for (TopLevel.Item item : items) {
            if (item.kind() != TopLevel.Kind.FUNCTION || !item.name().equals(k.name())) continue;
            Matcher m = TopLevel.FUNCTION.matcher(item.header());
            m.matches();
            String params = m.group(3).trim();
            if (!m.group(1).equals("void") || !(params.isEmpty() || params.equals("void"))) {
                throw fail(item.start(), "kernel " + k.name() + " is 'void " + k.name() + "()': it takes nothing and "
                        + "answers nothing");
            }
            defined = true;
        }
        if (!defined) throw fail(k.at(), "declares kernel " + k.name() + " but defines no 'void " + k.name() + "()'");

        Set<String> functions = new LinkedHashSet<>();
        Set<String> used = new LinkedHashSet<>();
        Set<String> expandedMacros = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        functions.add(k.name());
        queue.add(k.name());
        while (!queue.isEmpty()) {
            for (String body : functionBodies.getOrDefault(queue.poll(), List.of())) {
                Deque<String> idents = new ArrayDeque<>(GlslText.identifiers(body));
                while (!idents.isEmpty()) {
                    String id = idents.poll();
                    if (!used.add(id)) continue;
                    if (functionBodies.containsKey(id) && functions.add(id)) queue.add(id);
                    String macro = defines.get(id);
                    if (macro != null && expandedMacros.add(id)) idents.addAll(GlslText.identifiers(macro));
                }
            }
        }

        String kernel = "kernel " + k.name() + " (" + k.shape().name().toLowerCase() + ")";
        Set<String> shared = new LinkedHashSet<>();
        Set<String> accessors = new LinkedHashSet<>();
        Set<String> subgroups = new LinkedHashSet<>();
        Set<String> builtins = new LinkedHashSet<>();
        Set<CgBufferDecl> touched = new LinkedHashSet<>();
        for (String id : used) {
            if (sharedItems.containsKey(id)) shared.add(id);
            if (CgGlslBuiltins.versioned(id) && !functionBodies.containsKey(id) && !defines.containsKey(id)) builtins.add(id);
            if (id.startsWith("CG_SUBGROUP_")) subgroups.add(id);
            Reach target = generated.get(id);
            if (target != null) {
                String refusal = target.refusal(k.shape());
                if (refusal != null) throw fail(id, kernel + " uses " + id + ": " + refusal);
                accessors.add(id);
                if (target.buffer() != null) touched.add(target.buffer());
            }
            boolean own = functionBodies.containsKey(id) || defines.containsKey(id);
            if (!k.shape().unrestricted() && !own) {
                String why = generalOnly(id);
                if (why != null) throw fail(id, kernel + " reaches " + id + ", which " + why + "; declare it general");
            }
        }
        Set<String> samplers = new LinkedHashSet<>();
        for (CgMaterialProperty p : properties) if (p.getType().isSampler() && used.contains(p.getName())) samplers.add(p.getName());
        if (!k.shape().unrestricted() && !shared.isEmpty()) {
            String first = shared.iterator().next();
            throw fail(first, kernel + " reaches shared " + first + ": shared memory is a general kernel's");
        }
        if (k.shape().lowerable()) {
            for (CgBufferDecl b : touched) {
                if (!b.lowerable()) throw fail(b.name(), kernel + " reaches " + b.name() + ", whose element " + b.element()
                        + " a tier without compute cannot hold: a float, int or uint, their 2- and 4-vectors, or a "
                        + "struct of vec4, ivec4 or uvec4 only. Declare it general to keep the layout");
            }
        }
        if (k.shape() == CgKernelShape.APPEND && buffers.stream().noneMatch(b -> b.access() == CgBufferAccess.APPEND)) {
            throw fail(k.at(), kernel + " has no append buffer to append to: declare one in Buffers { }");
        }
        if (k.shape() == CgKernelShape.IMAGE && images.stream().noneMatch(i -> i.access().writable())) {
            throw fail(k.at(), kernel + " has no writable image: declare one in Images { }");
        }
        return new CgKernelDecl(k.name(), k.x(), k.y(), k.z(), k.dimensions(), k.shape(), fallbacks.get(k.name()),
                Collections.unmodifiableSet(functions), Collections.unmodifiableSet(shared),
                Collections.unmodifiableSet(accessors), Collections.unmodifiableSet(samplers),
                Collections.unmodifiableSet(subgroups), sharedBytes(k, shared),
                Collections.unmodifiableSet(builtins), computeOnly.containsKey(k.name()));
    }

    /** Why only a general kernel may name {@code id}, or null when any kernel may: what a refusal names. */
    public static String generalOnly(String id) {
        String why = GENERAL_ONLY.get(id);
        if (why != null) return why;
        if (id.startsWith("_cg_")) return "is the engine's own";
        for (String[] p : GENERAL_ONLY_PREFIXES) if (id.startsWith(p[0])) return p[1];
        return null;
    }

    private int sharedBytes(KernelPragma k, Set<String> shared) {
        Map<String, String> names = new HashMap<>(constantDefines);
        int group = k.x() * k.y() * k.z();
        names.put("CG_LOCAL_SIZE_X", Integer.toString(k.x()));
        names.put("CG_LOCAL_SIZE_Y", Integer.toString(k.y()));
        names.put("CG_LOCAL_SIZE_Z", Integer.toString(k.z()));
        names.put("CG_GROUP_SIZE", Integer.toString(group));
        names.put("CG_GROUP_POW2", Integer.toString(Integer.highestOneBit(group) == group ? group : Integer.highestOneBit(group) << 1));
        Std430 layout = new Std430(structBodies, e -> ConstantInt.eval(e, names));
        int bytes = 0;
        for (String name : shared) {
            String[] type = TopLevel.sharedType(sharedItems.get(name));
            Std430.Layout l = layout.array(type[0], type[1]);
            if (l == null) return -1;
            bytes += l.size();
        }
        return bytes;
    }

    private void checkFallbacks(List<CgKernelDecl> kernels) {
        for (Map.Entry<String, String> e : fallbacks.entrySet()) {
            CgKernelDecl kernel = kernels.stream().filter(k -> k.name().equals(e.getKey())).findFirst().orElse(null);
            CgKernelDecl fallback = kernels.stream().filter(k -> k.name().equals(e.getValue())).findFirst().orElse(null);
            if (kernel == null) throw fail("#pragma fallback", "#pragma fallback names no kernel '" + e.getKey() + "'");
            if (fallback == null) throw fail("#pragma fallback", "#pragma fallback " + e.getKey() + " names no kernel '"
                    + e.getValue() + "'");
            if (kernel.shape().lowerable()) throw fail("#pragma fallback", "kernel " + kernel.name() + " is "
                    + kernel.shape().name().toLowerCase() + ", which every tier runs: only a general kernel takes a fallback");
            if (!fallback.shape().lowerable()) throw fail("#pragma fallback", kernel.name() + "'s fallback "
                    + fallback.name() + " is general itself: a fallback is lowerable");
        }
    }

    private List<String> engineBuffers() {
        for (String token : uses) {
            if (CgEngineBufferRegistry.get(token) == null) {
                throw fail("#pragma cg_use", "#pragma cg_use: unknown buffer token '" + token + "' -- registered: "
                        + CgEngineBufferRegistry.tokens());
            }
        }
        List<String> declared = CgEngineBufferRegistry.withRequirements(uses);
        Set<String> code = GlslText.identifiers(blanked);
        for (String token : CgEngineBufferRegistry.tokens()) {
            if (declared.contains(token)) continue;
            String macro = CgEngineBufferRegistry.get(token).macroName();
            String family = "CG_" + (macro.endsWith("_DATA") ? macro.substring(0, macro.length() - 5) : macro) + "_";
            for (String id : code) {
                if (id.equals(macro) || id.startsWith(family)) {
                    throw fail(id, "uses '" + id + "' but does not declare it: add '#pragma cg_use " + token + "'");
                }
            }
        }
        return declared;
    }

    // ── Parts ─────────────────────────────────────────────────────────────────

    private List<CgSourcePart> parts(List<TopLevel.Item> items) {
        List<CgSourcePart> parts = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int cursor = 0;
        for (TopLevel.Item item : items) {
            text.append(source, cursor, item.start());
            cursor = item.end();
            String original = source.substring(item.start(), item.end());
            CgSourcePart part = switch (item.kind()) {
                case FUNCTION -> new CgSourcePart.Function(item.name(), original);
                case SHARED -> new CgSourcePart.Shared(item.name(), original);
                case BLOCK -> switch (item.name()) {
                    case "Buffers" -> new CgSourcePart.Buffers();
                    case "Images" -> new CgSourcePart.Images();
                    default -> null;
                };
                case DIRECTIVE -> {
                    String line = GlslText.squash(item.header());
                    if (line.startsWith("#pragma") || line.startsWith("#extension")) yield null;
                    text.append(original);
                    yield null;
                }
                default -> {
                    text.append(original);
                    yield null;
                }
            };
            if (part != null) {
                flush(text, parts);
                parts.add(part);
            }
        }
        text.append(source, cursor, source.length());
        flush(text, parts);
        return List.copyOf(parts);
    }

    private static void flush(StringBuilder text, List<CgSourcePart> parts) {
        if (!text.toString().isBlank()) parts.add(new CgSourcePart.Text(text.toString()));
        text.setLength(0);
    }

    // ── Failures ──────────────────────────────────────────────────────────────

    private CgBufferDecl buffer(String name) {
        for (CgBufferDecl b : buffers) if (b.name().equals(name)) return b;
        return null;
    }

    private CgImageDecl image(String name) {
        for (CgImageDecl i : images) if (i.name().equals(name)) return i;
        return null;
    }

    private static boolean reserved(String name) {
        return name.startsWith("cg_") || name.startsWith("CG_") || name.startsWith("_cg_") || name.startsWith("_v2f_");
    }

    private CgShaderParseException fail(int at, String message) {
        return CgShaderParseException.at("[" + path + "] " + message, source, at);
    }

    /** Placed at the first whole-word {@code token} in the code, or unplaced. */
    private CgShaderParseException fail(String token, String message) {
        Matcher m = Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(token) + "(?![A-Za-z0-9_])").matcher(blanked);
        return m.find() ? fail(m.start(), message) : new CgShaderParseException("[" + path + "] " + message);
    }

    private static String quoted(String message) {
        Matcher m = Pattern.compile("'([^']{1,80})'").matcher(message == null ? "" : message);
        return m.find() ? m.group(1) : null;
    }
}
