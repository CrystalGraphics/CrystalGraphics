package com.crystalgraphics.render.scene.renderer.feature.translucency;

import com.crystalgraphics.render.look.CgLookChannels;
import com.crystalgraphics.api.material.CgRenderPassVariant;
import com.crystalgraphics.api.material.CgRenderQueue;
import com.crystalgraphics.api.state.CgBlendState;
import com.crystalgraphics.api.state.CgColorMask;
import com.crystalgraphics.api.state.CgDepthState;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.material.CgMaterialProxy;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.render.draw.CgDrawCommands;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.draw.CgPipelineForm;
import com.crystalgraphics.render.graph.CgDepthAccess;
import com.crystalgraphics.render.graph.CgExecutor;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.look.CgLookIds;
import com.crystalgraphics.render.meshpass.engine.CgEnginePasses;
import com.crystalgraphics.render.post.settings.CgPostSettings;
import com.crystalgraphics.render.scene.batch.CgMeshBatches;
import com.crystalgraphics.render.scene.pass.CgCommandForm;
import com.crystalgraphics.render.scene.pass.CgViewPasses;
import com.crystalgraphics.render.scene.renderer.CgSceneRenderer;
import com.crystalgraphics.render.scene.sort.CgSortLayer;
import com.crystalgraphics.render.shader.CgBindingModel;
import com.crystalgraphics.render.stage.CgStageFiring;
import com.crystalgraphics.render.view.CgView;
import com.crystalgraphics.trace.CgGpuTrace;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The layer passes: each look's channel this firing ({@link CgLookChannels}), and what a translucent row graded by a look
 * of its own ({@code grade(look)}) draws, drawn again into that channel's layer as the light it added and the cover it
 * laid, which the look weights read; and separate translucency drawn into a layer of its own after every other
 * translucent draw, unbent by distortion (Unreal's {@code TranslucencyAfterDOF}, {@link CgSeparateTranslucency}). A
 * graded solid is not drawn here: its look is in custom depth's stencil ({@code CgLookIds}). The scene renderer's feature.
 *
 * <pre>{@code
 * boolean layered = layers.prepare(firing, passes, constants);    // before the translucent passes
 * ... the translucent passes: the output slot's layer their second output, for the rows layers.carries(...) ...
 * if (layered) layers.record(firing, passes, constants);
 * layers.recordSeparate(firing, passes, constants);
 * }</pre>
 *
 * <ul>
 *   <li>The slot whose translucent rows the device can carry most of is the translucent passes' second output: those
 *       draw once. The rest, and every look on GL, draw again into their layer.</li>
 *   <li>A blend that multiplies has no form of light and coverage: such a separate draw stays in the scene, warned once.</li>
 * </ul>
 */
public final class CgLayerPasses {

    private static final Logger LOGGER = LogManager.getLogger("CgLayerPasses");
    private static final CgFormat GRADE_FORMAT = CgFormat.RGBA16_FLOAT;
    private static final int GPU_GRADE = CgGpuTrace.name("scene.channels"), GPU_SEPARATE = CgGpuTrace.name("scene.separateTranslucency");
    /** Graded draws drawn once, their layer the translucent passes' second output; drawn again into a layer. */
    private static final int LAYER_ONCE = CgTrace.name("scene.graded-once"), LAYER_AGAIN = CgTrace.name("scene.graded-again");
    /** Each pipeline's layer form, interned; the pipeline itself where it has none (a multiply). */
    private static final Map<CgPipeline, CgPipeline> LAYER_FORMS = new ConcurrentHashMap<>();

    private final CgSceneRenderer renderer;
    private final CgLookChannels channels = new CgLookChannels();
    private final CgSeparateTranslucency separate = new CgSeparateTranslucency();
    /** Each channel's look, kept while it draws so its baked table stays baked; and whether it is in view this firing. */
    private final CgPostSettings[] slots = new CgPostSettings[CgLookChannels.MAX];
    private final int[] slotIds = new int[CgLookChannels.MAX];
    /** Whether each channel has a translucent row this firing: a layer to draw. */
    private final boolean[] translucent = new boolean[CgLookChannels.MAX];
    private final boolean[] seen = new boolean[CgLookChannels.MAX];
    private final CgGraphTexture[] targets = new CgGraphTexture[CgLookChannels.MAX], apartTargets = new CgGraphTexture[CgLookChannels.MAX];
    @Nullable
    private CgGraphTexture separateTarget;
    private int outputSlot = -1;
    /** Per row this firing: carried into the output slot's layer by the translucent passes, or a cover drawn there. */
    private boolean[] carried = new boolean[64];
    private CgMeshBatches rows;
    private boolean merge, gradesWarned, depthWarned, multiplyWarned;
    /** The merged lists this firing's layer passes draw: each read when the recording is built, so one a pass. */
    private CgDrawCommands[] lists = new CgDrawCommands[CgLookChannels.MAX * 2 + 1];
    private int listsUsed;
    /** Per channel, its layer's form: the rest of its draws, its separate ones, and over what the passes carried. */
    private final SlotForm[] main = new SlotForm[CgLookChannels.MAX], apart = new SlotForm[CgLookChannels.MAX],
            over = new SlotForm[CgLookChannels.MAX];
    private final SlotForm separateAll = new SlotForm(-1, true, false);
    /** A separate row that cannot go apart (its blend multiplies): drawn in the scene as the after-distortion pass draws. */
    private final CgCommandForm inScene = (rows, b, p) -> layered(p) == null && p.pass() != CgRenderPassVariant.EMISSIVE ? p : null;

    public CgLayerPasses(CgSceneRenderer renderer) {
        this.renderer = renderer;
        for (int k = 0; k < CgLookChannels.MAX; k++) {
            main[k] = new SlotForm(k, false, false);
            apart[k] = new SlotForm(k, true, false);
            over[k] = new SlotForm(k, false, true);
        }
    }

    /** The slot whose layer the translucent passes write as their second output this firing; -1 for none. */
    public int outputSlot() {
        return outputSlot;
    }

    /** That slot's layer. */
    public CgGraphTexture outputTarget() {
        return targets[outputSlot];
    }

    /** Whether the translucent passes write row {@code b} into the output slot's layer too. */
    public boolean carries(CgMeshBatches rows, int b) {
        return outputSlot >= 0 && b < carried.length && carried[b] && rows == this.rows;
    }

    /**
     * This firing's looks given channels, and their layers made, before its translucent passes: the channel with the
     * most translucent rows the device can carry becomes the passes' second output, its layer cleared. False with no
     * graded row in view, or a host lending no depth to share.
     */
    public boolean prepare(CgStageFiring firing, CgViewPasses passes, CgPassConstants constants) {
        outputSlot = -1;
        listsUsed = 0;
        Arrays.fill(seen, false);
        Arrays.fill(translucent, false);
        channels.reset();
        separate.reset();
        rows = passes.visibility().batches();
        merge = firing.view().flag(CgSceneRenderer.MERGE_EMISSION);
        int n = rows.size();
        if (carried.length < n) carried = new boolean[Math.max(n, carried.length * 2)];
        Arrays.fill(carried, 0, n, false);
        boolean any = false;
        for (int b = 0; b < n; b++) {
            CgPostSettings look = look(rows, b);
            if (look == null) continue;
            int id = CgLookIds.of(look);
            if (id == 0) continue;
            int k = slotOf(id);
            if (k < 0) {
                for (int s = 0; s < CgLookChannels.MAX && k < 0; s++) if (!seen[s]) k = s;
                if (k < 0) {
                    if (!gradesWarned) {
                        LOGGER.warn("More than {} looks graded in one frame: {} keeps the frame's look, the channels holding {}",
                                CgLookChannels.MAX, look, Arrays.toString(slots));
                    }
                    gradesWarned = true;
                    continue;
                }
                slots[k] = CgLookIds.look(id);
                slotIds[k] = id;
            }
            seen[k] = any = true;
            if (CgSceneRenderer.queue(rows, b) >= CgRenderQueue.TRANSPARENT_THRESHOLD) translucent[k] = true;
        }
        if (!any) return false;
        if (!firing.host().target().lendsAttachments()) {
            if (!depthWarned) LOGGER.warn("The host's framebuffer lends no depth to share: graded draws keep the frame's look");
            depthWarned = true;
            return false;
        }
        CgView view = firing.view();
        int w = Math.max(1, view.width()), h = Math.max(1, view.height());
        for (int k = 0; k < CgLookChannels.MAX; k++) {
            if (!translucent[k]) continue;
            if (targets[k] == null || targets[k].getWidth() != w || targets[k].getHeight() != h) {
                targets[k] = CgGraphTexture.transientTexture("cg_grade_layer" + k, new CgTextureDesc(w, h, GRADE_FORMAT));
                apartTargets[k] = CgGraphTexture.transientTexture("cg_grade_apart" + k, new CgTextureDesc(w, h, GRADE_FORMAT));
            }
        }
        if (!CgExecutor.drawsOnDevice()) return true;
        int best = 0;
        for (int k = 0; k < CgLookChannels.MAX; k++) {
            if (!translucent[k]) continue;
            int count = 0;
            for (int b = 0; b < n; b++) if (inSlot(rows, b, k) && carriable(rows, b)) count++;
            if (count > best) {
                best = count;
                outputSlot = k;
            }
        }
        if (outputSlot < 0) return true;
        for (int b = 0; b < n; b++) carried[b] = inSlot(rows, b, outputSlot) && carriable(rows, b);
        // Its layer cleared here: the translucent passes write into it as they draw
        firing.recording().raster("scene.gradeLayerClear").color(targets[outputSlot], CgLoad.CLEAR).end();
        return true;
    }

    /**
     * Each look's translucent draws into its channel's layer, tested against the scene's depth and writing none: what
     * they add premultiplied, and in alpha how much of what is behind them they cover. The output channel's layer holds
     * what the passes carried already: the rest is added over it. Published in the view as {@link CgLookChannels#KEY},
     * with every channel's look and id, solids' too.
     */
    public void record(CgStageFiring firing, CgViewPasses passes, CgPassConstants constants) {
        CgRecording rec = firing.recording();
        int once = 0, again = 0;
        for (int k = 0; k < CgLookChannels.MAX; k++) {
            if (!seen[k]) continue;
            channels.setLook(k, slots[k], slotIds[k]);
            if (!translucent[k]) continue;
            boolean output = k == outputSlot;
            CgDrawCommands rest = layerList(rec, passes, output ? over[k] : main[k]);
            again += rest.size();
            if (!output || rest.size() > 0) layerPass(firing, constants, targets[k], output, GPU_GRADE).add(rest).end();
            CgDrawCommands apartDraws = layerList(rec, passes, apart[k]);
            if (apartDraws.size() > 0) {
                layerPass(firing, constants, apartTargets[k], false, GPU_GRADE).add(apartDraws).end();
                separate.layers[k] = apartTargets[k];
            }
            channels.setLayer(k, targets[k]);
        }
        if (outputSlot >= 0) for (int b = 0; b < rows.size(); b++) if (carried[b]) once++;
        CgTrace.add(CgChannels.WORLD, LAYER_ONCE, once);
        CgTrace.add(CgChannels.WORLD, LAYER_AGAIN, again);
        outputSlot = -1;
        firing.view().publish(CgLookChannels.KEY, channels);
        if (separate.any()) firing.view().publish(CgSeparateTranslucency.KEY, separate);
    }

    /**
     * Separate translucency into a target of its own, published as {@link CgSeparateTranslucency#KEY} for post; a row
     * whose blend multiplies draws into the scene where post's colour regions grade it, warned once.
     */
    public void recordSeparate(CgStageFiring firing, CgViewPasses passes, CgPassConstants constants) {
        CgRecording rec = firing.recording();
        rows = passes.visibility().batches();
        CgDrawCommands stays = passes.commands(CgEnginePasses.SEPARATE, rec, inScene);
        if (stays.size() > 0) {
            if (!multiplyWarned) LOGGER.warn("A separate translucency draw whose blend multiplies draws into the scene, where colour regions grade it");
            multiplyWarned = true;
            CgRasterPass pass = firing.recording().raster("scene.separateInScene").color(firing.target(), CgLoad.load())
                    .depth(firing.depth(), CgLoad.load(), CgDepthAccess.WRITE).constants(constants).readsDepth(firing.depth());
            renderer.mediated(pass);
            pass.add(stays).end();
        }
        merge = firing.view().flag(CgSceneRenderer.MERGE_EMISSION);
        CgDrawCommands apartDraws = passes.commands(CgEnginePasses.SEPARATE, rec, separateAll);
        if (apartDraws.size() == 0) return;
        CgView view = firing.view();
        int w = Math.max(1, view.width()), h = Math.max(1, view.height());
        if (separateTarget == null || separateTarget.getWidth() != w || separateTarget.getHeight() != h) {
            separateTarget = CgGraphTexture.transientTexture("cg_separate_translucency", new CgTextureDesc(w, h, GRADE_FORMAT));
        }
        layerPass(firing, constants, separateTarget, false, GPU_SEPARATE).add(apartDraws).end();
        separate.target = separateTarget;
        view.publish(CgSeparateTranslucency.KEY, separate);
    }

    /** One channel's translucent draws of every pass type a layer holds, in key order. */
    private CgDrawCommands layerList(CgRecording rec, CgViewPasses passes, SlotForm form) {
        if (listsUsed == lists.length) lists = Arrays.copyOf(lists, listsUsed * 2);
        CgDrawCommands list = lists[listsUsed];
        if (list == null || list.table() != rec.primitives()) lists[listsUsed] = list = new CgDrawCommands(rec.primitives());
        listsUsed++;
        list.clear();
        list.append(passes.commands(CgEnginePasses.EMISSIVE, rec, form));
        list.append(passes.commands(CgEnginePasses.TRANSLUCENT, rec, form));
        list.append(passes.commands(CgEnginePasses.AFTER_DISTORTION, rec, form));
        list.append(passes.commands(CgEnginePasses.SEPARATE, rec, form));
        list.sort();
        return list;
    }

    /** A layer pass into {@code target}: cleared, or over what is in it; tested against the scene's depth. */
    private CgRasterPass layerPass(CgStageFiring firing, CgPassConstants constants, CgGraphTexture target, boolean over, int gpu) {
        CgRasterPass pass = firing.recording().raster("scene.gradeLayer").color(target, over ? CgLoad.load() : CgLoad.CLEAR)
                .depth(CgGraphTexture.current(), CgLoad.load(), CgDepthAccess.WRITE).constants(constants).timed(gpu)
                .readsDepth(CgGraphTexture.current())
                .texture(CgBindingModel.LIGHTMAP, firing.view().hostTextures().lightmapTexture());
        CgGraphTexture scene = firing.view().textures().sceneColor();
        if (scene != null) pass.readsColor(scene);
        renderer.mediated(pass);
        return pass;
    }

    /** A channel's layer: which of its translucent rows, through which forms. */
    private final class SlotForm implements CgCommandForm {
        final int slot;
        final boolean apartRows, overCarried;

        SlotForm(int slot, boolean apartRows, boolean overCarried) {
            this.slot = slot;
            this.apartRows = apartRows;
            this.overCarried = overCarried;
        }

        @Nullable
        @Override
        public CgPipeline form(CgMeshBatches rows, int b, CgPipeline p) {
            if ((rows.flags(b) & CgMeshBatches.HALF_RESOLUTION) != 0) return null;
            if (slot >= 0 && !inSlot(rows, b, slot)) return null;
            boolean separateRow = separate(rows, b);
            if (separateRow != apartRows) return null;
            if (overCarried && carries(rows, b)) return null;
            boolean solid = CgSceneRenderer.queue(rows, b) < CgRenderQueue.TRANSPARENT_THRESHOLD;
            // A graded solid's look is its custom stencil's, read by the look weights: it has no layer
            if (slot >= 0 && solid) return null;
            boolean glows = CgSceneRenderer.emits(rows, b);
            if (p.pass() == CgRenderPassVariant.EMISSIVE) {
                if (!glows) return null;
                return solid || !(merge && CgSceneRenderer.folds(rows, b)) ? layered(p) : null;
            }
            // A solid apart covers what is behind it whatever alpha its fragment writes
            if (solid) return layered(p.as(CgPipelineForm.SOLID));
            if (glows && merge) {
                CgPipeline folded = p.as(CgPipelineForm.FOLD);
                if (folded != null) return layered(folded);
            }
            return p.state().writesNothing() ? null : layered(p);
        }
    }

    /**
     * Whether translucent row {@code b} can draw its layer in the translucent passes: graded, not half size, not apart,
     * one object, and every program it draws there with a layer form already built (one not yet built is started, and
     * the layer pass draws the row meanwhile).
     */
    private boolean carriable(CgMeshBatches rows, int b) {
        if (CgSceneRenderer.queue(rows, b) < CgRenderQueue.TRANSPARENT_THRESHOLD || (rows.flags(b) & CgMeshBatches.HALF_RESOLUTION) != 0
                || separate(rows, b) || rows.instances(b) != null) return false;
        CgMaterialProxy material = CgMaterialProxy.of(rows.material(b));
        CgPipeline pipeline = material.pipeline(CgInstanceKind.OBJECT);
        if (pipeline == null) return true;
        boolean skinned = rows.bones(b) >= 0 || rows.mesh(b).format().isSkinned();
        if (CgSceneRenderer.emits(rows, b)) {
            // Its glow only folded into its colour: a glow drawn apart is an Emissive pass, which takes no layer form
            CgPipeline folded = merge ? pipeline.as(CgPipelineForm.FOLD) : null;
            return folded != null && built(folded.as(CgPipelineForm.LAYER), skinned);
        }
        return pipeline.state().writesNothing() || built(pipeline.as(CgPipelineForm.LAYER), skinned);
    }

    private static boolean built(@Nullable CgPipeline layered, boolean skinned) {
        if (layered == null) return false;
        CgPipeline p = skinned ? layered.as(CgPipelineForm.SKINNED) : layered;
        return p == null || p.precache();
    }

    @Nullable
    private static CgPostSettings look(CgMeshBatches rows, int b) {
        return rows.grade(b);
    }

    private static boolean separate(CgMeshBatches rows, int b) {
        return rows.layer(b) == CgSortLayer.AFTER_DOF || CgMaterialProxy.of(rows.material(b)).translucencyPass().apart();
    }

    /** The channel holding look id {@code id}: the one it held last firing while it is free, so its table stays baked. */
    private int slotOf(int id) {
        for (int k = 0; k < CgLookChannels.MAX; k++) if (slotIds[k] == id && seen[k]) return k;
        for (int k = 0; k < CgLookChannels.MAX; k++) if (slotIds[k] == id && !seen[k]) return k;
        return -1;
    }

    /** Whether row {@code b} is graded by channel {@code k}'s look. */
    private boolean inSlot(CgMeshBatches rows, int b, int k) {
        CgPostSettings look = look(rows, b);
        return look != null && CgLookIds.of(look) == slotIds[k];
    }

    /**
     * {@code pipeline} drawing into a graded layer: its colour blend kept, its alpha the coverage (over what is behind
     * for a blend that covers, nothing for one that adds), depth tested at LEQUAL and unwritten. Null for no pipeline,
     * or one whose blend multiplies, which a layer of added light cannot hold.
     */
    @Nullable
    public static CgPipeline layered(@Nullable CgPipeline pipeline) {
        if (pipeline == null) return null;
        CgPipeline made = LAYER_FORMS.computeIfAbsent(pipeline, p -> {
            CgRenderState state = layerState(p.state());
            return state == null ? p : p.withState(state);
        });
        return made == pipeline ? null : made;
    }

    @Nullable
    private static CgRenderState layerState(CgRenderState own) {
        // Unset is the translucent pass's alpha blend.
        CgBlendState b = own.getBlend() != null ? own.getBlend() : CgBlendState.ALPHA;
        CgBlendState blend = b.layered();
        if (blend == null) return null;
        CgDepthState depth = own.getDepth();
        return CgRenderState.builder().blend(blend)
                .depth(depth == null || depth.test() ? CgDepthState.TEST_ONLY : CgDepthState.NONE)
                .cull(own.getCull()).colorMask(CgColorMask.ALL).build();
    }
}
