package com.crystalgraphics.audio;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontStyle;
import com.crystalgraphics.api.font.CgGenericFamily;
import com.crystalgraphics.api.font.CgSystemFontFace;
import com.crystalgraphics.api.font.CgSystemFonts;
import com.crystalgraphics.api.text.CgTextAlign;
import com.crystalgraphics.api.text.CgTextLayout;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.settings.CgGraphicsSettings;
import com.crystalgraphics.text.render.CgTextRenderer;

import javax.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The shown subtitle over the HUD, in Unreal's canvas look ({@code FSubtitleManager::DisplaySubtitle}): 13 px (Roboto's
 * 10 pt), white, a 1 px
 * black outline drawn as four passes at the diagonals ({@code FCanvasTextItem::bOutlined}), centred in the region a
 * viewport gives (the bottom 90% of the screen, trimmed to the 10-90% safe zone), a single line's top 24 px above its
 * bottom and lines 1.1 apart. Laid out again only when the line or the screen changes.
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

    private final CgAudioEngine engine;
    private final CgRenderStage.Registration registration;
    private final Runnable draw = this::draw;
    @Nullable
    private CgTextRenderer renderer;
    @Nullable
    private CgFont font;
    @Nullable
    private CgTextLayout layout;
    @Nullable
    private String line, laidText;
    private int laidWidth, width, height, drawn;
    private boolean failed;

    CgSubtitleOverlay(CgAudioEngine engine) {
        this.engine = engine;
        registration = CgRenderStage.HUD.register(ORDER, frame -> {
            if (failed || !CgGraphicsSettings.SUBTITLES.get()) return;
            line = engine.subtitles().text();
            if (line == null) return;
            width = frame.host().width();
            height = frame.host().height();
            frame.callback("audio.subtitles", draw);
        });
    }

    /** Frames it has drawn a line. */
    int drawn() {
        return drawn;
    }

    void close() {
        registration.close();
        if (renderer != null && !renderer.isDeleted()) renderer.delete();
        renderer = null;
    }

    private void draw() {
        try {
            if (font == null) {
                font = sansSerif(FONT_PX);
                layout = null;
                if (font == null) {
                    failed = true;
                    LOGGER.warn("[CgAudio] no sans-serif font installed: no subtitles");
                    return;
                }
            }
            int region = Math.round(width * (1f - 2f * SAFE_SIDE));
            if (layout == null || region != laidWidth || !line.equals(laidText)) {
                layout = CgTextLayout.of(line, font).maxWidth(region).align(CgTextAlign.CENTER)
                        .lineHeightOverride(font.getMetrics().getLineHeight() * LINE_SPACING).build();
                laidText = line;
                laidWidth = region;
            }
            if (renderer == null) renderer = CgTextRenderer.create();
            // Glyphs at z = 0 on the nearest depth (+1 reversed, -1 standard), so they pass against whatever the HUD
            // left in the host's depth; the depth row stays non-zero, or the culler's far plane culls every glyph.
            renderer.context().projection().m22(-1.0e-4f).m32(CgGL.isDepthReversed() ? 1f : -1f);
            float bottom = height * REGION_BOTTOM - CHAR_HEIGHT;
            renderer.beginBatch();
            renderer.draw().layout(layout).font(font).at(width * SAFE_SIDE, bottom - layout.totalHeight()).color(TEXT).shadowCount(4)
                    .shadow(0, -1f, -1f, 0f, 0f, OUTLINE, false).shadow(1, 1f, -1f, 0f, 0f, OUTLINE, false)
                    .shadow(2, -1f, 1f, 0f, 0f, OUTLINE, false).shadow(3, 1f, 1f, 0f, 0f, OUTLINE, false).submit();
            renderer.endBatch();
            drawn++;
        } catch (RuntimeException e) {
            failed = true;
            LOGGER.error("[CgAudio] the subtitles failed to draw; they stay off", e);
        }
    }

    @Nullable
    private static CgFont sansSerif(int px) {
        CgSystemFonts fonts = CgSystemFonts.get();
        CgSystemFontFace face = fonts.generic(CgGenericFamily.SANS_SERIF, CgFontStyle.REGULAR);
        return face != null ? fonts.load(face, CgFontStyle.REGULAR, px) : null;
    }
}
