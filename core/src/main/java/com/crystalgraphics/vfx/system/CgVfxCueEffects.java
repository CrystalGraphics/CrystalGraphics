package com.crystalgraphics.vfx.system;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.crystalgraphics.asset.camera.CgCameraShakeDefinition;
import com.crystalgraphics.audio.CgActiveSounds;
import com.crystalgraphics.audio.CgAudioEngine;
import com.crystalgraphics.audio.CgSoundClass;
import com.crystalgraphics.cue.CgCueDefinition;
import com.crystalgraphics.cue.CgCueDefinition.Effects;
import com.crystalgraphics.cue.CgCueDefinition.Lens;
import com.crystalgraphics.cue.CgCueDefinition.Particles;
import com.crystalgraphics.cue.CgCueDefinition.Placement;
import com.crystalgraphics.cue.CgCueDefinition.Shake;
import com.crystalgraphics.cue.CgCueDefinition.Slot;
import com.crystalgraphics.cue.CgCueDefinition.Write;
import com.crystalgraphics.cue.CgCueDefinition.Sound;
import com.crystalgraphics.cue.CgCueEffects;
import com.crystalgraphics.cue.CgCueManager;
import com.crystalgraphics.cue.CgCueNet;
import com.crystalgraphics.cue.CgCueParameters;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.platform.world.CgEntityQuery;
import com.crystalgraphics.render.stage.CgHostView;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.post.CgPostStack;
import com.crystalgraphics.render.post.volume.CgPostBlock;
import com.crystalgraphics.render.post.volume.CgPostVolume;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.camera.CgShakePlay;
import com.crystalgraphics.vfx.look.CgVfxParam;
import com.crystalgraphics.world.CgAttachment;
import com.crystalgraphics.world.CgEntityAttachments;
import com.crystalgraphics.world.CgEntityQueries;
import com.crystalgraphics.world.CgWorldQueries;

/**
 * Plays a cue's systems, sounds, shake and lens on a client: the VFX engine's {@link CgCueEffects}. Each entry is placed as its
 * {@code placement} says on the cue's target, if its {@code spawn_condition} passes for this viewer. Its {@code writes}
 * reach the user parameters of the systems it plays: the values a play starts with
 * ({@link CgCueParameters#write}, a burst's per-hit values), then a looping play's each frame.
 *
 * <pre>{@code
 * CgVfxCueEffects.installClient(CgVfx.world());   // the engine's, as a client starts: the client world's manager wired
 *
 * // a scene's own manager
 * CgCueManager cues = world.add(new CgCueManager(CgVfxAssets.instance()));
 * cues.effects(new CgVfxCueEffects(vfx, cues));
 * }</pre>
 *
 * <ul>
 *   <li>{@code particles} and a looping block's {@code lens} play here. The lens is a post volume over the whole
 *       screen for the cue's life. In the world it plays where the viewer ({@link CgCueManager#viewer}) stands within
 *       its reach as it starts; otherwise only on the target's own machine ({@link CgCueManager#localControl}).
 *       Its sounds play through the client world's {@link CgAudioEngine}, as Unreal's notify plays them beside its
 *       particles. Force feedback and decals are their own systems'.</li>
 *   <li>A {@code shake} plays its shake asset as {@code FGameplayCueNotify_CameraShakeInfo::PlayCameraShake} does:
 *       {@code in_world}, on this viewer at {@code scale} times Unreal's falloff from where it is placed, taken once
 *       as it starts; otherwise at {@code scale} on the target's own machine alone. A looping block's shake blends out
 *       at removal. A cue's magnitude scales none of it, as in Unreal: a stronger hit is another cue.</li>
 *   <li>{@code attach_to_target} follows the target's socket where the target is an entity, else the target itself;
 *       {@code do_not_attach} spawns at the socket and stays. A sound does the same, a burst's one-shot included,
 *       for as long as it plays.</li>
 * </ul>
 */
public final class CgVfxCueEffects implements CgCueEffects {

    private static final Logger LOGGER = LogManager.getLogger("CgCue");

