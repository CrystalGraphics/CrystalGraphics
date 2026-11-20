package com.crystalgraphics.widget;

import static com.crystalgraphics.widget.CgUserWidget.ANGLE;
import static com.crystalgraphics.widget.CgUserWidget.BLUR;
import static com.crystalgraphics.widget.CgUserWidget.COLOUR;
import static com.crystalgraphics.widget.CgUserWidget.CONTENT_COLOUR;
import static com.crystalgraphics.widget.CgUserWidget.MATERIAL_PARAMETERS;
import static com.crystalgraphics.widget.CgUserWidget.PARAMETERS;
import static com.crystalgraphics.widget.CgUserWidget.RENDER_OPACITY;
import static com.crystalgraphics.widget.CgUserWidget.SCALE;
import static com.crystalgraphics.widget.CgUserWidget.SHEAR;
import static com.crystalgraphics.widget.CgUserWidget.SLOTS;
import static com.crystalgraphics.widget.CgUserWidget.TRANSLATION;

import com.crystalgraphics.api.canvas.CgCanvas;
import com.crystalgraphics.api.canvas.CgCanvasLayer;
import com.crystalgraphics.api.canvas.CgPaintChunk;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.api.material.CgMaterialInstance;
import com.crystalgraphics.api.paint.CgColor;
import com.crystalgraphics.api.paint.brush.CgBrush;
import com.crystalgraphics.api.paint.effect.CgFilter;
import com.crystalgraphics.api.text.CgText;
import com.crystalgraphics.material.CgMaterialProperty;
import com.crystalgraphics.text.style.CgTextStyle;

/**
 * Painting one user widget onto a canvas, Slate's {@code OnPaint} for each widget it holds: images, text, retainers
 * and their filters, materials, render transforms. It records the paint as a chunk and replays it while nothing it
 * shows changed, posing the nodes a move or fade put widgets in. Reads its owner's boxes and properties.
 */
final class CgWidgetPainter {

    private final CgUserWidget owner;
    private final CgWidgetLayout layout;
    private final CgWidgetTree tree;
    private final int n;
    private final float[] state;
    private final CgWidget.Visibility[] visibility;
    private final CgTextStyle[] style;
    private final CgBrush[] brush;
    private final CgBrush[] drawn;
    private final CgUserWidget[] nested;
    /** Per widget: its brush's material instance, the widget's own (UMG's dynamic material instance). */
    private final CgMaterialInstance[] materials;
    private final CgMaterialDefinition[] materialOf;
    private final boolean[] takesParameters;
    /** Per retainer box: the filter it was last drawn through, and what made it. */
    private final CgFilter[] filters;
    private final float[] filterKey;
    /** Per widget, itself at n: a change has moved it (render transform) or faded it, so it draws in a node of its own. */
    private final boolean[] moves;
    private final boolean[] fades;
    /** Per border: its content colour's alpha has changed, so its content draws in an effect node. */
    private final boolean[] contentFades;
    /** Per widget, itself at n: its nodes as last recorded, as the chunk numbered them. */
    private final int[] nodeOf;
    private final int[] effectOf;
    private final int[] contentEffectOf;
    /** Per widget: it or something under it draws in a node, so a replay's pose walk enters it. */
    private final boolean[] posed;
    /** Its paint, kept to replay while its owner's revision and the scale baked under its root node hold. */
    private final CgPaintChunk kept = new CgPaintChunk();
    private long keptRevision = -1;
    /** Refused chunks in a row at {@link #refusedAt}, and the repaint before which it takes none again. */
    private int refusals;
    private long refusedAt = -1;
    private long retryAt;
    private float keptScaleX;
    private float keptScaleY;
    /** No retainer box anywhere in it: a layer opens a pass, which a chunk refuses. */
    final boolean chunkable;
    private long repaints;
    private final float[] affine = new float[6];

    CgWidgetPainter(CgUserWidget owner) {
        this.owner = owner;
        this.layout = owner.layout;
        this.tree = owner.tree();
        this.n = tree.size();
        this.state = owner.state;
        this.visibility = owner.visibility;
        this.style = owner.style;
        this.brush = owner.brush;
        this.drawn = owner.drawn;
        this.nested = owner.nested;
        materials = new CgMaterialInstance[n];
        materialOf = new CgMaterialDefinition[n];
        takesParameters = new boolean[n];
        filters = new CgFilter[n];
        filterKey = new float[n * 5];
        moves = new boolean[n + 1];
        fades = new boolean[n + 1];
        contentFades = new boolean[n];
        nodeOf = new int[n + 1];
        effectOf = new int[n + 1];
        contentEffectOf = new int[n];
        posed = new boolean[n];
        boolean plain = true;
        for (int i = 0; i < n; i++) {
            if (tree.widget(i).kind == CgWidget.Kind.RETAINER_BOX || nested[i] != null && !nested[i].painter.chunkable) plain = false;
        }
        chunkable = plain;
    }

