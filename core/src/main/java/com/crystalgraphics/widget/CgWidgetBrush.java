package com.crystalgraphics.widget;

import java.util.Objects;

import javax.annotation.Nullable;

import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.api.material.CgMaterialDomain;

/**
 * What an image or a border's background draws: Slate's {@code FSlateBrush}. A texture or a UI material stretched
 * over the widget, tinted; its image size is an image's desired size.
 *
 * <pre>{@code
 * CgWidgetBrush banner = CgWidgetBrush.texture("kk:textures/hud/damage_number/base_critical.png", 224f, 56f);
 * CgWidgetBrush tinted = banner.tint(CgLinearColour.argb(0xFFFF8000));
 * CgWidgetBrush shaded = CgWidgetBrush.material(BANNER_MATERIAL, 224f, 56f);   // a UI-domain material
 * }</pre>
 *
 * <pre>{@code
 * # as data: an image's brush, a UI material's, and a background that draws nothing
 * brush: { texture: kk:textures/hud/damage_number/base_critical.png, image_size: [224, 56] }
 * brush: { material: kk:materials/hud/damage_number/banner, image_size: [224, 56] }
 * background: { draw_as: none }
 * }</pre>
 *
 * <ul>
 *   <li>{@link #WHITE}, a brush with neither texture nor material, still draws: an untextured box in its tint, as
 *       Slate draws one. A border's background is this unless set; {@link #NONE} draws nothing.</li>
 *   <li>A material must be of the UI domain: another fails here, naming it.</li>
 * </ul>
 *
 * @param texture  its texture, as {@code CgTextures.of} reads it, or null
 * @param material its UI material, or null
 * @param width    its image size, in widget units
 * @param height   its image size, in widget units
 * @param tint     multiplied into what draws it
 * @param draws    false for Slate's {@code NoDrawType}: nothing drawn, its size still desired
 */
public record CgWidgetBrush(@Nullable String texture, @Nullable CgMaterialDefinition material, float width,
                            float height, CgLinearColour tint, boolean draws) {

    /** Slate's default brush: nothing bound, 32 x 32, white. It draws an untextured white box. */
    public static final CgWidgetBrush WHITE = new CgWidgetBrush(null, null, 32f, 32f, CgLinearColour.WHITE, true);

    /** Draws nothing, at Slate's default size. */
    public static final CgWidgetBrush NONE = new CgWidgetBrush(null, null, 32f, 32f, CgLinearColour.WHITE, false);

    public CgWidgetBrush {
        Objects.requireNonNull(tint, "tint");
        if (texture != null && material != null) throw new IllegalArgumentException("a brush is a texture or a material");
        if (material != null) material.requireDomain(CgMaterialDomain.UI, "a widget brush");
    }

    /** {@code texture} at {@code width} x {@code height} units, untinted. */
    public static CgWidgetBrush texture(String texture, float width, float height) {
        return new CgWidgetBrush(Objects.requireNonNull(texture, "texture"), null, width, height, CgLinearColour.WHITE,
                true);
    }

    /** A UI material drawn over {@code width} x {@code height} units, untinted. */
    public static CgWidgetBrush material(CgMaterialDefinition material, float width, float height) {
        return new CgWidgetBrush(null, Objects.requireNonNull(material, "material"), width, height, CgLinearColour.WHITE,
                true);
    }

    /** This brush tinted {@code tint}. */
    public CgWidgetBrush tint(CgLinearColour tint) {
        return new CgWidgetBrush(texture, material, width, height, tint, draws);
    }

    /** This brush at {@code width} x {@code height} units. */
    public CgWidgetBrush size(float width, float height) {
        return new CgWidgetBrush(texture, material, width, height, tint, draws);
    }
}
