package com.crystalgraphics.loading;

import javax.annotation.Nullable;

/**
 * The engine's loading screen in the minimal-premium style of HoYoverse's start screens: the logo and its wordmark, a
 * light sweeping across the logo, over a view up at the ocean's surface from below; Minecraft's white outlined load bar,
 * made smooth, over a line saying what loads. It eases in, and eases out a little larger as the screen fades.
 *
 * <pre>{@code
 * CgLoadingScreen.view(new CgPremiumLoadingView());                                  // the default
 * CgLoadingScreen.view(new CgPremiumLoadingView("mymod:textures/gui/lockup.png"));    // a mod's lockup on the same look
 * }</pre>
 *
 * A mod's lockup is laid out as the engine's: its icon 64 / 92 of the image's height, 14 / 486.8 of its width padding
 * each side (the plan repository's tools/logo/export_lockup.py makes one).
 */
public final class CgPremiumLoadingView implements CgLoadingView {

    private static final int STATUS = 0xF2FFFFFF, SHADOW = 0x8C0A0830;

    private final CgLoadingCanvas canvas;

    public CgPremiumLoadingView() {
        this(CgLoadingCanvas.LOCKUP);
    }

    /** {@code lockup}, an icon and wordmark image's path, on the engine's look. */
    public CgPremiumLoadingView(String lockup) {
        canvas = new CgLoadingCanvas(lockup);
    }

    @Override
    public void draw(int width, int height, float progress, float alpha, @Nullable String reason) {
        if (width <= 0 || height <= 0) return;
        canvas.begin(width, height);
        float t = canvas.seconds();
        float intro = smooth(Math.min(1f, t / 0.9f));
        float shown = intro * alpha;
        // 4% larger as it fades out, as a game's splash leaves for its menu; settling from 3% larger as it comes in
        float grow = 1f + 0.04f * (1f - alpha) + 0.03f * (1f - intro);
        float cx = width / 2f;
        // The bar spans the logo and its wordmark, so the three read as one block
        float lockup = canvas.hero(alpha, shown, cx, height * 0.45f, Math.min(width, height) * 0.10f * grow);
        float barWidth = Math.round(lockup), barX = Math.round(cx - barWidth / 2f);
        float barHeight = Math.max(8f, Math.round(height / 60f)), barY = Math.round(height * 0.58f);
        canvas.bar(barX, barY, barWidth, barHeight, progress, shown);
        int textPx = Math.max(16, Math.round(height / 42f));
        String status = canvas.status(reason, progress, "   ·   ");
        float textY = barY + barHeight + textPx * 0.9f, drop = Math.max(1f, Math.round(textPx / 12f));
        // White over a soft shadow: the water behind it runs from light to deep
        canvas.text(1, status, cx, textY + drop, textPx, CgLoadingCanvas.argb(shown, SHADOW), 0.5f);
        canvas.text(0, status, cx, textY, textPx, CgLoadingCanvas.argb(shown, STATUS), 0.5f);
    }

    private static float smooth(float x) {
        return x * x * (3f - 2f * x);
    }
}