    /** Replays its kept paint where nothing it shows changed, else paints it, recording a chunk where it can. */
    void paint(CgCanvas canvas) {
        owner.layoutIfDirty();
        long key = owner.deepRevision();
        canvas.transform(affine);
        // What stays baked under its root node: an axis-aligned scale (CgCanvas.movable)
        boolean aligned = affine[1] == 0f && affine[2] == 0f && affine[0] > 0f && affine[3] > 0f;
        float sx = aligned ? affine[0] : 1f, sy = aligned ? affine[3] : 1f;
        if (key == keptRevision && sx == keptScaleX && sy == keptScaleY && canvas.replay(kept)) {
            canvas.save();
            poseAt(canvas, kept);
            canvas.restore();
            return;
        }
        repaints++;
        if (key != refusedAt) refusals = 0;
        if (!chunkable || refusals > 0 && repaints < retryAt || canvas.chunking()) {
            paintAt(canvas, 1f, 1f, 1f, 1f);
            return;
        }
        canvas.beginChunk();
        paintAt(canvas, 1f, 1f, 1f, 1f);
        CgPaintChunk.Refusal why = canvas.endChunk(kept);
        keptRevision = why == CgPaintChunk.Refusal.NONE ? key : -1;
        keptScaleX = sx;
        keptScaleY = sy;
        // What a widget refuses is transient: glyphs generating, an SVG's cells rasterised at their first use. Tried
        // again at once, then less often, up to every 64th paint
        if (why == CgPaintChunk.Refusal.NONE) {
            refusals = 0;
        } else {
            refusedAt = key;
            retryAt = repaints + (1L << Math.min(refusals++, 6));
        }
    }

    /** How often it has been painted rather than replayed. */
    long repaints() {
        return repaints;
    }

    /** Widget {@code w} moved by a render transform: whether this is its first, which puts it in a node. */
    boolean move(int w) {
        if (moves[w]) return false;
        moves[w] = true;
        return true;
    }

    /** Widget {@code w}'s opacity changed: whether this is its first, which puts it in an effect node. */
    boolean fade(int w) {
        if (fades[w]) return false;
        fades[w] = true;
        return true;
    }

    /** Border {@code w}'s content opacity changed: whether this is its first. */
    boolean contentFade(int w) {
        if (contentFades[w]) return false;
        contentFades[w] = true;
        return true;
    }

    /** The filter retainer box {@code i} was last drawn through; null before. */
    CgFilter filter(int i) {
        return filters[i];
    }

    /** Paints it with its box at the canvas's origin, under a parent's tint (linear). */
    void paintAt(CgCanvas c, float r, float g, float b, float a) {
        owner.layoutIfDirty();
        int self = n * SLOTS * 4;
        c.save();
        // Its root is a node, so a replay places it wherever it is drawn
        renderAffine(self, owner.selfPivotX * layout.rootWidth(), owner.selfPivotY * layout.rootHeight());
        nodeOf[n] = c.movable(affine[0], affine[1], affine[2], affine[3], affine[4], affine[5]);
        // SObjectWidget is a compound widget: the user widget's colour and opacity tints its content.
        float own = state[self + RENDER_OPACITY * 4] * state[self + COLOUR * 4 + 3];
        if (fades[n]) effectOf[n] = c.fader(own);
        else a *= own;
        int k = self + COLOUR * 4;
        if (n > 0) paint(c, 0, r * state[k], g * state[k + 1], b * state[k + 2], a);
        c.restore();
    }

