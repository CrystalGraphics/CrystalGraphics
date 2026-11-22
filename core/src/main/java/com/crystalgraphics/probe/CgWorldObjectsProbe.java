package com.crystalgraphics.probe;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;
import javax.imageio.ImageIO;

import org.joml.Matrix4dc;

import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgBodyKinds;
import com.crystalgraphics.asset.kind.CgSequenceKinds;
import com.crystalgraphics.asset.load.CgVfxAssets;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.gauntlet.CgGauntletCapture;
import com.crystalgraphics.gauntlet.CgGauntletController;
import com.crystalgraphics.gauntlet.CgGauntletState;
import com.crystalgraphics.lifecycle.CgEngineLifecycle;
import com.crystalgraphics.mc.block.CgBlockState;
import com.crystalgraphics.mc.item.CgItemStack;
import com.crystalgraphics.mc.render.world.CgBlockStateComponent;
import com.crystalgraphics.mc.render.world.CgHostWorld;
import com.crystalgraphics.mc.render.world.CgItemDisplay;
import com.crystalgraphics.mc.render.world.CgItemStackComponent;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.world.CgEntityQuery;
import com.crystalgraphics.platform.world.CgWorldStimulus;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.scene.component.CgHostDrawnComponent;
import com.crystalgraphics.render.scene.component.CgPointLight;
import com.crystalgraphics.render.scene.component.CgPrimitiveComponent;
import com.crystalgraphics.sequence.CgSequencePlayer;
import com.crystalgraphics.sequence.CgSequencer;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;
import com.crystalgraphics.world.CgAttachment;
import com.crystalgraphics.world.CgEntityQueries;
import com.crystalgraphics.world.actor.CgEntity;
import com.crystalgraphics.world.actor.CgEntityActor;
import com.crystalgraphics.world.actor.CgEntityActors;

/**
 * Proves, in a running single-player game, that Minecraft draws its own objects in the engine's scene: an item in an
 * entity actor's hand, an entity a sequence places, a block, each lit by a point light of the engine's. The gauntlet's
 * test {@code crystalgraphics:world_objects}; it enters a world itself.
 *
 * <pre>{@code
 * -Dcrystalgraphics.gauntlet=crystalgraphics:world_objects
 * -Dcrystalgraphics.world_objects.item=mymod:blade        // a mod's item in the hand; a diamond sword by default
 * // what it logs:
 * crystalgraphics:world_objects world.drawn: true (3 drawn by the host a frame; 18342 pixels change when they show)
 * crystalgraphics:world_objects world.rides: true (the sword 0.0 blocks from the actor's hand)
 * crystalgraphics:world_objects world.sequence: true (the sequence's zombie 0.0 blocks from its origin)
 * crystalgraphics:world_objects world.lit: true (their pixels 41.2 brighter under the light, mean of 6210)
 * }</pre>
 *
 * <ul>
 *   <li>It summons two armour stands three blocks ahead (pigs on 1.7.10), registers an actor for their type between the
 *       two, and clears every entity but players within {@value #CLEAR} blocks at the end. Never point it at a world
 *       that matters.</li>
 *   <li>Three captures: the objects hidden, shown, and shown under the light ({@code crystalgraphics-world_objects-*.png}).</li>
 *   <li>Skipped, with the reason, where there is no single-player server or the host draws no objects.</li>
 * </ul>
 */
public final class CgWorldObjectsProbe {

    /** Client ticks: in a world first; the most for a summon to arrive; frames for a change to show. */
    private static final int SETTLE = 60, ARRIVE = 100, WATCH = 20;
    private static final double AHEAD = 3, SIDE = 1.5, FIND = 1.5, CLEAR = 8, FOLLOW = 1e-3;
    private static final String PREFAB = "crystalgraphics:probe_hand";
    /** Its zombie: a sequence's entity spawnable standing under the play's origin. */
    private static final String SEQUENCE = "_v: 1\ntick_resolution: 1000\ndisplay_rate: 50\nrange: [0, 600000]\n"
            + "spawnables:\n  - { id: z1, name: zombie, entity: { data: \"minecraft:zombie\" } }\n";

    private enum Phase { WAITING, SETTLING, CLEARING, FIRST, SECOND, SHOWN, HIDDEN, LIT, CHECK, DONE }

