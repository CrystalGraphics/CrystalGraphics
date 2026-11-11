package com.crystalgraphics.widget;

import javax.annotation.Nullable;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector4fc;

import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.text.style.CgTextStyle;

/**
 * Where a {@link CgUserWidget}'s leaves go: the measuring and drawing a tree needs, and nothing else.
 * {@link CgCanvasWidgetPainter} draws onto a canvas; a test answers with sizes of its own and records the draws.
 *
 * <pre>{@code
 * painter.begin(frame.canvas(), frame.recording(), texelsPerUnit);   // each frame
 * for (CgUserWidget w : live) w.retain(painter);                      // every retainer box first
 * for (CgUserWidget w : live) w.paint(painter, placement(w));
 * }</pre>
 *
 * <ul>
 *   <li>Each pose maps the widget's own box, (0, 0) to (width, height) in widget units, to where it draws.</li>
 *   <li>Colours are linear, everything above the widget already multiplied in; the painter encodes them for what it
 *       draws with, as Slate packs a vertex colour.</li>
 *   <li>{@link #layout} runs only when a text block's text, style or wrap changes; the rest each frame.</li>
 *   <li>Between {@link #beginRetained} and {@link #endRetained}, draws go into that region of the retained image, its
 *       box at the pose's origin.</li>
 * </ul>
 */
public interface CgWidgetPainter {

    /**
     * Lays {@code text} out in {@code style}, wrapped at {@code wrap} units (0 for none), its lines justified; its
     * size in widget units into {@code out}. What it returns comes back to {@link #text} until the next layout.
     */
    @Nullable
    Object layout(CgTextStyle style, String text, float wrap, CgWidget.Justification justification, Vector2f out);

    /**
     * {@code brush} stretched over (0, 0) to ({@code width}, {@code height}) under {@code pose}, tinted; a UI
     * material's {@code parameters} reach its shader as {@code CG_QUAD_CUSTOM0}.
     */
    void image(CgWidgetBrush brush, Matrix4f pose, float width, float height, float r, float g, float b, float a,
               Vector4fc parameters);

    /**
     * The text {@link #layout} laid out as {@code laidOut}, justified across a box {@code width} units wide at (0, 0)
     * under {@code pose}, tinted. {@code layoutPose} is the same placement with no render transform in it (what a
     * sequence animates): the glyphs are made at the size it gives, and {@code pose} only stretches them, as Slate's
     * render transform does.
     */
    void text(CgTextStyle style, String text, @Nullable Object laidOut, CgWidget.Justification justification,
              Matrix4f pose, Matrix4f layoutPose, float width, float r, float g, float b, float a);

    /**
     * Opens a region of the retained image {@code width} x {@code height} units for a retainer box's content; its
     * handle. {@code reach} is how far its content draws past that box (its text's outlines, shadows and slant,
     * {@code CgTextStyle.reach}); {@code blurX} and {@code blurY}, Gaussian sigmas in units across and down, blur it
     * where {@link #retained} draws it, as CSS's {@code filter: blur()}, 0 for none on that axis. The region is padded
     * by both, three sigma of the blur, so nothing is cut.
     */
    int beginRetained(float width, float height, float reach, float blurX, float blurY);

    /** Closes the region {@link #beginRetained} opened: draws go where they went before it. */
    void endRetained();

    /**
     * The region {@code region} drawn as one quad over (0, 0) to ({@code width}, {@code height}) under {@code pose},
     * premultiplied and tinted; or through {@code effect}, its {@code textureParameter} sampler taking the region;
     * blurred where its {@link #beginRetained} asked.
     */
    void retained(int region, @Nullable CgMaterialDefinition effect, String textureParameter, Matrix4f pose, float width,
                  float height, float r, float g, float b, float a);
}
