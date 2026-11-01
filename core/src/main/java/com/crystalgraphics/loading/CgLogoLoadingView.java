package com.crystalgraphics.loading;

import javax.annotation.Nullable;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.texture.CgTextures;
import com.crystalgraphics.gl.render.CgQuadRenderer;
import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgraphics.render.CgImmediate;

/**
 * The engine's loading screen: its logo centred over a load bar, laid out as Minecraft's loading overlay lays out its
 * own (the logo a third of the height above the middle, an outlined bar under it a little under half the width).
 *
 * <pre>{@code
 * CgLoadingScreen.view(new CgLogoLoadingView(0xFF101828, "mymod:textures/gui/splash.png"));   // a mod's colours and logo
 * }</pre>
 */
public final class CgLogoLoadingView implements CgLoadingView {

    /** The engine's logo, beside its shaders. */
    public static final String LOGO = "crystalgraphics:textures/gui/logo.png";
    /** A deep indigo, the dark end of the logo's own gradient. */
    public static final int BACKGROUND = 0xFF15142C;
    private static final String IMAGE = "crystalgraphics:shaders/hud_image.shader";

    private final int background;
    private final String logoPath;
    private CgQuadRenderer solid, logo;
    private CgTexture2D logoTexture;

    /** The engine's logo on its indigo. */
    public CgLogoLoadingView() {
        this(BACKGROUND, LOGO);
    }

    /** {@code logo} (a square image's path) on {@code background} (0xAARRGGBB). */
    public CgLogoLoadingView(int background, String logo) {
        this.background = background;
        this.logoPath = logo;
    }

    @Override
    public void draw(int width, int height, float progress, float alpha, @Nullable String reason) {
        if (width <= 0 || height <= 0) return;
        if (solid == null) {
            solid = CgQuadRenderer.create().useMaterial(CgMaterial.load(IMAGE));
            logo = CgQuadRenderer.create().useMaterial(CgMaterial.load(IMAGE));
            logoTexture = CgTextures.of(logoPath);
        }
        CgImmediate.constants().view.identity();
        CgImmediate.constants().projection.setOrtho(0f, width, height, 0f, -1f, 1f);

        float size = Math.min(width, height) / 3f;
        float barWidth = Math.min(width * 0.47f, height * 1.2f), barHeight = Math.max(6f, Math.round(height / 40f));
        float line = Math.max(1f, Math.round(barHeight / 5f));
        float barX = Math.round((width - barWidth) / 2f), barY = Math.round(height * 0.76f);
        int white = argb(alpha, 0xFFFFFFFF);

        solid.useMaterial(CgMaterial.load(IMAGE));
        solid.begin();
        solid.quad().at(0f, 0f).size(width, height).uv(0f, 0f, 1f, 1f).color(argb(alpha, background)).submit();
        // The outline as four strips, then the fill inset by one line's gap.
        solid.quad().at(barX, barY).size(barWidth, line).uv(0f, 0f, 1f, 1f).color(white).submit();
        solid.quad().at(barX, barY + barHeight - line).size(barWidth, line).uv(0f, 0f, 1f, 1f).color(white).submit();
        solid.quad().at(barX, barY).size(line, barHeight).uv(0f, 0f, 1f, 1f).color(white).submit();
        solid.quad().at(barX + barWidth - line, barY).size(line, barHeight).uv(0f, 0f, 1f, 1f).color(white).submit();
        float inner = barWidth - 4f * line, fill = Math.round(inner * Math.min(1f, Math.max(0f, progress)));
        if (fill > 0f) {
            solid.quad().at(barX + 2f * line, barY + 2f * line).size(fill, barHeight - 4f * line).uv(0f, 0f, 1f, 1f)
                    .color(white).submit();
        }
        solid.flush();
        solid.end();

        logo.useMaterial(CgMaterial.load(IMAGE));
        logo.bindTexture(0, logoTexture);
        logo.begin();
        logo.quad().at(Math.round((width - size) / 2f), Math.round(height * 0.45f - size / 2f)).size(size, size)
                .uv(0f, 0f, 1f, 1f).color(white).submit();
        logo.flush();
        logo.end();
    }

    /** {@code argb} with its alpha times {@code alpha}. */
    private static int argb(float alpha, int argb) {
        int a = Math.round((argb >>> 24) * Math.min(1f, Math.max(0f, alpha)));
        return a << 24 | argb & 0x00FFFFFF;
    }
}
