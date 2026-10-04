package com.crystalgraphics.render.post;

import com.crystalgraphics.render.post.bloom.CgBloom;
import com.crystalgraphics.render.post.composite.CgPostComposite;
import com.crystalgraphics.render.post.distortion.CgPostDistortion;
import com.crystalgraphics.render.post.debug.CgPostDebug;
import com.crystalgraphics.render.post.look.CgPostLooks;
import com.crystalgraphics.render.post.volume.CgPostSettings;
import com.crystalgraphics.render.post.volume.CgPostVolume;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.stage.CgHostView;
import org.joml.Matrix4f;
import org.joml.Vector4f;
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
 *
 * // An effect's look for a moment: a volume, never the global settings
 * CgPostVolume burst = post.volume(10, new CgPostSettings().flash(1.5f)).at(x, y, z).radius(24f).blend(16f);
 * burst.weight(fade);
 * burst.close();
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
    private final CgPostDistortion distortion = new CgPostDistortion();
    private final CgPostContext context = new CgPostContext(composite, distortion);
    private final CgBloom bloom = new CgBloom();
    private final CgPostLooks looks = new CgPostLooks();
    /** {@code -Dcrystalgraphics.post.debug}'s view; null when unset. */
    private final CgPostDebug debug = CgPostDebug.fromProperty();
    /** Every effect, by point then order then when it was added: replaced whole, so recording reads a snapshot. */
    private volatile CgPostEffect[] effects = debug == null ? new CgPostEffect[]{bloom, looks} : new CgPostEffect[]{bloom, looks, debug};
    /** Every volume, by priority ascending then when it was made: replaced whole, as the effects are. */
    private volatile CgPostVolume[] volumes = new CgPostVolume[0];
    /** The volumes blended at the camera, this firing. */
    private final CgPostSettings settings = new CgPostSettings();
    private final Matrix4f viewProjection = new Matrix4f();
    private final Vector4f clip = new Vector4f();
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

    /**
     * A volume applying {@code settings} at {@code priority} (a higher blends over a lower), everywhere at full weight
     * until placed, sized or weighed. Close it to stop it.
     */
    public CgPostVolume volume(int priority, CgPostSettings settings) {
        Objects.requireNonNull(settings, "settings");
        CgPostVolume volume = new CgPostVolume(priority, settings, this::remove);
        synchronized (this) {
            CgPostVolume[] next = Arrays.copyOf(volumes, volumes.length + 1);
            next[next.length - 1] = volume;
            Arrays.sort(next, (a, b) -> Integer.compare(a.priority(), b.priority()));   // stable: ties keep their order
            volumes = next;
        }
        return volume;
    }

    private synchronized void remove(CgPostVolume volume) {
        CgPostVolume[] current = volumes;
        for (int i = 0; i < current.length; i++) {
            if (current[i] != volume) continue;
            CgPostVolume[] next = new CgPostVolume[current.length - 1];
            System.arraycopy(current, 0, next, 0, i);
            System.arraycopy(current, i + 1, next, i, current.length - i - 1);
            volumes = next;
            return;
        }
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
        distortion.release();
        bloom.release();
        if (debug != null) debug.release();
    }

    private void record(CgStageFrame stage) {
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.WORLD, "post.record")) {
            resolve(stage);
            context.begin(stage, settings);
            composite.begin();
            CgPostEffect[] current = effects;
            int i = recordAt(CgPostPoint.AFTER_WORLD, current, 0);
            i = recordAt(CgPostPoint.BEFORE_COMPOSITE, current, i);
            composite.record(stage.recording(), stage.target(), stage.constants());
            recordAt(CgPostPoint.AFTER_COMPOSITE, current, i);
        }
    }

    /** Blends every volume into {@link #settings} at the stage's camera, a placed one's focus where it is on screen. */
    private void resolve(CgStageFrame stage) {
        settings.reset();
        CgPostVolume[] current = volumes;
        if (current.length == 0) return;
        CgHostView view = stage.host().view();
        double cx = view.x(), cy = view.y(), cz = view.z();
        CgPassConstants camera = stage.constants();
        camera.projection.mul(camera.view, viewProjection);
        for (CgPostVolume v : current) {
            float w = v.weightAt(cx, cy, cz);
            if (w <= 0f) continue;
            float fx = v.settings().focusX(), fy = v.settings().focusY();
            if (v.placed() && !v.settings().overrides(CgPostSettings.FOCUS)) {
                viewProjection.transform((float) (v.x() - cx), (float) (v.y() - cy), (float) (v.z() - cz), 1f, clip);
                if (clip.w > 0f) {
                    fx = clip.x / clip.w * 0.5f + 0.5f;
                    fy = clip.y / clip.w * 0.5f + 0.5f;
                }
            }
            settings.blend(v.settings(), w, fx, fy);
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
