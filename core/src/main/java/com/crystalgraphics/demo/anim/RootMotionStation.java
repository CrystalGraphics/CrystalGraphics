package com.crystalgraphics.demo.anim;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntConsumer;

import javax.annotation.Nullable;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import com.crystalgraphics.animation.CgAnimInstance;
import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.api.animation.CgClipModifiers;
import com.crystalgraphics.api.animation.CgSkeleton;
import com.crystalgraphics.api.animation.CgWarpWindow;
import com.crystalgraphics.api.mesh.CgSkeletalMesh;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.net.object.CgAnchor;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.world.CgEntityMovement;
import com.crystalgraphics.platform.world.CgEntityQuery;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.scene.component.CgSkeletalMeshComponent;
import com.crystalgraphics.sequence.CgSequencePlayer;
import com.crystalgraphics.sequence.CgSequencer;
import com.crystalgraphics.sequence.animation.CgSequenceSkeletal;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;
import com.crystalgraphics.sequence.net.CgSequenceNet;
import com.crystalgraphics.world.CgEntityQueries;
import com.crystalgraphics.world.actor.CgEntity;
import com.crystalgraphics.world.actor.CgEntityActor;
import com.crystalgraphics.world.actor.CgEntityActors;

/**
 * Root motion moving Minecraft's entities: every husk that joins the world while it stands is played by Goku, whose
 * server actor lunges at the nearest player in reach, motion warped to stop at arm's length; its root motion moves the
 * husk on the server, and the lunge is served to every client that sees it, so a second client watches the same
 * strike. The player is Goku too: Up lunges at the nearest husk, the root motion moving the player's own entity.
 *
 * <p>Only husks that join after it is built: summon them ({@code /summon minecraft:husk ~ ~ ~4 {NoAI:1b}}, NoAI so
 * their own walking stays out of it). A host with no server (the harness) shows the station's hint alone.</p>
 *
 * <p>Keys: Up the player's lunge, Down the husks' lunges on and off, ` the stand-off.</p>
 */
final class RootMotionStation implements AnimDemoStation {

    private static final String CLIP = "CMB_S_Range05_Cut";
    private static final String TYPE = "minecraft:husk";
    private static final float LUNGE_START = 0.2f, LUNGE_END = 0.6f, STAND_OFF = 0.9f;
    /** How near a target must be to lunge at, metres: the clip's own 2.4 m, warped down or up to these. */
    private static final double MIN_REACH = 1.2, MAX_REACH = 5.0;
    /** Seconds between one husk's lunges. */
    private static final float REST = 2.5f;

    /**
     * What both sides play, made once at build: read by the server's thread as well as the client's, never changed
     * after. Whether husks lunge and the stand-off are the station's switches, read each tick.
     */
    private static final class Kit {
        final CgSkeletalMesh mesh;
        final Matrix4f fit;
        final AnimDemoCast cast;
        final CgAnimSequence lunge, stand;
        final CgSequenceDefinition sequence;
        volatile boolean husksLunge = true, standOff = true;

        Kit(AnimDemoCast cast, CgAnimSequence lunge, CgAnimSequence stand, CgSequenceDefinition sequence) {
            this.cast = cast;
            this.mesh = cast.mesh;
            this.fit = new Matrix4f(cast.fit);
            this.lunge = lunge;
            this.stand = stand;
            this.sequence = sequence;
        }
    }

    /** Goku playing a husk or the player: its own role in the lunge. */
    private static final class Fighter extends CgEntityActor implements CgSequenceSkeletal {
        private final Kit kit;
        private final double[] pose = new double[CgEntityQuery.POSE_LENGTH];
        private final IntConsumer nearest = this::nearer;
        private CgSkeletalMeshComponent body;
        @Nullable
        private CgSequencePlayer playing;
        @Nullable
        private CgEntityQuery entities;
        private float rest = REST;
        private int lunges;
        /** The nearest target found by the last search: its id, where it stands, how far. */
        private int target;
        private double tx, ty, tz, best, cx, cz;
        private int wanted;

