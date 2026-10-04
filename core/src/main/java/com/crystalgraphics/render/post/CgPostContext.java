package com.crystalgraphics.render.post;

import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.post.composite.CgPostComposite;
import com.crystalgraphics.render.post.volume.CgPostSettings;
import com.crystalgraphics.render.stage.CgFrameResources;
import com.crystalgraphics.render.stage.CgStageFrame;

/**
 * What a {@link CgPostEffect} records with: the stage firing it runs in, the firing's blackboard, and the composite it
 * may feed. One instance, refilled every firing; never hold it past {@link CgPostEffect#record}.
 *
 * <pre>{@code
 * CgGraphTexture emission = post.resources().get(CgFrameKeys.EMISSION);
 * CgRasterPass pass = post.recording().raster(post.target(), CgLoad.load(), post.constants(), null, CgOrder.SORTED);
 * post.composite().bloom(level1, intensity);   // at BEFORE_COMPOSITE: an input of the one composite pass
 * }</pre>
 */
public final class CgPostContext {

    private final CgPostComposite composite;
    private CgStageFrame stage;
    private CgPostSettings settings;

    CgPostContext(CgPostComposite composite) {
        this.composite = composite;
    }

    void begin(CgStageFrame stage, CgPostSettings settings) {
        this.stage = stage;
        this.settings = settings;
    }

    /** Every volume blended at the camera this firing: what the looks are drawn with. Read it; never keep it. */
    public CgPostSettings settings() {
        return settings;
    }

    /** The stage firing the stack records in. */
    public CgStageFrame stage() {
        return stage;
    }

    public CgRecording recording() {
        return stage.recording();
    }

    /** The host's target: what the composite draws onto. */
    public CgGraphTexture target() {
        return stage.target();
    }

    /** The host's camera as pass constants ({@link CgStageFrame#constants()}). */
    public CgPassConstants constants() {
        return stage.constants();
    }

    /** The firing's blackboard: what the scene's renderers published ({@code CgFrameKeys}). */
    public CgFrameResources resources() {
        return stage.resources();
    }

    /** The composite this firing: an effect at {@link CgPostPoint#BEFORE_COMPOSITE} sets its inputs. */
    public CgPostComposite composite() {
        return composite;
    }
}
