package com.crystalgraphics.loading;

import javax.annotation.Nullable;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontStyle;
import com.crystalgraphics.api.font.CgGenericFamily;
import com.crystalgraphics.api.font.CgSystemFontFace;
import com.crystalgraphics.api.font.CgSystemFonts;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.text.CgTextLayout;
import com.crystalgraphics.api.texture.CgMipmapConfig;
import com.crystalgraphics.api.texture.CgTextureSpec;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.api.texture.CgTextures;
import com.crystalgraphics.gl.render.CgQuadRenderer;
import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.render.CgImmediate;
import com.crystalgraphics.text.render.CgTextRenderer;

/**
 * What the styled loading views draw with: quads of {@code loading_fx.shader} in its modes, the logo with its light
 * sweep, and lines of text in the system's sans-serif. One frame is {@link #begin}, the parts in back-to-front order,
 * and nothing after. Render thread.
 *
 * <pre>{@code
 * canvas.begin(width, height);
 * canvas.quads().quad(0, 0, width, height, 0xFFFFFFFF, CgLoadingCanvas.BACKDROP).end();
 * float lockupWidth = canvas.hero(1f, 1f, width / 2f, height * 0.45f, height * 0.10f);
 * canvas.text(1, "Building pipelines", width / 2f, y, 18, 0x99FFFFFF, 0.5f);
 * }</pre>
 */
final class CgLoadingCanvas {

    static final String FX = "crystalgraphics:shaders/loading/loading_fx.shader";
    /** {@code loading_fx.shader}'s modes. */
    static final int BACKDROP = 0, GLOW = 1, IMAGE = 2, FLAT = 3, SPINNER = 4, BAR = 5;
    /**
     * The engine's lockup, its icon and "Crystal Graphics" beside it, shadows included: exported by the plan
     * repository's tools/logo/export_lockup.py, which {@link #ICON_SHARE} and {@link #SIDE_PAD} follow.
     */
    static final String LOCKUP = "crystalgraphics:textures/gui/lockup.png";
    /** The icon's height against the lockup image's, and each side's padding against its width (64 / 92, 14 / 486.8). */
    private static final float ICON_SHARE = 64f / 92f, SIDE_PAD = 14f / 486.8f;
    // A light halo lifts the lockup off the bright water behind it
    private static final int HERO_GLOW = 0xFFDDF2FF;
    // Drawn at about a quarter of its size: without mips its strokes alias
    private static final CgTextureSpec MIPMAPPED = CgTextureSpec.builder().type(CgTextureType.RGBA8)
            .mipmaps(CgMipmapConfig.TRILINEAR).build();

    private final String logoPath;
    private final long started = System.nanoTime();
    private final Line[] lines = {new Line(), new Line(), new Line(), new Line()};
    /** A renderer per batch in a frame: a renderer's quads live on the frame ring, so a second batch would overwrite them. */
    private final CgQuadRenderer[] batches = new CgQuadRenderer[3];
    private int nextBatch;
    @Nullable private CgQuadRenderer fx, image;
    @Nullable private CgTexture2D logo;
    @Nullable private CgTextRenderer text;
    private int width, height;
    private boolean noFont;
    private float seconds, aspect;

    CgLoadingCanvas(String logoPath) {
        this.logoPath = logoPath;
    }

    /** Seconds since the view first drew: what its animation runs on. */
    float seconds() {
        return seconds;
    }

    /** Starts a frame over {@code width} x {@code height} pixels, top left at 0, 0. */
    void begin(int width, int height) {
        if (image == null) {
            image = CgQuadRenderer.create().useMaterial(CgMaterial.load(FX));
            logo = CgTextures.of(logoPath, MIPMAPPED);
        }
        nextBatch = 0;
        seconds = (System.nanoTime() - started) / 1e9f;
        aspect = width / (float) height;
        this.width = width;
        this.height = height;
        CgImmediate.constants().view.identity();
        CgImmediate.constants().projection.setOrtho(0f, width, height, 0f, -1f, 1f);
    }

    /** Opens a batch of fx quads, at most three a frame; {@link #quad} into it, then {@link #end}. */
    CgLoadingCanvas quads() {
        if (batches[nextBatch] == null) batches[nextBatch] = CgQuadRenderer.create();
        fx = batches[nextBatch++];
        fx.useMaterial(CgMaterial.load(FX));
        fx.begin();
        return this;
    }

    CgLoadingCanvas quad(float x, float y, float w, float h, int argb, int mode) {
        fx.quad().at(x, y).size(w, h).uv(0f, 0f, 1f, 1f).color(argb).custom0(seconds, aspect, 0f, mode).submit();
        return this;
    }

    void end() {
        fx.flush();
        fx.end();
    }

