package com.crystalgraphics.render.post;

import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshTopology;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.render.CgFrameClock;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgOrder;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.graph.CgTextureDesc;

/**
 * A cut's crossfade ({@link CgPostStack#crossfade}): the firing it is asked in holds its final picture, and the frames
 * after it draw that picture over theirs, its alpha falling linearly to 0 over the crossfade on the frame clock. Last
 * at {@code AFTER_COMPOSITE}, so a later crossfade holds a picture with an earlier one's fade in it.
 */
final class CgPostCrossfade implements CgPostEffect {

    private static final String HOLD = "crystalgraphics:shaders/post/hold.shader";
    private static final String FADE = "crystalgraphics:shaders/post/crossfade.shader";
    private static final CgFrameBufferFormat RGBA8 = CgFrameBufferFormat.builder("cg_post_held")
            .color(0, CgTextureType.RGBA8).build();
    private static final CgMesh FULLSCREEN = CgMesh.vertices(3, CgMeshTopology.TRIANGLES);

    private CgMaterial hold, fade;
    private CgGraphTexture held, bound;
    private volatile float asked = Float.NaN;
    private float seconds;
    private double start = Double.NaN;

    /** Holds the next firing's final picture and fades it out over {@code seconds}. Any thread. */
    void crossfade(float seconds) {
        asked = Math.max(1e-3f, seconds);
    }

    @Override
    public CgPostPoint point() {
        return CgPostPoint.AFTER_COMPOSITE;
    }

    @Override
    public int order() {
        return Integer.MAX_VALUE;
    }

    @Override
    public boolean active(CgPostContext post) {
        return asked == asked || share() > 0f;
    }

    /** How much of the held picture shows now. */
    private float share() {
        if (start != start) return 0f;
        float t = (float) (CgFrameClock.seconds() - start) / seconds;
        return t <= 0f ? 0f : Math.max(0f, 1f - t);
    }

    @Override
    public void record(CgPostContext post) {
        CgRecording recording = post.recording();
        float share = share();
        if (share > 0f) draw(post, recording, share);
        float s = asked;
        if (s == s) {
            asked = Float.NaN;
            if (hold(post, recording)) {
                seconds = s;
                start = CgFrameClock.seconds();
            }
        } else if (share <= 0f) {
            start = Double.NaN;
        }
    }

    private void draw(CgPostContext post, CgRecording recording, float share) {
        if (fade == null) fade = CgMaterial.newInstance(FADE);
        if (held != bound) {
            CgGraphTexture h = held;
            fade.applyProperties(b -> b.sampler("_Held", 0, h));
            bound = h;
        }
        CgPipeline pipeline = fade.pipeline(CgInstanceKind.OBJECT);
        if (pipeline == null) return;
        CgRasterPass pass = recording.raster(post.target(), CgLoad.load(), post.constants(), null, CgOrder.SORTED);
        CgChunkBuilder chunks = recording.chunks().begin();
        chunks.draw(pipeline, fade.captureBindings(recording.bindings()), FULLSCREEN);
        CgPostMaterial.weighted(chunks, share);
        pass.add(chunks.end());
        pass.end();
    }

    /** Copies the target as it stands into {@link #held}, made at its size; whether it could. */
    private boolean hold(CgPostContext post, CgRecording recording) {
        int w = post.stage().host().width(), h = post.stage().host().height();
        if (w <= 0 || h <= 0) return false;
        if (held == null || held.getWidth() != w || held.getHeight() != h) {
            if (held != null) recording.release(held);
            held = CgGraphTexture.requested("cg_post_held", new CgTextureDesc(w, h, RGBA8));
        }
        if (hold == null) hold = CgMaterial.newInstance(HOLD);
        CgPipeline pipeline = hold.pipeline(CgInstanceKind.OBJECT);
        if (pipeline == null) return false;
        CgRasterPass pass = recording.raster(held, CgLoad.clear(0f, 0f, 0f, 1f), post.constants(), null, CgOrder.SORTED)
                .sceneColor(CgBindingPoints.SCENE_COLOR_TEXTURE_UNIT, post.target());
        CgChunkBuilder chunks = recording.chunks().begin();
        chunks.draw(pipeline, hold.captureBindings(recording.bindings()), FULLSCREEN);
        CgPostMaterial.weighted(chunks, 1f);
        pass.add(chunks.end());
        pass.end();
        return true;
    }

    /** Forgets its materials and held frame, which the registry and the graph free. */
    void release() {
        hold = fade = null;
        held = bound = null;
        start = asked = Float.NaN;
    }
}