    private final CgVfxSystem vfx;
    /** The systems logged as compiled to nothing here: once each. */
    private final Set<String> undrawnLogged = new HashSet<>();
    private final CgCueManager cues;
    private final double[] point = new double[3];
    private final double[] viewer = new double[3];
    private final Quaternionf turn = new Quaternionf();
    private final Vector3f spawnScale = new Vector3f(), parentScale = new Vector3f();
    /** Sounds attached to their target, each moved with it while it plays. */
    private long[] followIds = new long[16];
    private CgAttachment[] followTargets = new CgAttachment[16];
    private String[] followSockets = new String[16];
    private int following;

    public CgVfxCueEffects(CgVfxSystem vfx, CgCueManager cues) {
        this.vfx = vfx;
        this.cues = cues;
    }

    /** The client level's entities by network id, as attachments at their middle (Unreal's actor location). */
    private static final Map<Integer, CgAttachment> TARGETS = new HashMap<>();
    private static final double[] POSE = new double[CgEntityQuery.POSE_LENGTH];
    private static int targetsEpoch;

    /**
     * Wires the client world's {@link CgCueManager}: its effects played on {@code vfx}, the local player as local
     * control, the viewer the local player (else the camera), entities by network id, and the cues the server sends.
     * The engine calls it once as a client starts.
     */
    public static void installClient(CgVfxSystem vfx) {
        CgCueManager cues = CgCueManager.client();
        cues.effects(new CgVfxCueEffects(vfx, cues))
                .localControl(a -> a.entity() >= 0 && a.entity() == CgEntityQueries.client().localPlayer())
                .viewer(CgVfxCueEffects::viewer)
                .entities(CgVfxCueEffects::target);
        CgCueNet.client(obj -> CgCueManager.client());
    }

    /** The local player's feet, else the camera: where an in-world shake or lens is felt from. */
    private static boolean viewer(double[] out) {
        CgEntityQuery entities = CgEntityQueries.client();
        int player = entities.localPlayer();
        if (player >= 0 && entities.pose(player, 1f, POSE)) {
            out[0] = POSE[CgEntityQuery.X];
            out[1] = POSE[CgEntityQuery.Y];
            out[2] = POSE[CgEntityQuery.Z];
            return true;
        }
        if (CgWorldQueries.client().levelEpoch() == 0) return false;
        CgHostView view = CgRenderStage.WORLD_OPAQUE.host().view();
        out[0] = view.x();
        out[1] = view.y();
        out[2] = view.z();
        return true;
    }

    /** Entity {@code id} of the client level, one attachment an entity while the level lasts. */
    private static CgAttachment target(int id) {
        int epoch = CgWorldQueries.client().levelEpoch();
        if (epoch != targetsEpoch) {
            TARGETS.clear();
            targetsEpoch = epoch;
        }
        CgAttachment a = TARGETS.get(id);
        if (a == null) {
            a = CgEntityAttachments.of(CgEntityQueries.client(), id, CgEntityAttachments.CENTRE);
            TARGETS.put(id, a);
        }
        return a;
    }

    /** Whether {@code p}'s system compiled to something that plays; warns once a system where it did not. */
    private boolean drawable(CgCueDefinition cue, Particles p) {
        if (p.definition() != null) return true;
        if (undrawnLogged.add(p.system())) LOGGER.warn("{}: {} compiled to no system here, so it plays nothing", cue.id(), p.system());
        return false;
    }

    @Override
    public void burst(CgCueDefinition cue, Effects block, CgCueParameters at, float age) {
        List<Particles> particles = block.particles();
        for (int i = 0; i < particles.size(); i++) {
            Particles p = particles.get(i);
            if (drawable(cue, p) && cues.spawns(p.where().condition(), at, i)) spawn(cue, p, at, age);
        }
        List<Sound> sounds = block.sounds();
        for (int i = 0; i < sounds.size(); i++) {
            Sound s = sounds.get(i);
            if (cues.spawns(s.where().condition(), at, particles.size() + i)) sound(s, at, false);
        }
        shake(block, at);
    }

