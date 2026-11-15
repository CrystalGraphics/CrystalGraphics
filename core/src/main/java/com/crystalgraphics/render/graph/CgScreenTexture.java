package com.crystalgraphics.render.graph;

/**
 * A graph texture and the size of the picture in it: what a post effect takes and answers (Unreal's
 * {@code FScreenPassTexture}). One per texture, kept on it, so passing one along allocates nothing.
 *
 * <pre>{@code
 * CgPost.client().add(CgPostPoint.HDR, (ctx, input) -> {
 *     CgScreenTexture out = ctx.texture("mymod.outline", input);   // input's size and format
 *     return ctx.screen(outline).into(out);
 * });
 * CgScreenTexture mine = CgScreenTexture.of(myTexture);             // a texture of your own, its own size
 * }</pre>
 *
 * <ul>
 *   <li>Render thread.</li>
 *   <li>The host's target knows no size until it executes: the post chain gives it the view's.</li>
 * </ul>
 */
public final class CgScreenTexture {

    private final CgGraphTexture texture;
    private int width, height;

    private CgScreenTexture(CgGraphTexture texture) {
        this.texture = texture;
    }

    /** {@code texture}'s screen value, at its own size; a target that knows none keeps the size it was last given. */
    public static CgScreenTexture of(CgGraphTexture texture) {
        CgScreenTexture screen = texture.screen;
        if (screen == null) texture.screen = screen = new CgScreenTexture(texture);
        if (texture.getWidth() > 0) {
            screen.width = texture.getWidth();
            screen.height = texture.getHeight();
        }
        return screen;
    }

    /** Engine side: {@code texture}'s screen value at {@code width} x {@code height}, for a target that knows no size. */
    public static CgScreenTexture sized(CgGraphTexture texture, int width, int height) {
        CgScreenTexture screen = of(texture);
        screen.width = width;
        screen.height = height;
        return screen;
    }

    public CgGraphTexture texture() {
        return texture;
    }

    /** The picture's width, in texels. */
    public int width() {
        return width;
    }

    /** The picture's height, in texels. */
    public int height() {
        return height;
    }

    @Override
    public String toString() {
        return texture + " " + width + "x" + height;
    }
}
