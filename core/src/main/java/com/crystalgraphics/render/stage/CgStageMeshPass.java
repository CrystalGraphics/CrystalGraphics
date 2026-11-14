package com.crystalgraphics.render.stage;

import com.crystalgraphics.render.graph.CgDepthAccess;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.render.meshpass.CgMeshPassType;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.scene.pass.CgViewPasses;

import javax.annotation.Nullable;
import java.util.Objects;

/**
 * A mesh pass run from a render stage: its type's commands for the firing's view, drawn into the attachments it
 * declares, as Unreal's {@code AddSimpleMeshPass} runs a mesh pass from a scene view extension. Made by
 * {@link CgStageFrame#meshPass}; one a firing at a time.
 *
 * <pre>{@code
 * CgRenderStage.WORLD_TRANSLUCENT.register(frame -> {
 *     CgGraphTexture heat = CgGraphTexture.transientTexture("mymod.heat", CgTextureDesc.of(w, h, CgFormat.R16_FLOAT));
 *     frame.meshPass(THERMAL)
 *             .color(heat, CgLoad.clear(0f, 0f, 0f, 0f))
 *             .depth(frame.depth(), CgLoad.load(), CgDepthAccess.READ)
 *             .draw();                                   // the client scene's batches its processor took, culled, sorted
 * });
 *
 * frame.meshPass(THERMAL).scene(preview).color(target, CgLoad.CLEAR).culled(frame.depthPyramid()).draw();
 * }</pre>
 *
 * <ul>
 *   <li>The client scene's unless {@link #scene} says otherwise; the firing's view and camera.</li>
 *   <li>Declare its colour or depth first, then {@link #draw}: the pass ends there, and what it drew is what a later read
 *       of its targets sees.</li>
 *   <li>{@link #culled} culls the view's instanced meshes on the GPU first, against a depth pyramid; without it every
 *       instance in the frustum draws.</li>
 * </ul>
 */
public final class CgStageMeshPass {

    private final CgStageFrame frame;
    private CgMeshPassType type;
    private CgScene scene;
    @Nullable
    private CgRasterPass pass;
    private boolean culled;
    @Nullable
    private CgGraphTexture pyramid;

    CgStageMeshPass(CgStageFrame frame) {
        this.frame = frame;
    }

    CgStageMeshPass begin(CgMeshPassType type) {
        this.type = Objects.requireNonNull(type, "type");
        scene = CgScene.client();
        pass = null;
        culled = false;
        pyramid = null;
        return this;
    }

    /** Draws {@code scene}'s primitives rather than the client's. */
    public CgStageMeshPass scene(CgScene scene) {
        this.scene = Objects.requireNonNull(scene, "scene");
        return this;
    }

    /** Draws into {@code texture}, loaded or cleared by {@code load}: its first colour, then a second. */
    public CgStageMeshPass color(CgGraphTexture texture, CgLoad load) {
        pass().color(texture, load);
        return this;
    }

    /** Tests against {@code texture}'s depth, writing it unless {@code access} is {@code READ}. */
    public CgStageMeshPass depth(CgGraphTexture texture, CgLoad load, CgDepthAccess access) {
        pass().depth(texture, load, access);
        return this;
    }

    /** Culls the view's instances on the GPU before drawing, against {@code pyramid} (null for the frustum alone). */
    public CgStageMeshPass culled(@Nullable CgGraphTexture pyramid) {
        culled = true;
        this.pyramid = pyramid;
        return this;
    }

    /** Records the pass: the type's commands for the view, under the firing's camera. Answers the ended pass. */
    public CgRasterPass draw() {
        if (pass == null) throw new IllegalStateException(type + " draws into nothing: declare its colour or depth first");
        CgViewPasses passes = CgViewPasses.of(frame.view(), scene, frame.camera());
        if (culled) passes.cull(frame.recording(), pyramid);
        CgRasterPass p = pass;
        p.constants(frame.constants()).add(passes.commands(type, frame.recording()));
        p.end();
        pass = null;
        return p;
    }

    private CgRasterPass pass() {
        if (pass == null) pass = frame.recording().raster(type.id());
        return pass;
    }
}
