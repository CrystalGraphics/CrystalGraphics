package com.crystalgraphics.text.render;

import com.crystalgraphics.api.PoseStack;
import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontFamily;
import com.crystalgraphics.api.text.CgShapedParagraph;
import com.crystalgraphics.api.text.CgTextLayout;
import com.crystalgraphics.render.canvas.CgClipTable;
import com.crystalgraphics.render.property.CgPalette;
import com.crystalgraphics.text.style.CgTextLayer;
import com.crystalgraphics.text.style.CgTextStyle;
import org.joml.Matrix4f;
import org.joml.Vector2f;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * One text item: what to draw, where, and in which {@link CgTextStyle}. From a canvas it draws there, in order with
 * the canvas's other items:
 *
 * <pre>{@code
 * canvas.text().text("Hello").font(font).at(20f, 40f).submit();                  // white: the plain style
 * canvas.text().style(TITLE).text("Level up!").at(x, y).submit();                // the style's font and layers
 * canvas.text().style(TITLE).text(name).family(family).targetPx(24).color(0x80FFFFFF).submit();  // on your font, faded
 *
 * CgTextDraw fps = canvas.retainedText().style(HUD).at(8f, 8f);   // held across frames, on that canvas
 * fps.text(currentFps).submit();                                  // each frame
 * float h = fps.measure().totalHeight();                          // without drawing
 * }</pre>
 *
 * <ul>
 *   <li>{@link #submit()} needs text ({@link #text}, {@link #paragraph} or {@link #layout}, the last winning) and a
 *       font: {@link #family} over {@link #font} over the style's, which a non-empty layout supplies itself.</li>
 *   <li>{@link #color} tints every layer of the style, as a widget's colour does Slate's; a span's colour tints its
 *       glyphs' fills.</li>
 *   <li>A style's lengths are at its own size and scale with the text: drawn at twice it, an outline is twice as wide.</li>
 *   <li>Defaults: the plain style (a white fill), at (0, 0), no tint, identity pose, no clip, the pass's own node,
 *       {@link CgTextGamma#initial()}.</li>
 *   <li>Placed in the canvas's space and projected by its camera: the window's pixels on a GUI stage, the world on a
 *       world stage. Under a perspective camera it is world text: always distance-field, its tier from its size on screen.</li>
 *   <li>A {@link #layout} is drawn as built; a {@link #paragraph} or {@link #text} wraps at {@link #constraints} in
 *       screen pixels, re-wrapped as the pose's scale changes (never for world text).</li>
 *   <li>{@code canvas.text()} is the canvas's one scratch, reset by the next call: never hold it.</li>
 * </ul>
 */
public final class CgTextDraw {

    /** What {@code text.shader} reads for an outline's {@link CgTextLayer.Align}: on the edge, outside it, inside it. */
    static final float ALIGN_CENTER = 0f, ALIGN_OUTSET = 1f, ALIGN_INSET = 2f;

    @Nullable
    final CgTextBatch owner;
    @Nullable
    private final Consumer<CgTextDraw> queue;

    CgTextLayout layout;
    CgShapedParagraph paragraph;
    String text;
    CgFont font;
    CgFontFamily family;
    float maxWidth, maxHeight;
    int targetPx = -1;
    float x, y;
    int rgba = 0xFFFFFFFF;
    @Nullable
    PoseStack poseStack;
    @Nullable
    Matrix4f poseMatrix;
    CgTextStyle style = CgTextStyle.PLAIN;
    /** Per glyph of the layout, its scope; null when no layer is scoped. Held, not copied. */
    @Nullable
    int[] glyphScopes;
    /** Per layer of the style, the glyph scope it applies to, or -1 for every glyph; grow-only. */
    int[] layerScopes = new int[0];
    int scopedLayers;
    boolean contained = true;
    int clip;
    int spatialNode, effectNode;
    CgTextGamma gamma = CgTextGamma.initial();
    @Nullable
    private String transformedFrom, transformed;

    CgTextDraw(@Nullable CgTextBatch owner, @Nullable Consumer<CgTextDraw> queue) {
        this.owner = owner;
        this.queue = queue;
    }

    /**
     * A draw whose {@link #submit()} hands it to {@code queue}, for an owner that draws it later under a camera it
     * knows then: {@code CgWorldRenderer}'s labels.
     */
    public static CgTextDraw queued(Consumer<CgTextDraw> queue) {
        return new CgTextDraw(null, Objects.requireNonNull(queue, "queue"));
    }

    /** A standalone draw holding fields only: a copy to compare with {@link #sameAs}. */
    public static CgTextDraw detached() {
        return new CgTextDraw(null, null);
    }

    /** Every field back to its default. */
    public CgTextDraw reset() {
        layout = null;
        paragraph = null;
        text = null;
        font = null;
        family = null;
        maxWidth = maxHeight = 0f;
        targetPx = -1;
        x = y = 0f;
        rgba = 0xFFFFFFFF;
        poseStack = null;
        poseMatrix = null;
        style = CgTextStyle.PLAIN;
        glyphScopes = null;
        scopedLayers = 0;
        contained = true;
        clip = 0;
        spatialNode = effectNode = 0;
        gamma = CgTextGamma.initial();
        return this;
    }

    /**
     * Whether {@code other} draws the same text the same way: every field but the pose. Text by content; layout,
     * paragraph, font and family by identity. A field added here joins this, {@link #set} and {@link #reset}.
     */
    public boolean sameAs(CgTextDraw other) {
        return layout == other.layout && paragraph == other.paragraph && Objects.equals(text, other.text)
                && font == other.font && family == other.family && maxWidth == other.maxWidth
                && maxHeight == other.maxHeight && targetPx == other.targetPx && x == other.x && y == other.y
                && rgba == other.rgba && style == other.style && contained == other.contained && clip == other.clip
                && spatialNode == other.spatialNode && effectNode == other.effectNode && gamma.equals(other.gamma)
                && sameScopes(other);
    }

    /** Takes every field of {@code other} but its pose. */
    public CgTextDraw set(CgTextDraw other) {
        layout = other.layout;
        paragraph = other.paragraph;
        text = other.text;
        font = other.font;
        family = other.family;
        maxWidth = other.maxWidth;
        maxHeight = other.maxHeight;
        targetPx = other.targetPx;
        x = other.x;
        y = other.y;
        rgba = other.rgba;
        style = other.style;
        glyphScopes = other.glyphScopes == null ? null : other.glyphScopes.clone();
        scopedLayers = other.scopedLayers;
        if (layerScopes.length < other.scopedLayers) layerScopes = new int[other.layerScopes.length];
        System.arraycopy(other.layerScopes, 0, layerScopes, 0, other.scopedLayers);
        contained = other.contained;
        clip = other.clip;
        spatialNode = other.spatialNode;
        effectNode = other.effectNode;
        gamma = other.gamma;
        return this;
    }

    /** A laid-out layout, drawn as built. Over {@link #paragraph} and {@link #text}. */
    public CgTextDraw layout(CgTextLayout layout) {
        this.layout = layout;
        return this;
    }

    /** A shaped paragraph, wrapped at {@link #constraints} on each submit. Over {@link #text}, under {@link #layout}. */
    public CgTextDraw paragraph(CgShapedParagraph paragraph) {
        this.paragraph = paragraph;
        return this;
    }

    /** Text laid out at submit, through the layout cache. */
    public CgTextDraw text(String text) {
        this.text = text;
        return this;
    }

    /** One font. Under {@link #family}. */
    public CgTextDraw font(CgFont font) {
        this.font = font;
        return this;
    }

    /** A font family, with its fallbacks. Over {@link #font}. */
    public CgTextDraw family(CgFontFamily family) {
        this.family = family;
        return this;
    }

    /** The size in pixels the font or family is resized to; unset, it must already be sized. */
    public CgTextDraw targetPx(int targetPx) {
        this.targetPx = targetPx;
        return this;
    }

    /** Where {@link #text} and {@link #paragraph} wrap and stop; 0 or less is unbounded. */
    public CgTextDraw constraints(float maxWidth, float maxHeight) {
        this.maxWidth = maxWidth;
        this.maxHeight = maxHeight;
        return this;
    }

    /** The layout's origin, in the pose's space. */
    public CgTextDraw at(float x, float y) {
        this.x = x;
        this.y = y;
        return this;
    }

    /** A tint over every layer of its style, {@code 0xAARRGGBB}: opaque white, the default, leaves them as they are. */
    public CgTextDraw color(int argb) {
        this.rgba = argb;
        return this;
    }

    /** The pose its quads are placed by: the stack's top, read at submit. */
    public CgTextDraw pose(@Nullable PoseStack pose) {
        this.poseStack = pose;
        this.poseMatrix = null;
        return this;
    }

    /** The pose its quads are placed by, read at submit. */
    public CgTextDraw pose(@Nullable Matrix4f pose) {
        this.poseMatrix = pose;
        this.poseStack = null;
        return this;
    }

    /**
     * The style it is drawn in: its font where the draw names none, its layers, spacing and skew. The plain style (one
     * white fill) by default.
     *
     * <pre>{@code
     * canvas.text().style(TITLE).text("Level up!").at(x, y).submit();
     * }</pre>
     */
    public CgTextDraw style(CgTextStyle style) {
        this.style = Objects.requireNonNull(style, "style");
        return this;
    }

    /**
     * Whether its style's outlines and shadow offsets stay inside the text's box, as Slate's runs keep them:
     * {@code true}, the default, moves the text in by their reach and {@link #measureSize} counts it; {@code false}
     * draws at {@link #at} and lets both overflow, as CSS does.
     */
    public CgTextDraw contained(boolean contained) {
        this.contained = contained;
        return this;
    }

    /**
     * Scopes glyphs for {@link #layerScope}: {@code scopeByGlyph[i]} is the scope of the layout's glyph {@code i}.
     * Read at submit, not copied. A scoped layer draws nothing for decorations.
     *
     * <pre>{@code
     * draw.style(withHighlight).scopes(scopes).layerScope(3, 0);   // the style's layer 3 on scope 0's glyphs alone
     * }</pre>
     */
    public CgTextDraw scopes(int[] scopeByGlyph) {
        this.glyphScopes = scopeByGlyph;
        return this;
    }

    /** Restricts the style's layer {@code layer} (its index, bottom first) to the glyphs whose scope is {@code scope}. */
    public CgTextDraw layerScope(int layer, int scope) {
        if (layer >= layerScopes.length) layerScopes = Arrays.copyOf(layerScopes, Math.max(layer + 1, layerScopes.length * 2));
        for (int i = scopedLayers; i < layer; i++) layerScopes[i] = -1;
        scopedLayers = Math.max(scopedLayers, layer + 1);
        layerScopes[layer] = scope;
        return this;
    }

    /** The glyph scope layer {@code layer} applies to, or -1 for every glyph. */
    int scopeOf(int layer) {
        return layer < scopedLayers ? layerScopes[layer] : -1;
    }

    /** Glyph {@code glyph}'s scope, or {@link Integer#MIN_VALUE} where it has none. */
    int glyphScope(int glyph) {
        return glyphScopes != null && glyph < glyphScopes.length ? glyphScopes[glyph] : Integer.MIN_VALUE;
    }

    private boolean sameScopes(CgTextDraw other) {
        if (!Arrays.equals(glyphScopes, other.glyphScopes) || scopedLayers != other.scopedLayers) return false;
        for (int i = 0; i < scopedLayers; i++) if (layerScopes[i] != other.layerScopes[i]) return false;
        return true;
    }

    /** The {@link CgClipTable} entry it is clipped by; 0 is none. */
    public CgTextDraw clip(int entry) {
        this.clip = entry;
        return this;
    }

    /** Its spatial and effect nodes; its pose is then in the spatial node's space. @see CgPalette */
    public CgTextDraw node(int spatial, int effect) {
        this.spatialNode = spatial;
        this.effectNode = effect;
        return this;
    }

    /** The coverage correction its fill is drawn with. {@link CgTextGamma#initial()} by default. */
    public CgTextDraw gamma(CgTextGamma gamma) {
        this.gamma = Objects.requireNonNull(gamma, "gamma");
        return this;
    }

    /**
     * Draws it on its canvas, or hands a {@link #queued} draw to its queue.
     *
     * @return whether everything drew as asked; false while a glyph or shadow is still generating: paint again
     * @throws IllegalStateException without text, without a font where the layout gives none, or for a
     *                               {@link #detached} draw
     */
    public boolean submit() {
        validate("submit");
        if (queue != null) {
            queue.accept(this);
            return true;
        }
        if (owner == null) throw new IllegalStateException("a detached text draw has no canvas: canvas.text(draw)");
        return owner.draw(this);
    }

    /**
     * The layout {@link #submit()} would draw now, wrapped for the current pose, without drawing: to place what
     * follows it. Cheap per frame; the wrap is memoised.
     *
     * @throws IllegalStateException as {@link #submit()}
     */
    public CgTextLayout measure() {
        validate("measure");
        if (owner == null) throw new IllegalStateException("a detached text draw has no canvas to measure under");
        return owner.measure(this);
    }

    /**
     * The box the text takes in the layout's pixels: {@link #measure()}'s size, and under {@link #contained} the
     * outline's reach on every side and the shadows' offsets.
     *
     * <pre>{@code
     * nextY += draw.measureSize(size).y;
     * }</pre>
     */
    public Vector2f measureSize(Vector2f out) {
        CgTextLayout measured = measure();
        out.set(measured.totalWidth(), measured.totalHeight());
        if (!contained) return out;
        float outline = outlineReach();
        return out.add(2f * outline + shadowReach(true, false) + shadowReach(true, true),
                2f * outline + shadowReach(false, false) + shadowReach(false, true));
    }

    /**
     * The size its text is laid out at: {@code targetPx} where set, else its family's or font's, else its layout's
     * first glyph's; 0 with none.
     */
    public int basePx() {
        if (targetPx > 0 && (family != null || font != null)) return targetPx;
        if (family != null) return family.getPrimarySource().getKey().getTargetPx();
        if (font != null) return font.getTargetPx();
        if (layout == null && style.font() != null) return stylePx();
        if (layout != null && layout.baked().fontKeys().length > 0) return layout.baked().fontKeys()[0].getTargetPx();
        return 0;
    }

    void validate(String call) {
        if (layout == null && paragraph == null && text == null) {
            throw new IllegalStateException("a text draw needs text(...), paragraph(...) or layout(...) before " + call + "()");
        }
        if (layout == null && family == null && font == null && style.font() == null) {
            throw new IllegalStateException("a text draw needs font(...), family(...) or a style with a font before "
                    + call + "()");
        }
    }

    /** The pose matrix it is placed by now, or null for identity. */
    @Nullable
    Matrix4f poseNow() {
        if (poseMatrix != null) return poseMatrix;
        return poseStack != null ? poseStack.last().pose() : null;
    }

    /** The style's size in whole pixels: the size its own font is drawn at. */
    int stylePx() {
        return Math.max(1, Math.round(style.px()));
    }

    /** The style's font at its own size, where the draw names no font of its own; null otherwise. */
    @Nullable
    CgFont styleFont() {
        return font == null && family == null && layout == null && style.font() != null ? style.font(stylePx()) : null;
    }

    /** Its text in its style's case: allocates only when the text changes. */
    String transformedText() {
        if (style.transform() == CgTextStyle.Transform.NONE || text == null) return text;
        if (!text.equals(transformedFrom)) {
            transformedFrom = text;
            transformed = style.transform(text);
        }
        return transformed;
    }

    /** Local pixels a Slate unit of its style is: its text's size over the style's own. */
    float unit() {
        return basePx() / Math.max(1e-6f, style.px());
    }

    /** Where the glyphs' origin lands: {@link #at}, moved in under {@link #contained}. */
    float originX() {
        return contained ? x + outlineReach() + shadowReach(true, true) : x;
    }

    float originY() {
        return contained ? y + outlineReach() + shadowReach(false, true) : y;
    }

    /** How far its outermost outline reaches past the glyphs, local pixels. */
    float outlineReach() {
        float reach = 0f;
        for (CgTextLayer l : style.layers()) {
            if (l.kind() != CgTextLayer.Kind.OUTLINE) continue;
            float share = l.align() == CgTextLayer.Align.INSIDE ? 0f : l.align() == CgTextLayer.Align.CENTRE ? 0.5f : 1f;
            reach = Math.max(reach, l.width() * share);
        }
        return reach * unit();
    }

    /** The outer shadows' furthest offset along one axis, before the text ({@code before}) or after it. */
    private float shadowReach(boolean horizontal, boolean before) {
        float reach = 0f;
        for (CgTextLayer l : style.layers()) {
            if (l.kind() != CgTextLayer.Kind.SHADOW || l.inset()) continue;
            float offset = horizontal ? l.offsetX() : l.offsetY();
            reach = Math.max(reach, before ? -offset : offset);
        }
        return reach * unit();
    }

    /** How far the style paints past the layout, local pixels: the cull's slack. */
    float reach() {
        float reach = outlineReach();
        for (CgTextLayer l : style.layers()) {
            if (l.kind() != CgTextLayer.Kind.SHADOW) continue;
            float offset = Math.max(Math.abs(l.offsetX()), Math.abs(l.offsetY()));
            reach = Math.max(reach, (offset + (float) Math.ceil(3f * l.blur()) + l.spread()) * unit());
        }
        return reach;
    }
}
