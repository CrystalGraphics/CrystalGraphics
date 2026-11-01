package com.crystalgraphics.loading;

import javax.annotation.Nullable;

/**
 * Something the loading screen waits on, Lyra's {@code ILoadingProcessInterface}: asked once a frame whether it is
 * still loading, and why. The screen shows while any process answers a reason.
 *
 * <pre>{@code
 * CgLoadingScreen.add(() -> arena.ready() ? null : "building the arena");
 *
 * CgLoadingScreen.add(new CgLoadingProcess() {                  // with progress for the bar
 *     public String loading() { return left > 0 ? left + " chunks to mesh" : null; }
 *     public float progress() { return 1f - left / (float) total; }
 * });
 * }</pre>
 *
 * <ul>
 *   <li>Render thread; asked every frame, so it allocates nothing on the answer it gives most (a constant reason, or
 *       null).</li>
 *   <li>Work started in code and ended in code is a {@link CgLoadingScreen#task}, not a process of its own.</li>
 * </ul>
 */
@FunctionalInterface
public interface CgLoadingProcess {

    /** Why it is still loading, shown and logged; null once it is done. */
    @Nullable
    String loading();

    /** How much of its work is done, 0 to 1, for the bar; NaN when it cannot say. */
    default float progress() {
        return Float.NaN;
    }
}