    /** {@code block}'s shake, started if it plays on this machine; null where it does not. */
    @Nullable
    private CgShakePlay shake(Effects block, CgCueParameters at) {
        Shake s = block.shake();
        if (s == null || s.scale() <= 0f || !(s.definition() instanceof CgCameraShakeDefinition d)) return null;
        if (!cues.spawns(s.where().condition(), at, block.particles().size() + block.sounds().size())) return null;
        placedPoint(s.where().placement(), at);
        float scale = s.scale();
        if (s.inWorld()) {
            // Felt by this viewer at its distance as it starts, then held: StartCameraShake takes no source
            if (!cues.viewerAt(viewer)) return null;
            double dx = viewer[0] - point[0], dy = viewer[1] - point[1], dz = viewer[2] - point[2];
            scale *= falloff(Math.sqrt(dx * dx + dy * dy + dz * dz), s.inner(), s.outer(), s.falloff());
            if (scale <= 0f) return null;
        } else if (!cues.locallyControlled(at.target())) {
            return null;
        }
        CgShakePlay dbg = d.play(point[0], point[1], point[2], scale, at.seed(), 0f, 0f, 1f,
                CgCameraShakeDefinition.worldSpace(s.space(), "space"));
        return dbg;
    }

    /**
     * {@code s} played where its placement puts it ({@code FGameplayCueNotify_SoundInfo::PlaySound}): its target's
     * socket, the target, or the cue's point. Its id; 0 where this client plays no sound.
     */
    private long sound(Sound s, CgCueParameters at, boolean loop) {
        CgAudioEngine audio = CgWorld.client().subsystem(CgAudioEngine.class);
        if (audio == null || !audio.active()) return 0;
        Placement placement = s.where().placement();
        placedPoint(placement, at);
        long id = audio.play(s.sound(), CgSoundClass.SFX, point[0], point[1], point[2], 1f, 1f, loop, Double.NaN, at.seed());
        // SpawnSoundAttached for attach_to_target, one-shot or looping; otherwise it stays where it started
        if (id != 0 && at.target() != null && placement.attach() == Placement.Attach.ATTACH_TO_TARGET) {
            follow(id, at.target(), placement.socket());
        }
        return id;
    }

    private void follow(long id, CgAttachment target, @Nullable String socket) {
        if (following == followIds.length) {
            followIds = Arrays.copyOf(followIds, following * 2);
            followTargets = Arrays.copyOf(followTargets, following * 2);
            followSockets = Arrays.copyOf(followSockets, following * 2);
        }
        followIds[following] = id;
        followTargets[following] = target;
        followSockets[following] = socket;
        following++;
    }

    /** Each attached sound moved to its target's socket, else the target; one that ended is dropped. */
    @Override
    public void update(float dt) {
        if (following == 0) return;
        CgAudioEngine audio = CgWorld.client().subsystem(CgAudioEngine.class);
        CgActiveSounds sounds = audio == null ? null : audio.activeSounds();
        int kept = 0;
        for (int i = 0; i < following; i++) {
            long id = followIds[i];
            if (sounds == null || !sounds.playing(id)) continue;
            CgAttachment target = followTargets[i];
            String socket = followSockets[i];
            // A target that has gone leaves the sound where it last was
            if (socket != null && target.point(socket, point) || target.place(point, null)) {
                sounds.at(id, point[0], point[1], point[2]);
            }
            followIds[kept] = id;
            followTargets[kept] = target;
            followSockets[kept] = socket;
            kept++;
        }
        Arrays.fill(followTargets, kept, following, null);
        Arrays.fill(followSockets, kept, following, null);
        following = kept;
    }

    /** Where {@code placement} puts an entry on the cue's target now (its socket, else the target), else the cue's point. */
    private void placedPoint(Placement placement, CgCueParameters at) {
        point[0] = at.x();
        point[1] = at.y();
        point[2] = at.z();
        CgAttachment target = at.target();
        if (target == null) return;
        String socket = placement.socket();
        if (socket == null || !target.point(socket, point)) target.place(point, null);
    }

    @Override
    public Lasting lasting(CgCueDefinition cue) {
        return new Playing(cue);
    }

    /** {@code lens}'s look over the whole screen, at full weight. */
    static CgPostVolume openLens(CgPostStack stack, Lens lens) {
        return stack.volume(0, CgPostBlock.settings(lens.post() == null ? Map.of() : lens.post()));
    }

