package com.crystalgraphics.render.scene.renderer;

import javax.annotation.Nullable;

import org.joml.Quaternionfc;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontFamily;
import com.crystalgraphics.text.render.CgTextDraw;

/**
 * Text in the world for one frame: where it stands, how tall, which point of it stands there and, for a sign, its turn.
 * {@code scene.textOnce(...)} hands one out; {@link #font}, {@link #family} or {@link #draw()} gives its text draw, and
 * submitting that draw queues it.
 *
 * <pre>{@code
 * scene.textOnce("12").at(x, y, z).anchor(0.5f, 0f).font(font).color(0xFFFF5040).style(DAMAGE).submit();
 * scene.textOnce(sign).at(x, y, z).rotation(facingSouth).family(family).targetPx(48).constraints(400f, 0f).submit();
 * }</pre>
 *
 * <ul>
 *   <li>It lives one frame: hand one out and submit it each frame it shows. Text that stays is a
 *       {@code CgTextComponent} ({@code scene.text(...)}).</li>
 *   <li>Pooled: never keep one past its frame.</li>
 *   <li>The {@code i}th text queued is the same text frame to frame: queued in another order it is captured again.</li>
 * </ul>
 */
public final class CgFrameText {

    private final CgTextDraw draw;
    double x, y, z;
    float height = 0.25f, anchorX = 0.5f, anchorY = 0.5f;
    @Nullable
    Quaternionfc rotation;

    CgFrameText(CgTextDraw draw) {
        this.draw = draw;
    }

    CgFrameText reset() {
        draw.reset();
        x = y = z = 0.0;
        height = 0.25f;
        anchorX = anchorY = 0.5f;
        rotation = null;
        return this;
    }

    /** Where its anchor stands, absolute. The origin unless set. */
    public CgFrameText at(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
        return this;
    }

    /** How tall a line of it stands, in blocks: its draw's {@code basePx()} maps to this. 0.25 unless set. */
    public CgFrameText height(float blocks) {
        this.height = blocks;
        return this;
    }

    /** Which point of the text its position names: 0 its left or bottom, 1 its right or top. The middle unless set. */
    public CgFrameText anchor(float x, float y) {
        this.anchorX = x;
        this.anchorY = y;
        return this;
    }

    /** Turned by {@code rotation} in the world instead of facing the camera; the text reads along +x, up +y. */
    public CgFrameText rotation(Quaternionfc rotation) {
        this.rotation = rotation;
        return this;
    }

    /** Its draw, in {@code font}: every field a {@link CgTextDraw} has. Its {@code submit()} queues it. */
    public CgTextDraw font(CgFont font) {
        return draw.font(font);
    }

    /** Its draw, in {@code family}, falling back across its faces; size it with {@code targetPx}. */
    public CgTextDraw family(CgFontFamily family) {
        return draw.family(family);
    }

    /** Its draw as it stands: for a layout, whose fonts are its own. */
    public CgTextDraw draw() {
        return draw;
    }
}
