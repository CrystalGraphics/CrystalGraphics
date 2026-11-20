package com.crystalgraphics.render.scene.component;

import java.util.Objects;

import org.joml.Matrix4d;
import org.joml.Quaternionf;

import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.render.scene.component.CgCanvasLabels;
import com.crystalgraphics.render.scene.batch.CgMeshBatches;
import com.crystalgraphics.render.view.CgView;
import com.crystalgraphics.shape.bounds.CgAABB;
import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontFamily;
import com.crystalgraphics.text.render.CgTextDraw;

/**
 * Text standing in the world until removed: a name tag, a sign, a waypoint. Unreal's {@code UTextRenderComponent}.
 * Its glyphs are captured once and kept on the GPU; a label that only moves writes one matrix a frame, and one whose
 * text or style changed is captured again on the next frame.
 *
 * <pre>{@code
 * CgLabelComponent tag = scene.label("Spawn").font(font).height(0.5f).at(x, y + 2, z).spawn();
 * tag.text("Spawn (3)");                                   // captured again, next frame
 * tag.draw().color(0xFFFF5040).style(DAMAGE);              // any field of its text draw, edited in place
 * tag.remove();
 * }</pre>
 *
 * <p>A sign, turned in the world rather than facing the camera:</p>
 *
 * <pre>{@code
 * scene.label(sign).family(family).turned().anchor(0f, 1f).at(x, y, z).rotation(facingSouth).spawn();
 * }</pre>
 *
 * <ul>
 *   <li>{@code height} is a line's height in blocks; its raster tier follows how tall it stands on screen.</li>
 *   <li>Unlit, unfogged, depth-tested, drawn after the translucent draws: a nearer label's text hides a farther one's.</li>
 *   <li>Its draw is never {@code submit()}ted: the scene draws it every frame while it is spawned.</li>
 *   <li>{@code visible(false)} hides it and keeps its glyphs; {@code maxDistance} does not apply to it.</li>
 *   <li>For text shown one frame, {@code scene.labelOnce(...)}. Render thread, as its scene.</li>
 * </ul>
 */
public final class CgLabelComponent extends CgPrimitiveComponent<CgLabelComponent> {

    private final CgCanvasLabels.Label label = CgCanvasLabels.held();
    private final Quaternionf turn = new Quaternionf();
    private float height = 0.25f;
    private boolean turned;
    private int slot = -1;

    CgLabelComponent(CgScenePrimitives scene, String text) {
        super(scene);
        label.draw().text(Objects.requireNonNull(text, "text"));
    }

    /** What it reads: captured again on the next frame where it differs. */
    public CgLabelComponent text(String text) {
        label.draw().text(Objects.requireNonNull(text, "text"));
        return this;
    }

    /** Its text draw: font, colour, style, wrap, every field, edited in place. Never submit it. */
    public CgTextDraw draw() {
        return label.draw();
    }

    /** Its draw in {@code font}. */
    public CgLabelComponent font(CgFont font) {
        label.font(font);
        return this;
    }

    /** Its draw in {@code family}, falling back across its faces; size it with {@code draw().targetPx}. */
    public CgLabelComponent family(CgFontFamily family) {
        label.family(family);
        return this;
    }

    /** How tall a line of it stands, in blocks. 0.25 unless set. */
    public CgLabelComponent height(float blocks) {
        this.height = blocks;
        label.height(blocks);
        return this;
    }

    /** Which point of the text its position names: 0 its left or bottom, 1 its right or top. The middle unless set. */
    public CgLabelComponent anchor(float x, float y) {
        label.anchor(x, y);
        return this;
    }

    /** Turned by its rotation in the world instead of facing the camera; the text reads along +x, up +y. */
    public CgLabelComponent turned() {
        turned = true;
        if (spawned()) place();
        return this;
    }

    /** Shown or hidden; hidden it keeps its capture, so showing it again costs nothing. */
    @Override
    public CgLabelComponent visible(boolean visible) {
        super.visible(visible);
        label.shown(visible);
        return this;
    }

    public float height() {
        return height;
    }

    /** Its label where its transform puts it: the point, and its turn when turned. */
    private void place() {
        Matrix4d w = world();
        label.at(w.m30(), w.m31(), w.m32());
        label.rotation(turned ? w.getNormalizedRotation(turn) : null);
    }

    @Override
    void onSpawn() {
        place();
        slot = scene.holdLabel(label);
    }

    @Override
    void onRemove() {
        scene.dropLabel(slot);
        slot = -1;
    }

    @Override
    void onMoved() {
        place();
    }

    @Override
    void onLooked() {
    }

    @Override
    boolean kindBounds(CgAABB out) {
        return false;
    }

    @Override
    void gather(CgView view, CgCamera camera, CgMeshBatches out, CgSceneGather gather) {
    }

    @Override
    public String toString() {
        return "CgLabelComponent(" + label.draw() + ")";
    }
}
