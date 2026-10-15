package com.crystalgraphics.vfx;

import com.crystalgraphics.collision.CgCollisionWorld;
import com.crystalgraphics.collision.hit.CgHit;
import com.crystalgraphics.collision.hit.CgHitPart;
import com.crystalgraphics.collision.hitbox.CgHitbox;
import com.crystalgraphics.easing.CgEasing;
import com.crystalgraphics.easing.CgKeyframes;
import com.crystalgraphics.render.post.CgPostStack;
import com.crystalgraphics.render.post.volume.CgPostSettings;
import com.crystalgraphics.render.post.volume.CgPostVolume;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.shape.CgShape;
import com.crystalgraphics.vfx.camera.CgCameraShake;
import com.crystalgraphics.vfx.gas.CgVfxGas;
import com.crystalgraphics.vfx.look.CgVfxEnding;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxParam;
import com.crystalgraphics.vfx.look.CgVfxValues;
import com.crystalgraphics.vfx.particle.CgVfxEmitterInstance;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * An effect playing in a world: placed at an origin in doubles, simulated in floats relative to it on the
 * {@link CgVfxSystem}'s fixed tick, and drawn each frame through a {@link CgVfxFrame}.
 *
 * <pre>{@code
 * public final class MyEffect extends CgVfxEffect<MyEffect> {
 *     public MyEffect(CgVfxLook look, double x, double y, double z) { super(look, x, y, z); }
 *     @Override protected void tick(float dt) { ... }          // CgVfxSystem.TICK seconds of simulation
 *     @Override protected void submit(CgVfxFrame frame) { ... } // draw, interpolated by frame.alpha() (particles:
 *                                                               // frame.particleAlpha())
 * }
 * MyEffect effect = vfx.play(new MyEffect(look, x, y, z));
 * effect.stop();                                                // finishes what is in flight, then dies
 *
 * // Bound to an entity, and joined late: its origin follows the entity's hands, and it starts 2.5 s into its life
 * MyEffect late = owner.play(new MyEffect(look, x, y, z).follow(entityId, CgEntityAttachments.HANDS_FORWARD).startAt(2.5f));
 * }</pre>
 *
 * <p>Name the class in its own declaration ({@code extends CgVfxEffect<MyEffect>}): then every setter here
 * ({@code follow}, {@code startAt}, {@code set}, {@code onHit}, ...) answers a {@code MyEffect}, and a chain can end
 * in the effect's own methods. Code holding any effect writes {@code CgVfxEffect<?>}.</p>
 *
 * <ul>
 *   <li>Its values start as its look's; {@link #set} changes this effect alone, {@link #animate} tweens one there.</li>
 *   <li>{@link #stop()} is its graceful end; {@link #end(CgVfxEnding)} ends it another way, one the caller picks.</li>
 *   <li>An effect is done when it calls {@link #die()}; the system then drops it before the next frame.</li>
 *   <li>It announces the moments of its life with {@link #moment}, which a {@link CgVfxMomentListener} hears: name
 *       them as constants on the effect.</li>
 *   <li>It shakes the camera through shakes declared on its schema, so a look can change them:
 *       {@link #playShake} for a hit, {@link #holdShake} every tick for a tremor that lasts. What it holds stops when
 *       it dies.</li>
 *   <li>Its screen-wide look at a moment (a flash, an aberration) is a post volume it opens with {@link #openVolume}
 *       and weighs each tick; it closes when the effect dies.</li>
 * </ul>
 */
public abstract class CgVfxEffect<E extends CgVfxEffect<E>> {

    private static final Logger LOGGER = LogManager.getLogger("CgVfx");

    public enum State { PLAYING, STOPPING, DEAD }

    /** Where it is: fixed, or moved each frame by {@link #follow}. An effect reads it and never writes it. */
    protected double originX, originY, originZ;
    /** The system playing it, set by {@link CgVfxSystem#play}. */
    CgVfxSystem system;
    /** Who played it, or null for a system played into directly. */
    CgVfxOwner owner;
    /** What runs it on a server, or null on a client: {@link #authoritative}. */
    CgVfxAuthority authority;
    /** Its index in its owner's list; -1 out of it. */
    int ownerSlot = -1;
    /** The entity it follows and the attachment point on it ({@code CgEntityAttachments}); -1 for none. */
    int followEntity = -1, followPoint;
    /** Keeps its offset from the point ({@link #followKeepingOffset}): NaN until its first placement measures it. */
    boolean followKeep;
    double followDx = Double.NaN, followDy, followDz;
    /** The followed entity's box this frame: its centre in the world and half extents; halves 0 while it follows none. */
    double followBoxX, followBoxY, followBoxZ;
    float followHalfX, followHalfY, followHalfZ;
    /** Seconds into its life it starts at, for its system's first update to fast-forward to; 0 once there. */
    float startAge;
    /** Fast-forwarding to its start age: shakes and moments are not played. */
    boolean seeking;
    /** What its emitters threw on a worker, for the render thread to kill it over. */
    volatile RuntimeException failure;
    /** Ended by {@link #kill}, not by {@link #die} alone: its GPU particles go too. */
    boolean killed;
    /** Every instance it has scheduled for the GPU: the slots a kill closes. */
    private CgVfxEmitterInstance[] scheduled = new CgVfxEmitterInstance[0];
    private int scheduledCount;
    /** Its own over its look's ({@link #scalability(CgVfxScalability)}); null for the look's. */
    private CgVfxScalability scalability;
    /** What it drew last frame, relative to its origin: min then max; empty (min above max) until it submits. */
    float boundMinX = Float.POSITIVE_INFINITY, boundMinY, boundMinZ, boundMaxX = Float.NEGATIVE_INFINITY, boundMaxY, boundMaxZ;
    /** Culled and holding: seconds asleep, and seconds since anything of it was in view. */
    boolean asleep;
    float sleptFor, unseenFor;
    /** Its system's culling this update: wanted, how far the camera is from its bounds, the tiers it draws below. */
    boolean wanted = true;
    float cullDistance;
    int lowered;
    /** The share of its emitters' particles its distance leaves. */
    float distanceShare = 1f;
    /** Its look's materials have been handed to the system to compile ahead of use. */
    boolean warmed;
    /** Its emitters' GPU programs have been started. */
    boolean gpuPrepared;
    private final CgVfxLook look;
    private final CgVfxValues values;
    private State state = State.PLAYING;
    private double groundY = Double.NaN;
    /** The shakes it holds, by parameter; made when first held. */
    private CgVfxParam.Shake[] heldParams;
    private CgCameraShake.Held[] held;
    /** What hears its hits; null for none. */
    private List<Consumer<? super CgHit>> hitListeners;
    /** Its hitboxes, ticked after each tick; whether each has had its hits routed to {@link #hit}. */
    private CgHitbox[] hitboxes;
    private boolean[] routed;
    private int hitboxCount;
    private Consumer<CgHit> hitboxHits;
    /** The post volumes it opened, closed when it dies. */
    private CgPostVolume[] volumes;
    /** The emitters {@link #tick(CgVfxEmitterInstance, float)} queued this tick, run by the system after the steps. */
    private CgVfxEmitterInstance[] due = new CgVfxEmitterInstance[0];
    private int dueCount;
    /** Each queued emitter's step, in particle steps: an effect may tick each at its own dt. */
    private float[] dueDts = new float[0];
    /** The emitters {@link #tickEmitters} scheduled for the first time, for the system's GPU queue. */
    private CgVfxEmitterInstance[] admitted = new CgVfxEmitterInstance[0];
    private int admittedCount;
    /** Values easing toward a target: each one's float offset (-1 its presence), from, to, start, length, easing. */
    private int[] tweenAt = new int[0];
    private float[] tweenFrom = new float[0], tweenTo = new float[0], tweenStart = new float[0], tweenLength = new float[0];
    private CgEasing[] tweenEasing = new CgEasing[0];
    private int tweens;
    /** How present it is, 0 to 1: every engine layer multiplies its light by it ({@code FX_PRESENCE}). */
    private float presence = 1f;
    /** The ending it plays and the age it began; null while it plays on. */
    private CgVfxEnding ending;
    private float endingAge;
    /** Its draws' row per placed layer this frame ({@code CgVfxFrame}), valid while {@link #rowsFrame} is the frame's. */
    int[] rows = new int[0];
    long rowsFrame = -1;
    /** Seconds simulated since it started. */
    protected float age;
    /** A stable random number for this effect, 0..1, which shaders read to tell two effects apart. */
    protected final float seed;
    /**
     * Its draws' impact frame subject key ({@code CgWorldRenderer.Draw.subject}), never 0: an impact frame it opens
     * names it ({@code CgPostSettings.subject}) to fill this effect alone in ink.
     */
    protected final int subject = SUBJECTS.incrementAndGet() & Integer.MAX_VALUE | 1;
    private static final AtomicInteger SUBJECTS = new AtomicInteger();
    /** The seed {@link #seeded} hands the effect its supplier constructs; NaN for a random one. */
    private static final ThreadLocal<float[]> PENDING_SEED = ThreadLocal.withInitial(() -> new float[]{Float.NaN});

    protected CgVfxEffect(CgVfxLook look, double x, double y, double z) {
        this.look = look;
        this.values = look.copyValues();
        this.originX = x;
        this.originY = y;
        this.originZ = z;
        float[] pending = PENDING_SEED.get();
        this.seed = Float.isNaN(pending[0]) ? (float) Math.random() : pending[0];
        pending[0] = Float.NaN;
    }

    /**
     * The effect {@code make} constructs, given {@code seed} (0..1) rather than a random one, so every client playing one
     * cue draws the same effect.
     *
     * <pre>{@code
     * HitSpark spark = CgVfxEffect.seeded(0.37f, () -> new HitSpark(HitSpark.LOOK, x, y, z));
     * }</pre>
     *
     * Only the first effect {@code make} constructs takes it; one it constructs inside that is random.
     */
    public static <T extends CgVfxEffect<?>> T seeded(float seed, Supplier<T> make) {
        float[] pending = PENDING_SEED.get();
        pending[0] = seed;
        try {
            return make.get();
        } finally {
            pending[0] = Float.NaN;
        }
    }

    /** This effect as its own class, for the setters' chains. */
    @SuppressWarnings("unchecked")
    private E self() {
        return (E) this;
    }

    /** Its stable random number, 0..1. */
    public final float seed() {
        return seed;
    }

    protected abstract void tick(float dt);

    protected abstract void submit(CgVfxFrame frame);

    /** Ends emission; what is in flight plays out. */
    public void stop() {
        if (state == State.PLAYING) state = State.STOPPING;
    }

    /** Removes it at once, its particles with it: gone by its system's next particle step. */
    public void kill() {
        killed = true;
        die();
    }

    /**
     * Follows entity {@code entity} ({@code CgEntityQuery}'s id): each frame, before it ticks, its origin moves to the
     * attachment point {@code point} of the entity as drawn: a built-in one ({@code CgEntityAttachments.FEET},
     * {@code HANDS_FORWARD}, ...) or one a mod declared and answers from its model ({@code CgEntityAttachments.declare},
     * {@code provide}). Particles already spawned stay in the world unless their emitter is {@code local()}. When the
     * entity is gone it stops, and what is in flight plays out where the entity was last.
     *
     * <pre>{@code
     * owner.play(new Aura(look, x, y, z).follow(playerId, CgEntityAttachments.CENTRE));
     * owner.play(new Spark(look, x, y, z).follow(playerId, MyMod.RIGHT_PALM));   // a point of the mod's own
     * }</pre>
     */
    public final E follow(int entity, int point) {
        followEntity = entity;
        followPoint = point;
        followKeep = false;
        return self();
    }

    /**
     * As {@link #follow(int, int)}, but it stays where it was played relative to the point rather than jumping to it: the
     * offset is taken at its first placement and kept, in world axes. A hit spark at the contact riding the body.
     *
     * <pre>{@code
     * owner.play(new HitSpark(look, contactX, contactY, contactZ).followKeepingOffset(targetId, CgEntityAttachments.CHEST));
     * }</pre>
     */
    public final E followKeepingOffset(int entity, int point) {
        follow(entity, point);
        followKeep = true;
        followDx = Double.NaN;
        return self();
    }

    /** Places it at the point it follows, now at {@code (px, py, pz)}, or at its kept offset from it. */
    final void moveToFollowed(double px, double py, double pz) {
        if (!followKeep) {
            moveTo(px, py, pz);
            return;
        }
        if (Double.isNaN(followDx)) {
            followDx = originX - px;
            followDy = originY - py;
            followDz = originZ - pz;
        }
        moveTo(px + followDx, py + followDy, pz + followDz);
    }

    /** As {@link #follow(int, int)}, at the entity's feet. */
    public final E follow(int entity) {
        return follow(entity, 0);
    }

    /**
     * Culled, lowered and put to sleep by {@code scalability} rather than its look's: one effect that matters more (the
     * player's own) or less than the others of its look.
     *
     * <pre>{@code
     * VFX.play(new Aura(Aura.LOOK, x, y, z).follow(playerId, CgEntityAttachments.FEET)).scalability(CgVfxScalability.HERO);
     * }</pre>
     */
    public final E scalability(CgVfxScalability scalability) {
        this.scalability = scalability;
        return self();
    }

    /**
     * The box its {@code entity()} emitters spawn in while it follows nothing: its centre in the world and its half
     * extents. Following an entity, the entity's box takes its place each frame.
     *
     * <pre>{@code
     * VFX.play(new Aura(LOOK, x, y, z).spawnBox(x, y + 0.9, z, 0.3f, 0.9f, 0.3f));   // round a mannequin
     * }</pre>
     */
    public final E spawnBox(double x, double y, double z, float halfX, float halfY, float halfZ) {
        followBoxX = x;
        followBoxY = y;
        followBoxZ = z;
        followHalfX = halfX;
        followHalfY = halfY;
        followHalfZ = halfZ;
        return self();
    }

    /** What culls it: its own, else its look's. */
    public final CgVfxScalability scalability() {
        return scalability != null ? scalability : look.scalability();
    }

    /** Whether it is culled and holding ({@link CgVfxScalability.Reaction#SLEEP}): ticking and drawing nothing. */
    public final boolean asleep() {
        return asleep;
    }

    /** The entity it follows by network id, or -1. */
    public final int followingId() {
        return followEntity;
    }

    /** The host's entity it follows, cast where it is asked, found in its {@link #world()}; null for none. */
    public final <V> V following() {
        return followEntity < 0 ? null : world().entities().entity(followEntity);
    }

    /**
     * Starts it {@code seconds} into its life: its system's next update steps it there at once, on its fixed tick, so its
     * phase is what it would be had it played from zero. Its particles are prewarmed over the last
     * {@link CgVfxSystem#PREWARM_SECONDS} at most, in coarse steps; older ones are not made. Call before its first tick:
     * before playing it, or just after.
     *
     * <pre>{@code
     * owner.play(new Beam(look, x, y, z).startAt(serverAge));   // a viewer joining while it fires
     * }</pre>
     *
     * <ul>
     *   <li>Its phase is stepped for {@link CgVfxSystem#MAX_START_SECONDS} at most; past that its age jumps, which suits an
     *       effect that works its phase out from {@link #age} and not one that accumulates it.</li>
     *   <li>While it steps there it plays no camera shake and announces no moment: those happened before the viewer
     *       saw it.</li>
     * </ul>
     */
    public final E startAt(float seconds) {
        if (age > 0f) throw new IllegalStateException("startAt after its first tick: it has started");
        startAge = Math.max(0f, seconds);
        return self();
    }

    /**
     * Whether it runs on a server ({@link CgVfxAuthority}): there it decides what gameplay hears, where it goes and what
     * it strikes, while each client draws it. Its shakes, post volumes, particles and moments do nothing there. Guard
     * what else is the client's:
     *
     * <pre>{@code
     * if (!authoritative()) decals.scorch(hitX, hitY, hitZ);   // the client's world
     * }</pre>
     */
    public final boolean authoritative() {
        return authority != null;
    }

    /**
     * What its hitboxes and queries test: its server's level when {@link #authoritative}, else the client's, its
     * entities where this frame draws them.
     *
     * <pre>{@code
     * if (world().cast(BOLT).at(x, y, z).along(dx, dy, dz, 64).source(followingId()).blocksToo().first(hit)) ...
     * }</pre>
     *
     * Ask during {@link #tick}, and end each query before the next ({@code CgCollisionQuery}).
     */
    protected final CgCollisionWorld world() {
        return authority != null ? authority.world
                : CgCollisionWorld.client(CgRenderStage.WORLD_OPAQUE.host().partialTick());
    }

    /**
     * A hitbox it owns, of one part: ticked with it after each {@link #tick(float)} against {@link #world()}, and
     * stopped when it dies. Its hits are the effect's, so {@code onHit(part, ...)} hears every target once. Configure it
     * where it is made; its time is game ticks, as every hitbox's.
     *
     * <pre>{@code
     * // in tick, as the blast bursts: a front 2 thick running 10 blocks out over 8 ticks
     * hitbox(PART_BLAST, CgShell.front(2)).at(originX + x, originY + y, originZ + z).radius(0, 10, 8).activeFor(8)
     *         .source(followingId());
     * }</pre>
     *
     * <ul>
     *   <li>Give it no listener: its hits are the effect's, and its first tick throws if it has one.</li>
     *   <li>Authoritative on a server, against the level; cosmetic on a client, against what it draws.</li>
     *   <li>Silent while it fast-forwards to its start age, as every hit is.</li>
     * </ul>
     */
    protected final CgHitbox hitbox(CgHitPart part, CgShape shape) {
        CgHitbox hitbox = CgHitbox.of(part, shape);
        if (hitboxes == null) {
            hitboxes = new CgHitbox[2];
            routed = new boolean[2];
            hitboxHits = this::hit;
        } else if (hitboxCount == hitboxes.length) {
            hitboxes = Arrays.copyOf(hitboxes, hitboxCount * 2);
            routed = Arrays.copyOf(routed, hitboxCount * 2);
        }
        hitboxes[hitboxCount] = hitbox;
        routed[hitboxCount++] = false;
        return hitbox;
    }

    private void tickHitboxes(float dt) {
        CgCollisionWorld world = world();
        for (int i = 0; i < hitboxCount; i++) {
            CgHitbox hitbox = hitboxes[i];
            if (!hitbox.stopped()) {
                if (!routed[i]) {
                    if (hitbox.hasListeners()) {
                        throw new IllegalStateException(getClass().getSimpleName()
                                + "'s hitbox has a listener of its own: its hits are the effect's, heard by onHit");
                    }
                    hitbox.onHit(hitboxHits);
                    routed[i] = true;
                }
                hitbox.tick(world, dt / CgVfxSystem.GAME_TICK);
            }
            if (hitbox.stopped()) {
                hitboxes[i] = hitboxes[--hitboxCount];
                routed[i] = routed[hitboxCount];
                hitboxes[hitboxCount] = null;
                i--;
            }
        }
    }

    /** Who played it; null when played into a system directly. */
    public final CgVfxOwner owner() {
        return owner;
    }

    /**
     * Hears what it strikes: each hit its {@link #hitbox hitboxes} make and each it {@linkplain #hit reports}, its part
     * saying which part of it struck.
     *
     * <pre>{@code
     * owner.play(new CgEnergyWave(look, x, y, z).collide()).onHit(h -> { if (h.entityId() >= 0) flash(h.entity()); });
     * }</pre>
     *
     * <ul>
     *   <li>The thread it ticks on: the render thread, or the server thread for an {@link #authoritative} one. Before
     *       it plays is fine too.</li>
     *   <li>The hit is valid during the call: copy what you keep ({@code CgHit.set}).</li>
     *   <li>One that throws is logged, and the others still hear.</li>
     * </ul>
     */
    public final E onHit(Consumer<? super CgHit> listener) {
        if (listener == null) throw new IllegalArgumentException("listener");
        if (hitListeners == null) hitListeners = new ArrayList<>(2);
        hitListeners.add(listener);
        return self();
    }

    /**
     * As {@link #onHit(Consumer)}, hearing only what {@code part} strikes.
     *
     * <pre>{@code
     * wave.onHit(CgEnergyWave.PART_BEAM, h -> stagger(h.entity()))
     *     .onHit(CgEnergyWave.PART_BLAST, h -> damage(h.entity()));
     * }</pre>
     */
    public final E onHit(CgHitPart part, Consumer<? super CgHit> listener) {
        if (part == null) throw new IllegalArgumentException("part");
        if (listener == null) throw new IllegalArgumentException("listener");
        return onHit(h -> {
            if (h.part() == part) listener.accept(h);
        });
    }

    /**
     * Reports something it struck to its {@link #onHit} listeners: a query's hit, or one it makes itself. Its part
     * ({@code CgHit.part}) is one its schema declares; nothing while it fast-forwards to its start age.
     *
     * <pre>{@code
     * if (world().cast(BOLT).at(x, y, z).along(dx, dy, dz, 64).part(PART_BOLT).first(hit)) hit(hit);
     * hit(burst.setPoint(bx, by, bz, PART_BURST));        // where it burst, struck by no test
     * }</pre>
     */
    protected final void hit(CgHit hit) {
        if (seeking || hitListeners == null) return;
        for (int i = 0; i < hitListeners.size(); i++) {
            try {
                hitListeners.get(i).accept(hit);
            } catch (RuntimeException e) {
                LOGGER.error("A hit listener of {} threw on {}", getClass().getSimpleName(), hit, e);
            }
        }
    }

    protected final void die() {
        state = State.DEAD;
        for (int i = 0; i < hitboxCount; i++) {
            hitboxes[i].stop();
            hitboxes[i] = null;
        }
        hitboxCount = 0;
        if (held != null) {
            for (CgCameraShake.Held h : held) {
                if (h != null) h.close();
            }
        }
        if (volumes != null) {
            for (CgPostVolume v : volumes) v.close();
            volumes = null;
        }
    }

    /**
     * A post volume applying {@code settings} at {@code priority}, centred at {@code (x, y, z)} relative to the
     * origin, at weight 0: size it, then weigh it each tick. It closes when the effect dies.
     *
     * <pre>{@code
     * flash = openVolume(10, new CgPostSettings().flash(1.5f), hitX, hitY, hitZ).radius(24f).blend(64f);
     * flash.weight(curve(FLASH).at(t));   // each tick
     * }</pre>
     */
    protected final CgPostVolume openVolume(int priority, CgPostSettings settings, float x, float y, float z) {
        // On a server, one no stack applies: the effect weighs it as on a client.
        CgPostVolume volume = (authority != null ? new CgPostVolume(priority, settings, v -> { })
                : CgPostStack.get().volume(priority, settings)).at(originX + x, originY + y, originZ + z).weight(0f);
        volumes = volumes == null ? new CgPostVolume[1] : Arrays.copyOf(volumes, volumes.length + 1);
        volumes[volumes.length - 1] = volume;
        return volume;
    }

    public final State state() {
        return state;
    }

    public final CgVfxLook look() {
        return look;
    }

    /** Where it is in world coordinates, as its last update placed it ({@link #follow} moves it each frame). */
    public final double originX() {
        return originX;
    }

    public final double originY() {
        return originY;
    }

    public final double originZ() {
        return originZ;
    }

    public final float age() {
        return age;
    }

    public final float get(CgVfxParam.Scalar param) {
        return values.get(param);
    }

    /** Component {@code index} of a colour: 0 red, 1 green, 2 blue, 3 the fourth value. */
    public final float get(CgVfxParam.Color param, int index) {
        return values.get(param, index);
    }

    /** Component {@code component} of colour {@code entry} of a palette. */
    public final float get(CgVfxParam.Palette param, int entry, int component) {
        return values.get(param, entry, component);
    }

    /**
     * Sets a number for this effect alone, stopping a tween of it; what follows it follows. On a server, an effect a
     * lasting cue runs sends it to every viewer at the tick's end, as it does a colour ({@code CgVfxCue.authoritative()}).
     */
    public final E set(CgVfxParam.Scalar param, float value) {
        untween(values.layout().offset(param), 1);
        values.set(param, value);
        if (authority != null) authority.set(param);
        return self();
    }

    public final E set(CgVfxParam.Color param, float r, float g, float b, float a) {
        untween(values.layout().offset(param), 4);
        values.set(param, r, g, b, a);
        if (authority != null) authority.set(param);
        return self();
    }

    /** Sets colour {@code entry} of a palette. */
    public final E set(CgVfxParam.Palette param, int entry, float r, float g, float b, float a) {
        untween(values.layout().offset(param) + entry * 4, 4);
        values.set(param, entry, r, g, b, a);
        if (authority != null) authority.set(param);
        return self();
    }

    /** {@code 0xAARRGGBB}. */
    public final E set(CgVfxParam.Color param, int argb) {
        return set(param, argb, 1f);
    }

    /** {@code 0xAARRGGBB}, its rgb times {@code intensity}: past white where above 1. */
    public final E set(CgVfxParam.Color param, int argb, float intensity) {
        return set(param, CgVfxParam.Color.red(argb) * intensity, CgVfxParam.Color.green(argb) * intensity,
                CgVfxParam.Color.blue(argb) * intensity, CgVfxParam.Color.alpha(argb));
    }

    /** Colour {@code entry} of a palette, {@code 0xAARRGGBB}. */
    public final E set(CgVfxParam.Palette param, int entry, int argb) {
        return set(param, entry, argb, 1f);
    }

    /** Colour {@code entry} of a palette, {@code 0xAARRGGBB}, its rgb times {@code intensity}. */
    public final E set(CgVfxParam.Palette param, int entry, int argb, float intensity) {
        return set(param, entry, CgVfxParam.Color.red(argb) * intensity, CgVfxParam.Color.green(argb) * intensity,
                CgVfxParam.Color.blue(argb) * intensity, CgVfxParam.Color.alpha(argb));
    }

    public final CgKeyframes curve(CgVfxParam.Curve param) {
        return values.curve(param);
    }

    public final E set(CgVfxParam.Curve param, CgKeyframes curve) {
        values.set(param, curve);
        return self();
    }

    public final CgCameraShake shake(CgVfxParam.Shake param) {
        return values.shake(param);
    }

    public final E set(CgVfxParam.Shake param, CgCameraShake shake) {
        values.set(param, shake);
        return self();
    }

    /**
     * A tween: moves a number from where it is to {@code to} over {@code seconds} rather than jumping there, eased along
     * {@code easing}, a step each tick. What follows it by a link follows. On a server, an effect a lasting cue runs
     * plays it on every viewer at once, rather than sending each step.
     *
     * <pre>{@code
     * wave.animate(CgEnergyWave.RADIUS, 1.4f, 0.5f, CgEasings.OUT_BACK);   // powered up: past 1.4, then settling there
     * }</pre>
     *
     * A later tween of the same value replaces it from where it has got to; a {@link #set} of it stops it. It breaks the
     * value's own link.
     */
    public final E animate(CgVfxParam.Scalar param, float to, float seconds, CgEasing easing) {
        int at = values.layout().offset(param);
        values.set(param, values.get(param));   // breaks the link it followed: the tween drives it now
        tween(at, to, seconds, easing);
        if (authority != null) authority.animated(param, new float[] {to}, seconds, easing);
        return self();
    }

    /** Eases a colour to {@code (r, g, b, a)} over {@code seconds}. */
    public final E animate(CgVfxParam.Color param, float r, float g, float b, float a, float seconds,
                                     CgEasing easing) {
        int at = values.layout().offset(param);
        float[] to = {r, g, b, a};
        for (int i = 0; i < 4; i++) tween(at + i, to[i], seconds, easing);
        if (authority != null) authority.animated(param, to, seconds, easing);
        return self();
    }

    /** Eases a colour to {@code 0xAARRGGBB} over {@code seconds}. */
    public final E animate(CgVfxParam.Color param, int argb, float seconds, CgEasing easing) {
        return animate(param, CgVfxParam.Color.red(argb), CgVfxParam.Color.green(argb), CgVfxParam.Color.blue(argb),
                CgVfxParam.Color.alpha(argb), seconds, easing);
    }

    /**
     * Ends it by {@code ending}: its values eased to where the ending puts them over its seconds, then gone. Nothing new
     * starts meanwhile, as after {@link #stop()}; an effect skips what a graceful end would play in
     * {@link #ending(CgVfxEnding)}. A second ending, or one after it died, is ignored.
     *
     * <pre>{@code
     * wave.end(CgEnergyWave.FADE);
     * }</pre>
     */
    public final E end(CgVfxEnding ending) {
        if (state == State.DEAD || this.ending != null) return self();
        this.ending = ending;
        endingAge = age;
        for (CgVfxEnding.Target target : ending.targets()) {
            int at = values.layout().offset(target.param());
            float[] to = target.to();
            for (int i = 0; i < to.length; i++) tween(at + i, to[i], ending.seconds(), target.easing());
        }
        if (ending.fadesOut()) tween(-1, 0f, ending.seconds(), ending.fadeEasing());
        if (authority != null) authority.ended(ending);
        ending(ending);
        stop();
        return self();
    }

    /** The ending it plays, or null: an effect asks to skip what its graceful end would have played. */
    public final CgVfxEnding ending() {
        return ending;
    }

    /** How present it is, 0 to 1: 1 unless an ending fades it ({@link CgVfxEnding#FADE_OUT}). */
    public final float presence() {
        return presence;
    }

    /**
     * Heard as it starts {@code ending}, before {@link #stop()}: an effect drops what its graceful end plays (a wave
     * bursting into its blast). Nothing by default.
     */
    protected void ending(CgVfxEnding ending) {
    }

    /** Stops the tweens of the {@code count} floats from {@code at}, leaving each where it has got to. */
    private void untween(int at, int count) {
        for (int i = 0; i < tweens; i++) {
            if (tweenAt[i] < at || tweenAt[i] >= at + count) continue;
            removeTween(i--);
        }
    }

    /** Drops tween {@code i}, the last moving into its place. */
    private void removeTween(int i) {
        tweens--;
        tweenAt[i] = tweenAt[tweens];
        tweenFrom[i] = tweenFrom[tweens];
        tweenTo[i] = tweenTo[tweens];
        tweenStart[i] = tweenStart[tweens];
        tweenLength[i] = tweenLength[tweens];
        tweenEasing[i] = tweenEasing[tweens];
    }

    /** Eases the float at {@code at} (-1 its presence) to {@code to}; a later tween of it replaces this one. */
    private void tween(int at, float to, float seconds, CgEasing easing) {
        int i = 0;
        while (i < tweens && tweenAt[i] != at) i++;
        if (i == tweens) {
            if (tweens == tweenAt.length) {
                int n = Math.max(4, tweens * 2);
                tweenAt = Arrays.copyOf(tweenAt, n);
                tweenFrom = Arrays.copyOf(tweenFrom, n);
                tweenTo = Arrays.copyOf(tweenTo, n);
                tweenStart = Arrays.copyOf(tweenStart, n);
                tweenLength = Arrays.copyOf(tweenLength, n);
                tweenEasing = Arrays.copyOf(tweenEasing, n);
            }
            tweens++;
        }
        tweenAt[i] = at;
        tweenFrom[i] = at < 0 ? presence : values.raw(at);
        tweenTo[i] = to;
        tweenStart[i] = age;
        tweenLength[i] = Math.max(seconds, 0f);
        tweenEasing[i] = easing;
    }

    /** Steps every tween to this tick's age; one that has arrived ends. */
    private void stepTweens() {
        for (int i = 0; i < tweens; i++) {
            float t = tweenLength[i] <= 0f ? 1f : Math.min((age - tweenStart[i]) / tweenLength[i], 1f);
            float v = tweenFrom[i] + (tweenTo[i] - tweenFrom[i]) * (float) tweenEasing[i].ease(t);
            if (tweenAt[i] < 0) presence = v;
            else values.setRaw(tweenAt[i], v);
            if (t < 1f) continue;
            removeTween(i--);
        }
    }

    /** Plays the shake {@code param} at a point relative to its origin, its radii in units of {@code scale} blocks. */
    protected final void playShake(CgVfxParam.Shake param, float x, float y, float z, float scale) {
        if (!seeking && authority == null) values.shake(param).play(originX + x, originY + y, originZ + z, scale);
    }

    /** As {@link #playShake(CgVfxParam.Shake, float, float, float, float)}, its punch shoving along {@code (dx, dy, dz)}. */
    protected final void playShake(CgVfxParam.Shake param, float x, float y, float z, float dx, float dy, float dz, float scale) {
        if (!seeking && authority == null) values.shake(param).play(originX + x, originY + y, originZ + z, dx, dy, dz, scale);
    }

    /**
     * Holds the shake {@code param} at a point relative to its origin, its trauma times {@code level}: call it every tick
     * while it lasts. Left unset for a quarter second, it lapses.
     */
    protected final void holdShake(CgVfxParam.Shake param, float x, float y, float z, float scale, float level) {
        if (seeking || authority != null) return;
        CgCameraShake shake = values.shake(param);
        int slot = 0;
        if (heldParams == null) {
            heldParams = new CgVfxParam.Shake[4];
            held = new CgCameraShake.Held[4];
        }
        while (slot < heldParams.length && heldParams[slot] != null && heldParams[slot] != param) slot++;
        if (slot == heldParams.length) {
            heldParams = Arrays.copyOf(heldParams, slot * 2);
            held = Arrays.copyOf(held, slot * 2);
        }
        if (held[slot] == null || held[slot].shake() != shake) {
            if (held[slot] != null) held[slot].close();
            heldParams[slot] = param;
            held[slot] = shake.hold();
        }
        held[slot].at(originX + x, originY + y, originZ + z).scale(scale).level(level);
    }

    /**
     * Where its particles land where the host has no world (the harness), as a world height; NaN, the default, for no
     * ground. In a world they land on its blocks (the voxel window).
     */
    public final E ground(double worldY) {
        groundY = worldY;
        return self();
    }

    /** The ground's height relative to its origin, or NaN: what an emitter instance takes. */
    protected final float groundHeight() {
        return (float) (groundY - originY);
    }

    /**
     * Advances one of its emitters by a tick, at its spawn share: books what it spawns, which its GPU slot steps. Played,
     * the emitter runs after {@link #tick(float)} returns, with this effect's other emitters, in the order they were
     * asked, on whichever thread takes the effect, and only every {@link CgVfxSystem#particleStep()}th tick, by that many
     * ticks' time; unplayed, its books alone, at once, by {@code dt}. Call it every tick either way.
     *
     * <pre>{@code
     * for (CgVfxEmitterInstance e : blast) {
     *     tick(e, dt);
     *     done &= e.finished();          // as of the previous tick when played
     * }
     * }</pre>
     *
     * <ul>
     *   <li>{@link #authoritative} on a server, it does nothing: the emitter never finishes, so an effect whose end waits
     *       on its particles ends itself there.</li>
     *   <li>What it reads of the emitter in the same {@code tick} is the previous tick's.</li>
     *   <li>Nothing the emitter's modules read may change after this call within the tick: its ground's
     *       {@code fill} belongs before it.</li>
     * </ul>
     */
    protected final void tick(CgVfxEmitterInstance emitter, float dt) {
        if (authority != null) return;
        CgVfxGas fed = emitter.books().gas();
        if (fed != null && emitter.emitter().feedsGas()) {
            // Its particles' deposits land only where the gas holds tiles.
            fed.wantTiles(emitter.originX() + emitter.sourceX(), emitter.originY() + emitter.sourceY(),
                    emitter.originZ() + emitter.sourceZ(), Math.min(16f, emitter.emitter().reach(0f)));
        }
        if (system == null) {
            emitter.books().schedule(dt, originX, originY, originZ);
            return;
        }
        if (!system.particleTick()) return;
        emitter.books().share(system.spawnShare(emitter.emitter(), lowered) * distanceShare);
        for (int e = 0; e < emitter.emitter().events().size(); e++) {
            CgVfxEmitterInstance child = emitter.child(e);
            if (child != null) child.books().share(system.spawnShare(child.emitter(), lowered) * distanceShare);
        }
        if (dueCount == due.length) {
            due = Arrays.copyOf(due, Math.max(4, dueCount * 2));
            dueDts = Arrays.copyOf(dueDts, due.length);
        }
        dueDts[dueCount] = dt / CgVfxSystem.TICK * system.particleDt();
        due[dueCount++] = emitter;
    }

    /** Whether {@link #tick(CgVfxEmitterInstance, float)} queued emitters this tick. */
    final boolean hasEmitterTicks() {
        return dueCount > 0;
    }

    /**
     * Books the emitters queued this tick, in order, for its system's GPU queue: one first booked here takes a slot when
     * the system admits it. Any thread, one at a time per effect.
     */
    final void tickEmitters() {
        for (int i = 0; i < dueCount; i++) {
            CgVfxEmitterInstance emitter = due[i];
            boolean fresh = !emitter.books().scheduled();
            emitter.books().entityBox((float) (followBoxX - originX), (float) (followBoxY - originY), (float) (followBoxZ - originZ),
                    followHalfX, followHalfY, followHalfZ);
            emitter.books().schedule(dueDts[i], originX, originY, originZ);
            if (fresh) {
                if (admitted.length == admittedCount) admitted = Arrays.copyOf(admitted, Math.max(4, admittedCount * 2));
                admitted[admittedCount++] = emitter;
                if (scheduled.length == scheduledCount) scheduled = Arrays.copyOf(scheduled, Math.max(4, scheduledCount * 2));
                scheduled[scheduledCount++] = emitter;
            }
            due[i] = null;
        }
        dueCount = 0;
    }

    /** Ends the slots of every instance it scheduled, particles and all, at {@code steps}' next step. Render thread. */
    final void killSlots(CgVfxGpuSteps steps) {
        for (int i = 0; i < scheduledCount; i++) steps.kill(scheduled[i]);
    }

    /** Culled to sleep: its particles freed, its instances to ask for slots again when it wakes. Render thread. */
    final void sleep(CgVfxGpuSteps steps) {
        killSlots(steps);
        for (int i = 0; i < scheduledCount; i++) {
            scheduled[i].books().slotLost();
            scheduled[i] = null;
        }
        scheduledCount = 0;
        for (int i = 0; i < admittedCount; i++) admitted[i] = null;
        admittedCount = 0;
        for (int i = 0; i < dueCount; i++) due[i] = null;
        dueCount = 0;
        asleep = true;
        sleptFor = 0f;
    }

    /** Wanted again: its next update steps it on over the time it slept, as {@link #startAt} does. */
    final void wake() {
        asleep = false;
        startAge += sleptFor;
        sleptFor = 0f;
    }

    /** Empties its bounds, ahead of the draws that grow them. */
    final void clearBounds() {
        boundMinX = boundMinY = boundMinZ = Float.POSITIVE_INFINITY;
        boundMaxX = boundMaxY = boundMaxZ = Float.NEGATIVE_INFINITY;
    }

    /** Grows its bounds by a sphere at {@code (x, y, z)} from its origin. */
    final void bound(float x, float y, float z, float radius) {
        boundMinX = Math.min(boundMinX, x - radius);
        boundMinY = Math.min(boundMinY, y - radius);
        boundMinZ = Math.min(boundMinZ, z - radius);
        boundMaxX = Math.max(boundMaxX, x + radius);
        boundMaxY = Math.max(boundMaxY, y + radius);
        boundMaxZ = Math.max(boundMaxZ, z + radius);
    }

    /** Grows its bounds by a box from its origin. */
    final void boundBox(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        boundMinX = Math.min(boundMinX, minX);
        boundMinY = Math.min(boundMinY, minY);
        boundMinZ = Math.min(boundMinZ, minZ);
        boundMaxX = Math.max(boundMaxX, maxX);
        boundMaxY = Math.max(boundMaxY, maxY);
        boundMaxZ = Math.max(boundMaxZ, maxZ);
    }

    /** Whether it has drawn anything to bound. */
    final boolean bounded() {
        return boundMinX <= boundMaxX;
    }

    /** Hands the instances {@link #tickEmitters} scheduled for the first time to {@code steps}. Render thread. */
    final void admitScheduled(CgVfxGpuSteps steps) {
        for (int i = 0; i < admittedCount; i++) {
            steps.admit(admitted[i], this);
            admitted[i] = null;
        }
        admittedCount = 0;
    }

    /** Whether anything hears its moments: skip working out a moment's framing when nothing does. */
    protected final boolean momentsHeard() {
        return system != null && !seeking && system.hasMomentListeners();
    }

    /**
     * Announces that it has reached the moment {@code name}, framed by a point relative to its origin and a radius that
     * holds what matters: what a capture tool photographs. Call it once, as the moment is crossed in {@link #tick}.
     */
    protected final void moment(String name, float x, float y, float z, float radius) {
        if (momentsHeard()) system.moment(this, name, originX + x, originY + y, originZ + z, radius);
    }

    final void step(float dt) {
        values.takeTuning();
        age += dt;
        if (tweens > 0) stepTweens();
        tick(dt);
        if (hitboxCount > 0) tickHitboxes(dt);
        if (ending != null && state != State.DEAD && age - endingAge >= ending.seconds()) die();
    }

    /** Moves the origin, between ticks: {@link #follow}'s. */
    final void moveTo(double x, double y, double z) {
        followed(x, y, z);
    }

    /**
     * Where {@link #follow} puts the effect this frame, in world coordinates. By default it moves the origin, and with it
     * everything the effect draws and every particle it launched: an aura riding its entity. An effect whose launched
     * parts belong to the world moves only its source here.
     *
     * <pre>{@code
     * @Override
     * protected void followed(double x, double y, double z) {
     *     muzzleX = (float) (x - originX);   // the muzzle rides the hands; what it fired stays where it is
     *     muzzleY = (float) (y - originY);
     *     muzzleZ = (float) (z - originZ);
     * }
     * }</pre>
     */
    protected void followed(double x, double y, double z) {
        originX = x;
        originY = y;
        originZ = z;
    }

    /** Its own values, live: what its layers read each draw. */
    public final CgVfxValues values() {
        return values;
    }
}