    /** Paints widget {@code i} and what it holds; whether any of it draws in a node. */
    private boolean paint(CgCanvas c, int i, float r, float g, float b, float a) {
        if (!visibility[i].draws()) return posed[i] = false;
        CgWidget w = tree.widget(i);
        int at = i * SLOTS * 4;
        float width = layout.size[i * 2], height = layout.size[i * 2 + 1];
        c.save();
        c.translate(layout.position[i * 2], layout.position[i * 2 + 1]);
        if (w.kind == CgWidget.Kind.USER_WIDGET) {
            nested[i].painter.paintAt(c, r, g, b, a);
            c.restore();
            return posed[i] = true;
        }
        boolean identity = renderAffine(at, w.pivotX * width, w.pivotY * height);
        if (moves[i]) nodeOf[i] = c.movable(affine[0], affine[1], affine[2], affine[3], affine[4], affine[5]);
        else if (!identity) c.concat(affine[0], affine[1], affine[2], affine[3], affine[4], affine[5]);
        // SWidget::Paint: its render opacity into what it paints itself and its children with; a leaf's colour too
        int k = at + COLOUR * 4;
        float own = state[at + RENDER_OPACITY * 4] * (leaf(w) ? state[k + 3] : 1f);
        if (fades[i]) effectOf[i] = c.fader(own);
        else a *= own;
        boolean nodes = moves[i] || fades[i];
        switch (w.kind) {
            case IMAGE -> {
                CgBrush br = brush[i];
                if (br.draws()) {
                    // SImage: style tint x colour and opacity x brush tint.
                    CgColor t = br.linearTint();
                    image(c, i, br, width, height, r * state[k] * t.r(), g * state[k + 1] * t.g(),
                            b * state[k + 2] * t.b(), a * t.a());
                }
            }
            case TEXT_BLOCK -> text(c, i, w, width, r * state[k], g * state[k + 1], b * state[k + 2], a);
            case BORDER -> {
                CgBrush br = brush[i];
                if (br.draws()) {
                    // SBorder: brush tint x style tint x brush colour.
                    CgColor t = br.linearTint(), bc = w.brushColour;
                    image(c, i, br, width, height, r * t.r() * bc.r(), g * t.g() * bc.g(), b * t.b() * bc.b(),
                            a * t.a() * bc.a());
                }
                // SCompoundWidget: its content colour into its content's style.
                int cc = at + CONTENT_COLOUR * 4;
                if (contentFades[i]) {
                    c.save();
                    contentEffectOf[i] = c.fader(state[cc + 3]);
                    paintChildren(c, i, r * state[cc], g * state[cc + 1], b * state[cc + 2], a);
                    c.restore();
                    nodes = true;
                } else {
                    nodes |= paintChildren(c, i, r * state[cc], g * state[cc + 1], b * state[cc + 2], a * state[cc + 3]);
                }
            }
            case SCALE_BOX -> {
                // FGeometry::MakeChild's layout scale: everything inside is laid out and drawn in its scaled space.
                c.scale(w.userScale, w.userScale);
                nodes |= paintChildren(c, i, r, g, b, a);
            }
            case RETAINER_BOX -> retainer(c, i, w, at, width, height, r, g, b, a);
            default -> nodes |= paintChildren(c, i, r, g, b, a);
        }
        c.restore();
        return posed[i] = nodes;
    }

    private boolean paintChildren(CgCanvas c, int i, float r, float g, float b, float a) {
        boolean nodes = false;
        for (int ch : tree.paintOrder(i)) nodes |= paint(c, ch, r, g, b, a);
        return nodes;
    }

    /** Whether it draws itself in its colour: an image or a text block. */
    private static boolean leaf(CgWidget w) {
        return w.kind == CgWidget.Kind.IMAGE || w.kind == CgWidget.Kind.TEXT_BLOCK;
    }

    /** A replay's pose walk from its root: its nodes' values from its state now, nothing drawn. */
    void poseAt(CgCanvas c, CgPaintChunk chunk) {
        int self = n * SLOTS * 4;
        c.save();
        renderAffine(self, owner.selfPivotX * layout.rootWidth(), owner.selfPivotY * layout.rootHeight());
        c.moved(chunk.node(nodeOf[n]), affine[0], affine[1], affine[2], affine[3], affine[4], affine[5]);
        if (fades[n]) {
            c.nodeOpacity(chunk.effect(effectOf[n]), state[self + RENDER_OPACITY * 4] * state[self + COLOUR * 4 + 3]);
        }
        if (n > 0) pose(c, chunk, 0);
        c.restore();
    }

