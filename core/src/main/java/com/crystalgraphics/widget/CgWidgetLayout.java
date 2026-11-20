package com.crystalgraphics.widget;

import java.util.Arrays;

import org.joml.Vector2f;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.paint.brush.CgBrush;
import com.crystalgraphics.api.text.CgText;
import com.crystalgraphics.api.text.CgTextAlign;
import com.crystalgraphics.api.text.CgTextLayout;
import com.crystalgraphics.i18n.CgKitStrings;
import com.crystalgraphics.text.style.CgTextStyle;

/**
 * Slate's layout of one user widget: each widget's desired size bottom up, then each panel arranging its children
 * ({@code ComputeDesiredSize}, {@code OnArrangeChildren}), and each text block's text as laid out. Reads its owner's
 * tree and properties; writes boxes, parent-relative, in widget units.
 */
final class CgWidgetLayout {

    private static final String TEXT_ID = ":text/";

    private final CgUserWidget owner;
    private final CgWidgetTree tree;
    final int n;
    private final CgWidget.Visibility[] visibility;
    private final String[] text;
    private final CgTextStyle[] style;
    private final CgBrush[] brush;
    private final CgUserWidget[] nested;
    final float[] desired;
    final float[] position;
    final float[] size;
    /** A text block's text as shown and as laid out at its last layout, and its x in its box per justification. */
    final String[] shown;
    final CgText[] laid;
    final float[] laidInset;
    private final Vector2f measured = new Vector2f();
    private float allottedWidth = Float.NaN;
    private float allottedHeight = Float.NaN;
    private boolean dirty = true;

    CgWidgetLayout(CgUserWidget owner) {
        this.owner = owner;
        this.tree = owner.tree();
        this.n = tree.size();
        this.visibility = owner.visibility;
        this.text = owner.text;
        this.style = owner.style;
        this.brush = owner.brush;
        this.nested = owner.nested;
        desired = new float[n * 2];
        position = new float[n * 2];
        size = new float[n * 2];
        shown = new String[n];
        laid = new CgText[n];
        laidInset = new float[n];
    }

    /** Something it reads changed: it lays out again at the next paint or {@code layout}. */
    void invalidate() {
        dirty = true;
    }

    /** Lays out at {@code width} x {@code height} from now on: whether that differs from what it had. */
    boolean allot(float width, float height) {
        if (width == allottedWidth && height == allottedHeight) return false;
        allottedWidth = width;
        allottedHeight = height;
        dirty = true;
        return true;
    }

    /** Back to its root's desired size and no text laid: for its owner's reset. */
    void forget() {
        allottedWidth = allottedHeight = Float.NaN;
        Arrays.fill(laid, null);
        dirty = true;
    }

    boolean needsLayout() {
        if (dirty) return true;
        for (CgUserWidget u : nested) if (u != null && u.layout.needsLayout()) return true;
        return false;
    }

    boolean layoutIfDirty() {
        if (!needsLayout()) return false;
        desireAll();
        if (n > 0) {
            position[0] = position[1] = 0f;
            size[0] = Float.isNaN(allottedWidth) ? desired[0] : allottedWidth;
            size[1] = Float.isNaN(allottedHeight) ? desired[1] : allottedHeight;
        }
        for (int i = 0; i < n; i++) arrange(i);
        dirty = false;
        return true;
    }

    void desireAll() {
        for (int i = n - 1; i >= 0; i--) desire(i);
    }

    float rootWidth() {
        return n == 0 ? 0f : size[0];
    }

    float rootHeight() {
        return n == 0 ? 0f : size[1];
    }