    /**
     * Unreal's falloff of a world effect at {@code distance} ({@code CalculateFalloffIntensity}): 1 where neither
     * radius is set, falling from {@code inner} to {@code outer} as the linear share to the power {@code exponent},
     * or a cliff at {@code inner} where {@code outer} is not past it.
     */
    static float falloff(double distance, float inner, float outer, float exponent) {
        if (inner <= 0f && outer <= 0f) return 1f;
        if (inner < outer) {
            return (float) Math.pow(1.0 - Math.max(0.0, Math.min(1.0, (distance - inner) / (outer - inner))), exponent);
        }
        return distance < inner ? 1f : 0f;
    }

    /** Whether {@code lens} plays on this machine as its cue starts: once, at full strength for its life, as Unreal's. */
    private boolean lensPlays(Lens lens, CgCueParameters at) {
        if (!lens.inWorld()) return cues.locallyControlled(at.target());
        if (!cues.viewerAt(viewer)) return false;
        double dx = viewer[0] - at.x(), dy = viewer[1] - at.y(), dz = viewer[2] - at.z();
        return falloff(Math.sqrt(dx * dx + dy * dy + dz * dz), lens.inner(), lens.outer(), 1f) > 0f;
    }

    /** Plays {@code p}, {@code age} seconds into its life, with the {@code writes} values {@code at} carries. */
    private CgVfxSystemInstance<?> spawn(CgCueDefinition cue, Particles p, CgCueParameters at, float age) {
        CgVfxSystemInstance<?> made = place(p, at);
        for (int i = 0; i < at.writes(); i++) {
            Write w = cue.writes().get(at.writeName(i));
            if (w == null) continue;
            List<Slot> slots = w.slots();
            for (int s = 0; s < slots.size(); s++) {
                Slot slot = slots.get(s);
                if (slot.definition() == p.definition()) {
                    set(made, slot.param(), at.writeValue(i, 0), at.writeValue(i, 1), at.writeValue(i, 2), at.writeValue(i, 3));
                }
            }
        }
        // Its component's CustomTimeDilation and WarmupTime: simulated that far before it is first shown
        if (p.timeDilation() != 1f) made.timeDilation(p.timeDilation());
        if (age + p.warmup() > 0f) made.startAt(age + p.warmup());
        vfx.play(made);
        return made;
    }

    /** A {@code writes} value into the user parameter {@code param} of {@code e}, as many of its floats as it holds. */
    private static void set(CgVfxSystemInstance<?> e, CgVfxParam param, float x, float y, float z, float w) {
        if (param instanceof CgVfxParam.Scalar sc) e.set(sc, x);
        else if (param instanceof CgVfxParam.Vector v) e.set(v, x, y, z);
        else if (param instanceof CgVfxParam.Color c) e.set(c, x, y, z, w);
    }

    private CgVfxSystemInstance<?> place(Particles p, CgCueParameters at) {
        Placement placement = p.where().placement();
        CgAttachment target = at.target();
        point[0] = at.x();
        point[1] = at.y();
        point[2] = at.z();
        boolean attach = target != null && placement.attach() == Placement.Attach.ATTACH_TO_TARGET;
        boolean turned = false;
        if (target != null && !attach) {
            // FindSpawnTransform: the target's socket transform, its turn included
            turned = placement.socket() != null ? target.point(placement.socket(), point, turn) : target.place(point, turn);
            if (!turned && placement.socket() != null) target.place(point, null);
        }
        CgVfxSystemInstance<?> made = new CgVfxSystemInstance<>(p.definition(), point[0], point[1], point[2]);
        if (attach) {
            int socket = placement.socket() == null ? -1 : CgEntityAttachments.find(placement.socket());
            boolean keep = placement.rule() == Placement.Rule.KEEP_WORLD;
            // SpawnSystemAttached(TargetComponent, SocketName): the socket, whatever the target is
            CgAttachment parent = placement.socket() == null || socket >= 0 && target.entity() >= 0 ? target
                    : target.atSocket(placement.socket());
            if (target.entity() >= 0 && socket >= 0) {
                if (keep) made.followKeepingOffset(target.entity(), socket);
                else made.follow(target.entity(), socket);
            } else if (keep) {
                made.followKeepingOffset(parent);
            } else {
                made.follow(parent);
            }
        }
        if (placement.rotation() != null) made.orient(turn.set(placement.rotation()));
        else if (turned) made.orient(turn);
        // FindSpawnTransform's scale: the target socket's where the transform is its (bOverrideScale false), else 1
        Vector3f s = spawnScale.set(1f);
        if (placement.inheritScale()) {
            if (target != null && !attach) target.scale(placement.socket(), s);
        } else if (placement.scale() != null) {
            s.set(placement.scale());
        }
        if (attach && placement.rule() != Placement.Rule.KEEP_RELATIVE) {
            // SpawnSystemAttached: keep_world and snap_to_target keep the spawn scale as the world's, over the parent's
            Vector3f parent = target.scale(placement.socket(), parentScale);
            s.set(safeOver(s.x, parent.x), safeOver(s.y, parent.y), safeOver(s.z, parent.z));
        }
        if (s.x != 1f || s.y != 1f || s.z != 1f) made.scale(s.x, s.y, s.z);
        return made;
    }