        Fighter(Kit kit) {
            this.kit = kit;
        }

        @Override
        protected void onSpawn(CgEntity entity) {
            CgSkeletalMeshComponent c = scene().skeletal(kit.mesh);
            if (!server()) c.materials(kit.cast.materials::apply);
            body = rootMotion(add(c));
            body.transform(kit.fit);
            body.anim().play(kit.stand, true);
        }

        @Override
        protected void tick(CgEntity husk, float dt) {
            if (playing != null) {
                if (playing.finished() || playing.stopped()) {
                    playing.stop();
                    playing = null;
                } else if (server() && body.anim().motionWarping().open() != null && find(husk, CgEntityQuery.KIND_PLAYER)) {
                    aim(husk);                                       // re-aimed each tick: the player moves
                }
            }
            if (!server() || playing != null) return;
            Object entity = serverEntity();
            if (entity == null || !find(husk, CgEntityQuery.KIND_PLAYER)) return;
            // between lunges it turns to face the player, as a fighter squaring up
            CgEntityMovement.rotate(entity, (float) Math.toDegrees(Math.atan2(-(tx - husk.x()), tz - husk.z())), 0f);
            rest -= dt;
            if (!kit.husksLunge || rest > 0f || best < MIN_REACH || best > MAX_REACH) return;
            rest = REST;
            aim(husk);
            CgSequencer sequencer = CgSequencer.of(CgWorld.serverWorld());
            if (sequencer == null) sequencer = CgWorld.serverWorld().add(new CgSequencer());
            playing = sequencer.play(kit.sequence, Map.of("caster", this));
            CgSequenceNet.serve(playing, CgAnchor.on(entity), husk.id()).start();
            lunges++;
        }

        /** The local player's lunge at the nearest husk, on this client: false where none is in reach. */
        boolean lungeAtNearestHusk() {
            if (server() || playing != null) return false;
            CgEntity me = entity();
            if (!find(me, CgEntityQuery.KIND_MONSTER) || best > MAX_REACH) return false;
            aim(me);
            CgSequencer sequencer = CgSequencer.of(CgWorld.client());
            if (sequencer == null) return false;
            playing = sequencer.play(kit.sequence, Map.of("caster", this));
            lunges++;
            return true;
        }

        /** A client's copy of a lunge its server served. */
        @Nullable
        CgSequencePlayer showServed() {
            CgSequencer sequencer = CgSequencer.of(CgWorld.client());
            if (sequencer == null) return null;
            if (playing != null) playing.stop();
            playing = sequencer.play(kit.sequence, Map.of("caster", this));
            lunges++;
            return playing;
        }

        /** Its warp target where the target stands, facing it from there. */
        private void aim(CgEntity from) {
            float yaw = (float) Math.atan2(tx - from.x(), tz - from.z());
            body.anim().motionWarping().target("opponent", tx, ty, tz, yaw).standOff("opponent", kit.standOff ? STAND_OFF : 0f);
        }

        /** Finds the nearest entity of {@code kind} within reach of {@code from} but itself; true where there is one. */
        private boolean find(CgEntity from, int kind) {
            entities = server() ? CgEntityQueries.of(serverEntity()) : CgEntityQueries.client();
            wanted = kind;
            target = -1;
            best = Double.MAX_VALUE;
            cx = from.x();
            cz = from.z();
            double r = MAX_REACH + 3;
            entities.within(cx - r, from.y() - 3, cz - r, cx + r, from.y() + 3, cz + r, nearest);
            if (target < 0) return false;
            entities.pose(target, 1f, pose);
            tx = pose[CgEntityQuery.X];
            ty = pose[CgEntityQuery.Y];
            tz = pose[CgEntityQuery.Z];
            return true;
        }

        private void nearer(int id) {
            if (id == entity().id() || entities.kind(id) != wanted || !entities.pose(id, 1f, pose)) return;
            double d = Math.hypot(pose[CgEntityQuery.X] - cx, pose[CgEntityQuery.Z] - cz);
            if (d < best) {
                best = d;
                target = id;
            }
        }

