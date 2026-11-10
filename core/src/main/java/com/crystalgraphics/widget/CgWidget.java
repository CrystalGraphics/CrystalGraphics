package com.crystalgraphics.widget;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import javax.annotation.Nullable;

import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.api.material.CgMaterialDomain;
import com.crystalgraphics.text.style.CgTextStyle;

/**
 * One named widget of a {@link CgWidgetTree}, as designed: UMG's {@code UWidget}. Panels lay out their children (a
 * canvas by anchors, a horizontal box in a row, a border around one, a retainer box painting one as a single image);
 * leaves draw (an image, a text block) or nest another tree (a user widget). Every widget has UMG's common properties: a render transform about a pivot, a render
 * opacity and a visibility.
 *
 * <pre>{@code
 * // Kakarot's critical banner: a canvas panel holding an image, turned and widened about its middle
 * CgWidget critical = CgWidget.canvas("Pnl_Lang_Critical")
 *         .slot(CgWidgetSlot.builder().anchors(0f, 0.5f, 1f, 0.5f).alignment(0f, 0.5f).offsets(0f, 0f, 0f, 60f).build())
 *         .translation(45f, 0f)
 *         .child(CgWidget.image("Img_Base_Critical")
 *                 .slot(CgWidgetSlot.builder().anchors(1f, 1f).alignment(1f, 1f).offsets(115f, 48f, 224f, 56f).build())
 *                 .brush(CgWidgetBrush.texture("kk:textures/hud/damage_number/base_critical.png", 224f, 56f))
 *                 .scale(1.5f, 1.5f).angle(2f).colour(new CgLinearColour(1f, 1f, 1f, 0f)))
 *         .build();
 * }</pre>
 *
 * <pre>{@code
 * // a text block: its size is its text's, measured in its style; a string id reads the player's language
 * CgWidget.text("Num_Dmg").style(GOLD).text("1,250")
 * CgWidget.text("Txt_Name").style(NAME).text("kakarot:text/Cpl001A").justification(CgWidget.Justification.CENTRE)
 * }</pre>
 *
 * <ul>
 *   <li>Names are unique in a tree: a sequence finds a widget by its name.</li>
 *   <li>A border and a retainer box hold one child; {@link Builder#child} a second time fails. Its padding, (4, 2) unless set, and
 *       alignment are its child's {@link CgWidgetSlot}, as UMG's {@code UBorderSlot}.</li>
 *   <li>The pivot defaults to the middle (0.5, 0.5), UMG's, not Slate's corner.</li>
 *   <li>The angle is in degrees, clockwise on the y-down screen. Colours are linear ({@link CgLinearColour}).</li>
 * </ul>
 */
public final class CgWidget {

    /** What a widget is: the UMG classes the engine draws, as a widget file names them. */
    public enum Kind {
        /** {@code UCanvasPanel}: children placed by anchors and offsets. */
        CANVAS("canvas", true),
        /** {@code UHorizontalBox}: children in a row. */
        HORIZONTAL_BOX("horizontal_box", true),
        /** {@code UBorder}: a background brush around one child, padded, whose colour tints its content. */
        BORDER("border", true),
        /**
         * {@code URetainerBox}: one child, filling it, painted at full opacity into an image of its own and drawn as
         * one quad, so what fades above it fades one picture; through its effect material where it has one.
         */
        RETAINER_BOX("retainer_box", true),
        /** {@code UImage}: a brush. */
        IMAGE("image", false),
        /** {@code UTextBlock}: text in a {@link CgTextStyle}. */
        TEXT_BLOCK("text_block", false),
        /** A nested {@code UUserWidget}: an instance of another tree. */
        USER_WIDGET("user_widget", false);

        private final String id;
        private final boolean panel;

        Kind(String id, boolean panel) {
            this.id = id;
            this.panel = panel;
        }

        /** As a widget file names it. */
        public String id() {
            return id;
        }