    /** {@code value / by}, 0 for a vanishing {@code by}: FTransform's GetSafeScaleReciprocal. */
    private static float safeOver(float value, float by) {
        return Math.abs(by) <= 1e-8f ? 0f : value / by;
    }

    /**
     * One looping play: what it started with the socket each is on, its {@code looping} systems apart to stop them at
     * removal, and the entries still waiting out their {@code delay}.
     */
    private final class Playing implements Lasting {
        private final List<CgVfxSystemInstance<?>> alive = new ArrayList<>(), loops = new ArrayList<>();
        private final List<String> sockets = new ArrayList<>();
        private final List<Particles> waiting = new ArrayList<>();
        private final List<Boolean> waitingLoops = new ArrayList<>();
        /** Its looping sounds' ids, faded at removal, and which entry each plays. */
        private final List<Long> loopSounds = new ArrayList<>();
        private final List<Sound> loopSoundEntries = new ArrayList<>();
        private final CgCueDefinition cue;
        @Nullable
        private CgCueParameters at;
        private float age;
        @Nullable
        private CgPostVolume lens;
        /** The looping block's shake, blended out at removal. */
        @Nullable
        private CgShakePlay loopShake;

        Playing(CgCueDefinition cue) {
            this.cue = cue;
        }

        @Override
        public void start(CgCueParameters at, float age) {
            this.at = at;
            this.age = age;
            play(cue.application(), at, false);
            play(cue.looping(), at, true);
            playSounds(cue.application(), at, false);
            playSounds(cue.looping(), at, true);
            shake(cue.application(), at);
            loopShake = shake(cue.looping(), at);
            Lens l = cue.looping().lens();
            if (l != null && cues.spawns(l.where().condition(), at, 0) && lensPlays(l, at)) lens = openLens(CgPostStack.get(), l);
        }

        /** {@code block}'s sounds: looping ones kept to fade at removal. */
        private void playSounds(Effects block, CgCueParameters at, boolean loop) {
            List<Sound> sounds = block.sounds();
            for (int i = 0; i < sounds.size(); i++) {
                Sound s = sounds.get(i);
                if (!cues.spawns(s.where().condition(), at, block.particles().size() + i)) continue;
                long id = sound(s, at, loop);
                if (loop && id != 0) {
                    loopSounds.add(id);
                    loopSoundEntries.add(s);
                }
            }
        }

        /** Each of {@code block}'s systems that spawns here, now or once its delay is out. */
        private void play(Effects block, CgCueParameters at, boolean loop) {
            List<Particles> particles = block.particles();
            for (int i = 0; i < particles.size(); i++) {
                Particles p = particles.get(i);
                if (!drawable(cue, p) || !cues.spawns(p.where().condition(), at, i)) continue;
                if (p.delay() > age) {
                    waiting.add(p);
                    waitingLoops.add(loop);
                } else {
                    begin(p, at, loop);
                }
            }
        }

        private void begin(Particles p, CgCueParameters at, boolean loop) {
            String socket = p.where().placement().socket();
            // SetTemplate on the socket's one component: ResetParticles, then ActivateSystem (ParticleComponents.cpp 4.21, 5537-5558)
            if (p.replace()) {
                for (int i = alive.size() - 1; i >= 0; i--) {
                    if (!Objects.equals(sockets.get(i), socket)) continue;
                    CgVfxSystemInstance<?> old = alive.remove(i);
                    sockets.remove(i);
                    loops.remove(old);
                    old.kill();
                }
            }
            CgVfxSystemInstance<?> made = spawn(cue, p, at, age - p.delay());
            alive.add(made);
            sockets.add(socket);
            if (loop) loops.add(made);
        }

