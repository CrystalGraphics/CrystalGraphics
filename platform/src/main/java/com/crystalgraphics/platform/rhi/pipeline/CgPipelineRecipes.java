package com.crystalgraphics.platform.rhi.pipeline;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.crystalgraphics.platform.rhi.format.CgAttribFormat;
import com.crystalgraphics.platform.rhi.format.CgCompare;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.platform.rhi.pipeline.CgGraphicsPipelineDesc.Blend;
import com.crystalgraphics.platform.rhi.pipeline.CgGraphicsPipelineDesc.ColorTarget;
import com.crystalgraphics.platform.rhi.pipeline.CgGraphicsPipelineDesc.DepthStencil;
import com.crystalgraphics.platform.rhi.pipeline.CgGraphicsPipelineDesc.Raster;
import com.crystalgraphics.platform.rhi.pipeline.CgGraphicsPipelineDesc.StencilFace;
import com.crystalgraphics.platform.rhi.pipeline.CgGraphicsPipelineDesc.VertexAttrib;
import com.crystalgraphics.platform.rhi.pipeline.CgGraphicsPipelineDesc.VertexBuffer;
import com.crystalgraphics.platform.rhi.shader.CgShaderModule;
import com.github.bsideup.jabel.Desugar;

/**
 * The recorded pipeline list, Unreal's bundled PSO cache: every pipeline a draw built, written down by its program's
 * key (a hash of its sources) and the rest of its description, so a later start builds it on a worker as soon as that
 * program links, inside the loading screen rather than at the draw. Read from the list shipped in the jar
 * ({@link #SHIPPED}) and the user's own (a cache folder's {@code recorded.txt}), which every new pipeline is appended
 * to. Any thread; the appends run on the executor given.
 *
 * <pre>{@code
 * CgPipelineRecipes recipes = CgPipelineRecipes.load(CgCacheDirectory.of("pipelines"), executor);
 * for (CgPipelineRecipes.Recipe r : recipes.of(key)) build(r.desc(label, layout, vertex, fragment));   // as a program links
 * recipes.record(program.key(), CgPipelineRecipes.Recipe.of(desc, zeroToOne));          // a pipeline built at a draw
 * }</pre>
 *
 * <ul>
 *   <li>A recipe naming a program no run links again is never built: an edited shader is a new key.</li>
 *   <li>To ship what a gate built, copy its {@code recorded.txt} over {@link #SHIPPED} in the jar's resources.</li>
 * </ul>
 */
public final class CgPipelineRecipes {

    /** The list in the jar, beside the engine's shaders. */
    public static final String SHIPPED = "/assets/crystalgraphics/pipelines/recorded.txt";
    private static final String FILE = "recorded.txt";
    private static final Logger LOGGER = LogManager.getLogger("CgPipelineRecipes");

    private final Map<Long, Set<Recipe>> byProgram = new HashMap<>();
    private final Path file;
    private final Executor writer;

    private CgPipelineRecipes(Path folder, Executor writer) {
        this.file = folder == null ? null : folder.resolve(FILE);
        this.writer = writer;
    }

