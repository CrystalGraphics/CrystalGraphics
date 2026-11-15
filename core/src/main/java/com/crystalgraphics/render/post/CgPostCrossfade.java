package com.crystalgraphics.render.post;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialInstance;
import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.render.CgFrameClock;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.graph.CgScreenTexture;

/**
 * A cut's crossfade ({@link CgPost#crossfade}): the firing it is asked in holds its final picture, and the frames after
 * it draw that picture over theirs, its alpha falling linearly to 0 over the crossfade on the frame clock. The chain's
 * step after {@code DISPLAY}, so a later crossfade holds a picture with an earlier one's fade in it.
 */
final class CgPostCrossfade {

    private static final String HOLD = "crystalgraphics:shaders/post/hold.shader";
    private static final String FADE = "crystalgraphics:shaders/post/crossfade.shader";

    private CgMaterialInstance hold, fade;
    private CgGraphTexture held, bound;
    private volatile float asked = Float.NaN;
    private float seconds;
    private double start = Double.NaN;

    /** Holds the next firing's final picture and fades it out over {@code seconds}. Any thread. */
    void crossfade(float seconds) {
        asked = Math.max(1e-3f, seconds);
    }

    /** How much of the held picture shows now. */
    private float share() {
        if (start != start) return 0f;
        float t = (float) (CgFrameClock.seconds() - start) / seconds;
        return t <= 0f ? 0f : Math.max(0f, 1f - t);
    }

    CgScreenTexture apply(CgPostContext ctx, CgScreenTexture picture) {
        float share = share();
        float s = asked;
        if (s != s && share <= 0f) {
            start = Double.NaN;
            return picture;
        }
        if (share > 0f) draw(ctx, picture, share);
        if (s == s) {
            asked = Float.NaN;
            if (hold(ctx, picture)) {
                seconds = s;
                start = CgFrameClock.seconds();
            }
        }
        return picture;
    }

    private void draw(CgPostContext ctx, CgScreenTexture picture, float share) {
        if (fade == null) fade = CgMaterial.load(FADE).instance();
        if (held != bound) {
            fade.sampler("_Held", 0, held);
            bound = held;
        }
        ctx.screen(fade).name("postCrossfade.target").reads(held).weight(share).over(picture);
    }

    /** Copies the picture as it stands into {@link #held}, made at its size; whether it could. */
    private boolean hold(CgPostContext ctx, CgScreenTexture picture) {
        int w = picture.width(), h = picture.height();
        if (w <= 0 || h <= 0) return false;
        CgRecording recording = ctx.recording();
        if (held == null || held.getWidth() != w || held.getHeight() != h) {
            if (held != null) recording.release(held);
            held = CgGraphTexture.requested("cg_post_held", new CgTextureDesc(w, h, CgFormat.RGBA8_UNORM));
        }
        if (hold == null) hold = CgMaterial.load(HOLD).instance();
        ctx.screen(hold).name("postCrossfade.held").from(picture).into(CgScreenTexture.of(held));
        return true;
    }

    /** Forgets its materials and held frame, which the registry and the graph free. */
    void release() {
        hold = fade = null;
        held = bound = null;
        start = asked = Float.NaN;
    }
}
