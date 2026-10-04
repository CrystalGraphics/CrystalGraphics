package com.crystalgraphics.compute.lower;

import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.buffer.CgBufferLifetime;
import com.crystalgraphics.api.shader.CgShaderBindings;
import com.crystalgraphics.api.shader.CgShaderPreprocessor;
import com.crystalgraphics.api.shader.CgShaderProgram;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.compute.CgDispatchBindings;
import com.crystalgraphics.compute.emit.CgKernelEmitter;
import com.crystalgraphics.compute.lower.CgLowering.Kind;
import com.crystalgraphics.compute.lower.CgLowering.Op;
import com.crystalgraphics.compute.lower.CgLowering.Pass;
import com.crystalgraphics.compute.source.CgBufferAccess;
import com.crystalgraphics.compute.source.CgBufferAccessor;
import com.crystalgraphics.compute.source.CgBufferDecl;
import com.crystalgraphics.compute.source.CgComputeSource;
import com.crystalgraphics.compute.source.CgImageAccessor;
import com.crystalgraphics.compute.source.CgImageDecl;
import com.crystalgraphics.compute.source.CgImageDimension;
import com.crystalgraphics.compute.source.CgKernelDecl;
import com.crystalgraphics.gl.buffer.CgBufferReadback;
import com.crystalgraphics.gl.buffer.CgBufferTextures;
import com.crystalgraphics.gl.buffer.shader.CgEngineBufferRegistry;
import com.crystalgraphics.gl.buffer.shader.CgShaderBuffer;
import com.crystalgraphics.gl.buffer.shader.CgUniformBuffer;
import com.crystalgraphics.gl.material.CgMaterialProperties;
import com.crystalgraphics.gl.material.CgMaterialProperty;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.state.CgGlScope;
import com.crystalgraphics.platform.gl.state.CgGlSlot;
import com.crystalgraphics.platform.gl.state.CgGlState;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.CgBufferUtils;
import com.crystalgraphics.util.trace.CgChannels;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One kernel and keyword set lowered below compute (gpu-compute C5), compiled for the current context and wired: a
 * program per pass {@link CgLowering} plans, and the dispatch that runs them. What a GL context without compute runs
 * where a compute context runs a {@code CgKernelProgram}.
 *
 * <pre>{@code
 * CgLoweredKernel simulate = CgLoweredKernel.build(source, source.kernel("Simulate"), Set.of());
 * CgDispatchBindings b = new CgDispatchBindings(source).buffer(0, state, 0, bytes).elements(count, 1, 1);
 * try (CgGlScope scope = CgLoweredKernel.scope()) {
 *     simulate.properties().set1f("_Drag", 0.1f);
 *     simulate.dispatch(b);                    // uploads its own properties: a direct dispatch
 * }
 * }</pre>
 *
 * <ul>
 *   <li>Every pass reads what the bound buffers and images held before the dispatch; what the passes write to buffers
 *       is held in render targets and read back into them after the last, so a pass never sees another's output.</li>
 *   <li>A frame graph's dispatch leaves what it wrote in those targets ({@link #dispatchBound(CgDispatchBindings,
 *       boolean)}): a later lowered kernel reads a buffer from its target, and the graph reads it back before anything
 *       else touches it ({@code CgLoweredResources.land}).</li>
 *   <li>A frame graph binds the property block and sampler properties itself and calls {@link #dispatchBound}.</li>
 *   <li>No memory barrier is needed or issued: rendering, transform feedback and reading pixels into a buffer are
 *       ordinary GL writes.</li>
 *   <li>Render thread only.</li>
 * </ul>
 */
public final class CgLoweredKernel {

    private static final int LOWERED_PASSES = CgTrace.name("compute.lowered-passes");

    private final CgComputeSource source;
    private final CgKernelDecl kernel;
    private final Set<String> keywords;
    private final CgLoweredTarget target;
    private final List<PassProgram> passes = new ArrayList<>();
    private final int samplers;
    /**
     * Per declared buffer, the unit it is read through, or -1; and the unit of the target holding its words, or -1. Per
     * append buffer, its count's; per image read, its.
     */
    private final int[] bufferUnit, residentUnit, counterUnit, imageUnit;
    /** Per image: whether the kernel loads it, and what its copy is made as when it loads one it writes. */
    private final boolean[] loads;
    private final CgTextureType[] copyType;
    private final int argsUnit;
    private final IntBuffer dispatchValues = CgBufferUtils.createIntBuffer(6), spanValues = CgBufferUtils.createIntBuffer(3);
    private final CgMaterialProperties properties;
    private final CgUniformBuffer propertyBlock;
    private CgUniformBuffer frameBlock;
    private float[] frameValues;
    private boolean deleted;

    /** A pass's program and the uniforms a dispatch sets on it. */
    private record PassProgram(Pass pass, CgShaderProgram program, int dispatch, int argsAt, int layer, int texelsX,
                               int texelsY, int[] base, int[] length, int[] span, int[] counterAt, int[] level) {
    }

    private CgLoweredKernel(CgComputeSource source, CgKernelDecl kernel, Set<String> keywords, CgLoweredTarget target) {
        this.source = source;
        this.kernel = kernel;
        this.keywords = keywords;
        this.target = target;
        List<CgMaterialProperty> copies = new ArrayList<>();
        for (CgMaterialProperty p : source.properties()) copies.add(p.copyWithDefaults());
        this.properties = new CgMaterialProperties(copies);
        int sampled = 0;
        for (CgMaterialProperty p : properties.all()) if (p.getType().isSampler()) sampled++;
        this.samplers = sampled;
        this.propertyBlock = properties.hasUboProps()
                ? new CgUniformBuffer(CgKernelEmitter.PROPERTY_BLOCK, properties.buildUboFormat(),
                        CgBindingPoints.MATERIAL_PROPERTIES_UBO, CgBufferLifetime.FRAME)
                : null;
        int next = sampled;
        bufferUnit = new int[source.buffers().size()];
        counterUnit = new int[source.buffers().size()];
        imageUnit = new int[source.images().size()];
        loads = new boolean[imageUnit.length];
        copyType = new CgTextureType[imageUnit.length];
        for (CgImageDecl image : source.images()) {
            loads[image.index()] = CgLowering.uses(kernel, image, CgImageAccessor.LOAD);
            copyType[image.index()] = textureType(image);
        }
        for (CgBufferDecl b : source.buffers()) {
            bufferUnit[b.index()] = CgLowering.touches(kernel, b) ? next++ : -1;
            counterUnit[b.index()] = CgLowering.touches(kernel, b) && b.access() == CgBufferAccess.APPEND ? next++ : -1;
        }
        for (CgImageDecl i : source.images()) {
            boolean reads = CgLowering.uses(kernel, i, CgImageAccessor.LOAD) || CgLowering.uses(kernel, i, CgImageAccessor.SIZE);
            imageUnit[i.index()] = reads ? next++ : -1;
        }
        argsUnit = next++;
        seeds = new CgTexelTarget[source.buffers().size()];
        residentUnit = new int[source.buffers().size()];
        boolean resident = CgLoweredEmitter.residentReads(source, kernel, target);
        for (CgBufferDecl b : source.buffers()) residentUnit[b.index()] = resident && CgLowering.touches(kernel, b) ? next++ : -1;
        int limit = target.stageUnits();
        if (next > limit) {
            throw new IllegalStateException("[" + source.path() + "] kernel " + kernel.name() + " reads " + next
                    + " textures and buffers lowered; below compute a stage reads " + limit);
        }
    }

    /**
     * Lowers {@code kernel} of {@code source} with {@code keywords} for the current context.
     *
     * @throws IllegalStateException naming the construct when it cannot lower, or with the driver's log and the
     *                               emitted source when a pass fails to compile
     */
    public static CgLoweredKernel build(CgComputeSource source, CgKernelDecl kernel, Set<String> keywords) {
        return build(source, kernel, keywords, CgLoweredTarget.current());
    }

    public static CgLoweredKernel build(CgComputeSource source, CgKernelDecl kernel, Set<String> keywords,
                                        CgLoweredTarget target) {
        String refusal = CgLowering.refusal(source, kernel);
        if (refusal != null) throw new IllegalStateException("[" + source.path() + "] " + refusal);
        CgLoweredKernel lowered = new CgLoweredKernel(source, kernel, keywords, target);
        try (CgGlScope scope = CgGlState.save(CgGlSlot.PROGRAM)) {
            for (Pass pass : CgLowering.passes(source, kernel)) lowered.passes.add(lowered.compile(pass));
        } catch (RuntimeException e) {
            lowered.delete();
            throw e;
        }
        return lowered;
    }

    private PassProgram compile(Pass pass) {
        CgLoweredEmitter.Stages stages = CgLoweredEmitter.emit(source, kernel, keywords, pass, target);
        CgShaderPreprocessor preprocessor = new CgShaderPreprocessor();
        String vertex = preprocessor.process(stages.vertex(), source.path());
        String geometry = stages.geometry() == null ? null : new CgShaderPreprocessor().process(stages.geometry(), source.path());
        String fragment = stages.fragment() == null ? null : new CgShaderPreprocessor().process(stages.fragment(), source.path());
        CgShaderProgram program;
        try {
            program = CgShaderProgram.compileCapture(vertex, geometry, fragment, stages.varyings());
        } catch (IllegalStateException e) {
            String kernelStage = pass.kind() == Kind.OUTPUT || pass.kind() == Kind.IMAGE ? fragment : geometry;
            throw new IllegalStateException("[" + source.path() + "] kernel " + kernel.name() + keywords + " lowered for "
                    + describe(pass) + ": " + e.getMessage() + "\n--- emitted ---\n" + numbered(kernelStage), e);
        }
        int id = program.getId();
        CgGL.glUseProgram(id);
        int s = 0;
        for (CgMaterialProperty p : properties.all()) if (p.getType().isSampler()) unit(id, p.getName(), s++);
        uniformBlock(id, CgKernelEmitter.PROPERTY_BLOCK, CgBindingPoints.MATERIAL_PROPERTIES_UBO);
        uniformBlock(id, CgPassConstants.BLOCK_NAME, CgBindingPoints.FRAME_DATA_UBO);
        for (String token : source.engineBuffers()) {
            CgShaderBuffer buffer = CgEngineBufferRegistry.get(token).buffer().get();
            if (target.storageBlocks()) {
                int index = CgGL.glGetProgramResourceIndex(id, CgGL.GL_SHADER_STORAGE_BLOCK, buffer.getName());
                if (index != CgGL.GL_INVALID_INDEX) CgGL.glShaderStorageBlockBinding(id, index, buffer.getBindingLocation());
            } else {
                unit(id, buffer.getName(), buffer.getBindingLocation());
            }
        }
        unit(id, CgBindingPoints.DEPTH_TEXTURE_UNIFORM, CgBindingPoints.DEPTH_TEXTURE_UNIT);
        unit(id, CgBindingPoints.SCENE_COLOR_TEXTURE_UNIFORM, CgBindingPoints.SCENE_COLOR_TEXTURE_UNIT);
        unit(id, CgBindingPoints.LIGHTMAP_TEXTURE_UNIFORM, CgBindingPoints.LIGHTMAP_TEXTURE_UNIT);
        int n = source.buffers().size(), m = source.images().size();
        int[] base = new int[n], length = new int[n], span = new int[n], counterAt = new int[n], level = new int[m];
        for (CgBufferDecl b : source.buffers()) {
            int i = b.index();
            if (bufferUnit[i] >= 0) unit(id, CgLoweredEmitter.tbo(b), bufferUnit[i]);
            if (residentUnit[i] >= 0) unit(id, CgLoweredEmitter.resident(b), residentUnit[i]);
            span[i] = CgGL.glGetUniformLocation(id, CgLoweredEmitter.residentSpan(b));
            if (counterUnit[i] >= 0) unit(id, CgLoweredEmitter.counterTbo(b), counterUnit[i]);
            base[i] = CgGL.glGetUniformLocation(id, CgLoweredEmitter.base(b));
            length[i] = CgGL.glGetUniformLocation(id, CgLoweredEmitter.length(b));
            counterAt[i] = CgGL.glGetUniformLocation(id, CgLoweredEmitter.counterAt(b));
        }
        for (CgImageDecl image : source.images()) {
            if (imageUnit[image.index()] >= 0) unit(id, CgLoweredEmitter.sampler(image), imageUnit[image.index()]);
            level[image.index()] = CgGL.glGetUniformLocation(id, CgLoweredEmitter.level(image));
        }
        unit(id, CgLoweredEmitter.ARGS, argsUnit);
        return new PassProgram(pass, program, CgGL.glGetUniformLocation(id, CgKernelEmitter.DISPATCH_UNIFORM),
                CgGL.glGetUniformLocation(id, CgLoweredEmitter.ARGS_AT), CgGL.glGetUniformLocation(id, CgLoweredEmitter.LAYER),
                CgGL.glGetUniformLocation(id, CgLoweredEmitter.TEXELS_X), CgGL.glGetUniformLocation(id, CgLoweredEmitter.TEXELS_Y),
                base, length, span, counterAt, level);
    }

    // ── Use ───────────────────────────────────────────────────────────────────

    /** Saves what a direct dispatch changes, restored when the scope closes. */
    public static CgGlScope scope() {
        return CgGlState.save(SCOPE);
    }

    private static final CgGlSlot[] SCOPE = {CgGlSlot.PROGRAM, CgGlSlot.FBO, CgGlSlot.VIEWPORT, CgGlSlot.BLEND,
            CgGlSlot.DEPTH, CgGlSlot.CULL, CgGlSlot.STENCIL, CgGlSlot.SCISSOR, CgGlSlot.COLOR_MASK, CgGlSlot.POINT_SIZE,
            CgGlSlot.VERTEX_INPUT, CgGlSlot.TEXTURES, CgGlSlot.TRANSFORM_FEEDBACK, CgGlSlot.INDIRECT_BUFFERS};

    /** This kernel's {@code Properties} values, uploaded at every direct {@link #dispatch}. */
    public CgShaderBindings properties() {
        return properties;
    }

    /** The frame block a direct dispatch binds: time, camera, resolution. */
    public CgLoweredKernel frame(CgPassConstants constants) {
        if (frameBlock == null) {
            frameBlock = new CgUniformBuffer(CgPassConstants.BLOCK_NAME, CgPassConstants.FORMAT,
                    CgBindingPoints.FRAME_DATA_UBO, CgBufferLifetime.FRAME);
            frameValues = new float[CgPassConstants.FLOATS];
        }
        constants.write(frameValues, 0);
        return this;
    }

    /** A direct dispatch: this kernel's own properties and frame block bound, then {@link #dispatchBound}. */
    public void dispatch(CgDispatchBindings b) {
        if (propertyBlock != null) {
            properties.writeUboProps(propertyBlock.writer());
            propertyBlock.endRecord();
            propertyBlock.upload();
            propertyBlock.bind();
        }
        if (frameBlock != null) {
            frameBlock.uploadRaw(frameValues, frameValues.length);
            frameBlock.bind();
        }
        properties.bindSamplerTextures();
        for (String token : source.engineBuffers()) CgEngineBufferRegistry.get(token).buffer().get().bind();
        dispatchBound(b);
    }

    /**
     * Runs every pass with only what is bound now for blocks and sampler properties: a frame graph binds those
     * itself, as for a compute dispatch, and the engine buffers the file uses. What it writes is in its buffers after.
     */
    public void dispatchBound(CgDispatchBindings b) {
        dispatchBound(b, false);
    }

    /**
     * {@link #dispatchBound(CgDispatchBindings)}, leaving what it writes in its targets when {@code hold}: a frame
     * graph's dispatch, whose executor reads each back before anything but a lowered kernel touches it.
     *
     * <pre>{@code
     * kernel.dispatchBound(b, true);
     * // ... later lowered dispatches read the held words from their targets ...
     * CgLoweredResources.landAll();     // before the graph's execution ends
     * }</pre>
     */
    public void dispatchBound(CgDispatchBindings b, boolean hold) {
        long elements = b.isIndirect() ? -1 : (long) b.x() * b.y() * b.z();
        if (elements == 0) return;
        landInputs(b);
        CgGL.glBindVertexArray(CgLoweredResources.vertexArray());
        CgGL.glDisable(CgGL.GL_DEPTH_TEST);
        CgGL.glDisable(CgGL.GL_CULL_FACE);
        CgGL.glDisable(CgGL.GL_STENCIL_TEST);
        CgGL.glDisable(CgGL.GL_SCISSOR_TEST);
        CgGL.glColorMask(true, true, true, true);
        CgGL.glPointSize(1f);
        sizeIndirect(b);
        CgTexelTarget scatter = null;
        try {
            for (int p = 0; p < passes.size(); p++) {
                PassProgram pass = passes.get(p);
                CgTrace.add(CgChannels.GL, LOWERED_PASSES, 1);
                switch (pass.pass().kind()) {
                    case OUTPUT -> output(pass, b, elements);
                    case APPEND -> append(pass, b, elements);
                    case SCATTER -> {
                        CgBufferDecl buffer = pass.pass().buffer();
                        if (scatter == null) scatter = scatterTarget(buffer, pass.pass().floats(), b);
                        scatterPass(pass, b, elements, scatter);
                        boolean last = p + 1 == passes.size() || passes.get(p + 1).pass().kind() != Kind.SCATTER
                                || passes.get(p + 1).pass().buffer() != buffer;
                        if (last) {
                            CgTexelTarget resolved = scatter;
                            scatter = null;
                            resolve(resolved, buffer, pass.pass().floats(), b);
                        }
                    }
                    case IMAGE -> image(pass, b);
                }
            }
        } catch (RuntimeException e) {
            if (scatter != null) CgLoweredResources.release(scatter);
            for (int i = 0; i < seeds.length; i++) {
                if (seeds[i] != null) CgLoweredResources.release(seeds[i]);
                seeds[i] = null;
            }
            for (int p = 0; p < landCount; p++) CgLoweredResources.release(landing[p]);
            landCount = 0;
            throw e;
        }
        CgGL.glDisable(CgGL.GL_BLEND);
        if (command != 0) CgLoweredResources.release(command, 16);
        finish(hold);
        CgGL.glBindVertexArray(0);
    }

    /**
     * What this dispatch reads other than through its passes' loads, read back first where a target holds it: a
     * scatter's or an append's buffer, which a helper reads itself, counts, indirect arguments, and a buffer no pass can
     * read held (no unit for it, or held as texels of another width).
     */
    private void landInputs(CgDispatchBindings b) {
        if (!CgLoweredResources.holding()) return;
        for (CgBufferDecl buffer : source.buffers()) {
            int i = buffer.index(), held = CgLoweredResources.residentIndex(b.buffer(i));
            if (held >= 0 && (residentUnit[i] < 0 || CgLoweredResources.texelWords(CgLoweredResources.residentTarget(held))
                    != CgLoweredEmitter.texelWords(buffer))) {
                CgLoweredResources.land(b.buffer(i));
            }
            CgLoweredResources.land(b.counter(i));
        }
        for (PassProgram p : passes) {
            Kind kind = p.pass().kind();
            if (kind != Kind.SCATTER && kind != Kind.APPEND) continue;
            CgBufferDecl buffer = p.pass().buffer();
            int i = buffer.index();
            if (kind == Kind.SCATTER && !p.pass().floats() && seeds[i] == null) seeds[i] = heldSeed(buffer, b);
            if (seeds[i] == null) CgLoweredResources.land(b.buffer(i));
        }
        if (b.isIndirect()) CgLoweredResources.land(b.args());
    }

    /**
     * The target holding scattered buffer {@code buffer}'s view, taken to scatter into as it is: what its seed would be
     * drawn as, so it is neither landed nor drawn again. Null unless it holds that view whole, in the seed's layout, and
     * no pass reads the buffer, since a pass drawing into a target may not read it.
     */
    private CgTexelTarget heldSeed(CgBufferDecl buffer, CgDispatchBindings b) {
        int i = buffer.index();
        int held = CgLoweredResources.residentIndex(b.buffer(i));
        if (held < 0 || CgLowering.uses(kernel, buffer, CgBufferAccessor.READ)) return null;
        for (PassProgram p : passes) if (p.pass().kind() == Kind.OUTPUT && p.pass().buffers().contains(buffer)) return null;
        for (int j = 0; j < b.buffers(); j++) if (j != i && b.buffer(j) == b.buffer(i)) return null;   // read through another name
        long texels = b.bytes(i) / buffer.stride() * CgLoweredEmitter.texelsPerElement(buffer);
        int width = width(texels);
        CgTexelTarget t = CgLoweredResources.residentTarget(held);
        boolean whole = CgLoweredResources.residentAt(held) == b.offset(i) && CgLoweredResources.residentTexels(held) == texels;
        if (!whole || t.type() != texelType(buffer) || t.width() != width || t.height() != height(texels, width, buffer)) return null;
        return CgLoweredResources.take(held);
    }

    // ── Passes ────────────────────────────────────────────────────────────────

    /**
     * The pass's buffers, a render target each, laid out as their words are: texel {@code t} of a target is texel
     * {@code t} of the view, so it reads back into the buffer whole.
     */
    private void output(PassProgram pass, CgDispatchBindings b, long elements) {
        List<CgBufferDecl> buffers = pass.pass().buffers();
        int k = CgLoweredEmitter.texelsPerElement(pass.pass().buffer());
        long covered = 0;
        for (CgBufferDecl o : buffers) covered = Math.max(covered, writtenElements(o, b, elements));
        if (covered <= 0) return;
        long texels = covered * k;
        int width = width(texels), height = height(texels, width, pass.pass().buffer());
        // A new target binds its own framebuffer, so every one is taken before the outputs are bound.
        for (int n = 0; n < buffers.size(); n++) outputs[n] = CgLoweredResources.target(texelType(buffers.get(n)), width, height);
        CgLoweredResources.bindOutputs(buffers.size());
        for (int n = 0; n < buffers.size(); n++) {
            CgGL.glFramebufferTexture2D(CgGL.GL_FRAMEBUFFER, CgGL.GL_COLOR_ATTACHMENT0 + n, CgGL.GL_TEXTURE_2D,
                    outputs[n].texture(), 0);
        }
        CgGL.glViewport(0, 0, width, rows(texels, width));
        CgGL.glDisable(CgGL.GL_BLEND);
        use(pass, b);
        CgGL.glUniform1i(pass.texelsX(), width);
        CgGL.glDrawArrays(CgGL.GL_TRIANGLES, 0, 3);
        for (int n = 0; n < buffers.size(); n++) {
            CgGL.glFramebufferTexture2D(CgGL.GL_FRAMEBUFFER, CgGL.GL_COLOR_ATTACHMENT0 + n, CgGL.GL_TEXTURE_2D, 0, 0);
            CgBufferDecl o = buffers.get(n);
            landLater(outputs[n], b.buffer(o.index()), b.offset(o.index()), writtenElements(o, b, elements) * k);
            outputs[n] = null;
        }
    }

    /** The elements of buffer {@code o} a dispatch writes back: its view, cut to the dispatch where that is known. */
    private static long writtenElements(CgBufferDecl o, CgDispatchBindings b, long elements) {
        long length = b.bytes(o.index()) / o.stride();
        return b.isIndirect() ? length : Math.min(elements, length);
    }

    private void append(PassProgram pass, CgDispatchBindings b, long elements) {
        CgBufferDecl a = pass.pass().buffer();
        int i = a.index();
        long viewBytes = b.bytes(i);
        if (viewBytes < a.stride()) return;
        boolean zero = CgLoweredResources.isZero(b.counter(i), b.counterOffset(i), b.frame());
        int capture = zero ? b.buffer(i) : CgLoweredResources.scratch(viewBytes);
        long captureAt = zero ? b.offset(i) : 0;
        CgTexelTarget count = CgLoweredResources.count();
        CgGL.glBindFramebuffer(CgGL.GL_FRAMEBUFFER, count.framebuffer());
        CgGL.glViewport(0, 0, 1, 1);
        CgGL.glClearColor(0f, 0f, 0f, 0f);
        CgGL.glClear(CgGL.GL_COLOR_BUFFER_BIT);
        blend(Op.ADD);
        use(pass, b);
        CgGL.glBindBufferRange(CgGL.GL_TRANSFORM_FEEDBACK_BUFFER, 0, capture, captureAt, viewBytes);
        CgGL.glBeginTransformFeedback(CgGL.GL_POINTS);
        drawElements(b, elements);
        CgGL.glEndTransformFeedback();
        CgGL.glBindBufferBase(CgGL.GL_TRANSFORM_FEEDBACK_BUFFER, 0, 0);
        CgGL.glDisable(CgGL.GL_BLEND);
        if (zero) {
            CgLoweredResources.written(b.buffer(i));
        } else {
            place(a, capture, b);
            CgLoweredResources.release(capture, viewBytes);
        }
        CgTexelTarget sum = CgLoweredResources.target(CgTextureType.R32UI, 1, 1);
        CgGL.glBindFramebuffer(CgGL.GL_FRAMEBUFFER, sum.framebuffer());
        CgGL.glViewport(0, 0, 1, 1);
        CgLoweredPrograms.Helper add = CgLoweredPrograms.counterAdd();
        add.use();
        CgBufferTextures.bind(samplers, CgGL.GL_R32UI, b.counter(i));
        texture(samplers + 1, CgGL.GL_TEXTURE_2D, count.texture());
        add.set("_cg_counter", samplers);
        add.set("_cg_count", samplers + 1);
        add.set("_cg_at", (int) (b.counterOffset(i) / 4));
        CgGL.glDrawArrays(CgGL.GL_TRIANGLES, 0, 3);
        landLater(sum, b.counter(i), b.counterOffset(i), 1);
    }

    /** Appended elements captured at zero, moved to their counter in the buffer through its scatter target. */
    private void place(CgBufferDecl a, int captured, CgDispatchBindings b) {
        int i = a.index();
        CgTexelTarget t = scatterTarget(a, false, b);
        int k = CgLoweredEmitter.texelsPerElement(a);
        int length = (int) (b.bytes(i) / a.stride());
        CgGL.glBindFramebuffer(CgGL.GL_FRAMEBUFFER, t.framebuffer());
        CgGL.glViewport(0, 0, t.width(), t.height());
        CgGL.glDisable(CgGL.GL_BLEND);
        CgLoweredPrograms.Helper place = CgLoweredPrograms.place();
        place.use();
        CgBufferTextures.bind(samplers, CgLoweredEmitter.texelFormat(a), captured);
        CgBufferTextures.bind(samplers + 1, CgGL.GL_R32UI, b.counter(i));
        texture(samplers + 2, CgGL.GL_TEXTURE_2D, CgLoweredResources.count().texture());
        place.set("_cg_src", samplers);
        place.set("_cg_counter", samplers + 1);
        place.set("_cg_count", samplers + 2);
        place.set("_cg_at", (int) (b.counterOffset(i) / 4));
        place.set("_cg_k", k);
        place.set("_cg_len", length);
        place.set("_cg_width", t.width());
        place.set("_cg_height", t.height());
        CgGL.glDrawArrays(CgGL.GL_POINTS, 0, length * k);
        resolve(t, a, false, b);
    }

    /** A scatter target holding buffer {@code buffer}'s view: its words, or its scalars as floats. */
    private CgTexelTarget scatterTarget(CgBufferDecl buffer, boolean floats, CgDispatchBindings b) {
        int i = buffer.index();
        if (!floats && seeds[i] != null) {
            CgTexelTarget seed = seeds[i];
            seeds[i] = null;
            return seed;
        }
        int k = CgLoweredEmitter.texelsPerElement(buffer);
        long texels = b.bytes(i) / buffer.stride() * k;
        int width = width(texels), height = height(texels, width, buffer);
        CgTextureType type = floats ? CgTextureType.R32F : texelType(buffer);
        CgTexelTarget t = CgLoweredResources.target(type, width, height);
        CgGL.glBindFramebuffer(CgGL.GL_FRAMEBUFFER, t.framebuffer());
        CgGL.glViewport(0, 0, width, rows(texels, width));
        CgGL.glDisable(CgGL.GL_BLEND);
        CgLoweredPrograms.Helper init = CgLoweredPrograms.scatterInit(floats, buffer.element());
        init.use();
        CgBufferTextures.bind(samplers, CgLoweredEmitter.texelFormat(buffer), b.buffer(i));
        init.set("_cg_src", samplers);
        init.set("_cg_first", (int) (b.offset(i) / buffer.stride()) * k);
        init.set("_cg_count", (int) texels);
        init.set("_cg_width", width);
        CgGL.glDrawArrays(CgGL.GL_TRIANGLES, 0, 3);
        return t;
    }

    private void scatterPass(PassProgram pass, CgDispatchBindings b, long elements, CgTexelTarget t) {
        CgGL.glBindFramebuffer(CgGL.GL_FRAMEBUFFER, t.framebuffer());
        CgGL.glViewport(0, 0, t.width(), t.height());
        blend(pass.pass().op());
        use(pass, b);
        CgGL.glUniform1i(pass.texelsX(), t.width());
        CgGL.glUniform1i(pass.texelsY(), t.height());
        drawElements(b, elements);
        CgGL.glDisable(CgGL.GL_BLEND);
    }

    /**
     * A scatter target, owned from here, to land in its buffer's view: as it is when it holds the words, else its floats
     * first drawn back into the element's bits.
     */
    private void resolve(CgTexelTarget t, CgBufferDecl buffer, boolean floats, CgDispatchBindings b) {
        int i = buffer.index();
        long texels = b.bytes(i) / buffer.stride() * CgLoweredEmitter.texelsPerElement(buffer);
        if (!floats) {
            landLater(t, b.buffer(i), b.offset(i), texels);
            return;
        }
        CgTexelTarget bits = CgLoweredResources.target(CgTextureType.R32UI, t.width(), t.height());
        CgGL.glBindFramebuffer(CgGL.GL_FRAMEBUFFER, bits.framebuffer());
        CgGL.glViewport(0, 0, t.width(), rows(texels, t.width()));
        CgGL.glDisable(CgGL.GL_BLEND);
        CgLoweredPrograms.Helper toBits = CgLoweredPrograms.scatterBits(buffer.element());
        toBits.use();
        texture(samplers, CgGL.GL_TEXTURE_2D, t.texture());
        CgBufferTextures.bind(samplers + 1, CgLoweredEmitter.texelFormat(buffer), b.buffer(i));
        toBits.set("_cg_texels", samplers);
        toBits.set("_cg_src", samplers + 1);
        toBits.set("_cg_first", (int) (b.offset(i) / buffer.stride()));
        toBits.set("_cg_count", (int) texels);
        toBits.set("_cg_width", t.width());
        CgGL.glDrawArrays(CgGL.GL_TRIANGLES, 0, 3);
        CgLoweredResources.release(t);
        landLater(bits, b.buffer(i), b.offset(i), texels);
    }

    private void image(PassProgram pass, CgDispatchBindings b) {
        CgImageDecl image = pass.pass().image();
        int i = image.index();
        int texture = b.image(i), level = b.level(i);
        int width = b.width(i), height = b.height(i);
        CgTexelTarget copy = null;
        if (loads[i]) copy = copyOf(image, b);
        int framebuffer = CgLoweredResources.framebuffer();
        boolean layered = image.dimension() != CgImageDimension.D2 && b.layer(i) < 0;
        int layers = layered ? b.isIndirect() ? b.depth(i) : Math.min(b.z(), b.depth(i)) : 1;
        CgGL.glDisable(CgGL.GL_BLEND);
        for (int z = 0; z < layers; z++) {
            CgGL.glBindFramebuffer(CgGL.GL_FRAMEBUFFER, framebuffer);
            if (b.imageTarget(i) == CgGL.GL_TEXTURE_2D) {
                CgGL.glFramebufferTexture2D(CgGL.GL_FRAMEBUFFER, CgGL.GL_COLOR_ATTACHMENT0, CgGL.GL_TEXTURE_2D, texture, level);
            } else {
                CgGL.glFramebufferTextureLayer(CgGL.GL_FRAMEBUFFER, CgGL.GL_COLOR_ATTACHMENT0, texture, level,
                        layered ? z : Math.max(0, b.layer(i)));
            }
            CgGL.glViewport(0, 0, width, height);
            use(pass, b);
            if (copy != null) texture(imageUnit[i], CgGL.GL_TEXTURE_2D, copy.texture());
            if (copy != null && pass.level()[i] >= 0) CgGL.glUniform1i(pass.level()[i], 0);
            pinLevels(pass, b, i);
            CgGL.glUniform1i(pass.layer(), layered ? z : 0);
            CgGL.glDrawArrays(CgGL.GL_TRIANGLES, 0, 3);
        }
        CgGL.glFramebufferTexture2D(CgGL.GL_FRAMEBUFFER, CgGL.GL_COLOR_ATTACHMENT0, CgGL.GL_TEXTURE_2D, 0, 0);
        discardTarget();
        if (copy != null) CgLoweredResources.release(copy);
        unpinLevels(b, i);
    }

    /**
     * Where the pass reads another level of the texture it draws into, that texture samples only the level read
     * (GL's rule against a feedback loop: the drawn level must lie outside base to max), and the read is at its base.
     */
    private void pinLevels(PassProgram pass, CgDispatchBindings b, int written) {
        int pinned = -1;
        for (CgImageDecl image : source.images()) {
            int r = image.index();
            if (r == written || imageUnit[r] < 0 || b.image(r) != b.image(written)) continue;
            if (b.level(r) == b.level(written)) {
                throw new IllegalStateException(kernel.name() + " reads " + image.name() + " at the level it writes: "
                        + "below compute, bind one image to read and write it");
            }
            if (pinned >= 0 && pinned != b.level(r)) {
                throw new IllegalStateException(kernel.name() + " reads two levels of the texture it writes: below "
                        + "compute a pass reads one");
            }
            pinned = b.level(r);
            CgGL.glActiveTexture(CgGL.GL_TEXTURE0 + imageUnit[r]);
            CgGL.glTexParameteri(b.imageTarget(r), CgGL.GL_TEXTURE_BASE_LEVEL, pinned);
            CgGL.glTexParameteri(b.imageTarget(r), CgGL.GL_TEXTURE_MAX_LEVEL, pinned);
            CgGL.glUniform1i(pass.level()[r], 0);
        }
    }

    private void unpinLevels(CgDispatchBindings b, int written) {
        for (CgImageDecl image : source.images()) {
            int r = image.index();
            if (r == written || imageUnit[r] < 0 || b.image(r) != b.image(written)) continue;
            texture(imageUnit[r], b.imageTarget(r), b.image(r));
            CgGL.glTexParameteri(b.imageTarget(r), CgGL.GL_TEXTURE_BASE_LEVEL, 0);
            CgGL.glTexParameteri(b.imageTarget(r), CgGL.GL_TEXTURE_MAX_LEVEL, b.levels(r) - 1);
            return;
        }
    }

    /** What image {@code image} holds now, for a kernel that loads the image it writes. */
    private CgTexelTarget copyOf(CgImageDecl image, CgDispatchBindings b) {
        int i = image.index();
        if (image.dimension() != CgImageDimension.D2) {
            throw new IllegalStateException(kernel.name() + " loads and writes " + image.name() + ", a "
                    + image.dimension().token + " image: below compute only a 2D image is copied to be read while written");
        }
        if (copyType[i] == null) {
            throw new IllegalStateException(image.name() + "'s format " + image.format().qualifier() + " has no texture type");
        }
        CgTexelTarget copy = CgLoweredResources.target(copyType[i], b.width(i), b.height(i));
        int framebuffer = CgLoweredResources.framebuffer();
        CgGL.glBindFramebuffer(CgGL.GL_READ_FRAMEBUFFER, framebuffer);
        CgGL.glFramebufferTexture2D(CgGL.GL_READ_FRAMEBUFFER, CgGL.GL_COLOR_ATTACHMENT0, CgGL.GL_TEXTURE_2D, b.image(i), b.level(i));
        CgGL.glBindFramebuffer(CgGL.GL_DRAW_FRAMEBUFFER, copy.framebuffer());
        CgGL.glBlitFramebuffer(0, 0, b.width(i), b.height(i), 0, 0, b.width(i), b.height(i), CgGL.GL_COLOR_BUFFER_BIT,
                CgGL.GL_NEAREST);
        discardTarget();
        return copy;
    }

    // ── Shared steps ──────────────────────────────────────────────────────────

    /** Binds pass {@code pass}'s program, every unit the kernel reads through and the dispatch's uniforms. */
    private void use(PassProgram pass, CgDispatchBindings b) {
        CgGL.glUseProgram(pass.program().getId());
        for (CgBufferDecl buffer : source.buffers()) {
            int i = buffer.index();
            if (bufferUnit[i] < 0) continue;
            if (b.offset(i) % buffer.stride() != 0) {
                throw new IllegalStateException(buffer.name() + " is bound from byte " + b.offset(i) + ": below compute a "
                        + "view starts at a whole " + buffer.stride() + "-byte element");
            }
            long texels = (b.offset(i) + b.bytes(i)) / (CgLoweredEmitter.texelWords(buffer) * 4L);
            if (texels > target.maxTextureBufferSize()) {
                throw new IllegalStateException(buffer.name() + " reaches texel " + texels + "; this context reads a buffer "
                        + "as a texture of at most " + target.maxTextureBufferSize());
            }
            CgBufferTextures.bind(bufferUnit[i], CgLoweredEmitter.texelFormat(buffer), b.buffer(i));
            if (residentUnit[i] >= 0) resident(pass, buffer, b.buffer(i));
            CgGL.glUniform1i(pass.base()[i], (int) (b.offset(i) / buffer.stride()));
            CgGL.glUniform1i(pass.length()[i], (int) (b.bytes(i) / buffer.stride()));
            if (counterUnit[i] >= 0) {
                CgBufferTextures.bind(counterUnit[i], CgGL.GL_R32UI, b.counter(i));
                CgGL.glUniform1i(pass.counterAt()[i], (int) (b.counterOffset(i) / 4));
            }
        }
        for (CgImageDecl image : source.images()) {
            int i = image.index();
            if (imageUnit[i] < 0) continue;
            texture(imageUnit[i], b.imageTarget(i), b.image(i));
            CgGL.glUniform1i(pass.level()[i], b.level(i));
        }
        CgBufferTextures.bind(argsUnit, CgGL.GL_R32UI, b.isIndirect() ? b.args() : 0);
        CgGL.glUniform1i(pass.argsAt(), b.isIndirect() ? (int) (b.argsOffset() / 4) : 0);
        dispatchValues.put(0, 0).put(1, 0).put(2, 0);
        if (b.isIndirect()) dispatchValues.put(3, -1).put(4, -1).put(5, -1);
        else dispatchValues.put(3, b.x()).put(4, b.y()).put(5, b.z());
        CgGL.glUniform1(pass.dispatch(), dispatchValues);
    }

    /** Binds the target holding {@code name}'s words at the buffer's resident unit, and its span; an empty span for none. */
    private void resident(PassProgram pass, CgBufferDecl buffer, int name) {
        int held = CgLoweredResources.residentIndex(name);
        int unit = residentUnit[buffer.index()];
        if (held < 0) {
            texture(unit, CgGL.GL_TEXTURE_2D, 0);
            spanValues.put(0, 1).put(1, 0).put(2, 0);
        } else {
            CgTexelTarget t = CgLoweredResources.residentTarget(held);
            texture(unit, CgGL.GL_TEXTURE_2D, t.texture());
            long texelBytes = CgLoweredEmitter.texelWords(buffer) * 4L;
            spanValues.put(0, t.width()).put(1, (int) (CgLoweredResources.residentAt(held) / texelBytes))
                    .put(2, (int) CgLoweredResources.residentTexels(held));
        }
        CgGL.glUniform1(pass.span()[buffer.index()], spanValues);
    }

    /**
     * How an indirect dispatch's passes that draw a point per element size their draws, before any pass starts:
     * {@link #command} filled with a draw command where the GPU's count can size a draw, else {@link #readCount} read
     * back. A pass running its own capture cannot run another inside it.
     */
    private void sizeIndirect(CgDispatchBindings b) {
        command = 0;
        readCount = -1;
        boolean drawsPerElement = false;
        for (PassProgram p : passes) drawsPerElement |= p.pass().kind() == Kind.APPEND || p.pass().kind() == Kind.SCATTER;
        if (!b.isIndirect() || !drawsPerElement) return;
        if (!target.drawsGpuCounts()) {
            CgBufferReadback.readWords(b.args(), b.argsOffset(), groups, 0, 3);
            readCount = (long) groups[0] * kernel.sizeX() * groups[1] * kernel.sizeY() * groups[2] * kernel.sizeZ();
            return;
        }
        command = CgLoweredResources.scratch(16);
        discardTarget();
        CgLoweredPrograms.Helper helper = CgLoweredPrograms.command();
        helper.use();
        CgBufferTextures.bind(argsUnit, CgGL.GL_R32UI, b.args());
        helper.set("_cg_args", argsUnit);
        helper.set("_cg_at", (int) (b.argsOffset() / 4));
        helper.set("_cg_sx", kernel.sizeX());
        helper.set("_cg_sy", kernel.sizeY());
        helper.set("_cg_sz", kernel.sizeZ());
        CgGL.glBindBufferRange(CgGL.GL_TRANSFORM_FEEDBACK_BUFFER, 0, command, 0, 16);
        CgGL.glEnable(CgGL.GL_RASTERIZER_DISCARD);
        CgGL.glBeginTransformFeedback(CgGL.GL_POINTS);
        CgGL.glDrawArrays(CgGL.GL_POINTS, 0, 1);
        CgGL.glEndTransformFeedback();
        CgGL.glDisable(CgGL.GL_RASTERIZER_DISCARD);
        CgGL.glBindBufferBase(CgGL.GL_TRANSFORM_FEEDBACK_BUFFER, 0, 0);
    }

    /** One point per element: the dispatch's count, the command {@link #sizeIndirect} built, or the count read back. */
    private void drawElements(CgDispatchBindings b, long elements) {
        if (!b.isIndirect()) {
            CgGL.glDrawArrays(CgGL.GL_POINTS, 0, (int) elements);
        } else if (command != 0) {
            CgGL.glBindBuffer(CgGL.GL_DRAW_INDIRECT_BUFFER, command);
            CgGL.glDrawArraysIndirect(CgGL.GL_POINTS, 0);
            CgGL.glBindBuffer(CgGL.GL_DRAW_INDIRECT_BUFFER, 0);
        } else if (readCount > 0) {
            CgGL.glDrawArrays(CgGL.GL_POINTS, 0, (int) Math.min(readCount, Integer.MAX_VALUE));
        }
    }

    /** An indirect dispatch's draw command for this dispatch, 0 for none; or its count read back, -1 for none. */
    private int command;
    private long readCount;
    private final int[] groups = new int[3];
    private final CgTexelTarget[] outputs = new CgTexelTarget[CgLowering.MAX_TARGETS];
    /** Per buffer, a held target a scatter starts from as it is ({@link #heldSeed}), until its first scatter pass. */
    private final CgTexelTarget[] seeds;
    /** Targets read back into a buffer, or held, once every pass has read what the buffers held; per target buffer, at, texels. */
    private CgTexelTarget[] landing = new CgTexelTarget[8];
    private long[] landingAt = new long[8 * 3];
    private int landCount;

    /** Queues target {@code t}'s first {@code texels} to land at byte {@code at} of {@code buffer}; owns it from here. */
    private void landLater(CgTexelTarget t, int buffer, long at, long texels) {
        if (texels <= 0) {
            CgLoweredResources.release(t);
            return;
        }
        if (landCount == landing.length) {
            landing = Arrays.copyOf(landing, landCount * 2);
            landingAt = Arrays.copyOf(landingAt, landCount * 6);
        }
        landing[landCount] = t;
        landingAt[landCount * 3] = buffer;
        landingAt[landCount * 3 + 1] = at;
        landingAt[landCount * 3 + 2] = texels;
        landCount++;
    }

    /** Every queued target: its buffer's words held in it when {@code hold}, else read back into the buffer now. */
    private void finish(boolean hold) {
        for (int p = 0; p < landCount; p++) {
            CgTexelTarget t = landing[p];
            int buffer = (int) landingAt[p * 3];
            long at = landingAt[p * 3 + 1], texels = landingAt[p * 3 + 2];
            landing[p] = null;
            if (hold) {
                CgLoweredResources.hold(t, buffer, at, texels);
            } else {
                CgLoweredResources.land(buffer);   // what a target held of it before goes under what this wrote
                CgLoweredResources.land(t, buffer, at, texels);
                CgLoweredResources.release(t);
            }
        }
        landCount = 0;
    }

    /**
     * The width of a target holding {@code texels}: one row a power of two wide, or rows as wide as a texture may be.
     * Sizes in powers of two keep the pool to a few targets however a dispatch's count moves.
     */
    private int width(long texels) {
        int widest = Integer.highestOneBit(target.maxTextureSize());
        return texels >= widest ? widest : (int) powerOfTwo(texels);
    }

    private int height(long texels, int width, CgBufferDecl buffer) {
        long rows = rows(texels, width);
        if (rows > target.maxTextureSize()) {
            throw new IllegalStateException(buffer.name() + " holds " + texels + " texels: below compute a buffer written "
                    + "is a texture, at most " + target.maxTextureSize() + " square");
        }
        return (int) Math.min(target.maxTextureSize(), powerOfTwo(rows));
    }

    /** The rows {@code texels} fill of a target {@code width} wide. */
    private static int rows(long texels, int width) {
        return (int) Math.max(1, (texels + width - 1) / width);
    }

    private static long powerOfTwo(long n) {
        return n <= 1 ? 1 : Long.highestOneBit(n - 1) << 1;
    }

    /**
     * Binds the count's 1x1 target, complete whatever ran before: a draw under rasterizer discard still fails on an
     * incomplete framebuffer, and an image pass leaves its own with nothing attached.
     */
    private static void discardTarget() {
        CgGL.glBindFramebuffer(CgGL.GL_FRAMEBUFFER, CgLoweredResources.count().framebuffer());
    }

    private static void blend(Op op) {
        if (op == Op.STORE) {
            CgGL.glDisable(CgGL.GL_BLEND);
            return;
        }
        CgGL.glEnable(CgGL.GL_BLEND);
        CgGL.glBlendFunc(CgGL.GL_ONE, CgGL.GL_ONE);
        int equation = op == Op.ADD ? CgGL.GL_FUNC_ADD : op == Op.MIN ? CgGL.GL_MIN : CgGL.GL_MAX;
        CgGL.glBlendEquationSeparate(equation, equation);
    }

    private static void texture(int unit, int target, int texture) {
        CgGL.glActiveTexture(CgGL.GL_TEXTURE0 + unit);
        CgGL.glBindTexture(target, texture);
    }

    private static CgTextureType texelType(CgBufferDecl buffer) {
        int words = CgLoweredEmitter.texelWords(buffer);
        return words == 1 ? CgTextureType.R32UI : words == 2 ? CgTextureType.RG32UI : CgTextureType.RGBA32UI;
    }

    private static CgTextureType textureType(CgImageDecl image) {
        for (CgTextureType t : CgTextureType.values()) if (t.glInternalFormat == image.format().glFormat) return t;
        return null;   // a format no texture type names: refused when a pass copies one
    }

    private static void unit(int program, String uniform, int unit) {
        int location = CgGL.glGetUniformLocation(program, uniform);
        if (location >= 0) CgGL.glUniform1i(location, unit);
    }

    private static void uniformBlock(int program, String name, int point) {
        int index = CgGL.glGetUniformBlockIndex(program, name);
        if (index != CgGL.GL_INVALID_INDEX) CgGL.glUniformBlockBinding(program, index, point);
    }

    private static String describe(Pass pass) {
        return switch (pass.kind()) {
            case OUTPUT -> "its writes of " + pass.buffers().stream().map(CgBufferDecl::name).collect(Collectors.joining(", "));
            case APPEND -> "its appends to " + pass.buffer().name();
            case SCATTER -> "its " + pass.op().name().toLowerCase() + "s into " + pass.buffer().name();
            case IMAGE -> "its writes of " + pass.image().name();
        };
    }

    private static String numbered(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) sb.append(String.format("%4d  ", i + 1)).append(lines[i]).append('\n');
        return sb.toString();
    }

    public CgKernelDecl kernel() {
        return kernel;
    }

    public Set<String> keywords() {
        return keywords;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void delete() {
        if (deleted) return;
        for (PassProgram p : passes) p.program().delete();
        passes.clear();
        if (propertyBlock != null) propertyBlock.delete();
        if (frameBlock != null) frameBlock.delete();
        deleted = true;
    }
}
