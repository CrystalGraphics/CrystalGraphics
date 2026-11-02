package com.crystalgraphics.loading.task;

/**
 * A load task's render-thread part, run in slices: called once or more a frame while the frame's loading budget lasts,
 * until it answers true. Forge's {@code DeferredWorkQueue}, drained a slice at a time.
 *
 * <pre>{@code
 * plan.task("mymod:icons").work(() -> decoded = decodeAll())
 *         .render(task -> {                      // render thread: one upload a call
 *             if (next == decoded.size()) return true;
 *             upload(decoded.get(next++));
 *             task.progress(next / (float) decoded.size());
 *             return false;
 *         })
 *         .start();
 * }</pre>
 *
 * <ul>
 *   <li>Keep each call short (a texture, a buffer, a program started): the frame's budget is spent between calls, never
 *       inside one.</li>
 *   <li>A step that throws fails its task, and every task after it.</li>
 * </ul>
 */
@FunctionalInterface
public interface CgLoadStep {

    /** Does one slice of the work; true once all of it is done. Render thread. */
    boolean step(CgLoadTask task) throws Exception;
}