    /**
     * The engine's look behind everything else: the backdrop at {@code alpha}, then the lockup, its icon {@code icon}
     * tall, centred on {@code cx}, {@code cy}, with a breathing halo and a light sweep, at {@code shown}. Returns the
     * lockup's visible width, its padding left out.
     */
    float hero(float alpha, float shown, float cx, float cy, float icon) {
        float h = icon / ICON_SHARE, w = h * logo.getWidth() / logo.getHeight();
        float glow = w * 1.3f, breath = 0.30f + 0.06f * (float) Math.sin(seconds * 1.6);
        quads().quad(0f, 0f, width, height, argb(alpha, 0xFFFFFFFF), BACKDROP)
                .quad(cx - glow / 2f, cy - glow / 2f, glow, glow, argb(shown * breath, HERO_GLOW), GLOW)
                .end();
        draw(image, logo, Math.round(cx - w / 2f), Math.round(cy - h / 2f), w, h, argb(shown, 0xFFFFFFFF), 1f);
        return w * (1f - 2f * SIDE_PAD);
    }

    /** The load bar, {@code w} x {@code h} with its top left at {@code x}, {@code y}, {@code fill} of it full (0 to 1). */
    void bar(float x, float y, float w, float h, float fill, float alpha) {
        quads();
        fx.quad().at(x - 1.5f * h, y - 1.5f * h).size(w + 3f * h, 4f * h).uv(0f, 0f, 1f, 1f).color(argb(alpha, 0xFFFFFFFF))
                .custom0(seconds, aspect, Math.min(1f, Math.max(0f, fill)), BAR).submit();
        end();
    }

    /** A turning ring, {@code size} square with its top left at {@code x}, {@code y}. */
    void spinner(float x, float y, float size, int argb) {
        quads().quad(x, y, size, size, argb, SPINNER).end();
    }

    /** {@code texture} over {@code w} x {@code h} at {@code x}, {@code y} through its own renderer, a light sweeping across it at {@code sweep} (0 for none). */
    private void draw(CgQuadRenderer renderer, CgTexture2D texture, float x, float y, float w, float h, int argb, float sweep) {
        renderer.useMaterial(CgMaterial.load(FX));
        renderer.bindTexture(0, texture);
        renderer.begin();
        renderer.quad().at(x, y).size(w, h).uv(0f, 0f, 1f, 1f).color(argb).custom0(seconds, aspect, sweep, IMAGE).submit();
        renderer.flush();
        renderer.end();
    }

    /**
     * Line {@code slot} (0 to 3, each keeping its layout until its text or size changes): {@code value} at {@code px}
     * pixels, its left edge at {@code x} less {@code anchor} of its width, its top at {@code y}. Nothing where the system
     * has no sans-serif.
     */
    void text(int slot, String value, float x, float y, int px, int argb, float anchor) {
        if (noFont || value.isEmpty()) return;
        Line line = lines[slot];
        if (line.font == null || line.px != px) {
            CgSystemFonts fonts = CgSystemFonts.get();
            CgSystemFontFace face = fonts.generic(CgGenericFamily.SANS_SERIF, CgFontStyle.REGULAR);
            if (face == null) {
                noFont = true;
                return;
            }
            line.font = fonts.load(face, CgFontStyle.REGULAR, px);
            line.px = px;
            line.layout = null;
        }
        if (line.layout == null || !value.equals(line.value)) {
            line.layout = CgTextLayout.of(value, line.font).build();
            line.value = value;
        }
        // Sized to the frame: the window size the lifecycle knows may still be 0 x 0 while loading
        if (text == null) text = CgTextRenderer.createManualSized();
        text.context().updateOrtho(width, height);
        // Glyphs on the nearest depth, so nothing the host left in its depth hides them
        text.context().projection().m22(-1.0e-4f).m32(CgGL.isDepthReversed() ? 1f : -1f);
        text.beginBatch();
        text.draw().layout(line.layout).font(line.font).at(Math.round(x - anchor * line.layout.totalWidth()), Math.round(y))
                .color(argb).submit();
        text.endBatch();
    }

    /** {@code argb} with its alpha times {@code alpha}. */
    static int argb(float alpha, int argb) {
        int a = Math.round((argb >>> 24) * Math.min(1f, Math.max(0f, alpha)));
        return a << 24 | argb & 0x00FFFFFF;
    }

    /**
     * What the screen says it is doing: the reason capitalised ("holding" and the fade-out as the last moment), then the
     * whole percent after {@code separator}. Built again only when either changes.
     */
    String status(@Nullable String reason, float progress, String separator) {
        int percent = Math.round(Math.min(1f, Math.max(0f, progress)) * 100f);
        if (percent == statusPercent && (reason == null ? statusReason == null : reason.equals(statusReason))) return status;
        statusReason = reason;
        statusPercent = percent;
        String what = reason == null || reason.equals("holding") ? "Almost ready"
                : Character.toUpperCase(reason.charAt(0)) + reason.substring(1);
        status = what + separator + percent + "%";
        return status;
    }

    @Nullable private String statusReason;
    private int statusPercent = -1;
    private String status = "";

    private static final class Line {
        @Nullable String value;
        @Nullable CgTextLayout layout;
        @Nullable CgFont font;
        int px;
    }
}