    /** Widget {@code i}'s part of the pose walk, as {@link #paint(CgCanvas, int, float, float, float, float)} placed it. */
    private void pose(CgCanvas c, CgPaintChunk chunk, int i) {
        if (!posed[i]) return;
        CgWidget w = tree.widget(i);
        int at = i * SLOTS * 4;
        c.save();
        c.translate(layout.position[i * 2], layout.position[i * 2 + 1]);
        if (w.kind == CgWidget.Kind.USER_WIDGET) {
            nested[i].painter.poseAt(c, chunk);
            c.restore();
            return;
        }
        boolean identity = renderAffine(at, w.pivotX * layout.size[i * 2], w.pivotY * layout.size[i * 2 + 1]);
        if (moves[i]) c.moved(chunk.node(nodeOf[i]), affine[0], affine[1], affine[2], affine[3], affine[4], affine[5]);
        else if (!identity) c.concat(affine[0], affine[1], affine[2], affine[3], affine[4], affine[5]);
        if (fades[i]) {
            c.nodeOpacity(chunk.effect(effectOf[i]),
                    state[at + RENDER_OPACITY * 4] * (leaf(w) ? state[at + COLOUR * 4 + 3] : 1f));
        }
        if (w.kind == CgWidget.Kind.SCALE_BOX) c.scale(w.userScale, w.userScale);
        if (w.kind == CgWidget.Kind.BORDER && contentFades[i]) {
            c.nodeOpacity(chunk.effect(contentEffectOf[i]), state[at + CONTENT_COLOUR * 4 + 3]);
        }
        for (int ch : tree.paintOrder(i)) pose(c, chunk, ch);
        c.restore();
    }

    /**
     * A brush over (0, 0) to ({@code width}, {@code height}), tinted in linear: Slate encodes the colour once
     * ({@code PackVertexColor}); a UI material reads it linear, as Slate's material shaders do.
     */
    private void image(CgCanvas c, int i, CgBrush br, float width, float height, float r, float g, float b,
                       float a) {
        if (a <= 0f) return;
        if (br.material() != null) {
            c.box(0f, 0f, width, height).material(material(i, br.material())).fill(linear(r, g, b, a)).draw();
            return;
        }
        // Its tint is already in the colour, multiplied in linear: the canvas draws the brush untinted
        c.box(0f, 0f, width, height).fill(drawn[i]).tint(CgColor.toArgb(r, g, b, a)).draw();
    }

    /** A text block's text in a box {@code width} units wide, justified, tinted. */
    private void text(CgCanvas c, int i, CgWidget w, float width, float r, float g, float b, float a) {
        CgText t = layout.laid[i];
        if (a <= 0f || t == null || layout.shown[i].isEmpty()) return;
        float x = 0f;
        if (t.layout() != null) {
            // The layout justifies its lines across the wrap width; place the widest line's box in the widget's
            float f = switch (w.justification) {
                case CENTRE -> 0.5f;
                case RIGHT -> 1f;
                case LEFT -> 0f;
            };
            float total = t.layout().totalWidth();
            x = (width - total) * f - layout.laidInset[i] * f;
        }
        c.text(t, x, 0f, CgColor.toArgb(r, g, b, a));
    }

    /**
     * SRetainerWidget: its content drawn into a picture of its own at full opacity, blurred or drawn through its effect
     * material, then laid over at the tint everything above it gives.
     */
    private void retainer(CgCanvas c, int i, CgWidget w, int at, float width, float height, float r, float g, float b,
                          float a) {
        if (a <= 0f) return;
        float reach = 0f;
        for (int ch : tree.children(i)) reach = Math.max(reach, reach(ch, 1f));
        try (CgCanvasLayer layer = c.layer(-reach, -reach, width + 2f * reach, height + 2f * reach)
                .opacity(a).filter(filter(i, w, at, r, g, b))) {
            for (int ch : tree.children(i)) {
                if (contains(ch, CgWidget.Kind.RETAINER_BOX)) {
                    throw new IllegalStateException(w.name + ": a retainer box inside another is not drawn");
                }
                paint(c, ch, 1f, 1f, 1f, 1f);
            }
        }
    }

    /** A retainer box's filter: its effect material or its blur, then its tint's colour; kept while they hold. */
    private CgFilter filter(int i, CgWidget w, int at, float r, float g, float b) {
        float blur = state[at + BLUR * 4];
        int o = i * 5;
        CgFilter f = filters[i];
        if (f != null && filterKey[o] == blur && filterKey[o + 1] == r && filterKey[o + 2] == g && filterKey[o + 3] == b) {
            return f;
        }
        if (w.effect != null) {
            f = CgFilter.material(CgMaterial.of(w.effect), w.textureParameter);
        } else {
            CgWidget.BlurAxis axis = w.blurAxis;
            float sx = axis == CgWidget.BlurAxis.VERTICAL ? 0f : blur, sy = axis == CgWidget.BlurAxis.HORIZONTAL ? 0f : blur;
            f = blur > 0f ? CgFilter.blur(sx, sy) : CgFilter.NONE;
        }
        if (r != 1f || g != 1f || b != 1f) {
            // The tint's colour, encoded as the composite's vertex colour was: a premultiplied picture's rgb scaled
            int argb = CgColor.toArgb(r, g, b, 1f);
            float sr = (argb >>> 16 & 0xFF) / 255f, sg = (argb >>> 8 & 0xFF) / 255f, sb = (argb & 0xFF) / 255f;
            f = f.then(CgFilter.matrix(new float[]{sr, 0, 0, 0, 0, 0, sg, 0, 0, 0, 0, 0, sb, 0, 0, 0, 0, 0, 1, 0}));
        }
        filters[i] = f;
        filterKey[o] = blur;
        filterKey[o + 1] = r;
        filterKey[o + 2] = g;
        filterKey[o + 3] = b;
        return f;
    }

