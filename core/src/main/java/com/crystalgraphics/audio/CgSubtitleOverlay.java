package com.crystalgraphics.audio;

import java.util.Map;
import java.util.Objects;

import javax.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontStyle;
import com.crystalgraphics.api.font.CgGenericFamily;
import com.crystalgraphics.api.font.CgSystemFontFace;
import com.crystalgraphics.api.font.CgSystemFonts;
import com.crystalgraphics.api.text.CgTextAlign;
import com.crystalgraphics.api.text.CgTextLayout;
import com.crystalgraphics.asset.load.CgAssetRef;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.render.canvas.CgCanvas;
import com.crystalgraphics.render.canvas.CgCanvasMaterials;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.stage.CgStageFrame;
import com.crystalgraphics.sequence.CgSequencePlayer;
import com.crystalgraphics.sequence.CgSequencer;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;
import com.crystalgraphics.settings.CgGraphicsSettings;
import com.crystalgraphics.text.style.CgTextLayer;
import com.crystalgraphics.text.style.CgTextStyle;
import com.crystalgraphics.widget.CgCanvasWidgetPainter;
import com.crystalgraphics.widget.CgUiScale;
import com.crystalgraphics.widget.CgUserWidget;
import com.crystalgraphics.widget.CgWidget;
import com.crystalgraphics.widget.CgWidgetTree;

/**
 * The shown subtitle over the HUD. A line whose game ships a {@link CgSubtitleStyle} is drawn in the game's own
 * subtitle widget, as UMG draws it: the line and its speaker set into its text blocks, the widget filling the screen
 * in the kit's UI units, a new line playing the last one's hide animation and then its own show, one line at a time.
 * Any other line is drawn in Unreal's canvas look ({@code FSubtitleManager::DisplaySubtitle}): 13 px (Roboto's 10 pt),
 * white, a 1 px black outline drawn as four passes at the diagonals ({@code FCanvasTextItem::bOutlined}), centred in
 * the region a viewport gives (the bottom 90% of the screen, trimmed to the 10-90% safe zone), a single line's top
 * 24 px above its bottom and lines 1.1 apart.
 */
final class CgSubtitleOverlay {

    private static final Logger LOGGER = LogManager.getLogger("CgAudio");
    /** Over everything else on the HUD. */
    private static final int ORDER = 10_000;
    /** Unreal's subtitle font, Roboto at its LegacyFontSize of 10 pt, at Slate's 96 DPI: a fixed pixel size. */
    private static final int FONT_PX = Math.round(10f * 96f / 72f);
    /** {@code GetSubtitleRegion}'s bottom, the safe zone's sides, {@code SUBTITLE_CHAR_HEIGHT} and the line spacing. */
    private static final float REGION_BOTTOM = 0.9f, SAFE_SIDE = 0.1f, CHAR_HEIGHT = 24f, LINE_SPACING = 1.1f;
    /** Unreal's canvas look: white, outlined by four 1 px passes at the diagonals beneath it. */
    private static final CgTextStyle CANVAS_LOOK = CgTextStyle.builder("crystalgraphics:subtitle_canvas")
            .size(FONT_PX * 72f / 96f)
            .layer(CgTextLayer.shadow("outline_nw").offset(-1f, -1f).paint(0xFF000000))
            .layer(CgTextLayer.shadow("outline_ne").offset(1f, -1f).paint(0xFF000000))
            .layer(CgTextLayer.shadow("outline_sw").offset(-1f, 1f).paint(0xFF000000))
            .layer(CgTextLayer.shadow("outline_se").offset(1f, 1f).paint(0xFF000000))
            .layer(CgTextLayer.fill("fill"))
            .build();

    /** A line: what it says, by whom, in which look. */
    private record Line(@Nullable String text, @Nullable String speaker, @Nullable CgSubtitleStyle style) {
    }

    /** Where the styled widget stands: hidden, showing a line, or playing that line's hide. */
    private enum Phase { HIDDEN, SHOWN, HIDING }

    private static final Line NONE = new Line(null, null, null);

    private final CgAudioEngine engine;
    private final CgRenderStage.Registration registration;
    private final CgCanvasWidgetPainter painter =
            new CgCanvasWidgetPainter("crystalgraphics.subtitles", CgCanvasMaterials.SCREEN).retaining(2048f, 512f, 16f);
    private final Matrix4f placement = new Matrix4f();
    /** The plain look's layout, kept while its text and width hold. */
    @Nullable
    private CgTextLayout plainLayout;
    @Nullable
    private String plainText;
    private float plainWidth;
    @Nullable
    private CgFont canvasFont;

    private Line wanted = NONE;
    private Line shown = NONE;
    private Phase phase = Phase.HIDDEN;
    @Nullable
    private CgSubtitleStyle widgetStyle;
    @Nullable
    private CgUserWidget widget;
    @Nullable
    private CgSequencePlayer player;
    private int drawn;
    private boolean failed;

