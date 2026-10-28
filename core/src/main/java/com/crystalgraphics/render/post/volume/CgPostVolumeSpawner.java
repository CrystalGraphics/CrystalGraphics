package com.crystalgraphics.render.post.volume;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import org.joml.Vector4f;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.shader.CgShaderBindings;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.gl.material.CgMaterialProperty;
import com.crystalgraphics.object.value.CgQuantity;
import com.crystalgraphics.object.value.CgUnits;
import com.crystalgraphics.render.post.CgPostMaterial;
import com.crystalgraphics.render.post.CgPostPoint;
import com.crystalgraphics.render.post.CgPostStack;
import com.crystalgraphics.render.stage.CgHostView;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.sequence.CgSequenceTarget;
import com.crystalgraphics.sequence.definition.CgSequenceBinding;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;
import com.crystalgraphics.sequence.CgSequencePlayer;
import com.crystalgraphics.sequence.spawn.CgSequenceSpawned;
import com.crystalgraphics.sequence.spawn.CgSequenceSpawner;
import com.crystalgraphics.sequence.CgSequencer;
import com.crystalgraphics.world.CgAttachment;

/**
 * Makes a sequence's {@code post} spawnables: a volume on the post stack, as Unreal's spawnable post process volume, its
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
 *   <li>An {@code unbound} volume applies everywhere; otherwise it stands where its {@code at} places it, full within
 *       {@code radius} and fading over {@code blend_radius}, following that place each frame.</li>
 *   <li>Its weight starts at 1, as Unreal's; a track keys it from there.</li>
 *   <li>The stack orders volumes by whole priorities: a fractional {@code priority} fails at play, naming it.</li>
 *   <li>Its {@code post.materials} (Unreal's weighted blendables) each draw as a {@link CgPostMaterial} after the
 *       composite, at their weight times the volume's; a {@code material} track binds one by its name, and its
 *       {@code screen_point} writes where its target stands on screen each frame.</li>
 * </ul>
 */
public final class CgPostVolumeSpawner implements CgSequenceSpawner {

    private final CgPostStack stack;

    public CgPostVolumeSpawner(CgPostStack stack) {
        this.stack = stack;
    }

    /** Registers one for the {@code post} kind on {@code sequencer}, over the client's post stack. */
    public static void install(CgSequencer sequencer) {
        sequencer.spawner("post", new CgPostVolumeSpawner(CgPostStack.get()));
    }

    @Override
    public CgSequenceSpawned spawn(CgSequencePlayer player, CgSequenceDefinition.Spawnable spawnable, CgAttachment at) {
        Map<?, ?> tree = spawnable.tree();
        String path = player.definition().id() + ": spawnables." + spawnable.id();
        float priority = tree.get("priority") instanceof Number n ? n.floatValue() : 0f;
        if (priority != Math.rint(priority)) throw new CgAssetException(path + ".priority: " + priority + " is not a whole priority");
        CgPostVolume volume = stack.volume((int) priority, CgPostBlock.settings(tree.get("post") instanceof Map<?, ?> m ? m : Map.of()));
        boolean bound = !Boolean.TRUE.equals(tree.get("unbound"));
        if (bound) {
            CgUnits units = player.definition().units();
            volume.radius(length(units, tree.get("radius"))).blend(length(units, tree.get("blend_radius")));
        }
        double[] point = new double[3];
        if (bound && at.place(point, null)) volume.at(point[0], point[1], point[2]);
        List<Blend> blends = new ArrayList<>();
        if (spawnable.definition() instanceof List<?> list) {
            for (Object o : list) {
                CgSequenceDefinition.Blendable b = (CgSequenceDefinition.Blendable) o;
                if (b.material() == null) continue;
                CgPostMaterial post = CgPostMaterial.of(CgMaterial.newInstance(b.material(), null), point(b.material().post()), (int) priority);
                CgAttachment screen = b.screen() == null ? null
                        : player.attachment(b.screen(), path + ".post.materials." + b.name() + ".screen_point.target");
                blends.add(new Blend(b, post, stack.add(post), screen));
            }
        }
        Spawned spawned = new Spawned(volume, bound && spawnable.follow() ? at : null, blends);
        spawned.weigh();
        return spawned;
    }