    private void desire(int i) {
        CgWidget w = tree.widget(i);
        float dx = 0f, dy = 0f;
        if (visibility[i] != CgWidget.Visibility.COLLAPSED) {
            switch (w.kind) {
                case IMAGE -> {
                    dx = brush[i].imageWidth();
                    dy = brush[i].imageHeight();
                }
                case TEXT_BLOCK -> {
                    shown[i] = resolve(text[i]);
                    lay(i, w);
                    dx = measured.x;
                    dy = measured.y;
                }
                case USER_WIDGET -> {
                    CgUserWidget inner = nested[i];
                    inner.layout.desireAll();
                    if (inner.layout.n > 0) {
                        dx = inner.layout.desired[0];
                        dy = inner.layout.desired[1];
                    }
                }
                case BORDER, RETAINER_BOX -> {
                    // SCompoundWidget: its child and its padding.
                    for (int c : tree.children(i)) {
                        if (visibility[c] == CgWidget.Visibility.COLLAPSED) continue;
                        dx = desired[c * 2] + tree.padding[c * 4] + tree.padding[c * 4 + 2];
                        dy = desired[c * 2 + 1] + tree.padding[c * 4 + 1] + tree.padding[c * 4 + 3];
                    }
                }
                case SCALE_BOX -> {
                    // SScaleBox::ComputeDesiredSize at a user scale: SCompoundWidget's, times it.
                    float s = w.userScale;
                    for (int c : tree.children(i)) {
                        if (visibility[c] == CgWidget.Visibility.COLLAPSED) continue;
                        dx = (desired[c * 2] + tree.padding[c * 4] + tree.padding[c * 4 + 2]) * s;
                        dy = (desired[c * 2 + 1] + tree.padding[c * 4 + 1] + tree.padding[c * 4 + 3]) * s;
                    }
                }
                case HORIZONTAL_BOX -> {
                    // SBoxPanel's ComputeDesiredSizeForBox.
                    for (int c : tree.children(i)) {
                        if (visibility[c] == CgWidget.Visibility.COLLAPSED) continue;
                        dx += desired[c * 2] + tree.padding[c * 4] + tree.padding[c * 4 + 2];
                        dy = Math.max(dy, desired[c * 2 + 1] + tree.padding[c * 4 + 1] + tree.padding[c * 4 + 3]);
                    }
                }
                case CANVAS -> {
                    // SConstraintCanvas::ComputeDesiredSize.
                    float[] an = tree.anchors, off = tree.offsets;
                    for (int c : tree.children(i)) {
                        if (visibility[c] == CgWidget.Visibility.COLLAPSED) continue;
                        float sx = tree.autoSize[c] ? desired[c * 2] : off[c * 4 + 2];
                        float sy = tree.autoSize[c] ? desired[c * 2 + 1] : off[c * 4 + 3];
                        boolean dockX = an[c * 4] == an[c * 4 + 2] && (an[c * 4] == 0f || an[c * 4] == 1f);
                        boolean dockY = an[c * 4 + 1] == an[c * 4 + 3] && (an[c * 4 + 1] == 0f || an[c * 4 + 1] == 1f);
                        dx = Math.max(dx, sx + (dockX ? Math.abs(off[c * 4]) : 0f));
                        dy = Math.max(dy, sy + (dockY ? Math.abs(off[c * 4 + 1]) : 0f));
                    }
                }
            }
        }
        desired[i * 2] = dx;
        desired[i * 2 + 1] = dy;
    }

    /** A string id in the player's language, else the text itself (Unreal shows a missing string's key). */
    private static String resolve(String text) {
        if (text.indexOf(TEXT_ID) <= 0) return text;
        String s = CgKitStrings.text(text);
        return s != null ? s : text;
    }

    private void arrange(int i) {
        CgWidget w = tree.widget(i);
        float width = size[i * 2], height = size[i * 2 + 1];
        switch (w.kind) {
            case CANVAS -> {
                // SConstraintCanvas::ArrangeLayeredChildren.
                float[] an = tree.anchors, off = tree.offsets, al = tree.alignment;
                for (int c : tree.children(i)) {
                    float sx = tree.autoSize[c] ? desired[c * 2] : off[c * 4 + 2];
                    float sy = tree.autoSize[c] ? desired[c * 2 + 1] : off[c * 4 + 3];
                    canvasAxis(c, 0, an[c * 4] * width, an[c * 4 + 2] * width, off[c * 4], off[c * 4 + 2], sx, al[c * 2]);
                    canvasAxis(c, 1, an[c * 4 + 1] * height, an[c * 4 + 3] * height, off[c * 4 + 1], off[c * 4 + 3], sy,
                            al[c * 2 + 1]);
                }
            }
            case HORIZONTAL_BOX -> arrangeBox(i, width, height);
            case BORDER, RETAINER_BOX -> {
                // ArrangeSingleChild.
                for (int c : tree.children(i)) {
                    align(c, 0, width, desired[c * 2], tree.horizontal[c], tree.padding[c * 4], tree.padding[c * 4 + 2]);
                    align(c, 1, height, desired[c * 2 + 1], tree.vertical[c], tree.padding[c * 4 + 1],
                            tree.padding[c * 4 + 3]);
                }
            }
            case SCALE_BOX -> {
                // SScaleBox::OnArrangeChildren: aligned at its scaled size, then placed in the scaled space the
                // paint applies (the offset over the scale), at its own desired size, or the area's over the scale
                // along an axis it fills.
                float s = w.userScale;
                for (int c : tree.children(i)) {
                    float pl = tree.padding[c * 4], pt = tree.padding[c * 4 + 1];
                    float pr = tree.padding[c * 4 + 2], pb = tree.padding[c * 4 + 3];
                    align(c, 0, width, desired[c * 2] * s, tree.horizontal[c], pl, pr);
                    align(c, 1, height, desired[c * 2 + 1] * s, tree.vertical[c], pt, pb);
                    position[c * 2] /= s;
                    position[c * 2 + 1] /= s;
                    size[c * 2] = tree.horizontal[c] == CgWidgetSlot.Align.FILL ? width / s : desired[c * 2];
                    size[c * 2 + 1] = tree.vertical[c] == CgWidgetSlot.Align.FILL ? height / s : desired[c * 2 + 1];
                }
            }
            case USER_WIDGET -> nested[i].allot(width, height).layoutIfDirty();
            default -> {
            }
        }
    }

