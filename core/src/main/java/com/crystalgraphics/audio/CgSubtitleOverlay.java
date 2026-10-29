package com.crystalgraphics.audio;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontStyle;
import com.crystalgraphics.api.font.CgGenericFamily;
import com.crystalgraphics.api.font.CgSystemFontFace;
import com.crystalgraphics.api.font.CgSystemFonts;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.text.CgTextAlign;
import com.crystalgraphics.api.text.CgTextLayout;
import com.crystalgraphics.api.texture.CgTextures;
import com.crystalgraphics.gl.render.CgQuadRenderer;
import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.render.CgFrameClock;
import com.crystalgraphics.render.CgImmediate;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.settings.CgGraphicsSettings;
import com.crystalgraphics.text.render.CgTextRenderer;
import com.crystalgraphics.util.io.CgIO;

import javax.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The shown subtitle over the HUD. A line whose game ships a {@link CgSubtitleStyle} is drawn in it, as its UMG widget
 * draws: Slate text blocks in canvas slots at 96 DPI, scaled with the screen's height, an outer outline
 * ({@code FSlateFontRenderer}'s round-joined stroke), a band behind where it has one, the speaker's name over an
 * underline, faded and slid in and out on the style's keys, one line at a time. Any other line is drawn in Unreal's canvas look
 * ({@code FSubtitleManager::DisplaySubtitle}): 13 px (Roboto's 10 pt), white, a 1 px black outline drawn as four passes
 * at the diagonals ({@code FCanvasTextItem::bOutlined}), centred in the region a viewport gives (the bottom 90% of the
 * screen, trimmed to the 10-90% safe zone), a single line's top 24 px above its bottom and lines 1.1 apart.
 */
final class CgSubtitleOverlay {

    private static final Logger LOGGER = LogManager.getLogger("CgAudio");
    /** Over everything else on the HUD. */
    private static final int ORDER = 10_000;
    /** Unreal's subtitle font, Roboto at its LegacyFontSize of 10 pt, at Slate's 96 DPI: a fixed pixel size. */
    private static final int FONT_PX = Math.round(10f * 96f / 72f);
    /** {@code GetSubtitleRegion}'s bottom, the safe zone's sides, {@code SUBTITLE_CHAR_HEIGHT} and the line spacing. */
    private static final float REGION_BOTTOM = 0.9f, SAFE_SIDE = 0.1f, CHAR_HEIGHT = 24f, LINE_SPACING = 1.1f;
    private static final int TEXT = 0xFFFFFFFF, OUTLINE = 0xFF000000;
    private static final String IMAGE = "crystalgraphics:shaders/hud_image.shader";

    /** A line as shown: what it says, by whom, in which look, and from when. */
    private static final class Shown {
        @Nullable
        String text, speaker;
        @Nullable
        CgSubtitleStyle style;
        double at;

        void set(@Nullable String text, @Nullable String speaker, @Nullable CgSubtitleStyle style, double at) {
            this.text = text;
            this.speaker = speaker;
            this.style = style;
            this.at = at;
        }
    }

    /** A text block's layout, kept while what it lays out holds. */
    private static final class Laid {
        @Nullable
        CgTextLayout layout;
        @Nullable
        CgFont font;
        @Nullable
        String text;
        float width;
    }

    private final CgAudioEngine engine;
    private final CgRenderStage.Registration registration;
    private final Runnable draw = this::draw;
    private final Shown current = new Shown(), leaving = new Shown();
    private final Laid canvas = new Laid(), line = new Laid(), name = new Laid();
    private final Map<String, CgFont> fonts = new HashMap<>();
    @Nullable
    private CgTextRenderer renderer;
    /** The band: a quad of the image material's default white. */
    @Nullable
    private CgQuadRenderer bands;
    @Nullable
    private CgQuadRenderer images;
    @Nullable
    private CgFont canvasFont;
    private double now;
    private int width, height, drawn;
    private boolean failed;

    CgSubtitleOverlay(CgAudioEngine engine) {
        this.engine = engine;
        registration = CgRenderStage.HUD.register(ORDER, frame -> {
            if (failed || !CgGraphicsSettings.SUBTITLES.get()) return;
            now = CgFrameClock.seconds();
            step(engine.subtitles().text(), engine.subtitles().speaker(), engine.subtitles().style());
            if (current.text == null && leaving.text == null) return;
            width = frame.host().width();
            height = frame.host().height();
            frame.callback("audio.subtitles", draw);
        });
    }

    /** A styled line that changed fades out, and the next fades in once it has. */
    private void step(@Nullable String text, @Nullable String speaker, @Nullable CgSubtitleStyle style) {
        if (leaving.text != null && now - leaving.at > leaving.style.fadeOut().seconds()) leaving.set(null, null, null, 0);
        if (Objects.equals(text, current.text) && Objects.equals(speaker, current.speaker) && style == current.style) return;
        double start = now;
        if (current.text != null && current.style != null) {
            leaving.set(current.text, current.speaker, current.style, now);
            start += current.style.fadeOut().seconds();
        }
        current.set(text, speaker, style, start);
    }

    /** Frames it has drawn a line. */
    int drawn() {
        return drawn;
    }

    void close() {
        registration.close();
        if (renderer != null && !renderer.isDeleted()) renderer.delete();
        renderer = null;
        if (bands != null) bands.delete();
        if (images != null) images.delete();
        bands = null;
        images = null;
    }

    private void draw() {
        try {
            if (renderer == null) renderer = CgTextRenderer.create();
            // Glyphs at z = 0 on the nearest depth (+1 reversed, -1 standard), so they pass against whatever the HUD
            // left in the host's depth; the depth row stays non-zero, or the culler's far plane culls every glyph.
            renderer.context().projection().m22(-1.0e-4f).m32(CgGL.isDepthReversed() ? 1f : -1f);
            if (leaving.text != null) styled(leaving, leaving.style.fadeOut(), (float) (now - leaving.at));
            if (current.text != null) {
                if (current.style == null) canvas(current.text);
                else if (now >= current.at) styled(current, current.style.fadeIn(), (float) (now - current.at));
            }
            drawn++;
        } catch (RuntimeException e) {
            failed = true;
            LOGGER.error("[CgAudio] the subtitles failed to draw; they stay off", e);
        }
    }

    private void canvas(String text) {
        if (canvasFont == null) {
            canvasFont = sansSerif(FONT_PX);
            if (canvasFont == null) {
                failed = true;
                LOGGER.warn("[CgAudio] no sans-serif font installed: no subtitles");
                return;
            }
        }
        float region = Math.round(width * (1f - 2f * SAFE_SIDE));
        if (canvas.layout == null || region != canvas.width || !text.equals(canvas.text)) {
            canvas.layout = CgTextLayout.of(text, canvasFont).maxWidth(region).align(CgTextAlign.CENTER)
                    .lineHeightOverride(canvasFont.getMetrics().getLineHeight() * LINE_SPACING).build();
            canvas.text = text;
            canvas.width = region;
        }
        float bottom = height * REGION_BOTTOM - CHAR_HEIGHT;
        renderer.beginBatch();
        renderer.draw().layout(canvas.layout).font(canvasFont).at(width * SAFE_SIDE, bottom - canvas.layout.totalHeight())
                .color(TEXT).shadowCount(4)
                .shadow(0, -1f, -1f, 0f, 0f, OUTLINE, false).shadow(1, 1f, -1f, 0f, 0f, OUTLINE, false)
                .shadow(2, -1f, 1f, 0f, 0f, OUTLINE, false).shadow(3, 1f, 1f, 0f, 0f, OUTLINE, false).submit();
        renderer.endBatch();
    }

    /** {@code shown} in its style, {@code t} seconds into {@code fade}. */
    private void styled(Shown shown, CgSubtitleStyle.Fade fade, float t) {
        CgSubtitleStyle style = shown.style;
        float k = style.scale(height);
        CgImmediate.constants().view.identity();
        CgImmediate.constants().projection.set(renderer.context().projection());
        CgSubtitleStyle.Band band = style.band();
        if (band != null) {
            float alpha = fade.band(t) * (band.colour() >>> 24) / 255f;
            float centre = band.anchorY() * height + band.offsetY() * k, half = band.height() * k / 2f;
            if (bands == null) bands = CgQuadRenderer.create().useMaterial(CgMaterial.load(IMAGE));
            bands.begin();
            bands.quad().at(0f, centre - half).size(width, 2f * half).uv(0f, 0f, 1f, 1f)
                    .color(argb(alpha, encode(band.colour()))).submit();
            bands.flush();
            bands.end();
        }
        float alpha = fade.text(t), slide = fade.slide(t) * k;
        renderer.beginBatch();
        text(style.line(), shown.text, line, k, alpha, slide);
        CgSubtitleStyle.Text named = style.name();
        if (named != null && shown.speaker != null) text(named, shown.speaker, name, k, alpha, slide);
        renderer.endBatch();
        if (named != null && shown.speaker != null && named.underline() != null) underline(named, k, alpha, slide);
    }

    /** One Slate text block: placed by its slot's anchor, offset and alignment of its laid-out size, then {@code slide} px along x. */
    private void text(CgSubtitleStyle.Text block, String text, Laid laid, float k, float alpha, float slide) {
        int px = Math.max(1, Math.round(block.size() * 96f / 72f * k));
        CgFont font = font(block.font(), px);
        float wrap = block.wrap() > 0f ? block.wrap() * k : width;
        if (laid.layout == null || laid.font != font || laid.width != wrap || !text.equals(laid.text)) {
            CgTextAlign align = switch (block.justify()) {
                case "centre" -> CgTextAlign.CENTER;
                case "right" -> CgTextAlign.RIGHT;
                default -> CgTextAlign.LEFT;
            };
            laid.layout = CgTextLayout.of(text, font).maxWidth(wrap).align(align)
                    .lineHeightOverride(font.getMetrics().getLineHeight() * block.lineHeight()).build();
            laid.font = font;
            laid.text = text;
            laid.width = wrap;
        }
        CgTextLayout layout = laid.layout;
        // Lines are justified across the wrap width; the box is the widest line's.
        float inset = switch (block.justify()) {
            case "centre" -> (wrap - layout.totalWidth()) / 2f;
            case "right" -> wrap - layout.totalWidth();
            default -> 0f;
        };
        float x = block.anchorX() * width + block.offsetX() * k - block.alignmentX() * layout.totalWidth() - inset + slide;
        float y = block.anchorY() * height + block.offsetY() * k - block.alignmentY() * layout.totalHeight();
        CgTextRenderer.Draw draw = renderer.draw().layout(layout).font(font).at(x, y).color(argb(alpha, block.colour()));
        float outline = Math.round(block.outlineSize() * k);
        if (outline > 0f) draw.shadowCount(1).shadow(0, 0f, 0f, 0f, outline, argb(alpha, block.outlineColour()), false);
        draw.submit();
    }

    private void underline(CgSubtitleStyle.Text named, float k, float alpha, float slide) {
        CgSubtitleStyle.Underline u = named.underline();
        CgTexture2D image = CgTextures.of(u.image());
        if (images == null) images = CgQuadRenderer.create().useMaterial(CgMaterial.load(IMAGE));
        images.bindTexture(0, image);
        images.begin();
        images.quad().at(named.anchorX() * width + u.offsetX() * k + slide, named.anchorY() * height + u.offsetY() * k)
                .size(u.width() * k, u.height() * k).uv(0f, 0f, 1f, 1f).color(argb(alpha, 0xFFFFFFFF)).submit();
        images.flush();
        images.end();
    }

    /** {@code path}'s font at {@code px}: a kit's file, read once. */
    private CgFont font(String path, int px) {
        String key = path + '@' + px;
        CgFont font = fonts.get(key);
        if (font != null) return font;
        try (InputStream in = CgIO.openStream(path)) {
            if (in == null) throw new IllegalStateException("no font file " + path);
            font = CgFont.load(in.readAllBytes(), path, CgFontStyle.REGULAR, px);
        } catch (IOException e) {
            throw new IllegalStateException("font " + path + ": " + e.getMessage(), e);
        }
        fonts.put(key, font);
        return font;
    }

    /** {@code argb} with its alpha times {@code alpha}. */
    private static int argb(float alpha, int argb) {
        int a = Math.round(Math.max(0f, Math.min(1f, alpha)) * (argb >>> 24));
        return a << 24 | argb & 0xFFFFFF;
    }

    /** A linear tint's colour in sRGB, as the HUD's target holds it; its alpha kept. */
    private static int encode(int linear) {
        return linear & 0xFF000000 | srgb(linear >>> 16 & 0xFF) << 16 | srgb(linear >>> 8 & 0xFF) << 8 | srgb(linear & 0xFF);
    }

    private static int srgb(int c) {
        double v = c / 255.0;
        double s = v <= 0.0031308 ? 12.92 * v : 1.055 * Math.pow(v, 1 / 2.4) - 0.055;
        return (int) Math.round(s * 255.0);
    }

    @Nullable
    private static CgFont sansSerif(int px) {
        CgSystemFonts fonts = CgSystemFonts.get();
        CgSystemFontFace face = fonts.generic(CgGenericFamily.SANS_SERIF, CgFontStyle.REGULAR);
        return face != null ? fonts.load(face, CgFontStyle.REGULAR, px) : null;
    }
}