        @Override public CgAnimInstance instance() {
            return body.anim();
        }

        @Override public CgAnimSequence clip(String id) {
            return kit.lunge;
        }

        @Override public int slot(String name) {
            return -1;
        }

        @Override public void set(int slot, float[] values, int mask) {
        }

        @Override public void get(int slot, float[] out) {
        }
    }

    @Nullable
    private Kit kit;
    @Nullable
    private CgEntityActors.Registration husks, player;
    private String status = "";

    @Override
    public String name() {
        return "Root motion";
    }

    @Override
    public void build(AnimDemoSet set, AnimDemoCasts casts, double x, double y, double z) {
        AnimDemoCast cast = null;
        for (AnimDemoCast c : casts.kitCasts()) {
            if (MotionWarpingStation.exact(c, CLIP) != null && c.clip("FLD_Stand") != null && c.mesh.skeleton().indexOf("pelvis") >= 0) cast = c;
        }
        if (cast == null) {
            status = "\nNo kit character with Kakarot's lunging strike loaded";
            return;
        }
        CgSkeleton s = cast.mesh.skeleton();
        CgAnimSequence encoded = CgClipModifiers.encodeRootBone(MotionWarpingStation.exact(cast, CLIP), s, "root", "pelvis");
        CgAnimSequence lunge = encoded.with(encoded.settings().withWarpWindows(List.of(CgWarpWindow.of("opponent", LUNGE_START, LUNGE_END)
                .facing(true).rotation(CgWarpWindow.Rotation.SLERP_WITH_CLAMPED_RATE, 0.6f, 540f))));
        Kit k = new Kit(cast, lunge, cast.clip("FLD_Stand").sequence(), MotionWarpingStation.sequence("demo:entity_lunge", encoded.duration()));
        kit = k;
        // a client's copy of each lunge a server serves: on the husk's actor here, once it has spawned
        CgSequenceNet.client((obj, sequence, roles, seed) -> {
            if (!sequence.equals(k.sequence.id()) || roles.length == 0) return null;
            return CgEntityActors.actor(roles[0]) instanceof Fighter f ? f.showServed() : null;
        });
        husks = CgEntityActors.register(TYPE, () -> new Fighter(k));
        player = CgEntityActors.bindLocalPlayer(() -> new Fighter(k));
        status = "";
    }

    @Override
    public float[] view() {
        return new float[]{0f, 3.2f, 8.5f, 0f, -14f};
    }

    @Override
    public boolean press(int key, boolean shift) {
        if (kit == null) return false;
        switch (key) {
            case CgKeyCodes.KEY_UP -> {
                int me = CgEntityQueries.client().localPlayer();
                if (me >= 0 && CgEntityActors.actor(me) instanceof Fighter f) status = f.lungeAtNearestHusk() ? "" : "\nNo husk within 5 m";
            }
            case CgKeyCodes.KEY_DOWN -> kit.husksLunge = !kit.husksLunge;
            case CgKeyCodes.KEY_GRAVE -> kit.standOff = !kit.standOff;
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public String hud() {
        if (kit == null) return status;
        return "\nGoku plays every husk that joins now, and you: root motion moves the entity, on the server for a husk"
                + "\n  summon one: /summon minecraft:husk ~ ~ ~4 {NoAI:1b}   (F5 to watch yourself)"
                + "\nUp: lunge at the nearest husk (warped onto it)" + status
                + "\nDown: husks " + (kit.husksLunge ? String.format(Locale.ROOT, "lunge at you from %.0f to %.0f m", MIN_REACH, MAX_REACH) : "stand still")
                + "\n`: stand-off " + (kit.standOff ? String.format(Locale.ROOT, "%.1f m (the fist meets them)", STAND_OFF) : "off (onto their centre)");
    }

    @Override
    public void delete() {
        if (husks != null) husks.close();
        if (player != null) player.close();
        husks = player = null;
        kit = null;
    }
}