    private static Phase phase = Phase.WAITING;
    private static int ticks, first = -1, second = -1;
    private static double ax, ay, az, sx, sz, px, pz;
    private static CgEntityActors.Registration registration;
    @Nullable private static CgItemStackComponent sword;
    @Nullable private static CgBlockStateComponent chest;
    @Nullable private static CgPointLight light;
    @Nullable private static CgSequencePlayer play;
    @Nullable private static CgGauntletCapture.Shot hiddenShot, shownShot, litShot;
    private static int drawnMost;
    private static boolean installed;
    private static final double[] pose = new double[CgEntityQuery.POSE_LENGTH];
    @Nullable private static volatile Test active;

    private CgWorldObjectsProbe() {
    }

    /** The gauntlet's {@code crystalgraphics:world_objects}. */
    public static CgGauntletController test() {
        return new Test();
    }

    /** An actor drawing nothing of its own: its prefab's hand is what the item rides. */
    private static final class HandActor extends CgEntityActor {
        @Override
        protected void onSpawn(CgEntity entity) {
        }

        boolean hasPrefab() {
            return prefab() != null;
        }

        @Nullable
        CgPrimitiveComponent<?> hand() {
            return prefab() == null ? null : prefab().node("hand_r");
        }
    }

    private static final class Test extends CgGauntletController {
        @Override
        protected void onInit() {
            active = this;
            phase = Phase.WAITING;
            ticks = 0;
            first = second = -1;
            drawnMost = 0;
            CgVfxAssets.instance().prefetch(List.of(CgVfxAssets.ref(CgBodyKinds.PREFAB, PREFAB)));
            install();
        }

        @Override
        protected void onStateChange(CgGauntletState from, CgGauntletState to) {
            if (to.equals(CgGauntletState.TITLE)) enterWorld();
        }

        @Override
        protected void onEnd() {
            active = null;
            tidy();
        }

        void stated(String name, boolean ok, String detail) {
            check(name, ok, detail);
        }

        void skipped(String name, String reason) {
            skip(name, reason);
        }

        CgGauntletCapture.Shot shoot(String name) {
            return capture(name);
        }

        void done() {
            endTest();
        }
    }

    private static synchronized void install() {
        if (installed) return;
        installed = true;
        CgEngineLifecycle.onClientTick(CgWorldObjectsProbe::tick);
    }

