package com.crystalgraphics.render.post;

import com.crystalgraphics.api.material.CgMaterialInstance;
import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.graph.CgScreenTexture;
import com.crystalgraphics.render.post.distortion.CgPostDistortion;
import com.crystalgraphics.render.post.volume.CgPostSettings;
import com.crystalgraphics.render.scene.renderer.feature.distortion.CgDistortionField;
import com.crystalgraphics.render.stage.CgStageFiring;
import com.crystalgraphics.render.view.CgSceneTextures;
import com.crystalgraphics.render.view.CgView;

import java.util.HashMap;
import java.util.Map;

/**
 * What a {@link CgPostEffect} is handed: the view it runs for, its blended settings, its scene textures, and the calls
 * that draw. One per chain, refilled every firing; never hold it past the effect.
 *
 * <pre>{@code
 * (ctx, input) -> {
 *     CgScreenTexture half = ctx.texture("mymod.half", input.width() / 2, input.height() / 2, CgFormat.RGBA16_FLOAT);
 *     ctx.screen(down).into(half);                                      // reads input as CG_SCENE_COLOR
 *     return ctx.screen(up).from(half).over(input);
 * }
 * ctx.settings().vignette();                                            // the looks blended at the camera
 * ctx.scene().customDepth();                                            // what the scene renderer published
 * CgGraphTexture bent = ctx.distorted(ctx.scene().subject());           // a side input bent as the scene was
 * }</pre>
 */
public final class CgPostContext {

    private final CgPostDistortion distortion;
    private final CgScreenPass screen = new CgScreenPass(this);
    private final Map<String, CgGraphTexture> textures = new HashMap<>();
    private CgStageFiring firing;
    private CgPostSettings settings;
    private CgScreenTexture input;

    CgPostContext(CgPostDistortion distortion) {
        this.distortion = distortion;
    }

    void begin(CgStageFiring firing, CgPostSettings settings) {
        this.firing = firing;
        this.settings = settings;
        distortion.begin();
    }

    /** Engine side: the picture the next effect is given, which {@link #screen} reads unless told otherwise. */
    void input(CgScreenTexture input) {
        this.input = input;
    }

    /** Every volume blended at the camera this firing: what the looks are drawn with. Read it; never keep it. */
    public CgPostSettings settings() {
        return settings;
    }

    /** The view the chain runs for: its camera, size and environment. */
    public CgView view() {
        return firing.view();
    }

    /** The view's scene textures this frame (Unreal's {@code SceneTextures}): depth, custom depth, normals, the subject. */
    public CgSceneTextures scene() {
        return firing.view().textures();
    }

    /** The firing's recording, for a pass of your own: a compute pass, a copy. */
    public CgRecording recording() {
        return firing.recording();
    }

    /** The depth of the scene. */
    public CgGraphTexture depth() {
        return firing.depth();
    }

    /** The view's camera as pass constants, for a pass of your own. */
    public CgPassConstants constants() {
        return firing.constants();
    }

    /**
     * A full-screen draw of {@code material}, reading the effect's input as {@code CG_SCENE_COLOR}: drawn with
     * {@code over} or {@code into}. The same instance every call: draw it before the next.
     */
    public CgScreenPass screen(CgMaterialInstance material) {
        return screen.begin(material, input);
    }

    /** A texture for this frame of {@code like}'s size and format, kept by {@code name} while they hold. */
    public CgScreenTexture texture(String name, CgScreenTexture like) {
        CgFormat format = like.texture().format();
        return texture(name, like.width(), like.height(), format != null ? format : CgFormat.RGBA16_FLOAT);
    }

    /** A texture for this frame, kept by {@code name} while its size and format hold. */
    public CgScreenTexture texture(String name, int width, int height, CgFormat format) {
        int w = Math.max(1, width), h = Math.max(1, height);
        CgGraphTexture t = textures.get(name);
        if (t == null || t.getWidth() != w || t.getHeight() != h || t.format() != format) {
            t = CgGraphTexture.transientTexture(name, new CgTextureDesc(w, h, format));
            textures.put(name, t);
        }
        return CgScreenTexture.of(t);
    }

    /**
     * {@code input} bent by this frame's distortion, as the scene beneath it was: for an effect reading a side input of
     * the scene (a mask, a subject) rather than the picture, which is bent already. {@code input} itself when nothing
     * distorted; bent once however many effects ask.
     */
    public CgGraphTexture distorted(CgGraphTexture input) {
        return distortion.bend(recording(), view().read(CgDistortionField.KEY), constants(), input);
    }

    /** Engine side: the firing, for the chain's own steps. */
    CgStageFiring firing() {
        return firing;
    }
}
