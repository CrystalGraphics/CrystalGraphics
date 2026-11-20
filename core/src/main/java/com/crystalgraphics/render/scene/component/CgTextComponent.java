package com.crystalgraphics.render.scene.component;

import java.util.Objects;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontFamily;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.render.scene.batch.CgMeshBatches;
import com.crystalgraphics.render.view.CgView;
import com.crystalgraphics.shape.bounds.CgAABB;
import com.crystalgraphics.text.render.CgTextDraw;

/**
 * Text standing in the world until removed: a name tag, a sign, a waypoint. Unreal's {@code UTextRenderComponent}.
 * Its glyphs are captured once and kept on the GPU; text that only moves writes one matrix a frame, and text whose
 * words or style changed is captured again on the next frame.
 *
 * <pre>{@code
 * CgTextComponent tag = scene.text("Spawn").font(font).height(0.5f).at(x, y + 2, z).spawn();
 * tag.text("Spawn (3)");                                   // captured again, next frame
 * tag.draw().color(0xFFFF5040).style(DAMAGE);              // any field of its text draw, edited in place
 * tag.remove();
 * }</pre>
 *
 * <p>A sign, turned by its rotation rather than facing the camera:</p>
 *
 * <pre>{@code
 * scene.text(sign).family(family).turned(true).anchor(0f, 1f).at(x, y, z).rotation(facingSouth).spawn();
 * }</pre>
 *
 * <ul>
 *   <li>{@code height} is a line's height in blocks; its raster tier follows how tall it stands on screen.</li>
 *   <li>Unlit, unfogged, depth-tested, drawn after the translucent draws: a nearer text hides a farther one's.</li>
 *   <li>Its draw is never {@code submit()}ted: the scene draws it every frame while it is spawned.</li>
 *   <li>{@code visible(false)} hides it and keeps its glyphs; {@code maxDistance} does not apply to it.</li>
 *   <li>For text shown one frame, {@code scene.textOnce(...)}. Render thread, as its scene.</li>
 * </ul>
 */
public final class CgTextComponent extends CgPrimitiveComponent<CgTextComponent> {

    private final CgTextDraw draw = CgTextDraw.detached();
    private float height = 0.25f, anchorX = 0.5f, anchorY = 0.5f;
    private boolean turned;
    private int slot = -1;

    CgTextComponent(CgScenePrimitives scene, String text) {
        super(scene);
        draw.text(Objects.requireNonNull(text, "text"));
    }

    /** What it reads: captured again on the next frame where it differs. */
    public CgTextComponent text(String text) {
        draw.text(Objects.requireNonNull(text, "text"));
        return this;
    }

    /** Its text draw: font, colour, style, wrap, every field, edited in place. Never submit it. */
    public CgTextDraw draw() {
        return draw;
    }

    /** Its draw in {@code font}. */
    public CgTextComponent font(CgFont font) {
        draw.font(font);
        return this;
    }

    /** Its draw in {@code family}, falling back across its faces; size it with {@code draw().targetPx}. */
    public CgTextComponent family(CgFontFamily family) {
        draw.family(family);
        return this;
    }

    /** How tall a line of it stands, in blocks. 0.25 unless set. */
    public CgTextComponent height(float blocks) {
        this.height = blocks;
        return this;
    }

    public float height() {
        return height;
    }

    /** Which point of the text its position names: 0 its left or bottom, 1 its right or top. The middle unless set. */
    public CgTextComponent anchor(float x, float y) {
        this.anchorX = x;
        this.anchorY = y;
        return this;
    }

    public float anchorX() {
        return anchorX;
    }

    public float anchorY() {
        return anchorY;
    }

    /** Turned by its rotation in the world, the text along +x and up +y, instead of facing the camera. */
    public CgTextComponent turned(boolean turned) {
        this.turned = turned;
        return this;
    }

    public boolean turned() {
        return turned;
    }

    @Override
    void onSpawn() {
        slot = scene.holdText(this);
    }

    @Override
    void onRemove() {
        scene.dropText(slot);
        slot = -1;
    }

    @Override
    void onMoved() {
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
        return "CgTextComponent(" + draw + ")";
    }
}
