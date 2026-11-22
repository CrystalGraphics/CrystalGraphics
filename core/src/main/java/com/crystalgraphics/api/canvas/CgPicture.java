package com.crystalgraphics.api.canvas;

import java.util.Objects;

import javax.annotation.Nullable;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialInstance;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.material.CgMaterialProxy;

/**
 * Something a canvas composites that another producer keeps drawing: a kept layer, an item's icon, an entity Minecraft
 * draws. The canvas asks for it at the size it shows it, and the producer keeps that size fresh. Chromium's surface.
 *
 * <pre>{@code
 * canvas.picture(WINDOW, x, y, 320, 200);       // a kept layer
 * canvas.picture(radar, x, y, 128, 128);        // a CgCustomPicture Minecraft draws
 * }</pre>
 *
 * <p>Implementing one:</p>
 *
 * <pre>{@code
 * public final class Clock implements CgPicture {
 *     private volatile CgPicture.Image image;   // replaced when the texture or region changes, never per frame
 *     private volatile long version;
 *     public CgPicture.Image image(int deviceWidth, int deviceHeight, float scale) {
 *         wanted(deviceWidth, deviceHeight);     // drawn at that size by its producer, from the next frame
 *         return image;                          // null: nothing to show yet
 *     }
 *     public long version() { return version; }
 * }
 * }</pre>
 *
 * <ul>
 *   <li>{@link #image} is the one call a canvas makes, in the frame it records the picture, on the recording's thread:
 *       it means "shown, at this size". Answer fast and allocate nothing.</li>
 *   <li>A picture is premultiplied: Mojang's GUI blits its own pictures so ({@code GUI_TEXTURED_PREMULTIPLIED_ALPHA}).</li>
 *   <li>{@link #version} moves whenever the pixels do: a kept layer holding the picture walks again on it.</li>
 *   <li>A picture that knows its own size (a tooltip) answers {@link #naturalWidth}; {@code canvas.picture(p, x, y)}
 *       shows it at that size.</li>
 * </ul>
 */
public interface CgPicture {

    /**
     * The picture to show at {@code deviceWidth} by {@code deviceHeight} pixels, {@code scale} pixels a unit; null while
     * it holds nothing. Marks it shown at that size, so its producer keeps that size fresh.
     */
    @Nullable
    Image image(int deviceWidth, int deviceHeight, float scale);

    /** Moves whenever the picture's pixels change. */
    long version();

    /** Its own width in units, where it has one (a tooltip); 0 when it takes the rect it is shown in. */
    default float naturalWidth() {
        return 0f;
    }

    /** @see #naturalWidth */
    default float naturalHeight() {
        return 0f;
    }

    /**
     * A texture and the region of it the picture covers. Immutable: a producer makes one when its texture or region
     * changes, not per frame.
     *
     * <pre>{@code
     * Image.of(texture, 0f, 1f, 1f, 0f);                    // the whole of a bottom-up (GL) texture
     * Image.matted(overBlack, overWhite, 0f, 1f, 1f, 0f);   // drawn twice by a GUI that writes alpha raw
     * Image.of(texture, 0f, 1f, 1f, 0f).outset(0f, 0f, 0.125f, 0.125f);   // reaches 2 units past a 16-unit rect
     * }</pre>
     */
    final class Image {

        /** Minecraft's missing texture: what a picture that failed to draw shows. */
        public static final Image MISSING = new Image(null, null, 0f, 0f, 1f, 1f);

        private static final String MATTE = "crystalgraphics:shaders/ui/picture_matte.shader";

        @Nullable final CgTexture texture;
        @Nullable final CgTexture white;
        final float u0, v0, u1, v1;
        /** How far the region reaches past the rect it is shown in, each side, as fractions of the rect's size. */
        final float left, top, right, bottom;
        /** Where {@link #white} is set, the material that takes alpha from the two drawings. */
        @Nullable final CgMaterialInstance matte;

        private Image(@Nullable CgTexture texture, @Nullable CgTexture white, float u0, float v0, float u1, float v1) {
            this.texture = texture;
            this.white = white;
            this.u0 = u0;
            this.v0 = v0;
            this.u1 = u1;
            this.v1 = v1;
            this.left = this.top = this.right = this.bottom = 0f;
            this.matte = white == null ? null : matte(white);
        }

        private Image(Image of, float left, float top, float right, float bottom) {
            this.texture = of.texture;
            this.white = of.white;
            this.u0 = of.u0;
            this.v0 = of.v0;
            this.u1 = of.u1;
            this.v1 = of.v1;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.matte = of.matte;
        }

        /**
         * This region drawn past the rect it is shown in, each side a fraction of the rect's size: what Minecraft draws
         * outside the box it was given (a slot's count text, a tooltip mod's frame). Chromium's ink overflow.
         */
        public Image outset(float left, float top, float right, float bottom) {
            return new Image(this, left, top, right, bottom);
        }

        // _MainTex is the box's fill, bound by the element; _White is the material's own sampler, at its own unit
        private static CgMaterialInstance matte(CgTexture white) {
            CgMaterialInstance m = CgMaterial.load(MATTE).instance();
            return m.sampler("_White", CgMaterialProxy.of(m).samplerUnit("_White"), white);
        }

        /** {@code texture}'s region {@code (u0, v0)} to {@code (u1, v1)}, premultiplied; v1 below v0 for a bottom-up texture. */
        public static Image of(CgTexture texture, float u0, float v0, float u1, float v1) {
            return new Image(Objects.requireNonNull(texture, "texture"), null, u0, v0, u1, v1);
        }

        /**
         * The same picture drawn over black and over white, in the same layout: alpha is what the white let through and
         * the colour is what the black kept. For a host whose GUI writes alpha raw (every immediate-mode GUI, Minecraft
         * 1.7.10 to 1.21.5): exact for opaque, blended, additive and unblended draws alike. Render thread: it makes a
         * material instance.
         */
        public static Image matted(CgTexture overBlack, CgTexture overWhite, float u0, float v0, float u1, float v1) {
            return new Image(Objects.requireNonNull(overBlack, "overBlack"), Objects.requireNonNull(overWhite, "overWhite"),
                    u0, v0, u1, v1);
        }

        /** The texture shown, over black where {@link #matted}; null for {@link #MISSING}. */
        @Nullable
        public CgTexture texture() {
            return texture;
        }

        /**
         * The region of {@link #texture} shown, as texture coordinates: a picture on a shared page is a rect of it.
         * {@code v0} is the top edge as shown, so it is the larger where the texture's rows run bottom-up.
         */
        public float u0() {
            return u0;
        }

        /** @see #u0 */
        public float v0() {
            return v0;
        }

        /** @see #u0 */
        public float u1() {
            return u1;
        }

        /** @see #u0 */
        public float v1() {
            return v1;
        }
    }
}