    CgSubtitleOverlay(CgAudioEngine engine) {
        this.engine = engine;
        registration = CgRenderStage.HUD.register(ORDER, frame -> {
            if (failed || !CgGraphicsSettings.SUBTITLES.get()) return;
            try {
                CgSubtitles subtitles = engine.subtitles();
                String text = subtitles.text(), speaker = subtitles.speaker();
                CgSubtitleStyle style = subtitles.style();
                if (!Objects.equals(text, wanted.text()) || !Objects.equals(speaker, wanted.speaker())
                        || style != wanted.style()) {
                    wanted = new Line(text, speaker, style);
                }
                if (wanted.style() == null && phase == Phase.HIDDEN) {
                    if (wanted.text() != null) plain(frame, wanted.text());
                } else {
                    styled(frame);
                }
            } catch (RuntimeException e) {
                failed = true;
                LOGGER.error("[CgAudio] the subtitles failed to draw; they stay off", e);
            }
        });
    }

    /** Frames it has drawn a line. */
    int drawn() {
        return drawn;
    }

    void close() {
        registration.close();
    }

    /** Steps the widget toward the wanted line, then draws it while it shows one. */
    private void styled(CgStageFrame frame) {
        // A hide that has ended leaves nothing drawn; its player stops after, since stopping puts back what it found.
        if (phase == Phase.HIDING && (player == null || player.finished())) {
            phase = Phase.HIDDEN;
            stopPlayer();
            shown = NONE;
        }
        boolean changed = wanted != shown;
        if (changed && phase == Phase.SHOWN) {
            CgSequenceDefinition hide = definition(shown.style().hide());
            stopPlayer();
            if (hide != null) {
                player = play(hide);
                phase = Phase.HIDING;
            } else {
                phase = Phase.HIDDEN;
                shown = NONE;
            }
        }
        if (changed && phase == Phase.HIDDEN && wanted.text() != null && wanted.style() != null && show(wanted)) {
            shown = wanted;
        }
        if (phase == Phase.HIDDEN || widget == null) return;
        float scale = CgUiScale.of(widgetStyle.namespace()).scale(frame.host().width(), frame.host().height());
        widget.allot(frame.host().width() / scale, frame.host().height() / scale);
        painter.begin(frame.canvas(), frame.recording(), scale);
        widget.retain(painter);
        widget.paint(painter, placement.scaling(scale));
        drawn++;
    }

    /** Sets {@code line} into its style's widget and plays its show; false while what it needs has not loaded. */
    private boolean show(Line line) {
        CgSubtitleStyle style = line.style();
        CgWidgetTree tree = style.widget().loaded();
        if (tree == null || style.show() != null && style.show().loaded() == null) return false;
        if (widgetStyle != style || widget == null || widget.tree() != tree) {
            widget = tree.newInstance();
            widgetStyle = style;
            painter.prepare(tree);
        }
        widget.reset();
        widget.text(style.line(), line.text());
        boolean named = line.speaker() != null;
        if (style.name() != null) {
            widget.text(style.name(), named ? line.speaker() : "");
            widget.visibility(style.name(), named ? CgWidget.Visibility.VISIBLE : CgWidget.Visibility.COLLAPSED);
        }
        for (String w : style.withName()) {
            widget.visibility(w, named ? CgWidget.Visibility.VISIBLE : CgWidget.Visibility.COLLAPSED);
        }
        stopPlayer();
        CgSequenceDefinition show = definition(style.show());
        if (show != null) player = play(show);
        phase = Phase.SHOWN;
        return true;
    }

    private CgSequencePlayer play(CgSequenceDefinition definition) {
        return CgSequencer.widgets(CgWorld.client()).play(definition, Map.of(CgSubtitleStyle.ROLE, widget));
    }

    private void stopPlayer() {
        if (player != null) {
            player.stop();
            player = null;
        }
    }

    @Nullable
    private static CgSequenceDefinition definition(@Nullable CgAssetRef<CgSequenceDefinition> ref) {
        return ref == null ? null : ref.loaded();
    }

    private void plain(CgStageFrame frame, String text) {
        if (canvasFont == null) {
            canvasFont = sansSerif(FONT_PX);
            if (canvasFont == null) {
                failed = true;
                LOGGER.warn("[CgAudio] no sans-serif font installed: no subtitles");
                return;
            }
        }
        int width = frame.host().width(), height = frame.host().height();
        float region = Math.round(width * (1f - 2f * SAFE_SIDE));
        if (plainLayout == null || region != plainWidth || !text.equals(plainText)) {
            plainLayout = CgTextLayout.of(text, canvasFont).maxWidth(region).align(CgTextAlign.CENTER)
                    .lineHeightOverride(canvasFont.getMetrics().getLineHeight() * LINE_SPACING).build();
            plainText = text;
            plainWidth = region;
        }
        float bottom = height * REGION_BOTTOM - CHAR_HEIGHT;
        CgCanvas canvas = frame.canvas();
        canvas.text().style(CANVAS_LOOK).layout(plainLayout).font(canvasFont).contained(false)
                .at(width * SAFE_SIDE, bottom - plainLayout.totalHeight()).submit();
        drawn++;
    }

    @Nullable
    private static CgFont sansSerif(int px) {
        CgSystemFonts fonts = CgSystemFonts.get();
        CgSystemFontFace face = fonts.generic(CgGenericFamily.SANS_SERIF, CgFontStyle.REGULAR);
        return face != null ? fonts.load(face, CgFontStyle.REGULAR, px) : null;
    }
}