    private static void tick() {
        Test test = active;
        if (test == null) return;
        CgWorldStimulus stimulus = CgPlatform.get(CgWorldStimulus.SERVICE);
        CgEntityQuery entities = CgEntityQueries.client();
        int me = entities.localPlayer();
        ticks++;
        drawnMost = Math.max(drawnMost, CgHostWorld.drawn());
        switch (phase) {
            case WAITING:
                if (me < 0) return;
                String absent = CgPlatform.get(CgHostWorld.HOST).absent();
                if (absent != null) {
                    test.skipped("everything", absent);
                    finish(test);
                    return;
                }
                stimulus.keepRunning();
                next(Phase.SETTLING);
                return;
            case SETTLING:
                if (ticks < SETTLE || !entities.pose(me, 1f, pose)) return;
                // Level, so what stands ahead is in frame: a save may hold the player looking at the sky
                if (Math.abs(pose[CgEntityQuery.PITCH]) > 2 && stimulus.turnLook(0f, (float) -pose[CgEntityQuery.PITCH])) return;
                // ahead of the player, Minecraft's yaw 0 facing +z; the side axis to its right
                double yaw = Math.toRadians(pose[CgEntityQuery.YAW]);
                ax = pose[CgEntityQuery.X] - Math.sin(yaw) * AHEAD;
                ay = pose[CgEntityQuery.Y];
                az = pose[CgEntityQuery.Z] + Math.cos(yaw) * AHEAD;
                sx = -Math.cos(yaw);
                sz = -Math.sin(yaw);
                px = pose[CgEntityQuery.X];
                pz = pose[CgEntityQuery.Z];
                // A run that died before its own clear leaves stands here, entered before any actor type: gone first
                stimulus.clear(ax, ay, az, CLEAR);
                next(Phase.CLEARING);
                return;
            case CLEARING:
                if (ticks < 10) return;
                if (!stimulus.stand(ax, ay, az)) {
                    test.skipped("everything", "no single-player server to summon on");
                    finish(test);
                    return;
                }
                next(Phase.FIRST);
                return;
            case FIRST: {
                first = near(entities, me, -1);
                if (first < 0) {
                    if (ticks > ARRIVE) fail(test, "world.actor", "no stand arrived");
                    return;
                }
                String type = entities.type(first);
                if (type == null) {
                    fail(test, "world.actor", "#" + first + " has no type");
                    return;
                }
                registration = CgEntityActors.register(type, HandActor::new).prefab(PREFAB);
                stimulus.stand(ax, ay, az);
                next(Phase.SECOND);
                return;
            }
            case SECOND: {
                second = near(entities, me, first);
                CgEntityActor actor = second < 0 ? null : CgEntityActors.actor(second);
                CgPrimitiveComponent<?> hand = actor == null ? null : ((HandActor) actor).hand();
                if (hand == null) {
                    if (ticks > ARRIVE) {
                        // Which link is missing: the stand, its actor, the actor's prefab, or the node in it
                        fail(test, "world.actor", second < 0 ? "no second stand arrived"
                                : actor == null ? "#" + second + " of " + registration.type() + " has no actor"
                                : !((HandActor) actor).hasPrefab() ? "#" + second + "'s actor has no prefab " + PREFAB
                                : PREFAB + " has no node hand_r");
                    }
                    return;
                }
                place(test, hand);
                next(Phase.SHOWN);
                return;
            }
            case SHOWN:
                if (ticks == WATCH) shownShot = test.shoot("shown");
                if (ticks < WATCH || !shownShot.done()) return;
                rides(test);
                showAll(false);
                next(Phase.HIDDEN);
                return;
            case HIDDEN:
                if (ticks == WATCH) hiddenShot = test.shoot("hidden");
                if (ticks < WATCH || !hiddenShot.done()) return;
                showAll(true);
                // Halfway back toward the player and up: it lights the faces the camera sees
                light = CgScene.client().pointLight().at((ax + px) / 2, ay + 1.5, (az + pz) / 2)
                        .radius(6f).color(0xFFFFFFFF).intensity(60f).spawn();
                next(Phase.LIT);
                return;
            case LIT:
                if (ticks == WATCH) litShot = test.shoot("lit");
                if (ticks < WATCH || !litShot.done()) return;
                next(Phase.CHECK);
                return;
            case CHECK:
                images(test);
                finish(test);
                return;
            default:
        }
    }

    // The sword in the actor's hand, the sequence's zombie to one side, the chest to the other
    private static void place(Test test, CgPrimitiveComponent<?> hand) {
        String item = System.getProperty("crystalgraphics.world_objects.item", "minecraft:diamond_sword");
        sword = CgItemStackComponent.of(CgScene.client(), CgItemStack.of(item, 1))
                .display(CgItemDisplay.THIRD_PERSON_RIGHT_HAND).attach(hand, null).spawn();
        chest = CgBlockStateComponent.of(CgScene.client(), CgBlockState.parse("minecraft:chest[facing=south]"))
                .at(Math.floor(ax - sx * SIDE), Math.floor(ay), Math.floor(az - sz * SIDE)).spawn();
        CgSequencer sequencer = CgSequencer.of(CgWorld.client());
        if (sequencer == null) {
            test.skipped("world.sequence", "the client world has no gameplay sequencer");
            return;
        }
        double zx = ax + sx * SIDE, zz = az + sz * SIDE;
        CgAttachment origin = (point, turn) -> {
            point[0] = zx;
            point[1] = ay;
            point[2] = zz;
            if (turn != null) turn.identity();
            return true;
        };
        try {
            CgSequenceDefinition zombie = CgSequenceDefinition.compile(CgAssetData.of("crystalgraphics:probe/zombie",
                    CgSequenceKinds.SEQUENCE, (Map<?, ?>) CgAssetYaml.read(SEQUENCE), null));
            play = sequencer.play(zombie, Map.of(), origin);
        } catch (RuntimeException e) {
            // Stated once, the rest of the test played: a tick listener throwing would retry it every tick
            test.stated("world.sequence", false, "the sequence did not play: " + e.getMessage());
        }
    }

