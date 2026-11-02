package com.crystalgraphics.loading;

import javax.annotation.Nullable;

/**
 * The engine's loading screen laid out as an engine's splash: the logo alone in the middle of the engine's drifting
 * look, and what loads at the bottom right beside a turning ring, with no bar.
 *
 * <pre>{@code
 * CgLoadingScreen.view(new CgSplashLoadingView());                                     // -Dcrystalgraphics.loading.style=splash
 * CgLoadingScreen.view(new CgSplashLoadingView("mymod:textures/gui/splash.png"));
 * }</pre>
 */
public final class CgSplashLoadingView implements CgLoadingView {

    private static final int STATUS = 0xB33A3460, SPINNER = 0xFF7B6CF0;

    private final CgLoadingCanvas canvas;

    public CgSplashLoadingView() {
        this(CgLoadingCanvas.LOCKUP);
    }

    /** {@code logo}, a square image's path, on the engine's look. */
    public CgSplashLoadingView(String logo) {
        canvas = new CgLoadingCanvas(logo);
    }

    @Override
    public void draw(int width, int height, float progress, float alpha, @Nullable String reason) {
        if (width <= 0 || height <= 0) return;
        canvas.begin(width, height);
        float intro = smooth(Math.min(1f, canvas.seconds() / 0.9f));
        float shown = intro * alpha;
        // Settling from 3% larger as it comes in, 4% larger as it fades out
        float grow = 1f + 0.04f * (1f - alpha) + 0.03f * (1f - intro);

        canvas.hero(alpha, shown, width / 2f, height * 0.47f, Math.min(width, height) * 0.12f * grow);

        int textPx = Math.max(15, Math.round(height / 45f));
        float margin = Math.round(height * 0.04f), spinner = Math.round(textPx * 1.5f);
        float spinnerX = width - margin - spinner, spinnerY = height - margin - spinner;
        canvas.spinner(spinnerX, spinnerY, spinner, CgLoadingCanvas.argb(shown, SPINNER));
        canvas.text(0, canvas.status(reason, progress, "   "), spinnerX - spinner * 0.6f,
                spinnerY + (spinner - textPx) / 2f - textPx * 0.1f, textPx, CgLoadingCanvas.argb(shown, STATUS), 1f);
    }

    private static float smooth(float x) {
        return x * x * (3f - 2f * x);
    }
}
