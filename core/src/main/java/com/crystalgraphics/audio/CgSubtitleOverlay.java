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
 * The shown subtitle, drawn over the HUD as Unreal's {@code DisplaySubtitle} draws it: white, outlined black, centred,
 * its lines up from the bottom of the 80% safe zone. An arrow on the side its sound is on, where it is placed beside
 * the listener. Laid out again only when the line or the screen changes.
 */
final class CgSubtitleOverlay {

    private static final Logger LOGGER = LogManager.getLogger("CgAudio");
    /** Over everything else on the HUD. */
    private static final int ORDER = 10_000;
    /** Text height against the screen's height, and the smallest. */
    private static final float SIZE = 0.03f;
    private static final int MIN_PX = 12;
    /** How far to the side, as the sine of the azimuth, a sound must be for an arrow. */
    private static final double SIDE = 0.5;

    private final CgAudioEngine engine;
    private final CgRenderStage.Registration registration;
    private final Runnable draw = this::draw;
    @Nullable
    private CgTextRenderer renderer;
    @Nullable
    private CgFont font;
    private int fontPx;
    @Nullable
    private CgTextLayout layout;
    @Nullable
    private String line, display, laidText;
    private int side, laidWidth, width, height, drawn;
    private boolean failed;

    CgSubtitleOverlay(CgAudioEngine engine) {
        this.engine = engine;
        registration = CgRenderStage.HUD.register(ORDER, frame -> {
            if (failed || !CgGraphicsSettings.SUBTITLES.get()) return;
            String text = engine.subtitles().text();
            if (text == null) return;
            double az = engine.subtitleAzimuth();
            double s = Double.isNaN(az) ? 0 : Math.sin(Math.toRadians(az));
            int now = s > SIDE ? 1 : s < -SIDE ? -1 : 0;
            if (!text.equals(line) || now != side) {
                line = text;
                side = now;
                display = now > 0 ? text + "  >" : now < 0 ? "<  " + text : text;
            }
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
            int px = Math.max(MIN_PX, Math.round(height * SIZE));
            if (px != fontPx) {
                font = sansSerif(px);
                fontPx = px;
                layout = null;
                if (font == null) {
                    failed = true;
                    LOGGER.warn("[CgAudio] no sans-serif font installed: no subtitles");
                    return;
                }
            }
            // Unreal's safe zone: the middle 80% of the screen (TrimRegionToSafeZone).
            int region = Math.round(width * 0.8f);
            if (layout == null || region != laidWidth || !display.equals(laidText)) {
                layout = CgTextLayout.of(display, font).maxWidth(region).align(CgTextAlign.CENTER).build();
                laidText = display;
                laidWidth = region;
            }
            if (renderer == null) renderer = CgTextRenderer.create();
            // Glyphs at z = 0 on the nearest depth (+1 reversed, -1 standard), so they pass against whatever the HUD
            // left in the host's depth; the depth row stays non-zero, or the culler's far plane culls every glyph.
            renderer.context().projection().m22(-1.0e-4f).m32(CgGL.isDepthReversed() ? 1f : -1f);
            renderer.beginBatch();
            renderer.draw().layout(layout).font(font).at(width * 0.1f, height * 0.9f - layout.totalHeight())
                    .color(0xFFFFFFFF).stroke(0.12f, 0xFF000000).submit();
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
