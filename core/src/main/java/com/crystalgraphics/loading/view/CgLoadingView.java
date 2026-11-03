package com.crystalgraphics.loading.view;

import com.crystalgraphics.loading.CgLoadingScreen;
import com.crystalgraphics.platform.gl.CgGL;
import javax.annotation.Nullable;

/**
 * What the loading screen draws, Lyra's {@code LoadingScreenWidget}: the engine's is {@link CgLogoLoadingView}, the
 * logo over a load bar. A consumer replaces it with {@link CgLoadingScreen#view}.
 *
 * <pre>{@code
 * CgLoadingScreen.view((width, height, progress, appear, leave, reason) -> {
 *     CgImmediate.constants().projection.setOrtho(0, width, height, 0, -1, 1);
 *     float alpha = appear * (1f - leave);           // the plainest fade; ease and stagger as the look wants
 *     myRenderer.drawSplash(width, height, progress, alpha);
 * });
 * }</pre>
 *
 * <ul>
 *   <li>Called on the render thread into whatever target is bound, over the whole frame; once {@code appear} is 1
 *       and {@code leave} 0 draw every pixel opaque, since the world behind is not drawn while a process still loads.</li>
 *   <li>{@code appear} runs 0 to 1 over the fade-in, from the first frame drawn: the host's own loading screen shows
 *       through until it ends.</li>
 *   <li>{@code leave} runs 0 to 1 over the fade-out after the hide: what opens next shows through. Both are linear in
 *       time, each frame moving them a 30th of a second at most, so a held frame never skips one; the view eases them.</li>
 * </ul>
 */
@FunctionalInterface
public interface CgLoadingView {

    /**
     * @param progress 0 to 1, smoothed and never backwards
     * @param appear   0 to 1 over the fade-in, linear in frame time; 1 once it has ended
     * @param leave    0 to 1 over the fade-out, linear in frame time; 0 until the screen hides
     * @param reason   why it shows (the first process still loading, or "holding"); null in the fade-out
     */
    void draw(int width, int height, float progress, float appear, float leave, @Nullable String reason);

    /**
     * Starts building what the first {@link #draw} at {@code width} x {@code height} needs (its textures, its glyphs),
     * so it draws whole. Called once at the graphics start, before the first draw; nothing by default. Render thread.
     */
    default void warm(int width, int height) {
    }

    /**
     * Whether {@link #draw} would draw whole now, building and waiting for nothing: its shaders built, its textures
     * loaded. Until it answers true the screen draws none of itself (but its {@link #cover}) and its fade-in does not
     * start, so the host's own loading screen stays up. Polled once a frame until true; true by default. Render thread.
     *
     * <pre>{@code
     * public boolean ready() {
     *     return CgMaterial.load(FX).pipeline(CgInstanceKind.QUAD).precache() && splash.isDone();
     * }
     * }</pre>
     */
    default boolean ready() {
        return true;
    }

    /**
     * Covers the whole target with no program, once the host's first screen has opened behind the screen: every frame
     * until {@link #ready}, then under the fade-in, which {@link #draw} crosses from it. Clears only, so nothing waits on
     * a compile: a solid colour, or scissored bands of one. Black by default. Render thread, the target bound.
     *
     * <pre>{@code
     * public void cover(int width, int height) {
     *     CgGL.glClearColor(0.24f, 0.46f, 0.92f, 1f);
     *     CgGL.glClear(CgGL.GL_COLOR_BUFFER_BIT);
     * }
     * }</pre>
     */
    default void cover(int width, int height) {
        CgGL.glClearColor(0f, 0f, 0f, 1f);
        CgGL.glClear(CgGL.GL_COLOR_BUFFER_BIT);
    }
}