        /** Whether it holds children. */
        public boolean panel() {
            return panel;
        }

        /** The kind a file names {@code id}, or null. */
        @Nullable
        public static Kind of(String id) {
            for (Kind k : values()) if (k.id.equals(id)) return k;
            return null;
        }
    }

    /** UMG's {@code ESlateVisibility}: whether it draws, and whether it takes space. */
    public enum Visibility {
        VISIBLE, HIT_TEST_INVISIBLE, SELF_HIT_TEST_INVISIBLE,
        /** Takes its space, draws nothing. */
        HIDDEN,
        /** Neither takes space nor draws. */
        COLLAPSED;

        /** Whether it and its children draw. */
        public boolean draws() {
            return this != HIDDEN && this != COLLAPSED;
        }

        /** As a widget file names it. */
        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** A text block's lines across its box: Slate's {@code ETextJustify}. */
    public enum Justification { LEFT, CENTRE, RIGHT }

    final Kind kind;
    final String name;
    final CgWidgetSlot slot;
    final float[] transform;   // translation x, y, scale x, y, shear x, y, angle
    final float pivotX;
    final float pivotY;
    final float renderOpacity;
    final Visibility visibility;
    final CgLinearColour colour;
    final CgLinearColour contentColour;
    final CgLinearColour brushColour;
    final CgWidgetBrush brush;
    @Nullable final CgTextStyle style;
    final String text;
    final Justification justification;
    final float wrap;
    @Nullable final CgWidgetTree widget;
    @Nullable final CgMaterialDefinition effect;
    final String textureParameter;
    final List<CgWidget> children;

    private CgWidget(Builder b) {
        kind = b.kind;
        name = b.name;
        slot = b.slot;
        transform = b.transform.clone();
        pivotX = b.pivotX;
        pivotY = b.pivotY;
        renderOpacity = b.renderOpacity;
        visibility = b.visibility;
        colour = b.colour;
        contentColour = b.contentColour;
        brushColour = b.brushColour;
        brush = b.brush;
        style = b.style;
        text = b.text;
        justification = b.justification;
        wrap = b.wrap;
        widget = b.widget;
        effect = b.effect;
        textureParameter = b.textureParameter;
        children = Collections.unmodifiableList(new ArrayList<>(b.children));
    }

    public static Builder canvas(String name) {
        return new Builder(Kind.CANVAS, name);
    }

    public static Builder horizontalBox(String name) {
        return new Builder(Kind.HORIZONTAL_BOX, name);
    }

    /** A border; its background is {@link CgWidgetBrush#WHITE} until {@link Builder#brush} sets another. */
    public static Builder border(String name) {
        return new Builder(Kind.BORDER, name).brush(CgWidgetBrush.WHITE);
    }

    public static Builder image(String name) {
        return new Builder(Kind.IMAGE, name).brush(CgWidgetBrush.WHITE);
    }

    /** A retainer box: its one child painted as one image, drawn plain or through {@link Builder#effect}. */
    public static Builder retainerBox(String name) {
        return new Builder(Kind.RETAINER_BOX, name);
    }

    /** A text block; it needs a {@link Builder#style}. */
    public static Builder text(String name) {
        return new Builder(Kind.TEXT_BLOCK, name);
    }

    /** An instance of {@code tree}, nested: its own sequences play on it; the outer tree's bind it by its name alone. */
    public static Builder userWidget(String name, CgWidgetTree tree) {
        Builder b = new Builder(Kind.USER_WIDGET, name);
        b.widget = Objects.requireNonNull(tree, "tree");
        return b;
    }

    public Kind kind() {
        return kind;
    }

    public String name() {
        return name;
    }

    public CgWidgetSlot slot() {
        return slot;
    }

    /** Translation x, y, scale x, y, shear x, y in degrees, angle in degrees. */
    public float[] transform() {
        return transform.clone();
    }

