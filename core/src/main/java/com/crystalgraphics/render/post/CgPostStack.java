package com.crystalgraphics.render.post;

import com.crystalgraphics.render.post.bloom.CgBloom;
import com.crystalgraphics.render.post.composite.CgPostComposite;
import com.crystalgraphics.render.post.debug.CgPostDebug;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.stage.CgStageFrame;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;

import java.util.Arrays;
import java.util.Objects;

/**
 * The post-processing stack: what runs after the world renderer has drawn the scene, on the same stage firing. Every
 * active {@link CgPostEffect} records at its {@link CgPostPoint}, then one composite pass lays the firing's looks over
 * the target, as every production engine composes bloom inside its tonemap or uber pass. The world renderer produces
 * (it publishes {@code CgFrameKeys.EMISSION}); the stack consumes.
 *
 * <pre>{@code
 * CgPostStack post = CgPostStack.get();
 * post.bloom().intensity(1.2f);                                 // the built-in bloom's settings
 * CgRenderStage.Registration outline = post.add(new Outline()); // a mod's effect at its point
 * outline.close();
 * }</pre>
 *
 * <ul>
 *   <li>Registered on {@code WORLD_TRANSPARENT} at {@link #ORDER}, after the world renderer. The engine installs it
 *       with the world renderer.</li>
 *   <li>Render thread for recording; any thread may {@link #add} or remove an effect, which takes effect at the next
 *       firing.</li>
 * </ul>
 */
public final class CgPostStack {

    /** Where the stack records on {@code WORLD_TRANSPARENT}: after the world renderer (1000). */
    public static final int ORDER = 2000;

    private static final CgPostStack INSTANCE = new CgPostStack();

    private final CgPostComposite composite = new CgPostComposite();
    private final CgPostContext context = new CgPostContext(composite);
    private final CgBloom bloom = new CgBloom();
    /** {@code -Dcrystalgraphics.post.debug}'s view; null when unset. */
    private final CgPostDebug debug = CgPostDebug.fromProperty();
    /** Every effect, by point then order then when it was added: replaced whole, so recording reads a snapshot. */
    private volatile CgPostEffect[] effects = debug == null ? new CgPostEffect[]{bloom} : new CgPostEffect[]{bloom, debug};
    private boolean installed;

    private CgPostStack() {
    }

    public static CgPostStack get() {
        return INSTANCE;
    }

    /** Registers on {@code WORLD_TRANSPARENT}, once. The engine calls it when it starts. */
    public void install() {
        if (installed) return;
        installed = true;
        CgRenderStage.WORLD_TRANSPARENT.register(ORDER, this::record);
    }

    /** The built-in bloom, and its settings. */
    public CgBloom bloom() {
        return bloom;
    }

    /** Adds {@code effect} at its point; closing the registration removes it. */
    public CgRenderStage.Registration add(CgPostEffect effect) {
        Objects.requireNonNull(effect, "effect");
        synchronized (this) {
            CgPostEffect[] next = Arrays.copyOf(effects, effects.length + 1);
            next[next.length - 1] = effect;
            // Stable: ties keep the order they were added in.
            Arrays.sort(next, (a, b) -> a.point() != b.point() ? a.point().compareTo(b.point())
                    : Integer.compare(a.order(), b.order()));
            effects = next;
        }
        return () -> remove(effect);
    }

    private synchronized void remove(CgPostEffect effect) {
        CgPostEffect[] current = effects;
        for (int i = 0; i < current.length; i++) {
            if (current[i] != effect) continue;
            CgPostEffect[] next = new CgPostEffect[current.length - 1];
            System.arraycopy(current, 0, next, 0, i);
            System.arraycopy(current, i + 1, next, i, current.length - i - 1);
            effects = next;
            return;
        }
    }

    /** Forgets what it made on the GPU. At context teardown. */
    public void release() {
        composite.release();
        bloom.release();
        if (debug != null) debug.release();
    }

    private void record(CgStageFrame stage) {
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.WORLD, "post.record")) {
            context.begin(stage);
            composite.begin();
            CgPostEffect[] current = effects;
            int i = recordAt(CgPostPoint.AFTER_WORLD, current, 0);
            i = recordAt(CgPostPoint.BEFORE_COMPOSITE, current, i);
            composite.record(stage.recording(), stage.target(), stage.constants());
            recordAt(CgPostPoint.AFTER_COMPOSITE, current, i);
        }
    }

    /** Records the effects at {@code point}, which start at {@code from} in the sorted array; answers where the next point starts. */
    private int recordAt(CgPostPoint point, CgPostEffect[] current, int from) {
        int i = from;
        for (; i < current.length && current[i].point() == point; i++) {
            if (current[i].active(context)) current[i].record(context);
        }
        return i;
    }
}
