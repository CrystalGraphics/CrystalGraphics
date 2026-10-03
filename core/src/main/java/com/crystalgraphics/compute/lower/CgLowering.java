package com.crystalgraphics.compute.lower;

import com.crystalgraphics.compute.parse.CgComputeParser;
import com.crystalgraphics.compute.source.CgBufferAccess;
import com.crystalgraphics.compute.source.CgBufferAccessor;
import com.crystalgraphics.compute.source.CgBufferDecl;
import com.crystalgraphics.compute.source.CgComputeSource;
import com.crystalgraphics.compute.source.CgImageAccessor;
import com.crystalgraphics.compute.source.CgImageDecl;
import com.crystalgraphics.compute.source.CgImageDimension;
import com.crystalgraphics.compute.source.CgKernelDecl;
import com.crystalgraphics.compute.source.CgSourcePart;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How a kernel runs below compute (gpu-compute C5): the passes its shape lowers to, each a draw every GL from 3.3 has,
 * or the construct that stops it. GL-free.
 *
 * <pre>{@code
 * String why = CgLowering.refusal(source, kernel);         // null: it lowers
 * for (CgLowering.Pass pass : CgLowering.passes(source, kernel)) ...
 * }</pre>
 *
 * <ul>
 *   <li>{@link Kind#OUTPUT}: buffers the kernel writes its own element of, a fragment pass over their words laid out
 *       as texels, a render target each: up to {@link #MAX_TARGETS} buffers of one layout a pass.</li>
 *   <li>{@link Kind#APPEND}: an append buffer, captured from a geometry stage emitting a point per appended element,
 *       the points also counted into a texel.</li>
 *   <li>{@link Kind#SCATTER}: a buffer written at computed indices, as points into a texture laid out element to
 *       texel, one pass per operation (a store, or blended adds, minima, maxima).</li>
 *   <li>{@link Kind#IMAGE}: an image the kernel writes its own texel of, a fragment pass over it.</li>
 * </ul>
 *
 * <p>Every pass reads what the buffers held before the dispatch; what they write lands after the last, each target
 * read back into its buffer.</p>
 */
public final class CgLowering {

    /** The words one transform-feedback capture interleaves: GL 3.0's guaranteed 64 components. */
    public static final int MAX_CAPTURED_WORDS = 64;

    /** The render targets one pass writes: GL 3.3's guaranteed draw buffers. */
    public static final int MAX_TARGETS = 8;

    public enum Kind { OUTPUT, APPEND, SCATTER, IMAGE }

    /** A scatter pass's operation; a counter's {@code NAME_INC} is an add of one. */
    public enum Op { STORE, ADD, MIN, MAX }

    /**
     * One draw of a lowered kernel.
     *
     * @param buffers what it writes: an output pass's buffers, all of one layout; the one buffer an append or scatter
     *                pass writes; none for an image pass
     * @param floats  a scatter pass's target holds floats (it adds, takes minima or maxima), else the element's bits
     */
    public record Pass(Kind kind, List<CgBufferDecl> buffers, @Nullable CgImageDecl image, @Nullable Op op,
                       boolean floats) {

        /** The buffer an append or scatter pass writes, or an output pass's first. */
        public CgBufferDecl buffer() {
            return buffers.get(0);
        }
    }

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern COMMENTS = Pattern.compile("//[^\n]*|/\\*.*?\\*/", Pattern.DOTALL);

    private CgLowering() {}

    /**
     * Why {@code kernel} cannot run below compute, naming the construct, or null when it lowers. A general kernel's
     * reason is the first thing it reaches that only compute has.
     */
    @Nullable
    public static String refusal(CgComputeSource source, CgKernelDecl kernel) {
        String named = "kernel " + kernel.name();
        if (kernel.shape().unrestricted()) return named + " is general and " + generalConstruct(source, kernel);
        for (CgBufferDecl b : source.buffers()) {
            if (uses(kernel, b, CgBufferAccessor.APPEND) && b.stride() / 4 > MAX_CAPTURED_WORDS) {
                return named + " appends to " + b.name() + ", whose " + b.stride() + "-byte element is more than one "
                        + "capture of " + MAX_CAPTURED_WORDS + " words holds below compute";
            }
            if (b.access() == CgBufferAccess.COUNTER) {
                for (CgBufferAccessor a : new CgBufferAccessor[]{CgBufferAccessor.INC, CgBufferAccessor.ADD}) {
                    String call = b.name() + a.suffix;
                    if (uses(kernel, b, a) && resultUsed(source, kernel, call)) {
                        return named + " reads what " + call + " answers, the value before; below compute the add is "
                                + "a blend, which answers nothing";
                    }
                }
            }
        }
        for (CgImageDecl image : source.images()) {
            boolean writes = uses(kernel, image, CgImageAccessor.WRITE);
            boolean reads = uses(kernel, image, CgImageAccessor.LOAD) || uses(kernel, image, CgImageAccessor.SIZE);
            if ((writes || reads) && image.dimension() == CgImageDimension.CUBE) {
                return named + " reaches " + image.name() + ", a cube image, which GL below compute cannot fetch "
                        + "texels of or render a whole of";
            }
            if (writes && !image.format().renderable()) {
                return named + " writes " + image.name() + " as " + image.format().qualifier() + ", which GL 3 need not "
                        + "render into";
            }
        }
        return null;
    }

    /** The draws {@code kernel} lowers to, in the order they run. {@link #refusal} first. */
    public static List<Pass> passes(CgComputeSource source, CgKernelDecl kernel) {
        List<Pass> passes = new ArrayList<>();
        List<CgBufferDecl> written = new ArrayList<>();
        for (CgBufferDecl b : source.buffers()) if (uses(kernel, b, CgBufferAccessor.WRITE)) written.add(b);
        while (!written.isEmpty()) {
            int k = CgLoweredEmitter.texelsPerElement(written.get(0));
            List<CgBufferDecl> pass = new ArrayList<>();
            for (int i = 0; i < written.size() && pass.size() < MAX_TARGETS; ) {
                if (CgLoweredEmitter.texelsPerElement(written.get(i)) == k) pass.add(written.remove(i));
                else i++;
            }
            passes.add(new Pass(Kind.OUTPUT, List.copyOf(pass), null, null, false));
        }
        for (CgBufferDecl b : source.buffers()) {
            if (uses(kernel, b, CgBufferAccessor.APPEND)) passes.add(new Pass(Kind.APPEND, List.of(b), null, null, false));
        }
        for (CgBufferDecl b : source.buffers()) {
            boolean adds = uses(kernel, b, CgBufferAccessor.ADD) || uses(kernel, b, CgBufferAccessor.INC);
            boolean floats = adds || uses(kernel, b, CgBufferAccessor.MIN) || uses(kernel, b, CgBufferAccessor.MAX);
            List<CgBufferDecl> one = List.of(b);
            if (uses(kernel, b, CgBufferAccessor.STORE)) passes.add(new Pass(Kind.SCATTER, one, null, Op.STORE, floats));
            if (adds) passes.add(new Pass(Kind.SCATTER, one, null, Op.ADD, true));
            if (uses(kernel, b, CgBufferAccessor.MIN)) passes.add(new Pass(Kind.SCATTER, one, null, Op.MIN, true));
            if (uses(kernel, b, CgBufferAccessor.MAX)) passes.add(new Pass(Kind.SCATTER, one, null, Op.MAX, true));
        }
        for (CgImageDecl image : source.images()) {
            if (uses(kernel, image, CgImageAccessor.WRITE)) passes.add(new Pass(Kind.IMAGE, List.of(), image, null, false));
        }
        return passes;
    }

    /** Whether {@code kernel} reaches {@code buffer}'s accessor {@code accessor}. */
    public static boolean uses(CgKernelDecl kernel, CgBufferDecl buffer, CgBufferAccessor accessor) {
        return kernel.accessors().contains(buffer.name() + accessor.suffix);
    }

    public static boolean uses(CgKernelDecl kernel, CgImageDecl image, CgImageAccessor accessor) {
        return kernel.accessors().contains(image.name() + accessor.suffix);
    }

    /** Whether the kernel touches {@code buffer} at all. */
    public static boolean touches(CgKernelDecl kernel, CgBufferDecl buffer) {
        for (CgBufferAccessor a : CgBufferAccessor.values()) if (uses(kernel, buffer, a)) return true;
        return false;
    }

    public static boolean touches(CgKernelDecl kernel, CgImageDecl image) {
        for (CgImageAccessor a : CgImageAccessor.values()) if (uses(kernel, image, a)) return true;
        return false;
    }

    /** What a general kernel reaches that only compute has. */
    static String generalConstruct(CgComputeSource source, CgKernelDecl kernel) {
        if (!kernel.shared().isEmpty()) return "uses shared memory (" + kernel.shared().iterator().next() + ")";
        if (!kernel.subgroups().isEmpty()) return "uses " + kernel.subgroups().iterator().next();
        for (String accessor : kernel.accessors()) {
            if (accessor.endsWith(CgBufferAccessor.DATA.suffix)) return "uses " + accessor + ", a raw buffer array";
        }
        for (CgImageDecl image : source.images()) {
            for (CgImageAccessor a : new CgImageAccessor[]{CgImageAccessor.STORE, CgImageAccessor.ADD,
                    CgImageAccessor.MIN, CgImageAccessor.MAX}) {
                if (uses(kernel, image, a)) return "uses " + image.name() + a.suffix + ", an image write at any texel";
            }
        }
        for (String body : bodies(source, kernel)) {
            Matcher m = IDENTIFIER.matcher(body);
            while (m.find()) {
                String why = CgComputeParser.generalOnly(m.group());
                if (why != null && !m.group().startsWith("_cg_")) return "uses " + m.group() + ", which " + why;
            }
        }
        return "is declared so: a general kernel runs only where compute does";
    }

    /** Whether a call of {@code call} in the kernel's code is used as a value, not as a statement of its own. */
    static boolean resultUsed(CgComputeSource source, CgKernelDecl kernel, String call) {
        Pattern site = Pattern.compile("\\b" + call + "\\s*\\(");
        for (String body : bodies(source, kernel)) {
            Matcher m = site.matcher(body);
            while (m.find()) {
                int close = closing(body, m.end() - 1);
                if (close < 0) return true;
                int after = skip(body, close + 1, 1);
                if (after >= body.length() || body.charAt(after) != ';') return true;
                if (!statementStart(body, m.start())) return true;
            }
        }
        return false;
    }

    private static boolean statementStart(String body, int at) {
        int before = skip(body, at - 1, -1);
        if (before < 0) return true;
        char c = body.charAt(before);
        if (c == ';' || c == '{' || c == '}' || c == ')' || c == ':') return true;
        return body.startsWith("else", before - 3) && (before - 4 < 0 || !Character.isJavaIdentifierPart(body.charAt(before - 4)));
    }

    private static int skip(String body, int at, int step) {
        while (at >= 0 && at < body.length() && Character.isWhitespace(body.charAt(at))) at += step;
        return at;
    }

    private static int closing(String body, int open) {
        int depth = 0;
        for (int i = open; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return i;
        }
        return -1;
    }

    /** The text of every function the kernel reaches, comments blanked. */
    private static List<String> bodies(CgComputeSource source, CgKernelDecl kernel) {
        List<String> out = new ArrayList<>();
        for (CgSourcePart part : source.parts()) {
            if (part instanceof CgSourcePart.Function f && kernel.functions().contains(f.name())) {
                out.add(COMMENTS.matcher(f.text()).replaceAll(" "));
            }
        }
        return out;
    }
}