    /**
     * Where a post material's {@code post.stage} draws (11 § post materials, after 4.27 PostProcessing.cpp's chains):
     * before translucency onto the lit opaque scene, before tonemapping after the transparent passes, the rest after
     * the composite. Unreal's default is after tonemapping. {@code replacing_tonemapper} draws after the composite until
     * the composite takes one.
     */
    private static CgPostPoint point(@Nullable Map<?, ?> post) {
        Object stage = post == null ? null : post.get("stage");
        if ("before_translucency".equals(stage)) return CgPostPoint.BEFORE_TRANSLUCENCY;
        if ("before_tonemapping".equals(stage)) return CgPostPoint.AFTER_WORLD;
        return CgPostPoint.AFTER_COMPOSITE;
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
        private float weight = 1f;

        Spawned(CgPostVolume volume, CgAttachment at, List<Blend> blends) {
            this.volume = volume;
            this.at = at;
            this.blends = blends;
        }

        @Override
        public int slot(String name) {
            return name.equals("weight") ? WEIGHT : -1;
        }

        @Override
        public void set(int slot, float[] values, int mask) {
            if (slot == WEIGHT && (mask & 1) != 0) {
                volume.weight(weight = values[0]);
                weigh();
            }
        }

        @Override
        public void get(int slot, float[] out) {
            if (slot == WEIGHT) out[0] = weight;
        }

        /** Each post material at its own weight times the volume's, as Unreal blends a volume's weighted blendables. */
        void weigh() {
            for (Blend b : blends) b.post.weight(b.blendable.enabled() ? b.blendable.weight() * weight : 0f);
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
            if (at != null && at.place(point, null)) volume.at(point[0], point[1], point[2]);
            for (Blend b : blends) b.screen();
        }

        @Override
        public void destroy(boolean graceful) {
            volume.close();
            for (Blend b : blends) b.registration.close();
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
    private static final class Blend implements CgSequenceTarget, Consumer<CgShaderBindings> {
        final CgSequenceDefinition.Blendable blendable;
        private int writing;
        final CgPostMaterial post;
        final CgRenderStage.Registration registration;
        @Nullable
        private final CgAttachment screen;
        private final List<String> names = new ArrayList<>();
        private final List<float[]> values = new ArrayList<>();
        /** Each slot's property width: 1 for a float, else written as a vec4. */
        private final List<Integer> widths = new ArrayList<>();
        private final double[] point = new double[3];
        private final Vector4f clip = new Vector4f();
        private final float[] uv = new float[4];

        Blend(CgSequenceDefinition.Blendable blendable, CgPostMaterial post, CgRenderStage.Registration registration,
              @Nullable CgAttachment screen) {
            this.blendable = blendable;
            this.post = post;
            this.registration = registration;
            this.screen = screen;
        }

        @Override
        public int slot(String name) {
            int i = names.indexOf(name);
            if (i >= 0) return i;
            for (CgMaterialProperty p : post.material().properties()) {
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
            writing = slot;
            post.material().applyProperties(this);
        }

        @Override
        public void get(int slot, float[] out) {
            System.arraycopy(values.get(slot), 0, out, 0, 4);
        }

        /** The followed place on screen, 0 to 1 from the bottom left as the material's UV; nothing where it is behind. */
        void screen() {
            if (screen == null || !screen.place(point, null)) return;
            CgHostView view = CgRenderStage.WORLD_OPAQUE.host().view();
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
            writing = -1;
            post.material().applyProperties(this);
        }

        /** Writes the value at slot {@link #writing}, or at -1 the screen point; reused, so a frame allocates nothing. */
        @Override
        public void accept(CgShaderBindings b) {
            if (writing >= 0) {
                float[] v = values.get(writing);
                if (widths.get(writing) == 1) b.set1f(names.get(writing), v[0]);
                else b.vec4(names.get(writing), v[0], v[1], v[2], v[3]);
                return;
            }
            if (blendable.screenVector() != null) b.vec4(blendable.screenVector(), uv[0], uv[1], 0f, 0f);
            if (blendable.screenX() != null) b.set1f(blendable.screenX(), uv[0]);
            if (blendable.screenY() != null) b.set1f(blendable.screenY(), uv[1]);
        }
    }
}
