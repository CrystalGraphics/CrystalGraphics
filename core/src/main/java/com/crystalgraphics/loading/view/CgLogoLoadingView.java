package com.crystalgraphics.loading.view;

import java.util.List;

import javax.annotation.Nullable;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgDefaultFont;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.text.CgTextAlign;
import com.crystalgraphics.api.text.CgTextLayout;
import com.crystalgraphics.text.style.CgTextLayer;
import com.crystalgraphics.text.style.CgTextStyle;
import com.crystalgraphics.job.CgJob;
import com.crystalgraphics.job.CgJobLane;
import com.crystalgraphics.job.reply.CgReply;
import com.crystalgraphics.loading.task.CgLoadTask;
import com.crystalgraphics.loading.task.CgLoadTracker;
import com.crystalgraphics.variable.CgVar;
import com.crystalgraphics.api.texture.CgMipmapConfig;
import com.crystalgraphics.api.texture.CgTextureSpec;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.api.texture.CgTextures;
import com.crystalgraphics.render.canvas.CgCanvas;
import com.crystalgraphics.render.canvas.CgQuadBatch;
import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgraphics.render.CgImmediate;
import com.crystalgraphics.render.draw.CgInstanceKind;

/**
 * The engine's loading screen: its lockup (the logo and "Crystal Graphics") over a view up at the ocean's surface from
 * below, with a breathing halo and a light sweeping across it, and Minecraft's white outlined load bar under it,
 * spanning it. In and out it moves in stages: the backdrop, the lockup, then the bar coming in; the bar, the lockup
 * lifting away larger, then the backdrop going out.
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
    /** Its shader, which the screen precaches with the engine's passes. */
    public static final String FX ="crystalgraphics:shaders/loading/loading_fx.shader";
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
    /** Drawn at once, at the end of each draw: the loading screen draws outside any stage. */
    private final CgCanvas canvas = new CgCanvas();
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
    public void draw(int width, int height, float progress, float appear, float leave, @Nullable String reason) {
        if (width <= 0 || height <= 0) return;
        seconds = (System.nanoTime() - started) / 1e9f;
        aspect = width / (float) height;
        CgImmediate.constants().view.identity();
        CgImmediate.constants().projection.setOrtho(0f, width, height, 0f, -1f, 1f);

        // Staged, each part on its own eased window. In: the backdrop over the host's screen, then the lockup settling from 3% larger, then the bar rising into place. Out, the reverse:
        // the bar first, the lockup lifting away 6% larger as a game's splash leaves for its menu, the backdrop last
        float backdropIn = stage(appear, 0f, 0.5f);
        float lockupIn = stage(appear, 0.2f, 0.85f), barIn = stage(appear, 0.45f, 1f);
        float barOut = stage(leave, 0f, 0.3f), lockupOut = stage(leave, 0.1f, 0.7f), backdropOut = stage(leave, 0.35f, 1f);
        float alpha = backdropIn * (1f - backdropOut), shown = lockupIn * (1f - lockupOut), barShown = barIn * (1f - barOut);
        float grow = 1f + 0.06f * stage(leave, 0.1f, 0.8f) + 0.03f * (1f - lockupIn);
        // Vanilla LoadingOverlay's sizes, so the hand-off from Mojang's screen keeps its scale: the lockup as wide as its
        // logo, min(0.75 width, height), and a bar as wide
        float cx = width / 2f, logoWidth = Math.min(width * 0.75f, height);
        CgTexture2D texture = texture();
        float baseW = logoWidth / (1f - 2f * SIDE_PAD), icon = baseW * texture.getHeight() / texture.getWidth() * ICON_SHARE;
        // Its thickness a share of its width, never vanilla's GUI scale: that steps, heavier at 1440p than at 1080p
        float barHeight = Math.max(6f, Math.round(logoWidth * (32f / 1080f))), gap = icon * 0.36f;
        // The lockup and the bar a third of the icon below it, centred together
        float cy = Math.round(height / 2f - (gap + barHeight) / 2f);
        // Sinking into place as it comes in, lifting as it leaves: a few percent of the screen
        float lift = height * (0.015f * (1f - lockupIn) - 0.02f * stage(leave, 0.1f, 0.7f));
        cy += lift;

        // The lockup, its visible width the logo's, its image padded around that
        float w = baseW * grow, h = w * texture.getHeight() / texture.getWidth();
        float glow = w * 1.3f, breath = 0.30f + 0.06f * (float) Math.sin(seconds * 1.6);
        CgMaterial fx = CgMaterial.load(FX);
        quad(fx, null, 0f, 0f, width, height, argb(alpha, 0xFFFFFFFF), 0f, BACKDROP);
        quad(fx, null, cx - glow / 2f, cy - glow / 2f, glow, glow, argb(shown * breath, HALO), 0f, GLOW);
        // Pixel-snapped only at rest: snapping its corner while its size moves jumps its edges a pixel back and forth
        boolean still = grow == 1f && lift == 0f;
        float x = cx - w / 2f, y = cy - h / 2f;
        quad(fx, texture, still ? Math.round(x) : x, still ? Math.round(y) : y, w, h, argb(shown, 0xFFFFFFFF), 1f, IMAGE);

        // The bar spans the lockup a third of the icon below it, so the two read as one block
        float barWidth = Math.round(logoWidth), barX = Math.round(cx - barWidth / 2f);
        // Below the lockup's settled place, not its lift, and rising a bar's height into it as it comes in
        float barY = Math.round(cy - lift + icon / 2f + gap + barHeight * (1f - barIn));
        // The quad is 4 bars tall and 3 wider, the shader's room around the pill
        quad(fx, null, barX - 1.5f * barHeight, barY - 1.5f * barHeight, barWidth + 3f * barHeight, 4f * barHeight,
                argb(barShown, 0xFFFFFFFF), Math.min(1f, Math.max(0f, progress)), BAR);
        canvas.flush();
        if (SHOW_TASKS.get()) tasks(barX, barY + 2.5f * barHeight, barWidth, barHeight, barShown);
    }

    private static final CgVar.Bool SHOW_TASKS = CgVar.bool("crystalgraphics.loading.showTasks", false,
            "List what loads under the loading screen's bar: each running task, its owner and how far along, a line each.");
    /** At most this many lines; the oldest running first. */
    private static final int MAX_LINES = 6;
    /** White, edged a pixel each way in the backdrop's navy at the smallest size, 12 px. */
    private static final CgTextStyle TASK_LOOK = CgTextStyle.builder("crystalgraphics:loading_task")
            .size(12f * 72f / 96f)
            .layer(CgTextLayer.shadow("edge_nw").offset(-1f, -1f).paint(0xFF0A2A40).opacity(0.6f))
            .layer(CgTextLayer.shadow("edge_ne").offset(1f, -1f).paint(0xFF0A2A40).opacity(0.6f))
            .layer(CgTextLayer.shadow("edge_sw").offset(-1f, 1f).paint(0xFF0A2A40).opacity(0.6f))
            .layer(CgTextLayer.shadow("edge_se").offset(1f, 1f).paint(0xFF0A2A40).opacity(0.6f))
            .layer(CgTextLayer.fill("fill").opacity(0.9f))
            .build();
    @Nullable private CgFont font;
    private int fontPx;
    private boolean noFont;
    @Nullable private String lines;
    @Nullable private CgTextLayout layout;

    /** The running load tasks, a line each under the bar: several lines are tasks loading in parallel. */
    private void tasks(float x, float y, float width, float barHeight, float alpha) {
        String now = lines();
        if (now.isEmpty() || noFont) return;
        int px = Math.max(12, Math.round(barHeight * 0.8f));
        if (font == null || px != fontPx) {
            font = defaultFont(px);
            if (font == null) return;
            fontPx = px;
            layout = null;
        }
        if (layout == null || !now.equals(lines)) {
            lines = now;
            layout = CgTextLayout.of(now, font).maxWidth(width).align(CgTextAlign.CENTER).build();
        }
        canvas.text().style(TASK_LOOK).layout(layout).font(font).at(x, y).color(argb(alpha, 0xFFFFFFFF)).submit();
        canvas.flush();
    }

    /** {@code owner  task  42%}, a line per running task. */
    private static String lines() {
        List<CgLoadTask> running = CgLoadTracker.running();
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < Math.min(MAX_LINES, running.size()); i++) {
            CgLoadTask t = running.get(i);
            if (s.length() > 0) s.append('\n');
            String name = t.id().substring(t.owner().length() + 1);
            s.append(t.owner()).append("  ").append(name);
            float p = t.reported();
            if (!Float.isNaN(p)) s.append("  ").append(Math.round(p * 100f)).append('%');
            String detail = t.detail();
            if (detail != null) s.append("  ").append(detail);
        }
        if (running.size() > MAX_LINES) s.append("\n+").append(running.size() - MAX_LINES).append(" more");
        return s.toString();
    }

    /** The engine's default font, regular, asked on a worker: the start opens it ahead, and no frame waits if not yet. */
    @Nullable
    private CgReply<CgFont> face;

    /** The default font at {@code px}; null while it opens, and with {@link #noFont} set where it failed to. */
    @Nullable
    private CgFont defaultFont(int px) {
        if (face == null) lookUpFace();
        if (!face.isDone()) return null;
        CgFont found = face.result();
        if (found == null) {
            noFont = true;
            return null;
        }
        return found.atSize(px);
    }

    private void lookUpFace() {
        if (face == null) {
            face = CgJob.lane(CgJobLane.BACKGROUND).named("loading screen font").run(() -> CgDefaultFont.regular(16));
        }
    }

    /** The lockup's decode, on a worker: its upload lands at the next frame. */
    @Nullable private CgJob<Integer> lockupLoad;
    private boolean ready;

    /**
     * Starts its shader building and the lockup decoding, ahead of the engine's own boot list, and the lines' font's
     * lookup: until both are done the host's first screen shows where this screen will be.
     */
    @Override
    public void warm(int width, int height) {
        loadLockup();
        ready = CgMaterial.load(FX).pipeline(CgInstanceKind.QUAD).precache() && lockupLoad.isDone();
        if (SHOW_TASKS.get()) lookUpFace();
    }

    /** Its shader's pipeline built and the lockup loaded. */
    @Override
    public boolean ready() {
        if (ready) return true;
        loadLockup();
        boolean fx = CgMaterial.load(FX).pipeline(CgInstanceKind.QUAD).precache();
        return ready = fx && lockupLoad.isDone();
    }

    private void loadLockup() {
        if (lockupLoad != null) return;
        CgTexture2D t = texture();
        lockupLoad = CgJob.lane(CgJobLane.LATENCY).named("loading screen lockup").run(() -> t.getWidth());
    }

    private CgTexture2D texture() {
        if (lockup == null) lockup = CgTextures.of(lockupPath, MIPMAPPED);
        return lockup;
    }
    /**
     * A quad of {@code loading_fx.shader} in {@code mode}, {@code param} its per-mode value (the sweep, the fill),
     * {@code texture} at unit 0 where it samples one.
     */
    private void quad(CgMaterial fx, @Nullable CgTexture2D texture, float x, float y, float w, float h, int argb, float param,
                      int mode) {
        CgQuadBatch.Quad quad = canvas.quad(fx);
        if (texture != null) quad.texture(0, texture);
        quad.at(x, y).size(w, h).uv(0f, 0f, 1f, 1f).color(argb).custom0(seconds, aspect, param, mode).submit();
    }

    private static float smooth(float x) {
        return x * x * (3f - 2f * x);
    }

    /** {@code t}'s eased place in the window from {@code from} to {@code to}: 0 before it, 1 after. */
    private static float stage(float t, float from, float to) {
        return smooth(Math.min(1f, Math.max(0f, (t - from) / (to - from))));
    }

    /** {@code argb} with its alpha times {@code alpha}. */
    private static int argb(float alpha, int argb) {
        int a = Math.round((argb >>> 24) * Math.min(1f, Math.max(0f, alpha)));
        return a << 24 | argb & 0x00FFFFFF;
    }
}
