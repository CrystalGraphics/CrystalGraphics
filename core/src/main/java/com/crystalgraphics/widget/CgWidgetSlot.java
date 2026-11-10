package com.crystalgraphics.widget;

import java.util.Arrays;
import java.util.Objects;

import javax.annotation.Nullable;

/**
 * Where a widget sits in its parent: UMG's panel slot. A canvas panel reads its anchors, offsets, alignment, auto size
 * and z order ({@code UCanvasPanelSlot}); a horizontal box or a border its padding, alignment and size rule
 * ({@code UHorizontalBoxSlot}, {@code UBorderSlot}). Fields the parent does not read are ignored.
 *
 * <pre>{@code
 * // in a canvas panel: centred on the panel's middle, at its own desired size
 * CgWidgetSlot.builder().anchors(0.5f, 0.5f).alignment(0.5f, 0.5f).autoSize(true).build()
 *
 * // in a canvas panel: 224 x 56, its bottom-right corner 115 left of and 48 above the panel's
 * CgWidgetSlot.builder().anchors(1f, 1f).alignment(1f, 1f).offsets(115f, 48f, 224f, 56f).build()
 *
 * // in a horizontal box: overlapping its neighbours by 13 a side, centred vertically
 * CgWidgetSlot.builder().padding(-13f, 0f, -13f, 0f).vertical(CgWidgetSlot.Align.CENTRE).build()
 * }</pre>
 *
 * <ul>
 *   <li>A field left unset takes its parent kind's default, as each UMG slot class has its own: a border's padding is
 *       (4, 2), a box's 0; both fill.</li>
 *   <li>A canvas slot's offsets are left, top, right, bottom; on an axis whose anchors meet, right and bottom are the
 *       size, as in UMG's details panel.</li>
 * </ul>
 */
public final class CgWidgetSlot {

    /** A child's place across its slot: Slate's {@code EHorizontalAlignment} and {@code EVerticalAlignment}. */
    public enum Align {
        FILL, LEFT, CENTRE, RIGHT;

        /** Top: the vertical name of {@link #LEFT}. */
        public static final Align TOP = LEFT;
        /** Bottom: the vertical name of {@link #RIGHT}. */
        public static final Align BOTTOM = RIGHT;
    }

    /** A box child's share of the box: its desired size, or a part of what is left ({@code ESlateSizeRule}). */
    public enum Size { AUTO, FILL }

    /** Every field unset: each parent's defaults. */
    public static final CgWidgetSlot DEFAULT = builder().build();

    // Canvas: anchors min x, y, max x, y; offsets left, top, right, bottom; alignment x, y. NaN when unset.
    final float[] anchors;
    final float[] offsets;
    final float[] alignment;
    @Nullable final Boolean autoSize;
    final int zOrder;
    // Horizontal box, border.
    final float[] padding;
    @Nullable final Align horizontal;
    @Nullable final Align vertical;
    final Size size;
    final float fill;

    private CgWidgetSlot(Builder b) {
        anchors = b.anchors.clone();
        offsets = b.offsets.clone();
        alignment = b.alignment.clone();
        autoSize = b.autoSize;
        zOrder = b.zOrder;
        padding = b.padding.clone();
        horizontal = b.horizontal;
        vertical = b.vertical;
        size = b.size;
        fill = b.fill;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Its anchors: min x, min y, max x, max y; NaN where unset. */
    public float[] anchors() {
        return anchors.clone();
    }

    /** Its offsets: left, top, right, bottom; NaN where unset. */
    public float[] offsets() {
        return offsets.clone();
    }

    /** Its alignment, x and y; NaN where unset. */
    public float[] alignment() {
        return alignment.clone();
    }

    /** Whether a canvas sizes it to its desired size; null when unset. */
    @Nullable
    public Boolean autoSize() {
        return autoSize;
    }

    public int zOrder() {
        return zOrder;
    }

    /** Its padding: left, top, right, bottom; NaN where unset. */
    public float[] padding() {
        return padding.clone();
    }

    @Nullable
    public Align horizontal() {
        return horizontal;
    }

    @Nullable
    public Align vertical() {
        return vertical;
    }

    public Size size() {
        return size;
    }

    /** Its fill coefficient, for {@link Size#FILL}. */
    public float fill() {
        return fill;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof CgWidgetSlot s && Arrays.equals(anchors, s.anchors)
                && Arrays.equals(offsets, s.offsets) && Arrays.equals(alignment, s.alignment)
                && Objects.equals(autoSize, s.autoSize) && zOrder == s.zOrder
                && Arrays.equals(padding, s.padding) && horizontal == s.horizontal
                && vertical == s.vertical && size == s.size && fill == s.fill;
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(anchors), Arrays.hashCode(offsets),
                Arrays.hashCode(alignment), autoSize, zOrder, Arrays.hashCode(padding),
                horizontal, vertical, size, fill);
    }

    /** Builds a slot; every field starts unset. */
    public static final class Builder {
        private final float[] anchors = {Float.NaN, Float.NaN, Float.NaN, Float.NaN};
        private final float[] offsets = {Float.NaN, Float.NaN, Float.NaN, Float.NaN};
        private final float[] alignment = {Float.NaN, Float.NaN};
        private Boolean autoSize;
        private int zOrder;
        private final float[] padding = {Float.NaN, Float.NaN, Float.NaN, Float.NaN};
        private Align horizontal;
        private Align vertical;
        private Size size = Size.AUTO;
        private float fill = 1f;

        private Builder() {
        }

        /** Both anchors at one point of the parent, 0 to 1 each: the child does not stretch. */
        public Builder anchors(float x, float y) {
            return anchors(x, y, x, y);
        }

        /** The anchors, 0 to 1 of the parent each: an axis whose min and max differ stretches with the parent. */
        public Builder anchors(float minX, float minY, float maxX, float maxY) {
            anchors[0] = minX;
            anchors[1] = minY;
            anchors[2] = maxX;
            anchors[3] = maxY;
            return this;
        }

        /** Left, top, right, bottom; right and bottom are the size on an axis that does not stretch. */
        public Builder offsets(float left, float top, float right, float bottom) {
            offsets[0] = left;
            offsets[1] = top;
            offsets[2] = right;
            offsets[3] = bottom;
            return this;
        }

        /** The point of the child, 0 to 1 of its size each, that sits at its place. */
        public Builder alignment(float x, float y) {
            alignment[0] = x;
            alignment[1] = y;
            return this;
        }

        /** In a canvas, whether it takes its desired size rather than its offsets'. */
        public Builder autoSize(boolean autoSize) {
            this.autoSize = autoSize;
            return this;
        }

        /** In a canvas, its paint order: higher draws later, ties in tree order. */
        public Builder zOrder(int zOrder) {
            this.zOrder = zOrder;
            return this;
        }

        public Builder padding(float left, float top, float right, float bottom) {
            padding[0] = left;
            padding[1] = top;
            padding[2] = right;
            padding[3] = bottom;
            return this;
        }

        public Builder horizontal(Align align) {
            this.horizontal = Objects.requireNonNull(align, "align");
            return this;
        }

        public Builder vertical(Align align) {
            this.vertical = Objects.requireNonNull(align, "align");
            return this;
        }

        /** In a box: {@link Size#AUTO}, or {@link Size#FILL} with its coefficient. */
        public Builder size(Size size, float fill) {
            this.size = Objects.requireNonNull(size, "size");
            this.fill = fill;
            return this;
        }

        public CgWidgetSlot build() {
            return new CgWidgetSlot(this);
        }
    }
}