        @Override
        public void update(float dt) {
            age += dt;
            for (int i = 0; i < waiting.size(); ) {
                Particles p = waiting.get(i);
                if (p.delay() > age) {
                    i++;
                    continue;
                }
                boolean loop = waitingLoops.remove(i);
                waiting.remove(i);
                begin(p, at, loop);
            }
        }

        private void closeLens() {
            if (lens != null) lens.close();
            lens = null;
        }

        /** {@code StopShake(bStopImmediately)} on the looping shake, if it is still the one this play started. */
        private void stopShake(boolean immediately) {
            if (loopShake != null && loopShake.playing()) loopShake.stop(immediately);
            loopShake = null;
        }

        @Override
        public void write(Write write, float[] value) {
            List<Slot> slots = write.slots();
            for (int s = 0; s < slots.size(); s++) {
                Slot slot = slots.get(s);
                for (int i = 0; i < alive.size(); i++) {
                    CgVfxSystemInstance<?> e = alive.get(i);
                    if (e.definition() == slot.definition()) set(e, slot.param(), value[0], value[1], value[2], value[3]);
                }
            }
        }

        @Override
        public void remove(CgCueParameters at) {
            // What still waits never starts; removal's own delays count from now
            waiting.clear();
            waitingLoops.clear();
            // A loop removal replaces on its socket is reset by the replace, not deactivated first, as SetTemplate
            for (int i = 0; i < loops.size(); i++) {
                CgVfxSystemInstance<?> loop = loops.get(i);
                if (!replacedAtRemoval(sockets.get(alive.indexOf(loop)))) loop.stop();
            }
            loops.clear();
            fadeSounds();
            closeLens();
            stopShake(false);
            age = 0f;
            play(cue.removal(), at, false);
            playSounds(cue.removal(), at, false);
            shake(cue.removal(), at);
        }

        /** Whether a removal entry that starts at once replaces what plays on {@code socket}. */
        private boolean replacedAtRemoval(@Nullable String socket) {
            List<Particles> ends = cue.removal().particles();
            for (int i = 0; i < ends.size(); i++) {
                Particles p = ends.get(i);
                if (p.replace() && p.delay() <= 0f && Objects.equals(p.where().placement().socket(), socket)) return true;
            }
            return false;
        }

        /** {@code UAudioComponent::FadeOut(LoopingFadeOutDuration, LoopingFadeVolumeLevel)} on each looping sound. */
        private void fadeSounds() {
            CgAudioEngine audio = loopSounds.isEmpty() ? null : CgWorld.client().subsystem(CgAudioEngine.class);
            if (audio != null) {
                for (int i = 0; i < loopSounds.size(); i++) {
                    Sound s = loopSoundEntries.get(i);
                    audio.activeSounds().fadeOut(loopSounds.get(i), s.fadeOut(), s.fadeVolume());
                }
            }
            loopSounds.clear();
            loopSoundEntries.clear();
        }

        @Override
        public boolean finished() {
            if (!waiting.isEmpty()) return false;
            for (int i = 0; i < alive.size(); i++) {
                if (alive.get(i).executionState() != CgVfxExecutionState.COMPLETE) return false;
            }
            return true;
        }

        @Override
        public void kill() {
            for (int i = 0; i < alive.size(); i++) alive.get(i).kill();
            waiting.clear();
            waitingLoops.clear();
            CgAudioEngine audio = loopSounds.isEmpty() ? null : CgWorld.client().subsystem(CgAudioEngine.class);
            if (audio != null) for (int i = 0; i < loopSounds.size(); i++) audio.activeSounds().stop(loopSounds.get(i));
            loopSounds.clear();
            loopSoundEntries.clear();
            closeLens();
            stopShake(true);
        }

        @Override
        public void reset() {
            alive.clear();
            sockets.clear();
            loops.clear();
            waiting.clear();
            waitingLoops.clear();
            loopSounds.clear();
            loopSoundEntries.clear();
            at = null;
            age = 0f;
            closeLens();
            loopShake = null;
        }
    }
}