    public float pivotX() {
        return pivotX;
    }

    public float pivotY() {
        return pivotY;
    }

    public float renderOpacity() {
        return renderOpacity;
    }

    public Visibility visibility() {
        return visibility;
    }

    /** An image's, a text block's or a user widget's colour and opacity. */
    public CgLinearColour colour() {
        return colour;
    }

    /** A border's content colour and opacity: multiplied into everything inside it. */
    public CgLinearColour contentColour() {
        return contentColour;
    }

    /** A border's brush colour: its background's tint. */
    public CgLinearColour brushColour() {
        return brushColour;
    }

    /** An image's brush, a border's background; {@link CgWidgetBrush#NONE} for the rest. */
    public CgWidgetBrush brush() {
        return brush;
    }

    @Nullable
    public CgTextStyle style() {
        return style;
    }

    /** A text block's designer text: a string id ({@code <ns>:text/<id>}) or the literal text. */
    public String text() {
        return text;
    }

    public Justification justification() {
        return justification;
    }

    /** A text block's wrap width in units; 0 for none. */
    public float wrap() {
        return wrap;
    }

    /** A user widget's tree. */
    @Nullable
    public CgWidgetTree widget() {
        return widget;
    }

    /** A retainer box's effect, a UI material sampling the retained image; null to draw the image as it is. */
    @Nullable
    public CgMaterialDefinition effect() {
        return effect;
    }

    /** The sampler of {@link #effect} the retained image binds to: {@code Texture} unless set, UMG's. */
    public String textureParameter() {
        return textureParameter;
    }

    public List<CgWidget> children() {
        return children;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof CgWidget w && kind == w.kind && name.equals(w.name) && slot.equals(w.slot)
                && Arrays.equals(transform, w.transform) && pivotX == w.pivotX && pivotY == w.pivotY
                && renderOpacity == w.renderOpacity && visibility == w.visibility && colour.equals(w.colour)
                && contentColour.equals(w.contentColour) && brushColour.equals(w.brushColour)
                && brush.equals(w.brush) && Objects.equals(style, w.style) && text.equals(w.text)
                && justification == w.justification && wrap == w.wrap && Objects.equals(widget, w.widget)
                && Objects.equals(effect, w.effect) && textureParameter.equals(w.textureParameter)
                && children.equals(w.children);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, name, slot, children);
    }

    @Override
    public String toString() {
        return kind.id() + " " + name;
    }

    /** Builds a widget at UMG's defaults. */
    public static final class Builder {
        private final Kind kind;
        private final String name;
        private CgWidgetSlot slot = CgWidgetSlot.DEFAULT;
        private final float[] transform = {0f, 0f, 1f, 1f, 0f, 0f, 0f};
        private float pivotX = 0.5f;
        private float pivotY = 0.5f;
        private float renderOpacity = 1f;
        private Visibility visibility = Visibility.VISIBLE;
        private CgLinearColour colour = CgLinearColour.WHITE;
        private CgLinearColour contentColour = CgLinearColour.WHITE;
        private CgLinearColour brushColour = CgLinearColour.WHITE;
        private CgWidgetBrush brush = CgWidgetBrush.NONE;
        private CgTextStyle style;
        private String text = "";
        private Justification justification = Justification.LEFT;
        private float wrap;
        private CgWidgetTree widget;
        private CgMaterialDefinition effect;
        private String textureParameter = "Texture";
        private final List<CgWidget> children = new ArrayList<>();

        private Builder(Kind kind, String name) {
            if (name == null || !name.matches("[A-Za-z0-9_]+")) {
                throw new IllegalArgumentException("a widget's name is letters, digits and _: " + name);
            }
            this.kind = kind;
            this.name = name;
        }

        /** Its place in its parent. */
        public Builder slot(CgWidgetSlot slot) {
            this.slot = Objects.requireNonNull(slot, "slot");
            return this;
        }

