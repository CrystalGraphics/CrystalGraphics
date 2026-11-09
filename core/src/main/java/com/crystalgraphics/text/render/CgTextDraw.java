package com.crystalgraphics.text.render;

import com.crystalgraphics.api.PoseStack;
import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontFamily;
import com.crystalgraphics.api.text.CgShapedParagraph;
import com.crystalgraphics.api.text.CgStrokeAlign;
import com.crystalgraphics.api.text.CgStrokeJoin;
import com.crystalgraphics.api.text.CgTextLayout;
import com.crystalgraphics.api.text.CgTextStroke;
import com.crystalgraphics.render.canvas.CgClipTable;
import com.crystalgraphics.render.property.CgPalette;
import org.joml.Matrix4f;
import org.joml.Vector2f;

import javax.annotation.Nullable;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * One text item: what to draw, where and how. From a canvas it draws there, in order with the canvas's other items:
 *
 * <pre>{@code
 * canvas.text().text("Hello").font(font).at(20f, 40f).color(0xFFFFFFFF).submit();
 * canvas.text().layout(prebuilt).family(family).pose(poseStack).stroke(0.08f, 0xFF000000).submit();
 * canvas.text().text(title).family(family).targetPx(24)
 *         .shadowCount(1).shadow(0, 1f, 1f, 2f, 0f, 0x80000000, false).submit();
 *
 * CgTextDraw fps = canvas.retainedText().font(hudFont).at(8f, 8f);   // held across frames, on that canvas
 * fps.text(currentFps).submit();                                     // each frame
 * float h = fps.measure().totalHeight();                             // without drawing
 * }</pre>
 *
 * <ul>
 *   <li>{@link #submit()} needs text ({@link #text}, {@link #paragraph} or {@link #layout}, the last winning) and a
 *       font ({@link #family} over {@link #font}), which a non-empty layout supplies itself.</li>
 *   <li>Defaults: at (0, 0), opaque white, identity pose, no clip, the pass's own node, {@link CgTextGamma#initial()}.</li>
 *   <li>Placed in the canvas's space and projected by its camera: GUI pixels on a GUI stage, the world on a world
 *       stage. Under a perspective camera it is world text: always distance-field, its tier from its size on screen.</li>
 *   <li>A {@link #layout} is drawn as built; a {@link #paragraph} or {@link #text} wraps at {@link #constraints} in
 *       screen pixels, re-wrapped as the pose's scale changes (never for world text).</li>
 *   <li>{@code canvas.text()} is the canvas's one scratch, reset by the next call: never hold it.</li>
 * </ul>
 */
public final class CgTextDraw {

    /** What {@code text.shader} reads for a {@link CgStrokeAlign}: on the contour, outside it, inside it. */
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
    float strokeWidthEm;
    int strokeArgb;
    float strokeAlign = ALIGN_OUTSET;
    float strokeOver;
    boolean strokeRound = true;
    boolean shadowsStroked;
    boolean contained = true;
    int clip;
    int spatialNode, effectNode;
    CgTextGamma gamma = CgTextGamma.initial();
    final CgTextShadowList shadows = new CgTextShadowList();

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
        strokeWidthEm = 0f;
        strokeArgb = 0;
        strokeAlign = ALIGN_OUTSET;
        strokeOver = 0f;
        strokeRound = true;
        shadowsStroked = false;
        contained = true;
        clip = 0;
        spatialNode = effectNode = 0;
        gamma = CgTextGamma.initial();
        shadows.clear();
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
                && rgba == other.rgba && strokeWidthEm == other.strokeWidthEm && strokeArgb == other.strokeArgb
                && strokeAlign == other.strokeAlign && strokeOver == other.strokeOver
                && strokeRound == other.strokeRound && shadowsStroked == other.shadowsStroked
                && contained == other.contained && clip == other.clip && spatialNode == other.spatialNode
                && effectNode == other.effectNode && gamma.equals(other.gamma) && shadows.sameAs(other.shadows);
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
        strokeWidthEm = other.strokeWidthEm;
        strokeArgb = other.strokeArgb;
        strokeAlign = other.strokeAlign;
        strokeOver = other.strokeOver;
        strokeRound = other.strokeRound;
        shadowsStroked = other.shadowsStroked;
        contained = other.contained;
        clip = other.clip;
        spatialNode = other.spatialNode;
        effectNode = other.effectNode;
        gamma = other.gamma;
        shadows.set(other.shadows);
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

    /** The text's colour, {@code 0xAARRGGBB}; a styled span keeps its own. */
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
     * An outline {@code widthEm} of the em wide, outside the contour; 0 or a transparent colour draws none.
     *
     * <pre>{@code
     * .stroke(0.06f, 0xFF101418)
     * .stroke(0.06f, argb).strokeAlign(CgStrokeAlign.CENTER)
     * .stroke(0.06f, argb).strokeOverFill(true)
     * }</pre>
     *
     * <ul>
     *   <li>In em, since the raster size follows the pose. Clamped at {@link CgTextStroke#MAX_FIELD_WIDTH_EM}.</li>
     *   <li>Moves the draw to the distance-field tier, down to {@code CgMsdfAtlasConfig.minAntialiasablePx()};
     *       smaller, the stroke is dropped.</li>
     * </ul>
     */
    public CgTextDraw stroke(float widthEm, int argb) {
        this.strokeWidthEm = widthEm;
        this.strokeArgb = argb;
        return this;
    }

    /** The four stroke values of a {@link CgTextStroke}; null for none. */
    public CgTextDraw stroke(@Nullable CgTextStroke stroke) {
        CgTextStroke s = stroke == null ? CgTextStroke.NONE : stroke;
        return stroke(s.widthEm(), s.argb()).strokeAlign(s.align()).strokeOverFill(s.strokeOverFill());
    }

    /** The outline's width alone, in em. */
    public CgTextDraw strokeWidth(float widthEm) {
        this.strokeWidthEm = widthEm;
        return this;
    }

    /** The outline's colour alone; alpha 0 draws nothing. */
    public CgTextDraw strokeColor(int argb) {
        this.strokeArgb = argb;
        return this;
    }

    /** Where the outline sits on the contour. {@link CgStrokeAlign#OUTSET} by default. */
    public CgTextDraw strokeAlign(CgStrokeAlign align) {
        this.strokeAlign = align == CgStrokeAlign.CENTER ? ALIGN_CENTER
                : align == CgStrokeAlign.INSET ? ALIGN_INSET : ALIGN_OUTSET;
        return this;
    }

    /** {@code true} paints the outline over the fill; {@code false}, the default, behind it. */
    public CgTextDraw strokeOverFill(boolean strokeOverFill) {
        this.strokeOver = strokeOverFill ? 1f : 0f;
        return this;
    }

    /** How the outline turns corners. {@link CgStrokeJoin#ROUND} by default, as Unreal's. */
    public CgTextDraw strokeJoin(CgStrokeJoin join) {
        this.strokeRound = join != CgStrokeJoin.MITRE;
        return this;
    }

    /** Whether shadows take the outline's shape: false, Unreal's default; true as CSS {@code text-shadow}. */
    public CgTextDraw shadowsStroked(boolean stroked) {
        this.shadowsStroked = stroked;
        return this;
    }

    /**
     * Whether the outline and the shadows' offsets stay inside the text's box, as Slate's runs keep them:
     * {@code true}, the default, moves the text in by their reach and {@link #measureSize} counts it; {@code false}
     * draws at {@link #at} and lets both overflow, as CSS does.
     */
    public CgTextDraw contained(boolean contained) {
        this.contained = contained;
        return this;
    }

    /**
     * How many shadows it casts, each set with {@link #shadow}; 0 by default. Growing it keeps those set.
     *
     * <pre>{@code
     * draw.shadowCount(2)
     *     .shadow(0, 0f, 0f, 4f, 0f, 0xFF44CCFF, false)   // a glow, on top
     *     .shadow(1, 1f, 1f, 0f, 0f, 0x80000000, false);  // a drop shadow beneath it
     * }</pre>
     *
     * <p>As Chrome paints {@code text-shadow}: per glyph, blurred in local space, the first on top and all beneath
     * this draw's text.</p>
     */
    public CgTextDraw shadowCount(int count) {
        shadows.count(count);
        return this;
    }

    /** How many shadows it casts. */
    public int shadowCount() {
        return shadows.count();
    }

    /**
     * Scopes glyphs for {@link #shadowScope}: {@code scopeByGlyph[i]} is the scope of the layout's glyph {@code i}.
     * Read at submit, not copied. A scoped shadow casts nothing for decorations.
     *
     * <pre>{@code
     * draw.shadowScopes(scopes).shadowCount(2)
     *     .shadow(0, 1f, 1f, 1f, 0f, black, false)                     // every glyph
     *     .shadow(1, 0f, 0f, 3f, 0f, gold, false).shadowScope(1, 0);    // scope 0's alone
     * }</pre>
     */
    public CgTextDraw shadowScopes(int[] scopeByGlyph) {
        shadows.glyphScopes(scopeByGlyph);
        return this;
    }

    /** Restricts shadow {@code index} to the glyphs whose scope is {@code scope}; -1 is every glyph. */
    public CgTextDraw shadowScope(int index, int scope) {
        shadows.scope(index, scope);
        return this;
    }

    /**
     * Shadow {@code index}: an offset in local pixels (right and down), {@code sigma} the blur's standard deviation
     * (half a CSS blur radius), {@code spread} grown before blurring, {@code argb} its whole colour and opacity,
     * {@code inset} inside the glyph. Negative sigma or spread reads as 0; transparent casts nothing.
     */
    public CgTextDraw shadow(int index, float offsetX, float offsetY, float sigma, float spread, int argb, boolean inset) {
        shadows.set(index, offsetX, offsetY, sigma, spread, argb, inset);
        return this;
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
        if (layout != null && layout.baked().fontKeys().length > 0) return layout.baked().fontKeys()[0].getTargetPx();
        return 0;
    }

    void validate(String call) {
        if (layout == null && paragraph == null && text == null) {
            throw new IllegalStateException("a text draw needs text(...), paragraph(...) or layout(...) before " + call + "()");
        }
        if (layout == null && family == null && font == null) {
            throw new IllegalStateException("a text draw needs font(...) or family(...) before " + call + "()");
        }
    }

    /** The pose matrix it is placed by now, or null for identity. */
    @Nullable
    Matrix4f poseNow() {
        if (poseMatrix != null) return poseMatrix;
        return poseStack != null ? poseStack.last().pose() : null;
    }

    boolean strokeRequested() {
        return strokeWidthEm > 0f && (strokeArgb >>> 24) != 0;
    }

    /** Where the glyphs' origin lands: {@link #at}, moved in under {@link #contained}. */
    float originX() {
        return contained ? x + outlineReach() + shadowReach(true, true) : x;
    }

    float originY() {
        return contained ? y + outlineReach() + shadowReach(false, true) : y;
    }

    private float outlineReach() {
        if (!strokeRequested()) return 0f;
        float share = strokeAlign == ALIGN_INSET ? 0f : strokeAlign == ALIGN_CENTER ? 0.5f : 1f;
        return strokeWidthEm * share * basePx();
    }

    /** The outer shadows' furthest offset along one axis, before the text ({@code before}) or after it. */
    private float shadowReach(boolean horizontal, boolean before) {
        float reach = 0f;
        for (int i = 0; i < shadows.count(); i++) {
            if (shadows.inset(i)) continue;
            float offset = horizontal ? shadows.x(i) : shadows.y(i);
            reach = Math.max(reach, before ? -offset : offset);
        }
        return reach;
    }
}