    private void canvasAxis(int c, int axis, float anchorMin, float anchorMax, float offMin, float offMax, float s,
                            float alignment) {
        if (anchorMin != anchorMax) {
            position[c * 2 + axis] = anchorMin + offMin;
            size[c * 2 + axis] = anchorMax - position[c * 2 + axis] - offMax;
        } else {
            position[c * 2 + axis] = anchorMin + offMin - s * alignment;
            size[c * 2 + axis] = s;
        }
    }

    /** {@code SBoxPanel}'s {@code ArrangeChildrenAlong}, left to right: auto children their desired size, fill children the rest. */
    private void arrangeBox(int i, float width, float height) {
        float fixed = 0f, coefficients = 0f;
        boolean any = false;
        for (int c : tree.children(i)) {
            if (visibility[c] == CgWidget.Visibility.COLLAPSED) continue;
            any = true;
            fixed += tree.padding[c * 4] + tree.padding[c * 4 + 2];
            if (tree.fills[c]) coefficients += tree.fill[c];
            else fixed += desired[c * 2];
        }
        if (!any) return;
        float free = Math.max(0f, width - fixed);
        float so = 0f;
        for (int c : tree.children(i)) {
            boolean collapsed = visibility[c] == CgWidget.Visibility.COLLAPSED;
            float child = 0f;
            if (!collapsed) {
                child = tree.fills[c] ? (coefficients > 0f ? free * tree.fill[c] / coefficients : 0f) : desired[c * 2];
            }
            float padL = tree.padding[c * 4], padR = tree.padding[c * 4 + 2];
            float slot = child + padL + padR;
            align(c, 0, slot, desired[c * 2], tree.horizontal[c], padL, padR);
            position[c * 2] += so;
            align(c, 1, height, desired[c * 2 + 1], tree.vertical[c], tree.padding[c * 4 + 1], tree.padding[c * 4 + 3]);
            if (!collapsed) so += slot;
        }
    }

    /** {@code LayoutUtils.h}'s {@code AlignChild}: offset and size along one axis, clamped to the slot. */
    private void align(int c, int axis, float allotted, float childDesired, CgWidgetSlot.Align align, float pre,
                       float post) {
        float total = pre + post;
        if (align == CgWidgetSlot.Align.FILL) {
            position[c * 2 + axis] = pre;
            size[c * 2 + axis] = allotted - total;
            return;
        }
        float child = Math.min(childDesired, allotted - total);
        position[c * 2 + axis] = switch (align) {
            case LEFT -> pre;
            case CENTRE -> (allotted - child) / 2f + pre - post;
            default -> allotted - child - post;
        };
        size[c * 2 + axis] = child;
    }

    /**
     * Lays text block {@code i} out in its style, its size into {@code measured}: one line at its own width, or STextBlock's
     * lines justified across the wrap width, the box the widest line's.
     */
    private void lay(int i, CgWidget w) {
        CgTextStyle st = style[i];
        if (w.wrap <= 0f && w.justification == CgWidget.Justification.LEFT) {
            CgText t = CgText.of(shown[i]).style(st);
            CgTextLayout l = t.measure();
            laid[i] = t;
            laidInset[i] = 0f;
            measured.set(l.totalWidth(), l.totalHeight());
            return;
        }
        CgFont font = st.font(Math.max(1, Math.round(st.px())));
        CgTextAlign align = switch (w.justification) {
            case CENTRE -> CgTextAlign.CENTER;
            case RIGHT -> CgTextAlign.RIGHT;
            case LEFT -> CgTextAlign.LEFT;
        };
        CgTextLayout.Request request = CgTextLayout.of(shown[i], font).align(align);
        if (w.wrap > 0f) request.maxWidth(w.wrap);
        CgTextLayout layout = request.build();
        laid[i] = CgText.of(layout).style(st).contained(false);
        laidInset[i] = w.wrap > 0f ? w.wrap - layout.totalWidth() : 0f;
        measured.set(layout.totalWidth(), layout.totalHeight());
    }
}