        /** In widget units. */
        public Builder translation(float x, float y) {
            transform[0] = x;
            transform[1] = y;
            return this;
        }

        public Builder scale(float x, float y) {
            transform[2] = x;
            transform[3] = y;
            return this;
        }

        /** In degrees, each -89 to 89. */
        public Builder shear(float x, float y) {
            transform[4] = x;
            transform[5] = y;
            return this;
        }

        /** In degrees. */
        public Builder angle(float degrees) {
            transform[6] = degrees;
            return this;
        }

        /** 0 to 1 of its size each. */
        public Builder pivot(float x, float y) {
            pivotX = x;
            pivotY = y;
            return this;
        }

        /** 0 to 1, multiplied into it and everything inside it. */
        public Builder renderOpacity(float opacity) {
            renderOpacity = opacity;
            return this;
        }

        public Builder visibility(Visibility visibility) {
            this.visibility = Objects.requireNonNull(visibility, "visibility");
            return this;
        }

        /** An image's, a text block's or a user widget's colour and opacity. */
        public Builder colour(CgLinearColour colour) {
            this.colour = Objects.requireNonNull(colour, "colour");
            return this;
        }

        /** A border's content colour and opacity. */
        public Builder contentColour(CgLinearColour colour) {
            contentColour = Objects.requireNonNull(colour, "colour");
            return this;
        }

        /** A border's background tint. */
        public Builder brushColour(CgLinearColour colour) {
            brushColour = Objects.requireNonNull(colour, "colour");
            return this;
        }

        /** An image's brush, or a border's background. */
        public Builder brush(CgWidgetBrush brush) {
            this.brush = Objects.requireNonNull(brush, "brush");
            return this;
        }

        public Builder style(CgTextStyle style) {
            this.style = Objects.requireNonNull(style, "style");
            return this;
        }

        /** A string id ({@code <ns>:text/<id>}, read in the player's language) or the literal text. */
        public Builder text(String text) {
            this.text = Objects.requireNonNull(text, "text");
            return this;
        }

        public Builder justification(Justification justification) {
            this.justification = Objects.requireNonNull(justification, "justification");
            return this;
        }

        /** Wraps its lines at {@code width} units; 0 for none. */
        public Builder wrap(float width) {
            this.wrap = width;
            return this;
        }

        /** A retainer box's effect: a UI material whose {@code textureParameter} sampler takes the retained image. */
        public Builder effect(CgMaterialDefinition effect, String textureParameter) {
            effect.requireDomain(CgMaterialDomain.UI, name + "'s effect");
            this.effect = effect;
            this.textureParameter = Objects.requireNonNull(textureParameter, "textureParameter");
            return this;
        }

        /** Adds a child after those added: later children of a box sit after, of a canvas draw over. */
        public Builder child(CgWidget child) {
            if (!kind.panel()) throw new IllegalArgumentException(name + ": a " + kind.id() + " holds no children");
            if ((kind == Kind.BORDER || kind == Kind.RETAINER_BOX) && !children.isEmpty()) {
                throw new IllegalArgumentException(name + ": a " + kind.id() + " holds one child");
            }
            children.add(Objects.requireNonNull(child, "child"));
            return this;
        }

        public Builder child(Builder child) {
            return child(child.build());
        }

        /**
         * The widget.
         *
         * @throws IllegalArgumentException a text block with no style, a shear outside -89 to 89, a negative wrap
         */
        public CgWidget build() {
            if (kind == Kind.TEXT_BLOCK && style == null) {
                throw new IllegalArgumentException(name + ": a text block needs a style");
            }
            if (Math.abs(transform[4]) > 89f || Math.abs(transform[5]) > 89f) {
                throw new IllegalArgumentException(name + ": shear is -89 to 89 degrees");
            }
            if (wrap < 0f) throw new IllegalArgumentException(name + ": wrap " + wrap + " is below 0");
            return new CgWidget(this);
        }
    }
}
