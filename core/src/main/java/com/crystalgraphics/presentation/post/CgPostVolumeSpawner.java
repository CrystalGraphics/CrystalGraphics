package com.crystalgraphics.presentation.post;

import com.crystalgraphics.render.post.settings.CgPostBlendable;
import com.crystalgraphics.render.post.settings.CgPostBlock;
import com.crystalgraphics.render.post.settings.CgPostSettings;
import com.crystalgraphics.render.view.CgView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import org.joml.Vector4f;

import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.camera.CgCameraSystem;
import com.crystalgraphics.material.CgMaterialProperty;
import com.crystalgraphics.object.value.CgQuantity;
import com.crystalgraphics.object.value.CgUnits;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.scene.component.CgPostVolume;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.sequence.CgSequenceTarget;
import com.crystalgraphics.sequence.definition.CgSequenceBinding;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;
import com.crystalgraphics.sequence.definition.CgSequenceTrack;
import com.crystalgraphics.sequence.CgSequencePlayer;
import com.crystalgraphics.sequence.spawn.CgSequenceSpawned;
import com.crystalgraphics.sequence.spawn.CgSequenceSpawner;
import com.crystalgraphics.sequence.CgSequencer;
import com.crystalgraphics.world.CgAttachment;
import com.crystalgraphics.api.material.CgMaterialInstance;

/**
 * Makes a sequence's {@code post} spawnables: a post volume in a scene, as Unreal's spawnable post process volume, its
 * {@code weight} keyed by a property track ({@code BlendWeight}).
 *
 * <pre>{@code
 * CgPostVolumeSpawner.install(sequencer);           // once, on a client's sequencer
 * }</pre>
 *
 * <pre>{@code
 * spawnables:
 *   - { id: s4, name: flash, post: { bloom: 1.6, exposure: { flash: 1.5 } }, priority: 10, unbound: true }
 *   - { id: s5, name: haze, at: { locator: k1 }, post: { chromatic: 0.3 }, radius: 4, blend_radius: 2 }
 * }</pre>
 *
 * <ul>
 *   <li>An {@code unbound} volume in a sequence with a camera ({@code camera_animation}, {@code camera_cut}) is that
 *       camera's post settings: in a viewer's view at the weight its view goes through the camera
 *       ({@link CgCameraSystem#weightOf}), so a viewer outside it never sees them. Installed without a camera
 *       system, or in a sequence with none, it applies to the whole screen of a viewer the play
 *       {@linkplain CgSequencePlayer#involved() involves}, and to no one else's.</li>
 *   <li>Otherwise it stands where its {@code at} places it, full within
 *       {@code radius} and fading over {@code blend_radius}, following that place each frame.</li>
 *   <li>Its weight starts at 1, as Unreal's; a track keys it from there.</li>
 *   <li>Volumes order by whole priorities: a fractional {@code priority} fails at play, naming it.</li>
 *   <li>Its {@code post.materials} (Unreal's weighted blendables) are the volume's blendables, each drawn
 *       at its material's stage, at their weight times the volume's; a {@code material} track binds one by its name, and its
 *       {@code screen_point} writes where its target stands on screen each frame.</li>
 * </ul>
 */
public final class CgPostVolumeSpawner implements CgSequenceSpawner {

    /** The track kinds that give a sequence a camera of its own, whose post settings its unbound volumes are. */
    private static final Set<String> CAMERA_KINDS = Set.of("camera_animation", "camera_cut");

    private final CgScene scene;
    @Nullable
    private final CgCameraSystem cameras;

    /** Volumes in {@code scene}, with no camera system: every unbound volume applies everywhere. */
    public CgPostVolumeSpawner(CgScene scene) {
        this(scene, null);
    }

    public CgPostVolumeSpawner(CgScene scene, @Nullable CgCameraSystem cameras) {
        this.scene = scene;
        this.cameras = cameras;
    }

    /** Registers one for the {@code post} kind on {@code sequencer}, over the client scene and chain, with no camera system. */
    public static void install(CgSequencer sequencer) {
        install(sequencer, null);
    }