    /** How far widget {@code i}'s text draws past its boxes, its styles' reach under {@code scale}: a scale box's. */
    private float reach(int i, float scale) {
        CgWidget w = tree.widget(i);
        float reach = w.kind == CgWidget.Kind.TEXT_BLOCK && style[i] != null ? style[i].reach() * scale : 0f;
        float inner = w.kind == CgWidget.Kind.SCALE_BOX ? scale * w.userScale : scale;
        for (int c : tree.children(i)) reach = Math.max(reach, reach(c, inner));
        return reach;
    }

    private boolean contains(int i, CgWidget.Kind kind) {
        if (tree.widget(i).kind == kind) return true;
        for (int c : tree.children(i)) if (contains(c, kind)) return true;
        return false;
    }

    /**
     * Slate's render transform about the pivot ({@code px}, {@code py}) into {@link #affine}, as
     * {@link CgCanvas#concat} takes it; whether it is the identity. {@code Geometry.h}'s order.
     */
    private boolean renderAffine(int at, float px, float py) {
        float sx = state[at + SCALE * 4], sy = state[at + SCALE * 4 + 1];
        float kx = shear(state[at + SHEAR * 4]), ky = shear(state[at + SHEAR * 4 + 1]);
        float angle = state[at + ANGLE * 4];
        float tx = state[at + TRANSLATION * 4], ty = state[at + TRANSLATION * 4 + 1];
        // FWidgetTransform::ToSlateRenderTransform: scale, then shear, then rotation, then translation.
        double rad = Math.toRadians(angle);
        float cos = angle == 0f ? 1f : (float) Math.cos(rad), sin = angle == 0f ? 0f : (float) Math.sin(rad);
        float ka = sx, kb = ky * sx, kc = kx * sy, kd = sy;
        float la = cos * ka - sin * kb, lb = sin * ka + cos * kb;
        float lc = cos * kc - sin * kd, ld = sin * kc + cos * kd;
        affine[0] = la;
        affine[1] = lb;
        affine[2] = lc;
        affine[3] = ld;
        affine[4] = px + tx - (la * px + lc * py);
        affine[5] = py + ty - (lb * px + ld * py);
        return tx == 0f && ty == 0f && sx == 1f && sy == 1f && kx == 0f && ky == 0f && angle == 0f;
    }

    /** Widget {@code i}'s brush material as its own instance, its {@code _Parameters} this frame's. */
    private CgMaterialInstance material(int i, CgMaterialDefinition definition) {
        CgMaterialInstance m = materials[i];
        if (m == null || materialOf[i] != definition) {
            m = materials[i] = CgMaterial.of(definition).instance();
            materialOf[i] = definition;
            takesParameters[i] = false;
            for (CgMaterialProperty p : m.material().properties()) {
                if (p.getName().equals(PARAMETERS)) takesParameters[i] = true;
            }
        }
        if (takesParameters[i]) {
            int at = i * SLOTS * 4 + MATERIAL_PARAMETERS * 4;
            m.vec4(PARAMETERS, state[at], state[at + 1], state[at + 2], state[at + 3]);
        }
        return m;
    }

    /** Linear channels packed as they are, unencoded: what a UI material reads. */
    private static int linear(float r, float g, float b, float a) {
        return byteOf(a) << 24 | byteOf(r) << 16 | byteOf(g) << 8 | byteOf(b);
    }

    private static int byteOf(float value) {
        return Math.round(Math.max(0f, Math.min(1f, value)) * 255f);
    }

    private static float shear(float degrees) {
        return degrees == 0f ? 0f : (float) Math.tan(Math.toRadians(Math.max(-89f, Math.min(89f, degrees))));
    }
}
