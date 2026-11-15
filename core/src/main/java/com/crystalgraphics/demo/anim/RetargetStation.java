package com.crystalgraphics.demo.anim;

import com.crystalgraphics.animation.retarget.CgRetargetBake;
import com.crystalgraphics.animation.retarget.CgRetargetProcessor;
import com.crystalgraphics.animation.retarget.CgRetargeter;
import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.collision.debug.CgCollisionDebug;
import com.crystalgraphics.job.CgJob;
import com.crystalgraphics.platform.input.CgKeyCodes;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Goku's clips on RPG-Core's bodies: Goku on a gold plinth, and beside him every humanoid body playing his clip, each
 * retargeted onto its own skeleton by its default rig and baked once, so nothing retargets per frame.
 *
 * <ul>
 *   <li>Left/Right: Goku's clip; every body starts it together.</li>
 *   <li>Up/Down: what the retarget runs, to see what each step does: everything; rotations alone (no IK, the hands
 *       drift from Goku's); no pelvis motion (the body stays put while Goku moves); no floor constraint; the
 *       retarget pose alone (the arms turned out to meet Goku's stance).</li>
 *   <li>`: each body's rig over it, a colour a chain, and trails behind every hand.</li>
 *   <li>\: the playlist, every clip in turn.</li>
 * </ul>
 */
final class RetargetStation implements AnimDemoStation {

    private static final Logger LOGGER = LogManager.getLogger(RetargetStation.class);
    private static final double GAP = 1.5;
    /** Samples a hand trail keeps. */
    private static final int TRAIL = 48;

    /** What the retarget runs: the full stack, or it without one step. */
    private enum Mode {
        FULL("everything"),
        NO_IK("rotations only, no IK", "ik_chains"),
        NO_PELVIS("no pelvis motion", "pelvis_motion"),
        NO_FLOOR("no floor constraint", "floor_constraint"),
        POSE("the retarget pose alone", "fk_chains", "pelvis_motion", "ik_chains", "floor_constraint");

        final String label;
        final Set<String> dropped;

        Mode(String label, String... dropped) {
            this.label = label;
            this.dropped = Set.of(dropped);
        }

        CgRetargeter apply(CgRetargeter r) {
            List<CgRetargeter.Op> kept = new ArrayList<>();
            for (CgRetargeter.Op op : r.ops()) if (!dropped.contains(op.kind())) kept.add(op);
            return r.toBuilder().ops(kept.toArray(new CgRetargeter.Op[0])).build();
        }
    }

    /** A body playing Goku's clips through its own retarget, one processor a mode. */
    private static final class Target {
        final AnimDemoCast.Body body;
        final AnimDemoRigs.Drawn rig;
        final CgRetargetProcessor[] processors = new CgRetargetProcessor[Mode.values().length];
        final Trail left, right;
        volatile CgAnimSequence ready;

        Target(AnimDemoCast.Body body, AnimDemoRigs.Drawn rig, CgRetargeter retargeter, AnimDemoCast source) {
            this.body = body;
            this.rig = rig;
            for (Mode m : Mode.values()) {
                processors[m.ordinal()] = new CgRetargetProcessor(m.apply(retargeter), source.mesh.skeleton(), body.cast.mesh.skeleton());
            }
            left = new Trail(body, rig, "arm_l", 0xFF40E0FF);
            right = new Trail(body, rig, "arm_r", 0xFFFF9040);
        }
    }

    /** Where one hand has been, drawn as a line fading back. */
    private static final class Trail {
        final AnimDemoCast.Body body;
        final AnimDemoRigs.Drawn rig;
        final int hand, colour;
        final double[] xyz = new double[TRAIL * 3];
        final Vector3d at = new Vector3d();
        int head, count;

        Trail(AnimDemoCast.Body body, AnimDemoRigs.Drawn rig, String chain, int colour) {
            this.body = body;
            this.rig = rig;
            int[] bones = rig.bones.get(rig.rig.chains().indexOf(rig.rig.chain(chain)));
            this.hand = bones.length == 0 ? -1 : bones[bones.length - 1];
            this.colour = colour;
        }

        void sample() {
            if (hand < 0) return;
            rig.world(body, hand, at);
            xyz[head * 3] = at.x;
            xyz[head * 3 + 1] = at.y;
            xyz[head * 3 + 2] = at.z;
            head = (head + 1) % TRAIL;
            count = Math.min(count + 1, TRAIL);
        }

        void draw() {
            for (int i = 1; i < count; i++) {
                int a = Math.floorMod(head - i, TRAIL), b = Math.floorMod(head - i - 1, TRAIL);
                int alpha = 255 * (count - i) / count;
                CgCollisionDebug.line(xyz[a * 3], xyz[a * 3 + 1], xyz[a * 3 + 2], xyz[b * 3], xyz[b * 3 + 1], xyz[b * 3 + 2],
                        alpha << 24 | colour & 0xFFFFFF);
            }
        }

        void clear() {
            count = 0;
        }
    }

    private AnimDemoCast.Body goku;
    private AnimDemoRigs.Drawn gokuRig;
    private Trail gokuLeft, gokuRight;
    private final List<Target> targets = new ArrayList<>();
    private int clip, mode;
    private boolean overlay = true, playlist;
    /** The clip and mode asked for, and the one bakes are running for; -1 when none is running. */
    private volatile int request = -1;
    private volatile int baked;
    private volatile long bakeNanos;
    private String status = "";

    @Override
    public String name() {
        return "Retarget";
    }

    @Override
    public void build(AnimDemoSet set, AnimDemoCasts casts, double x, double y, double z) {
        AnimDemoCast source = null;
        for (AnimDemoCast c : casts.kitCasts()) {
            if (AnimDemoRigs.of(c) != null && !c.clips.isEmpty()) {
                source = c;
                break;
            }
        }
        if (source == null) return;
        gokuRig = AnimDemoRigs.of(source);
        List<AnimDemoCast> bodies = casts.humanoids();
        double x0 = x - GAP * bodies.size() / 2.0;
        set.box(x0 - 0.6, y, z - 0.6, x0 + 0.6, y + 0.15, z + 0.6, 0xFFD8A830);
        goku = source.spawn().at(x0, y + 0.15, z, 0f);
        gokuLeft = new Trail(goku, gokuRig, "arm_l", 0xFF40E0FF);
        gokuRight = new Trail(goku, gokuRig, "arm_r", 0xFFFF9040);
        set.box(x0 + GAP * 0.5, y, z - 0.6, x0 + GAP * (bodies.size() + 0.5), y + 0.05, z + 0.6, 0xFF5A6070);
        for (int i = 0; i < bodies.size(); i++) {
            AnimDemoCast cast = bodies.get(i);
            AnimDemoRigs.Drawn rig = AnimDemoRigs.of(cast);
            if (rig == null) continue;
            try {
                CgRetargeter r = CgRetargeter.auto(source.mesh.skeleton(), gokuRig.rig, cast.mesh.skeleton(), rig.rig);
                targets.add(new Target(cast.spawn().at(x0 + GAP * (i + 1), y + 0.05, z, 0f), rig, r, source));
            } catch (IllegalArgumentException e) {
                LOGGER.warn("[anim-demo] {} cannot take {}'s clips, left out: {}", cast.name, source.name, e.getMessage());
            }
        }
        clip = Math.min(clip, source.clips.size() - 1);
        bake();
    }

    /** Starts baking the clip and mode asked for, one job a body. */
    private void bake() {
        if (goku == null) return;
        int asked = clip * Mode.values().length + mode;
        request = asked;
        baked = 0;
        CgAnimSequence sequence = goku.cast.clips.get(clip).sequence();
        long start = System.nanoTime();
        for (Target t : targets) {
            CgRetargetProcessor p = t.processors[mode];
            CgJob.run(() -> {
                CgAnimSequence s = CgRetargetBake.cached(p, sequence);
                if (request != asked) return;
                t.ready = s;
                synchronized (this) {
                    baked++;
                    bakeNanos = System.nanoTime() - start;
                }
            }).named("anim-demo retarget bake").onError(e -> LOGGER.warn("[anim-demo] a bake failed: {}", e.detail(), e.cause()));
        }
        status = "baking";
    }

    private void playAll() {
        AnimDemoCast.Clip c = goku.cast.clips.get(clip);
        goku.instance.play(c.sequence(), !playlist);
        for (Target t : targets) t.body.instance.play(t.ready, !playlist);
        for (Trail tr : trails()) tr.clear();
        status = String.format(Locale.ROOT, "baked in %.1f ms", bakeNanos / 1e6);
    }

    private List<Trail> trails() {
        List<Trail> out = new ArrayList<>(List.of(gokuLeft, gokuRight));
        for (Target t : targets) {
            out.add(t.left);
            out.add(t.right);
        }
        return out;
    }

    @Override
    public float[] view() {
        return new float[]{0f, 1.4f, 6f, 0f, -6f};
    }

    @Override
    public void update(float dt) {
        if (goku == null) return;
        if (request >= 0 && baked == targets.size()) {
            request = -1;
            playAll();
        }
        if (playlist && request < 0 && goku.instance.position() >= goku.cast.clips.get(clip).sequence().duration() - 1e-3f) {
            clip = (clip + 1) % goku.cast.clips.size();
            bake();
        }
        if (!overlay) return;
        gokuRig.draw(goku);
        for (Target t : targets) t.rig.draw(t.body);
        for (Trail tr : trails()) {
            tr.sample();
            tr.draw();
        }
    }

    @Override
    public boolean press(int key, boolean shift) {
        if (goku == null) return false;
        int clips = goku.cast.clips.size(), modes = Mode.values().length;
        switch (key) {
            case CgKeyCodes.KEY_RIGHT -> clip = (clip + (shift ? 10 : 1)) % clips;
            case CgKeyCodes.KEY_LEFT -> clip = Math.floorMod(clip - (shift ? 10 : 1), clips);
            case CgKeyCodes.KEY_UP -> mode = (mode + 1) % modes;
            case CgKeyCodes.KEY_DOWN -> mode = Math.floorMod(mode - 1, modes);
            case CgKeyCodes.KEY_GRAVE -> {
                overlay = !overlay;
                return true;
            }
            case CgKeyCodes.KEY_BACKSLASH -> playlist = !playlist;
            default -> {
                return false;
            }
        }
        bake();
        return true;
    }

    @Override
    public String hud() {
        if (goku == null) return "\nNo kit character with a rig loaded: Goku is the source";
        AnimDemoCast.Clip c = goku.cast.clips.get(clip);
        StringBuilder ratios = new StringBuilder();
        for (Target t : targets) {
            ratios.append(ratios.length() == 0 ? "" : ", ")
                    .append(String.format(Locale.ROOT, "%s x%.2f", t.body.cast.name.replace("Humanoid", "").trim(),
                            t.processors[0].heightRatio()));
        }
        return String.format(Locale.ROOT, "%nGoku's clip [Left/Right, Shift x10]: %s (%d / %d), %.2f / %.2f s"
                        + "%nRetarget [Up/Down]: %s | %s%nRig and hand trails [`]: %s | Playlist [\\]: %s"
                        + "%nOnto %d bodies, by height: %s",
                c.name(), clip + 1, goku.cast.clips.size(), goku.instance.position(), c.sequence().duration(),
                Mode.values()[mode].label, status, overlay ? "on" : "off", playlist ? "on" : "off", targets.size(), ratios);
    }

    @Override
    public void delete() {
        if (goku != null) goku.remove();
        goku = null;
        for (Target t : targets) t.body.remove();
        targets.clear();
        request = -1;
    }
}
