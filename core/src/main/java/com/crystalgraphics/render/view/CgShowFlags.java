package com.crystalgraphics.render.view;

import com.crystalgraphics.render.post.debug.CgPostDebug;

/**
 * What a view draws (Unreal's {@code FEngineShowFlags}): a preview, a capture or an icon view turns a feature off
 * without touching the game's view. Every feature is on by default; the debug views ({@link #overdraw}) off.
 *
 * <pre>{@code
 * preview.showFlags().lights(false).post(false);       // a model preview: unlit by the world's lights, no grade
 * capture.showFlags().labels(false);                   // a portal's picture: no name tags
 * }</pre>
 *
 * <p>Render thread: set them between frames, as the view's owner.</p>
 */
public final class CgShowFlags {

    private boolean lights = true, media = true, post = true, labels = true, distortion = true, decals = true,
            translucency = true, overdraw;

    CgShowFlags() {
    }

    /** Takes {@code other}'s every flag. */
    void set(CgShowFlags other) {
        lights = other.lights;
        media = other.media;
        post = other.post;
        labels = other.labels;
        distortion = other.distortion;
        decals = other.decals;
        translucency = other.translucency;
        overdraw = other.overdraw;
    }

    /** The scene's lights: point, spot and tube lights, and their shadows. */
    public CgShowFlags lights(boolean on) {
        lights = on;
        return this;
    }

    public boolean lights() {
        return lights;
    }

    /** Smoke, haze and fog in the air (participating media). */
    public CgShowFlags media(boolean on) {
        media = on;
        return this;
    }

    public boolean media() {
        return media;
    }

    /** The post chain: grade, bloom, every post material. Off, the view's picture is its HDR scene encoded as it is. */
    public CgShowFlags post(boolean on) {
        post = on;
        return this;
    }

    public boolean post() {
        return post;
    }

    /** The scene's labels: name tags, signs. */
    public CgShowFlags labels(boolean on) {
        labels = on;
        return this;
    }

    public boolean labels() {
        return labels;
    }

    /** The screen bent by what refracts (heat haze). */
    public CgShowFlags distortion(boolean on) {
        distortion = on;
        return this;
    }

    public boolean distortion() {
        return distortion;
    }

    /**
     * The translucent overdraw count, into {@code textures().overdraw()} (Unreal's quad overdraw view): off by default,
     * on in every view under {@code -Dcrystalgraphics.post.debug=overdraw}, which also shows it.
     */
    public CgShowFlags overdraw(boolean on) {
        overdraw = on;
        return this;
    }

    public boolean overdraw() {
        return overdraw || "overdraw".equals(CgPostDebug.VIEW.get());
    }

    /** Decals projected onto the scene. */
    public CgShowFlags decals(boolean on) {
        decals = on;
        return this;
    }

    public boolean decals() {
        return decals;
    }

    /** Everything that blends: off, the view draws its solids alone. */
    public CgShowFlags translucency(boolean on) {
        translucency = on;
        return this;
    }

    public boolean translucency() {
        return translucency;
    }
}
