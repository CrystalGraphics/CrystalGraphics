package com.crystalgraphics.loading;

import javax.annotation.Nullable;

/**
 * What the loading screen draws, Lyra's {@code LoadingScreenWidget}: the engine's is {@link CgLogoLoadingView}, the
 * logo over a load bar. A consumer replaces it with {@link CgLoadingScreen#view}.
 *
 * <pre>{@code
 * CgLoadingScreen.view((width, height, progress, alpha, reason) -> {
 *     CgImmediate.constants().projection.setOrtho(0, width, height, 0, -1, 1);
 *     myRenderer.drawSplash(width, height, progress, alpha);
 * });
 * }</pre>
 *
 * <ul>
 *   <li>Called on the render thread into whatever target is bound, over the whole frame; draw every pixel at
 *       {@code alpha} 1, since the world behind is not drawn while a process still loads.</li>
 *   <li>{@code alpha} below 1 is the fade-out after the hide: the scene shows through it.</li>
 * </ul>
 */
@FunctionalInterface
public interface CgLoadingView {

    /**
     * @param progress 0 to 1, smoothed and never backwards
     * @param reason   why it shows (the first process still loading, or "holding"); null in the fade-out
     */
    void draw(int width, int height, float progress, float alpha, @Nullable String reason);

    /**
     * Starts building what the first {@link #draw} at {@code width} x {@code height} needs (its textures, its glyphs),
     * so it draws whole. Called once at the graphics start, before the first draw; nothing by default. Render thread.
     */
    default void warm(int width, int height) {
    }
}