    /** The shipped list and the user's in {@code folder} (null: shipped only, nothing written). */
    public static CgPipelineRecipes load(Path folder, Executor writer) {
        CgPipelineRecipes recipes = new CgPipelineRecipes(folder, writer);
        try (InputStream shipped = CgPipelineRecipes.class.getResourceAsStream(SHIPPED)) {
            if (shipped != null) recipes.read(new BufferedReader(new InputStreamReader(shipped, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            LOGGER.warn("shipped pipeline list unread: {}", e.toString());
        }
        if (recipes.file != null && Files.isRegularFile(recipes.file)) {
            try (BufferedReader in = Files.newBufferedReader(recipes.file, StandardCharsets.UTF_8)) {
                recipes.read(in);
            } catch (IOException e) {
                LOGGER.warn("pipeline list {} unread: {}", recipes.file, e.toString());
            }
        }
        return recipes;
    }

    private void read(BufferedReader in) throws IOException {
        for (String line; (line = in.readLine()) != null; ) {
            if (line.isEmpty() || line.charAt(0) == '#') continue;
            int space = line.indexOf(' ');
            try {
                long key = Long.parseUnsignedLong(line.substring(0, space), 16);
                byProgram.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(Recipe.decode(line.substring(space + 1)));
            } catch (RuntimeException skipped) {
                // A line an older engine wrote: its fields changed. Its pipeline is built at its draw and written anew.
            }
        }
    }

    /** The recipes recorded for a program of {@code key}; empty for none. */
    public synchronized List<Recipe> of(long key) {
        Set<Recipe> r = byProgram.get(key);
        return r == null ? Collections.emptyList() : new ArrayList<>(r);
    }

    /** Writes down a pipeline a draw built, once. */
    public synchronized void record(long key, Recipe recipe) {
        if (!byProgram.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(recipe) || file == null) return;
        String line = Long.toHexString(key) + " " + recipe.encode() + "\n";
        writer.execute(() -> append(line));
    }

    /** One line at a time, whichever builder runs it. */
    private synchronized void append(String line) {
        try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND)) {
            out.write(line);
        } catch (IOException e) {
            LOGGER.warn("pipeline list {} unwritten: {}", file, e.toString());
        }
    }

    /** A pipeline's description less its program, which a program of the recorded key fills in. */
    @Desugar
    public record Recipe(boolean zeroToOne, List<VertexBuffer> vertexBuffers, CgGraphicsPipelineDesc.Topology topology,
                         Raster raster, DepthStencil depthStencil, List<ColorTarget> colorTargets, CgFormat depthFormat,
                         int samples) {

        public static Recipe of(CgGraphicsPipelineDesc d, boolean zeroToOne) {
            return new Recipe(zeroToOne, d.vertexBuffers(), d.topology(), d.raster(), d.depthStencil(), d.colorTargets(),
                    d.depthFormat(), d.samples());
        }

        /**
         * The description for a program of the recorded key: its {@code vertex} stage the one of the recorded clip
         * convention ({@link #zeroToOne}).
         */
        public CgGraphicsPipelineDesc desc(String label, CgPipelineLayout layout, CgShaderModule vertex,
                                           CgShaderModule fragment) {
            return new CgGraphicsPipelineDesc(label, layout, vertex, fragment, vertexBuffers, topology, raster,
                    depthStencil, colorTargets, depthFormat, samples);
        }

        String encode() {
            StringBuilder s = new StringBuilder();
            s.append(zeroToOne ? 'z' : 'g').append(' ').append(topology).append(' ')
                    .append(raster.cull()).append(',').append(raster.frontFace()).append(',')
                    .append(raster.polygonMode()).append(',').append(raster.depthBias() ? 1 : 0).append(' ');
            DepthStencil d = depthStencil;
            s.append(d.depthTest() ? 1 : 0).append(',').append(d.depthWrite() ? 1 : 0).append(',').append(d.depthCompare())
                    .append(',').append(d.stencilTest() ? 1 : 0).append(',').append(face(d.front())).append(',')
                    .append(face(d.back())).append(',').append(d.readMask()).append(',').append(d.writeMask()).append(' ');
            if (colorTargets.isEmpty()) s.append('_');
            for (int i = 0; i < colorTargets.size(); i++) {
                ColorTarget t = colorTargets.get(i);
                if (i > 0) s.append(',');
                s.append(t.format()).append('/').append(blend(t.blend())).append('/').append(t.writeMask());
            }
            s.append(' ').append(depthFormat == null ? "-" : depthFormat.name()).append(' ').append(samples).append(' ');
            if (vertexBuffers.isEmpty()) s.append('_');
            for (int i = 0; i < vertexBuffers.size(); i++) {
                VertexBuffer b = vertexBuffers.get(i);
                if (i > 0) s.append(',');
                s.append(b.binding()).append('/').append(b.stride()).append('/').append(b.perInstance() ? 1 : 0).append('/');
                for (int a = 0; a < b.attribs().size(); a++) {
                    VertexAttrib at = b.attribs().get(a);
                    if (a > 0) s.append(':');
                    s.append(at.location()).append('.').append(at.format()).append('.').append(at.offset());
                }
            }
            return s.toString();
        }

        static Recipe decode(String line) {
            String[] f = line.split(" ");
            String[] r = f[2].split(","), d = f[3].split(",");
            Raster raster = new Raster(CgGraphicsPipelineDesc.CullMode.valueOf(r[0]), CgGraphicsPipelineDesc.FrontFace.valueOf(r[1]),
                    CgGraphicsPipelineDesc.PolygonMode.valueOf(r[2]), r[3].equals("1"));
            DepthStencil ds = new DepthStencil(d[0].equals("1"), d[1].equals("1"), CgCompare.valueOf(d[2]),
                    d[3].equals("1"), face(d[4]), face(d[5]), Integer.parseInt(d[6]), Integer.parseInt(d[7]));
            List<ColorTarget> targets = new ArrayList<>();
            if (!f[4].equals("_")) {
                for (String t : f[4].split(",")) {
                    String[] p = t.split("/");
                    targets.add(new ColorTarget(CgFormat.valueOf(p[0]), blend(p[1]), Integer.parseInt(p[2])));
                }
            }
            List<VertexBuffer> buffers = new ArrayList<>();
            if (!f[7].equals("_")) {
                for (String b : f[7].split(",")) {
                    String[] p = b.split("/", -1);
                    List<VertexAttrib> attribs = new ArrayList<>();
                    if (!p[3].isEmpty()) {
                        for (String a : p[3].split(":")) {
                            String[] q = a.split("\\.");
                            attribs.add(new VertexAttrib(Integer.parseInt(q[0]), CgAttribFormat.valueOf(q[1]), Integer.parseInt(q[2])));
                        }
                    }
                    buffers.add(new VertexBuffer(Integer.parseInt(p[0]), Integer.parseInt(p[1]), p[2].equals("1"),
                            List.copyOf(attribs)));
                }
            }
            return new Recipe(f[0].equals("z"), List.copyOf(buffers), CgGraphicsPipelineDesc.Topology.valueOf(f[1]), raster, ds,
                    List.copyOf(targets), f[5].equals("-") ? null : CgFormat.valueOf(f[5]), Integer.parseInt(f[6]));
        }

        private static String face(StencilFace f) {
            return f.compare() + "/" + f.fail() + "/" + f.depthFail() + "/" + f.pass();
        }

        private static StencilFace face(String s) {
            String[] p = s.split("/");
            return new StencilFace(CgCompare.valueOf(p[0]), CgGraphicsPipelineDesc.StencilOp.valueOf(p[1]),
                    CgGraphicsPipelineDesc.StencilOp.valueOf(p[2]), CgGraphicsPipelineDesc.StencilOp.valueOf(p[3]));
        }

        private static String blend(Blend b) {
            return b == null ? "-" : b.srcColor() + "+" + b.dstColor() + "+" + b.colorOp() + "+" + b.srcAlpha() + "+"
                    + b.dstAlpha() + "+" + b.alphaOp();
        }

        private static Blend blend(String s) {
            if (s.equals("-")) return null;
            String[] p = s.split("\\+");
            return new Blend(CgGraphicsPipelineDesc.BlendFactor.valueOf(p[0]), CgGraphicsPipelineDesc.BlendFactor.valueOf(p[1]),
                    CgGraphicsPipelineDesc.BlendOp.valueOf(p[2]), CgGraphicsPipelineDesc.BlendFactor.valueOf(p[3]),
                    CgGraphicsPipelineDesc.BlendFactor.valueOf(p[4]), CgGraphicsPipelineDesc.BlendOp.valueOf(p[5]));
        }
    }
}
