package com.crystalgraphics.loading;

import javax.annotation.Nullable;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.texture.CgMipmapConfig;
import com.crystalgraphics.api.texture.CgTextureSpec;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.api.texture.CgTextures;
import com.crystalgraphics.gl.render.CgQuadRenderer;
import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgraphics.render.CgImmediate;

/**
 * The engine's loading screen: its lockup (the logo and "Crystal Graphics") over a view up at the ocean's surface from
 * below, with a breathing halo and a light sweeping across it, and Minecraft's white outlined load bar under it,
 * spanning it. It eases in, and eases out a little larger as the screen fades.
 *
 * <pre>{@code
 * // the default; a mod's own lockup on the same look:
 * CgLoadingScreen.view(new CgLogoLoadingView("mymod:textures/gui/lockup.png"));
 * CgLoadingScreen.warm(width, height);   // after replacing the view at the graphics start: its texture loads now
 * }</pre>
 *
 * A mod's lockup is laid out as the engine's: its icon 64 / 92 of the image's height, 14 / 486.8 of its width padding
 * each side (the plan repository's tools/logo/export_lockup.py makes one).
 */
public final class CgLogoLoadingView implements CgLoadingView {

    /** The engine's lockup, its shadows included, exported by tools/logo/export_lockup.py. */
    public static final String LOCKUP = "crystalgraphics:textures/gui/lockup.png";
    static final String FX = "crystalgraphics:shaders/loading/loading_fx.shader";
    /** {@code loading_fx.shader}'s modes. */
    private static final int BACKDROP = 0, GLOW = 1, IMAGE = 2, BAR = 3;
    /** The icon's height against the lockup image's, and each side's padding against its width. */
    private static final float ICON_SHARE = 64f / 92f, SIDE_PAD = 14f / 486.8f;
    // A light halo lifts the lockup off the bright water behind it
    private static final int HALO = 0xFFDDF2FF;
    // Drawn at about a quarter of its size: without mips its strokes alias
    private static final CgTextureSpec MIPMAPPED = CgTextureSpec.builder().type(CgTextureType.RGBA8)
            .mipmaps(CgMipmapConfig.TRILINEAR).build();

    private final String lockupPath;
    private final long started = System.nanoTime();
    /** A renderer per batch: a renderer's quads live on the frame ring, so a second batch in one would overwrite them. */
    @Nullable private CgQuadRenderer back, bar, image;
    @Nullable private CgTexture2D lockup;
    private float seconds, aspect;

    /** The engine's lockup. */
    public CgLogoLoadingView() {
        this(LOCKUP);
    }

    /** {@code lockup}, an icon and wordmark image's path, on the engine's look. */
    public CgLogoLoadingView(String lockup) {
        this.lockupPath = lockup;
    }

    @Override
    public void draw(int width, int height, float progress, float alpha, @Nullable String reason) {
        if (width <= 0 || height <= 0) return;
        if (back == null) {
            back = CgQuadRenderer.create();
            bar = CgQuadRenderer.create();
            image = CgQuadRenderer.create();
        }
        seconds = (System.nanoTime() - started) / 1e9f;
        aspect = width / (float) height;
        CgImmediate.constants().view.identity();
        CgImmediate.constants().projection.setOrtho(0f, width, height, 0f, -1f, 1f);

        float intro = smooth(Math.min(1f, seconds / 0.9f)), shown = intro * alpha;
        // 4% larger as it fades out, as a game's splash leaves for its menu; settling from 3% larger as it comes in
        float grow = 1f + 0.04f * (1f - alpha) + 0.03f * (1f - intro);
        float cx = width / 2f, cy = height * 0.47f, icon = Math.min(width, height) * 0.10f;

        // The lockup, centred, its image padded around the icon's height
        CgTexture2D texture = texture();
        float h = icon * grow / ICON_SHARE, w = h * texture.getWidth() / texture.getHeight();
        float glow = w * 1.3f, breath = 0.30f + 0.06f * (float) Math.sin(seconds * 1.6);
        begin(back);
        quad(back, 0f, 0f, width, height, argb(alpha, 0xFFFFFFFF), 0f, BACKDROP);
        quad(back, cx - glow / 2f, cy - glow / 2f, glow, glow, argb(shown * breath, HALO), 0f, GLOW);
        end(back);
        image.bindTexture(0, texture);
        begin(image);
        quad(image, Math.round(cx - w / 2f), Math.round(cy - h / 2f), w, h, argb(shown, 0xFFFFFFFF), 1f, IMAGE);
        end(image);

        // The bar spans the lockup a third of the icon below it, so the two read as one block
        float barWidth = Math.round(w * (1f - 2f * SIDE_PAD)), barX = Math.round(cx - barWidth / 2f);
        float barHeight = Math.max(8f, Math.round(height / 60f)), barY = Math.round(cy + icon * 0.86f);
        begin(bar);
        // The quad is 4 bars tall and 3 wider, the shader's room around the pill
        quad(bar, barX - 1.5f * barHeight, barY - 1.5f * barHeight, barWidth + 3f * barHeight, 4f * barHeight,
                argb(shown, 0xFFFFFFFF), Math.min(1f, Math.max(0f, progress)), BAR);
        end(bar);
    }

    /** Loads the lockup now, at the graphics start, rather than at the first frame. */
    @Override
    public void warm(int width, int height) {
        texture().getWidth();
    }

    private CgTexture2D texture() {
        if (lockup == null) lockup = CgTextures.of(lockupPath, MIPMAPPED);
        return lockup;
    }

    private static void begin(CgQuadRenderer renderer) {
        renderer.useMaterial(CgMaterial.load(FX));
        renderer.begin();
    }

    private static void end(CgQuadRenderer renderer) {
        renderer.flush();
        renderer.end();
    }

    /** A quad of {@code loading_fx.shader} in {@code mode}, {@code param} its per-mode value (the sweep, the fill). */
    private void quad(CgQuadRenderer renderer, float x, float y, float w, float h, int argb, float param, int mode) {
        renderer.quad().at(x, y).size(w, h).uv(0f, 0f, 1f, 1f).color(argb).custom0(seconds, aspect, param, mode).submit();
    }

    private static float smooth(float x) {
        return x * x * (3f - 2f * x);
    }

    /** {@code argb} with its alpha times {@code alpha}. */
    private static int argb(float alpha, int argb) {
        int a = Math.round((argb >>> 24) * Math.min(1f, Math.max(0f, alpha)));
        return a << 24 | argb & 0x00FFFFFF;
    }
}
