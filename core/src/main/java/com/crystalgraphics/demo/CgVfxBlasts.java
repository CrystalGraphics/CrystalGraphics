package com.crystalgraphics.demo;

import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.vfx.CgVfxEffect;
import com.crystalgraphics.vfx.CgVfxFrame;
import com.crystalgraphics.vfx.CgVfx;
import com.crystalgraphics.vfx.CgVfxOwner;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.element.CgVfxBlast;
import com.crystalgraphics.vfx.element.CgVfxExplosion;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxSchema;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxEmitterInstance;
import com.crystalgraphics.world.CgWorldQueries;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Blasts as particles alone, a GPU particle showcase: the explosion kit's specks (and the dust they kick up), sparkles,
 * ink and rays, without its billows and rings, scattered over a disc in the energy waves' three palettes, each bursting
 * again every {@link #CYCLE} seconds a little way from its last. 120 keep about 360,000 particles alive.
 * {@link CgRenderDemo} plays it in a Minecraft world; the harness's {@code vfx-blasts} scene over the showcase's floor.
 *
 * <pre>{@code
 * CgVfxBlasts blasts = new CgVfxBlasts(120);
 * // every frame, before the world stages fire:
 * blasts.submit(CgWorldRenderer.get(), x, y, z, CgFrameClock.seconds());   // the disc centred on (x, z), its floor at y
 * // once, when the context goes:
 * blasts.delete();
 * }</pre>
 *
 * <p>Each burst's debris lands on the world's ground under it where the host answers {@link CgWorldQueries#groundUnder},
 * else on the floor at {@code y}. Moving the centre moves the bursts that follow; those in flight finish where they are.</p>
 */
public final class CgVfxBlasts {

    /** Seconds between one spot's bursts. */
    public static final float CYCLE = 2.6f;
    /** How far out the farthest spot is, and how far a burst strays from its spot. */
    public static final float REACH = 46f, STRAY = 3f;
    /** Blocks above the floor a burst goes off, at the least and at the most. */
    private static final float LOW = 1.5f, HIGH = 7.5f;
    /** Blocks over a burst its ground search starts from, and how far down it looks. */
    private static final int SEARCH = 16, DEPTH = 48;

    private static final CgVfxSchema SCHEMA = CgVfxSchema.of("crystalgraphics:demo/blasts");
    private static final CgVfxExplosion KIT = CgVfxBlast.EXPLOSION;
    /** The kit's emitters a burst starts: its particles without its meshes or its ink. */
    private static final List<CgVfxEmitter> PLAYED = List.of(KIT.specks, KIT.sparkles, KIT.rays);
    /** In the Kamehameha's blue, the Final Flash's gold and the Galick Gun's violet. */
    private static final CgVfxLook BLUE = CgVfxLook.builder(SCHEMA).with(KIT.kit()).build();
    private static final CgVfxLook[] LOOKS = {
            BLUE,
            BLUE.derive().set(KIT.hot, 0xFFFFE04C).set(KIT.debris, 0xFF1A0803).build(),
            BLUE.derive().set(KIT.hot, 0xFFFAA6FF).set(KIT.debris, 0xFF0F031A).build(),
    };

    private final CgVfxOwner vfx;
    /** A system of its own, updated and drawn by {@link #submit}; false on the world's. */
    private final boolean drives;
    /** Per spot, the burst it last fired. */
    private final int[] shots;
    /** Whether {@link #shots} follow the clock; false until the first submit, and again after a clear. */
    private boolean started;

    /** {@code count} spots, each bursting once a {@link #CYCLE}, staggered evenly over it, on a system of its own. */
    public CgVfxBlasts(int count) {
        this(count, null);
    }

    /** As {@link #CgVfxBlasts(int)}, played by {@code owner}: the world's system, which the engine updates and draws. */
    public CgVfxBlasts(int count, CgVfxOwner owner) {
        shots = new int[count];
        Arrays.fill(shots, -1);
        drives = owner == null;
        vfx = drives ? CgVfx.owner("crystalgraphics", new CgVfxSystem()) : owner;
    }

    /** How many spots burst. */
    public int count() {
        return shots.length;
    }

    /** Fires the bursts due at {@code seconds} round {@code (x, z)} over a floor at {@code y}, and submits every one alive. */
    public void submit(CgWorldRenderer world, double x, double y, double z, double seconds) {
        int count = shots.length;
        if (!started) {
            // Taken up mid-cycle (a host's clock is far from 0, or after a clear): each spot waits for its own next
            // burst, as the stagger has it, rather than all of them firing in this frame.
            for (int i = 0; i < count; i++) {
                double t = seconds - i * CYCLE / count;
                shots[i] = t < 0 ? -1 : (int) (t / CYCLE);
            }
            started = true;
        }
        for (int i = 0; i < count; i++) {
            double t = seconds - i * CYCLE / count;
            if (t < 0) continue;
            int shot = (int) (t / CYCLE);
            if (shot == shots[i]) continue;
            shots[i] = shot;
            // A golden-angle spiral, so the spots fill the disc evenly at any count.
            float angle = i * 2.39996f, out = REACH * (float) Math.sqrt((i + 0.5f) / count);
            double bx = x + Math.cos(angle) * out + STRAY * (hash(i, shot, 1) * 2f - 1f);
            double bz = z + Math.sin(angle) * out + STRAY * (hash(i, shot, 2) * 2f - 1f);
            double floor = CgWorldQueries.groundUnder(bx, y + SEARCH, bz, DEPTH);
            if (Double.isNaN(floor)) floor = y;
            double by = floor + LOW + (HIGH - LOW) * hash(i, shot, 3);
            vfx.play(new Burst(LOOKS[i % LOOKS.length], bx, by, bz, floor));
        }
        if (drives) {
            vfx.system().update(seconds);
            vfx.system().submit(world);
        }
    }

    /** Warms every look's kernels and programs, so the first bursts stall no frame. On the render thread. */
    public void prepare() {
        for (CgVfxLook look : LOOKS) vfx.prepare(look);
    }

    /** Whether everything {@link #prepare} started compiling is built. */
    public boolean warmed() {
        return vfx.warmed();
    }

    /** Ends every burst at once; from the next {@link #submit} each spot fires at its next turn. */
    public void clear() {
        clearPlays();
        started = false;
    }

    /** The system the blasts play through. */
    public CgVfxSystem vfx() {
        return vfx.system();
    }

    /** Releases its own system's meshes; on the world's, ends its bursts. Call on context teardown. */
    public void delete() {
        if (drives) {
            vfx.system().delete();
        } else {
            vfx.clear();
        }
    }

    /** Its own system cleared whole, its clock with it; on the world's, its bursts alone. */
    private void clearPlays() {
        if (drives) {
            vfx.system().clear();
        } else {
            vfx.clear();
        }
    }

    /** One burst of {@link #PLAYED} at its origin, landing on a floor at {@code floorY}; dies when they all have. */
    private static final class Burst extends CgVfxEffect<Burst> {
        private final List<CgVfxEmitterInstance> emitters = new ArrayList<>();

        Burst(CgVfxLook look, double x, double y, double z, double floorY) {
            super(look, x, y, z);
            for (int i = 0; i < PLAYED.size(); i++) {
                CgVfxEmitterInstance emitter = new CgVfxEmitterInstance(look.emitter(PLAYED.get(i).name()), seed + i * 0.137f);
                emitter.start(0f, 0f, 0f);
                emitter.ground((float) (floorY - y));
                emitters.add(emitter);
            }
        }

        @Override
        protected void tick(float dt) {
            boolean done = true;
            for (int i = 0; i < emitters.size(); i++) {
                tick(emitters.get(i), dt);
                done &= emitters.get(i).finished();
            }
            if (done && age > 1f) die();
        }

        @Override
        protected void submit(CgVfxFrame frame) {
            for (int i = 0; i < emitters.size(); i++) frame.particles(this, emitters.get(i));
        }
    }

    /** A hash of {@code (i, shot, salt)} in [0, 1). */
    private static float hash(int i, int shot, int salt) {
        int h = (i + 1) * 0x27d4eb2d ^ (shot + 1) * 0x165667b1 ^ salt * 0x61c88647;
        h ^= h >>> 15;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        h *= 0xc2b2ae35;
        h ^= h >>> 16;
        return (h & 0xFFFFFF) / (float) 0x1000000;
    }
}
