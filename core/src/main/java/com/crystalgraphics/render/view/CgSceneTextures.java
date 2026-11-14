package com.crystalgraphics.render.view;

import com.crystalgraphics.render.graph.CgGraphTexture;

import javax.annotation.Nullable;

/**
 * A view's scene textures this frame (Unreal's {@code FSceneTextures}): the scene's colour and depth, our surfaces'
 * normals, custom depth and stencil, custom data, the depth pyramid, distortion and the subject. A stage's pass reads them
 * by name; what is asked of the renderer before it draws is asked here too ({@link #requestNormals} and the others), as
 * Unreal's scene texture config is set before the view renders.
 *
 * <pre>{@code
 * CgRenderStage.PREPARE.register(frame -> frame.view().textures().requestNormals());   // this frame's opaque pass writes them
 *
 * CgRenderStage.SURFACES.register(frame -> {
 *     CgSceneTextures t = frame.view().textures();
 *     CgRasterPass pass = frame.recording().raster("mymod.outline").color(frame.target(), CgLoad.load())
 *             .reads(t.customDepth()).reads(t.normals()).camera(frame.camera());
 *     pass.draw(CgMesh.fullScreen(), outline);
 *     pass.end();
 * });
 * }</pre>
 *
 * <ul>
 *   <li>Render thread. Each is null until the renderer has made it this frame: {@link #customDepth()} after the
 *       custom depth pass (in {@code SURFACES} and later), {@link #normals()} only where asked and the host's target
 *       takes a second attachment.</li>
 *   <li>Requests last the frame they are made in: ask each frame, before the opaque callback draws ({@code PREPARE}).</li>
 * </ul>
 */
public final class CgSceneTextures {

    @Nullable
    private CgGraphTexture sceneColor, depth, normals, customDepth, customData, hzb, distortion, subject, overdraw;
    private boolean linear;
    private boolean normalsAsked, customDataAsked, subjectAsked;
    private int subjectKey;

    CgSceneTextures() {
    }

    /**
     * What the view's world stages draw into: the linear HDR scene from the translucent callback on (RGBA16F), else the
     * host's colour as it stands.
     */
    @Nullable
    public CgGraphTexture sceneColor() {
        return sceneColor;
    }

    /** Whether {@link #sceneColor()} is the linear HDR scene rather than the host's encoded colour. */
    public boolean linear() {
        return linear;
    }

    /** The scene's depth: the host's, or the scene's own where the host lends none. */
    @Nullable
    public CgGraphTexture depth() {
        return depth;
    }

    /**
     * Our opaque surfaces' world normals (Unreal's G-buffer normal): RGBA16F, {@code n * 0.5 + 0.5}, alpha the eye depth
     * drawn at, negative where none of ours is. Host-drawn surfaces write none, so a reader rebuilds theirs from depth.
     */
    @Nullable
    public CgGraphTexture normals() {
        return normals;
    }

    /**
     * Unreal's CustomDepth and CustomStencil (DEPTH32F_STENCIL8): the nearest depth of the primitives marked
     * {@code customDepth}, tested against each other alone; its stencil through {@link CgGraphTexture#stencil()}.
     */
    @Nullable
    public CgGraphTexture customDepth() {
        return customDepth;
    }

    /** Unreal's custom data and shading model of our opaque surfaces, RG32F: only where {@link #requestCustomData} asked. */
    @Nullable
    public CgGraphTexture customData() {
        return customData;
    }

    /** The depth pyramid (Unreal's furthest HZB): each level the farthest depth it covers; what a GPU cull tests against. */
    @Nullable
    public CgGraphTexture hzb() {
        return hzb;
    }

    /** The screen's bend this frame, where something distorts: what the distortion apply read. */
    @Nullable
    public CgGraphTexture distortion() {
        return distortion;
    }

    /**
     * What the hitting effect glows with this frame, linear HDR at a quarter of the target's size, for an impact frame:
     * only where {@link #requestSubject} asked.
     */
    @Nullable
    public CgGraphTexture subject() {
        return subject;
    }

    /** The overdraw view's counts this frame, where the overdraw debug view is on. */
    @Nullable
    public CgGraphTexture overdraw() {
        return overdraw;
    }

    /** That this frame's opaque pass writes {@link #normals()}: ask before the opaque callback draws. */
    public void requestNormals() {
        normalsAsked = true;
    }

    /** That this frame writes {@link #customData()}: ask before the translucent callback draws. */
    public void requestCustomData() {
        customDataAsked = true;
    }

    /**
     * That this frame draws {@link #subject()}: the glows of the primitives marked with {@code key}, or every glow for 0.
     * Ask before the opaque callback draws: a keyed subject is drawn there.
     */
    public void requestSubject(int key) {
        subjectAsked = true;
        subjectKey = key;
    }

    public boolean normalsRequested() {
        return normalsAsked;
    }

    public boolean customDataRequested() {
        return customDataAsked;
    }

    public boolean subjectRequested() {
        return subjectAsked;
    }

    public int subjectKey() {
        return subjectKey;
    }

    // ── Engine side: the scene renderer's ─────────────────────────────────────────────────────────

    /** Engine side: what the view's world stages draw into now, and whether it is the linear HDR scene. */
    public void sceneColor(@Nullable CgGraphTexture texture, boolean linear) {
        this.sceneColor = texture;
        this.linear = linear;
    }

    public void depth(@Nullable CgGraphTexture texture) {
        depth = texture;
    }

    public void normals(@Nullable CgGraphTexture texture) {
        normals = texture;
    }

    public void customDepth(@Nullable CgGraphTexture texture) {
        customDepth = texture;
    }

    public void customData(@Nullable CgGraphTexture texture) {
        customData = texture;
    }

    public void hzb(@Nullable CgGraphTexture texture) {
        hzb = texture;
    }

    public void distortion(@Nullable CgGraphTexture texture) {
        distortion = texture;
    }

    public void subject(@Nullable CgGraphTexture texture) {
        subject = texture;
    }

    public void overdraw(@Nullable CgGraphTexture texture) {
        overdraw = texture;
    }

    /** A new frame of the view: every texture and request dropped. */
    void reset() {
        sceneColor = depth = normals = customDepth = customData = hzb = distortion = subject = overdraw = null;
        linear = false;
        normalsAsked = customDataAsked = subjectAsked = false;
        subjectKey = 0;
    }
}