    /**
     * Registers one for the {@code post} kind on {@code sequencer}, over the client scene and chain. A sequence with a
     * camera of its own makes its unbound volumes that camera's post settings, weighed by {@code cameras}.
     */
    public static void install(CgSequencer sequencer, @Nullable CgCameraSystem cameras) {
        sequencer.spawner("post", new CgPostVolumeSpawner(CgScene.client(), cameras));
    }

    private static boolean hasCamera(CgSequenceDefinition d) {
        for (CgSequenceTrack t : d.tracks()) if (CAMERA_KINDS.contains(t.kind())) return true;
        return false;
    }

    @Override
    public CgSequenceSpawned spawn(CgSequencePlayer player, CgSequenceDefinition.Spawnable spawnable, CgAttachment at) {
        Map<?, ?> tree = spawnable.tree();
        String path = player.definition().id() + ": spawnables." + spawnable.id();
        float priority = tree.get("priority") instanceof Number n ? n.floatValue() : 0f;
        if (priority != Math.rint(priority)) throw new CgAssetException(path + ".priority: " + priority + " is not a whole priority");
        CgPostSettings look = CgPostBlock.settings(tree.get("post") instanceof Map<?, ?> m ? m : Map.of());
        List<Blend> blends = new ArrayList<>();
        if (spawnable.definition() instanceof List<?> list) {
            for (Object o : list) {
                CgPostBlendable b = (CgPostBlendable) o;
                CgMaterialInstance material = b.addTo(look, (int) priority);
                if (material == null) continue;
                CgAttachment screen = b.screen() == null ? null
                        : player.attachment(b.screen(), path + ".post.materials." + b.name() + ".screen_point.target");
                blends.add(new Blend(b, material, screen));
            }
        }
        CgPostVolume volume = scene.postVolume(look).priority((int) priority);
        boolean bound = !Boolean.TRUE.equals(tree.get("unbound"));
        if (bound) {
            CgUnits units = player.definition().units();
            volume.radius(length(units, tree.get("radius"))).blend(length(units, tree.get("blend_radius")));
        }
        double[] point = new double[3];
        if (bound && at.place(point, null)) volume.at(point[0], point[1], point[2]);
        volume.spawn();
        // Unbound in a sequence with a camera: that camera's post settings (UCameraComponent::PostProcessSettings), in a
        // viewer's view only at the weight its view goes through that camera.
        boolean camera = !bound && cameras != null && hasCamera(player.definition());
        Spawned spawned = new Spawned(volume, bound && spawnable.follow() ? at : null, blends, camera ? cameras : null, player,
                !bound && !camera);
        spawned.weigh();
        return spawned;
    }

    private static float length(CgUnits units, Object v) {
        return v instanceof Number ? ((Number) units.toHost(v, CgQuantity.LENGTH)).floatValue() : 0f;
    }

    private static final class Spawned implements CgSequenceSpawned {
        private static final int WEIGHT = 0;
        private final CgPostVolume volume;
        private final CgAttachment at;
        private final List<Blend> blends;
        private final double[] point = new double[3];
        @Nullable
        private final CgCameraSystem cameras;
        private final CgSequencePlayer owner;
        /** Unbound with no camera: the whole screen of a viewer its play involves. */
        private final boolean screen;
        private float weight = 1f, viewed = 1f;

        Spawned(CgPostVolume volume, CgAttachment at, List<Blend> blends, @Nullable CgCameraSystem cameras, CgSequencePlayer owner,
                boolean screen) {
            this.volume = volume;
            this.at = at;
            this.blends = blends;
            this.cameras = cameras;
            this.owner = owner;
            this.screen = screen;
            if (cameras != null) viewed = 0f;   // not in a view until its camera is
            else if (screen) viewed = owner.involved() ? 1f : 0f;
        }

        @Override
        public int slot(String name) {
            return name.equals("weight") ? WEIGHT : -1;
        }

        @Override
        public void set(int slot, float[] values, int mask) {
            if (slot == WEIGHT && (mask & 1) != 0) {
                weight = values[0];
                weigh();
            }
        }

        @Override
        public void get(int slot, float[] out) {
            if (slot == WEIGHT) out[0] = weight;
        }

