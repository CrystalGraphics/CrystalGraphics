package com.crystalgraphics.render.scene.component;

import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.render.CgFrameClock;
import com.crystalgraphics.render.post.CgPostRenderer;
import com.crystalgraphics.render.post.settings.CgPostSettings;
import com.crystalgraphics.render.scene.batch.CgMeshBatches;
import com.crystalgraphics.render.view.CgView;
import com.crystalgraphics.shape.bounds.CgAABB;

import javax.annotation.Nullable;
import java.util.Objects;

/**
 * Where and when a {@link CgPostSettings} applies to the views of its scene: Unreal's post process volume, a component.
 * Everywhere until placed and given a radius (Unreal's {@code bUnbound}), else full within the radius of where it stands
 * and fading over a blend distance beyond it; at a weight an effect moves over its life. Volumes blend by priority, a higher over a
 * lower, ties in the order spawned.
 *
 * <pre>{@code
 * CgPostVolume burst = scene.postVolume(new CgPostSettings().flash(1.5f).bloom(2f))
 *         .at(x, y, z).radius(24f).blend(16f).priority(10).spawn();
 * burst.weight(1f - age / life);   // each frame, from the effect's moment
 * burst.remove();                  // when the effect ends
 *
 * scene.postVolume(new CgPostSettings().vignette(0.3f)).spawn();          // everywhere
 * scene.postVolume(aura).radius(6f).attach(goku, "spine_02").spawn();     // rides a socket
 *
 * // A flash on the viewer's clock: it fades on through its character's hitstop.
 * flash.timeline(seconds -> FLASH_CURVE.at(Math.min(seconds / 0.6f, 1f)));
 * }</pre>
 *
 * <ul>
 *   <li>The frame thread's, as its scene is. The views read it at their next firing.</li>
 *   <li>Its weight applies whatever the distance; the distance only lowers it, outside the radius.</li>
 *   <li>Once placed ({@code at} or {@code attach}), an impact frame's focus is where it stands on screen unless its
 *       settings set one.</li>
 *   <li>Its settings are read each frame: change them in place and the next frame shows it.</li>
 *   <li>A {@link #timeline} replaces {@link #weight} until it is cleared: an effect's flash runs on it, since an effect
 *       is not stepped while its character is frozen.</li>
 * </ul>
 */
public final class CgPostVolume extends CgPrimitiveComponent<CgPostVolume> {

    private final CgPostSettings settings;
    private int priority;
    private float radius = Float.POSITIVE_INFINITY, blend, weight = 1f;
    private boolean placed;
    @Nullable
    private Timeline timeline;
    private double timelineStart;

    /** A weight over the viewer's time: given the seconds since it was set, 0 to 1. */
    @FunctionalInterface
    public interface Timeline {
        float weight(float seconds);
    }

    CgPostVolume(CgScenePrimitives scene, CgPostSettings settings) {
        super(scene);
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public CgPostSettings settings() {
        return settings;
    }

    /** Where it blends among the scene's volumes: a higher over a lower. 0 until set. */
    public CgPostVolume priority(int priority) {
        if (priority == this.priority) return this;
        this.priority = priority;
        if (spawned()) scene.sortPostVolumes();
        return this;
    }

    public int priority() {
        return priority;
    }

    /** Full weight within {@code radius} blocks of where it stands, once placed. Everywhere until set. */
    public CgPostVolume radius(float radius) {
        this.radius = Math.max(0f, radius);
        return this;
    }

    /** Fades from full weight at its radius to none {@code distance} blocks beyond it. 0 until set: a hard edge. */
    public CgPostVolume blend(float distance) {
        this.blend = Math.max(0f, distance);
        return this;
    }

    /** How much it applies, 0 to 1: what an effect animates. 1 until set. */
    public CgPostVolume weight(float weight) {
        this.weight = Math.max(0f, Math.min(1f, weight));
        return this;
    }

    /**
     * Weighs it by {@code timeline} from now on the frame clock, the viewer's: never dilated, never held by a
     * character's clock. Null goes back to {@link #weight}.
     */
    public CgPostVolume timeline(@Nullable Timeline timeline) {
        this.timelineStart = CgFrameClock.seconds();
        this.timeline = timeline;
        return this;
    }

    @Override
    public CgPostVolume at(double x, double y, double z) {
        placed = true;
        return super.at(x, y, z);
    }

    @Override
    public CgPostVolume attach(CgPrimitiveComponent<?> parent, @Nullable String socket) {
        placed = true;
        return super.attach(parent, socket);
    }

    // ── Engine side ───────────────────────────────────────────────────────────────────────────────

    /** Whether {@code at} or {@code attach} placed it: its radius and focus then count from where it stands. */
    public boolean placed() {
        return placed;
    }

    /** Its weight for a camera at absolute {@code (cx, cy, cz)}: its own or its timeline's, lowered past its radius. */
    public float weightAt(double cx, double cy, double cz) {
        if (!visible) return 0f;
        Timeline t = timeline;
        float w = t == null ? weight : Math.max(0f, Math.min(1f, t.weight((float) (CgFrameClock.seconds() - timelineStart))));
        if (w <= 0f || !placed || radius == Float.POSITIVE_INFINITY) return w;
        double dx = cx - x(), dy = cy - y(), dz = cz - z();
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (d <= radius) return w;
        if (blend <= 0f) return 0f;
        return w * (float) Math.max(0.0, 1.0 - (d - radius) / blend);
    }

    @Override
    void onSpawn() {
        scene.addPostVolume(this);
        CgPostRenderer.precacheLook(settings);
    }

    @Override
    void onRemove() {
        scene.postVolumes.remove(this);
    }

    @Override
    void onMoved() {
    }

    @Override
    void onLooked() {
    }

    @Override
    boolean kindBounds(CgAABB out) {
        return false;
    }

    @Override
    void gather(CgView view, CgCamera camera, CgMeshBatches out, CgSceneGather gather) {
    }

    @Override
    public String toString() {
        return "CgPostVolume(priority " + priority + ")";
    }
}
