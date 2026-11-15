package com.crystalgraphics.render.scene.capture;

import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.scene.component.CgPrimitiveComponent;
import com.crystalgraphics.render.scene.component.CgSceneCaptureComponent;
import com.crystalgraphics.render.target.CgRenderTarget;
import com.crystalgraphics.sequence.CgSequencePlayer;
import com.crystalgraphics.sequence.CgSequenceTarget;
import com.crystalgraphics.sequence.CgSequencer;
import com.crystalgraphics.sequence.definition.CgSequenceBinding;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;
import com.crystalgraphics.sequence.spawn.CgSequenceSpawned;
import com.crystalgraphics.sequence.spawn.CgSequenceSpawner;
import com.crystalgraphics.world.CgAttachment;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Makes a sequence's {@code capture} spawnables: a {@link CgSceneCaptureComponent} of the client scene standing where
 * its {@code at} puts it, drawing into the render target its {@code target} names, once at its spawn or every
 * {@code every} frames. Blueprint 05's M5: what a capture spawnable draws, a material shows.
 *
 * <pre>{@code
 * CgSceneCaptureSpawner.install(sequencer);   // CgVfxSequencer.install does it for the engine's sequencers
 * }</pre>
 *
 * <pre>{@code
 * spawnables:
 *   - id: s3
 *     name: mirror_shot
 *     at: { role: caster, offset: [0, 1.6, 2], rotation: [0, 180, 0] }
 *     capture: { target: mymod:textures/fx/mirror, fov: 60, every: 1, hide: [ { role: caster } ] }
 * }</pre>
 *
 * <ul>
 *   <li>{@code target} is a render target asset's path (a settings file with a {@code render_target} block), checked as
 *       its play starts.</li>
 *   <li>{@code show} and {@code hide} name roles and spawnables that draw as a scene primitive
 *       ({@link CgSequenceTarget#primitive}); one that draws as none fails at the spawn, naming it.</li>
 *   <li>A {@code fov} property track keys its field of view.</li>
 * </ul>
 */
public final class CgSceneCaptureSpawner implements CgSequenceSpawner {

    /**
     * A {@code capture} block as its sequence compiled it: its target's path, its horizontal field of view in degrees
     * (NaN for the capture's own), frames between captures (0: once at its spawn), and the bindings it shows alone or
     * leaves out.
     */
    public record Spec(String target, float fov, int every, List<CgSequenceBinding> show, List<CgSequenceBinding> hide) {

        /** {@code block} compiled at {@code path}, each binding through {@code bind}. */
        public static Spec of(Map<?, ?> block, String path, Function<Object, CgSequenceBinding> bind) {
            if (!(block.get("target") instanceof String target)) throw new CgAssetException(path + " names no target");
            float fov = block.get("fov") instanceof Number n ? n.floatValue() : Float.NaN;
            int every = block.get("every") instanceof Number n ? n.intValue() : 0;
            if (every < 0) throw new CgAssetException(path + ".every is " + every + ": frames between captures, 0 for once");
            return new Spec(target, fov, every, bindings(block.get("show"), bind), bindings(block.get("hide"), bind));
        }

        private static List<CgSequenceBinding> bindings(Object list, Function<Object, CgSequenceBinding> bind) {
            if (!(list instanceof List<?> l)) return List.of();
            List<CgSequenceBinding> out = new ArrayList<>(l.size());
            for (Object b : l) out.add(bind.apply(b));
            return List.copyOf(out);
        }
    }

    private static final int FOV = 0;

    /** Registers one for the {@code capture} kind on {@code sequencer}. */
    public static void install(CgSequencer sequencer) {
        sequencer.spawner("capture", new CgSceneCaptureSpawner());
    }

    @Override
    public void check(CgSequenceDefinition definition, CgSequenceDefinition.Spawnable spawnable) {
        Spec spec = spec(definition, spawnable);
        if (!CgRenderTarget.isAsset(spec.target())) {
            throw new CgAssetException(path(definition, spawnable) + ".target: " + spec.target()
                    + " is no render target: its settings file holds no render_target block");
        }
    }

    @Override
    public CgSequenceSpawned spawn(CgSequencePlayer player, CgSequenceDefinition.Spawnable spawnable, CgAttachment at) {
        Spec spec = spec(player.definition(), spawnable);
        CgSceneCaptureComponent capture = CgScene.client().capture().target(CgRenderTarget.of(spec.target()));
        if (!Float.isNaN(spec.fov())) capture.fov(spec.fov());
        String path = path(player.definition(), spawnable);
        for (CgSequenceBinding b : spec.show()) {
            CgPrimitiveComponent<?> p = primitive(player, b, path + ".show");
            if (p != null) capture.view().showOnly(p);
        }
        for (CgSequenceBinding b : spec.hide()) {
            CgPrimitiveComponent<?> p = primitive(player, b, path + ".hide");
            if (p != null) capture.view().hide(p);
        }
        if (spec.every() == 1) capture.everyFrame(true);
        else capture.captureScene();
        Spawned s = new Spawned(capture, at, spec.every());
        s.update();
        capture.spawn();
        return s;
    }

    /** What {@code binding} draws as; null while it resolves nothing (a role left empty, a spawnable not made yet). */
    private static CgPrimitiveComponent<?> primitive(CgSequencePlayer player, CgSequenceBinding binding, String path) {
        CgSequenceTarget target = player.target(binding);
        if (target == null) return null;
        CgPrimitiveComponent<?> p = target.primitive();
        if (p == null) throw new CgAssetException(path + ": " + target + " draws as no scene primitive, which a capture cannot show or hide");
        return p;
    }

    private static Spec spec(CgSequenceDefinition definition, CgSequenceDefinition.Spawnable spawnable) {
        if (!(spawnable.definition() instanceof Spec spec)) throw new CgAssetException(path(definition, spawnable) + " names no capture block");
        return spec;
    }

    private static String path(CgSequenceDefinition definition, CgSequenceDefinition.Spawnable spawnable) {
        return definition.id() + ": spawnables." + spawnable.id() + ".capture";
    }

    private static final class Spawned implements CgSequenceSpawned {
        private final CgSceneCaptureComponent capture;
        private final CgAttachment at;
        private final int every;
        private final double[] point = new double[3];
        private final Quaternionf turn = new Quaternionf();
        private int frames;

        Spawned(CgSceneCaptureComponent capture, CgAttachment at, int every) {
            this.capture = capture;
            this.at = at;
            this.every = every;
        }

        @Override
        public int slot(String name) {
            return name.equals("fov") || name.equals("capture.fov") ? FOV : -1;
        }

        @Override
        public void set(int slot, float[] values, int mask) {
            if (slot == FOV && (mask & 1) != 0) capture.fov(values[0]);
        }

        @Override
        public void get(int slot, float[] out) {
            if (slot == FOV) out[0] = capture.fov();
        }

        /** Where its place stands this frame; a capture every few frames asked for on each of them. */
        @Override
        public void update() {
            if (at.place(point, turn)) capture.at(point[0], point[1], point[2]).rotation(turn);
            if (every > 1 && ++frames % every == 0) capture.captureScene();
        }

        @Override
        public void destroy(boolean graceful) {
            capture.remove();
        }

        @Override
        public CgPrimitiveComponent<?> primitive() {
            return capture;
        }

        @Override
        public String toString() {
            return "scene capture into " + capture.target();
        }
    }
}