    private static void rides(Test test) {
        CgEntityActor actor = CgEntityActors.actor(second);
        CgPrimitiveComponent<?> hand = actor == null ? null : ((HandActor) actor).hand();
        if (sword == null || hand == null) {
            test.stated("world.rides", false, "the sword or the hand is gone");
            return;
        }
        Matrix4dc s = sword.worldTransform(), h = hand.worldTransform();
        double d = Math.sqrt(sq(s.m30() - h.m30()) + sq(s.m31() - h.m31()) + sq(s.m32() - h.m32()));
        test.stated("world.rides", d < FOLLOW, "the sword " + d + " blocks from the actor's hand");
        if (play == null) return;
        for (CgHostDrawnComponent<?> c : CgScene.client().primitives().hostDrawn()) {
            if (c == sword || c == chest) continue;
            double z = Math.sqrt(sq(c.x() - (ax + sx * SIDE)) + sq(c.y() - ay) + sq(c.z() - (az + sz * SIDE)));
            test.stated("world.sequence", z < FOLLOW, "the sequence's " + c + " " + z + " blocks from its origin");
            return;
        }
        test.stated("world.sequence", false, "the sequence spawned no entity component");
    }

    private static void showAll(boolean shown) {
        for (CgHostDrawnComponent<?> c : CgScene.client().primitives().hostDrawn()) c.visible(shown);
    }

    // Drawn: the shown frame against the hidden one; lit: the same pixels brighter under the light
    private static void images(Test test) {
        BufferedImage hidden = read(hiddenShot), shown = read(shownShot), lit = read(litShot);
        if (hidden == null || shown == null || lit == null) {
            test.stated("world.drawn", false, "a capture was not written");
            return;
        }
        long changed = 0, sumShown = 0, sumLit = 0;
        for (int y = 0; y < hidden.getHeight(); y++) {
            for (int x = 0; x < hidden.getWidth(); x++) {
                int h = hidden.getRGB(x, y), s = shown.getRGB(x, y);
                if (Math.abs(luma(h) - luma(s)) <= 8) continue;
                changed++;
                sumShown += luma(s);
                sumLit += luma(lit.getRGB(x, y));
            }
        }
        test.stated("world.drawn", drawnMost >= 3 && changed > 500, drawnMost + " drawn by the host a frame; " + changed
                + " pixels change when they show");
        double gain = changed == 0 ? 0 : (sumLit - sumShown) / (double) changed;
        test.stated("world.lit", gain > 5, String.format("their pixels %.1f brighter under the light, mean of %d", gain, changed));
    }

    @Nullable
    private static BufferedImage read(@Nullable CgGauntletCapture.Shot shot) {
        if (shot == null || !shot.written()) return null;
        try {
            return ImageIO.read(shot.file().toFile());
        } catch (IOException e) {
            return null;
        }
    }

    private static int luma(int argb) {
        return ((argb >> 16 & 255) * 2 + (argb >> 8 & 255) * 5 + (argb & 255)) / 8;
    }

    private static double sq(double v) {
        return v * v;
    }

    /** The nearest entity but the player and {@code not} within {@value #FIND} of the stands' point; -1 for none. */
    private static int near(CgEntityQuery entities, int me, int not) {
        final int[] best = {-1};
        final double[] bestD = {FIND * FIND};
        entities.within(ax - FIND, ay - FIND, az - FIND, ax + FIND, ay + FIND + 2, az + FIND, id -> {
            if (id == me || id == not || !entities.pose(id, 1f, pose)) return;
            double dx = pose[CgEntityQuery.X] - ax, dz = pose[CgEntityQuery.Z] - az;
            double d = dx * dx + dz * dz;
            if (d < bestD[0]) {
                bestD[0] = d;
                best[0] = id;
            }
        });
        return best[0];
    }

    private static void next(Phase p) {
        phase = p;
        ticks = 0;
    }

    private static void fail(Test test, String name, String reason) {
        test.stated(name, false, reason);
        finish(test);
    }

    private static void tidy() {
        if (play != null) play.stop();
        play = null;
        if (sword != null && !sword.removed()) sword.remove();
        if (chest != null && !chest.removed()) chest.remove();
        if (light != null && !light.removed()) light.remove();
        sword = null;
        chest = null;
        light = null;
        if (registration != null) registration.close();
        registration = null;
    }

    private static void finish(Test test) {
        tidy();
        CgPlatform.get(CgWorldStimulus.SERVICE).clear(ax, ay, az, CLEAR);
        phase = Phase.DONE;
        test.done();
    }
}
