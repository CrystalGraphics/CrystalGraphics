package com.crystalgraphics.render.post;

import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.post.composite.CgPostComposite;
import com.crystalgraphics.render.post.distortion.CgPostDistortion;
import com.crystalgraphics.render.post.volume.CgPostSettings;
import com.crystalgraphics.render.scene.renderer.feature.distortion.CgDistortionField;
import com.crystalgraphics.render.stage.CgStageFiring;
import com.crystalgraphics.render.view.CgSceneTextures;
import com.crystalgraphics.render.view.CgView;

/**
 * What a {@link CgPostEffect} records with: the view it runs for, its scene textures, the firing's recording and the
 * composite it may feed. One instance, refilled every firing; never hold it past {@link CgPostEffect#record}.
 *
 * <pre>{@code
 * CgGraphTexture subject = post.textures().subject();
 * CgRasterPass pass = post.recording().raster("mymod.post").color(post.target(), CgLoad.load()).constants(post.constants());
 * post.composite().bloom(level1, intensity);   // at BEFORE_COMPOSITE: an input of the one composite pass
 * CgGraphTexture bent = post.distorted(subject);   // a side input bent as the scene was
 * }</pre>
 */
public final class CgPostContext {

    private final CgPostComposite composite;
    private final CgPostDistortion distortion;
    private CgStageFiring firing;
    private CgPostSettings settings;

    CgPostContext(CgPostComposite composite, CgPostDistortion distortion) {
        this.composite = composite;
        this.distortion = distortion;
    }

    void begin(CgStageFiring firing, CgPostSettings settings) {
        this.firing = firing;
        this.settings = settings;
        distortion.begin();
    }

    /** Every volume blended at the camera this firing: what the looks are drawn with. Read it; never keep it. */
    public CgPostSettings settings() {
        return settings;
    }

    /** The view the stack records for: its camera, size and environment. */
    public CgView view() {
        return firing.view();
    }

    /** The view's scene textures this frame: the HDR scene, custom depth, normals, the subject. */
    public CgSceneTextures textures() {
        return firing.view().textures();
    }

    public CgRecording recording() {
        return firing.recording();
    }

    /** What the composite draws onto: the HDR scene before it, the host's target from it. */
    public CgGraphTexture target() {
        return firing.target();
    }

    /** The depth of the scene the composite draws over. */
    public CgGraphTexture depth() {
        return firing.depth();
    }

    /** The view's camera as pass constants. */
    public CgPassConstants constants() {
        return firing.constants();
    }

    /**
     * {@code input} bent by this frame's distortion, as the scene beneath it was: for an effect reading a side input of
     * the scene (a mask, a subject) rather than the target, which is bent already. {@code input} itself when nothing
     * distorted; bent once however many effects ask.
     *
     * <pre>{@code
     * CgGraphTexture subject = post.distorted(post.textures().subject());
     * }</pre>
     */
    public CgGraphTexture distorted(CgGraphTexture input) {
        return distortion.bend(recording(), view().read(CgDistortionField.KEY), constants(), input);
    }

    /** The composite this firing: an effect at {@link CgPostPoint#BEFORE_COMPOSITE} sets its inputs. */
    public CgPostComposite composite() {
        return composite;
    }
}