        /**
         * The volume at its weight, times its camera's in the view for a camera's: its post materials, its blendables,
         * draw at their own weights times the volume's, as Unreal weighs a volume's blendables.
         */
        void weigh() {
            volume.weight(weight * viewed);
        }

        @Override
        @Nullable
        public CgSequenceTarget part(CgSequenceBinding.Part part, String name) {
            if (part != CgSequenceBinding.Part.MATERIAL) return null;
            for (Blend b : blends) if (b.blendable.name().equals(name)) return b;
            return null;
        }

        @Override
        public void update() {
            if (cameras != null || screen) {
                float v = cameras != null ? cameras.weightOf(owner) : owner.involved() ? 1f : 0f;
                if (v != viewed) {
                    viewed = v;
                    weigh();
                }
            }
            if (at != null && at.place(point, null)) volume.at(point[0], point[1], point[2]);
            for (Blend b : blends) b.screen();
        }

        @Override
        public void destroy(boolean graceful) {
            volume.remove();
        }

        @Override
        public String toString() {
            return "post volume " + volume.priority();
        }
    }

    /**
     * One post material of a volume: its material's values as a {@code material} track's target, and its
     * {@code screen_point} (Kakarot's {@code UKoratPostProcessWorldToScreenComponent}): the followed place projected
     * through the world's camera into screen UV, written each frame.
     */
    private static final class Blend implements CgSequenceTarget {
        final CgPostBlendable blendable;
        final CgMaterialInstance material;
        @Nullable
        private final CgAttachment screen;
        private final List<String> names = new ArrayList<>();
        private final List<float[]> values = new ArrayList<>();
        /** Each slot's property width: 1 for a float, else written as a vec4. */
        private final List<Integer> widths = new ArrayList<>();
        private final double[] point = new double[3];
        private final Vector4f clip = new Vector4f();
        private final float[] uv = new float[4];

        Blend(CgPostBlendable blendable, CgMaterialInstance material, @Nullable CgAttachment screen) {
            this.blendable = blendable;
            this.material = material;
            this.screen = screen;
        }

        @Override
        public int slot(String name) {
            int i = names.indexOf(name);
            if (i >= 0) return i;
            for (CgMaterialProperty p : material.properties()) {
                if (!p.getName().equals(name) || p.getType().isSampler()) continue;
                names.add(name);
                widths.add(p.getFloatValue().length);
                values.add(Arrays.copyOf(p.getFloatValue(), 4));
                return names.size() - 1;
            }
            return -1;
        }

        @Override
        public void set(int slot, float[] in, int mask) {
            float[] v = values.get(slot);
            for (int i = 0; i < 4; i++) if ((mask & 1 << i) != 0) v[i] = in[i];
            CgMaterialInstance m = material;
            if (widths.get(slot) == 1) m.set1f(names.get(slot), v[0]);
            else m.vec4(names.get(slot), v[0], v[1], v[2], v[3]);
        }

        @Override
        public void get(int slot, float[] out) {
            System.arraycopy(values.get(slot), 0, out, 0, 4);
        }

        /** The followed place on screen, 0 to 1 from the bottom left as the material's UV; nothing where it is behind. */
        void screen() {
            if (screen == null || !screen.place(point, null)) return;
            CgCamera view = CgView.main().camera();
            clip.set((float) (point[0] - view.x()), (float) (point[1] - view.y()), (float) (point[2] - view.z()), 1f);
            view.view().transform(clip);
            view.projection().transform(clip);
            if (clip.w <= 1e-6f) return;
            float x = clip.x / clip.w * 0.5f + 0.5f, y = clip.y / clip.w * 0.5f + 0.5f;
            if (blendable.screenClamp()) {
                x = Math.max(0f, Math.min(1f, x));
                y = Math.max(0f, Math.min(1f, y));
            }
            uv[0] = x;
            uv[1] = y;
            CgMaterialInstance b = material;
            if (blendable.screenVector() != null) b.vec4(blendable.screenVector(), uv[0], uv[1], 0f, 0f);
            if (blendable.screenX() != null) b.set1f(blendable.screenX(), uv[0]);
            if (blendable.screenY() != null) b.set1f(blendable.screenY(), uv[1]);
        }
    }
}
