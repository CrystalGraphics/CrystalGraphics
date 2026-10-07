package com.crystalgraphics.vfx.world;

import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.compute.CgKernel;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgWorldEvents;
import com.crystalgraphics.platform.service.CgWorldQuery;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.graph.CgDispatch;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.graph.CgTextureDesc;
import com.crystalgraphics.render.stage.CgHostView;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.trace.CgGpuTrace;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.vfx.CgVfxTrace;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * The host world around the camera as volumes the GPU reads (vfx-gpu §4.8): per block, which octants are solid, its
 * light and its fluid ({@link CgVfxVoxels}), {@link #WIDTH} x {@link #HEIGHT} x {@link #DEPTH} blocks. What GPU particles
 * land on and are lit by. Filled from {@code CgWorldQuery} a section at a time, the nearest unknown first, under a time
 * budget each frame; addressed toroidally, so a camera crossing a section boundary fills only what came into view. With
 * it, while a reader asks for it ({@link #useDistance}), each block's distance to the nearest solid octant, up to 8
 * blocks: a jump flood on the GPU over the blocks around the sections whose solid octants changed, at most every
 * {@link #DISTANCE_FRAMES}.
 *
 * <pre>{@code
 * CgVfxVoxelWindow window = CgVfxVoxelWindow.get();
 * window.use();                       // any frame something reads it: it fills only while used
 * window.useDistance();               // and any frame something reads its distance field
 * // a kernel recorded on WORLD_OPAQUE after ORDER, which includes lib/vfx/fx_world.glsl and declares PROPERTIES:
 * window.bind(dispatch);
 * }</pre>
 *
 * <pre>{@code
 * // in the kernel
 * ivec3 base = ivec3(_WorldBaseX, _WorldBaseY, _WorldBaseZ), wrap = ivec3(_WorldWrapX, _WorldWrapY, _WorldWrapZ);
 * if (_WorldLive != 0) light = fx_world_light(_World, _WorldSections, base, wrap, block, light);
 * // the distance to solid at p from an origin, and the way out of it
 * vec4 field = fx_world_field(_WorldDistance, _WorldSections, base, wrap, originBlock, originFrac, p);
 * vec3 normal = dot(field.xyz, field.xyz) > 0.0 ? normalize(field.xyz) : vec3(0.0, 1.0, 0.0);
 * }</pre>
 *
 * <ul>
 *   <li>Absolute block coordinates throughout, never camera-relative: a kernel finds a particle's block from its
 *       origin's whole blocks plus a float within.</li>
 *   <li>A section not filled yet reads as unknown ({@code fx_world_known}): no floor there, and the fallback light.
 *       Nothing outside the window is known.</li>
 *   <li>With no level ({@code _WorldLive} 0) nothing in it means anything: a reader takes its fixed values.</li>
 *   <li>A broken block refills its section first; the rest refresh in turn, oldest first, a placed block or a light
 *       change reaching it within {@link #REFRESH_FRAMES}.</li>
 *   <li>Render thread.</li>
 * </ul>
 */
public final class CgVfxVoxelWindow {

    /** The window's blocks on each axis, and its sections. */
    public static final int WIDTH = 128, HEIGHT = 96, DEPTH = 128;
    public static final int SECTIONS_X = WIDTH / CgVfxVoxels.SECTION, SECTIONS_Y = HEIGHT / CgVfxVoxels.SECTION,
            SECTIONS_Z = DEPTH / CgVfxVoxels.SECTION;
    /** Where it records on {@code WORLD_OPAQUE}: ahead of the pools' steps and Range, which read it. */
    public static final int ORDER = CgWorldRenderer.ORDER - 150;
    /** Fill time a frame, at least one section. */
    public static final long BUDGET_NANOS = 500_000L;
    /** Frames between refills of one section once every section is known. */
    public static final int REFRESH_FRAMES = 1800;
    /** Frames without a {@link #use} after which it stops filling. */
    public static final int IDLE_FRAMES = 120;
    /** Frames at least between two floods of the distance field while the solid octants keep changing. */
    public static final int DISTANCE_FRAMES = 10;
    /** The distance field's reach in blocks: {@code FX_WORLD_FAR}. */
    public static final int DISTANCE_REACH = 8;

    private static final int SLOTS = SECTIONS_X * SECTIONS_Y * SECTIONS_Z;
    private static final byte UNKNOWN = 0, FILLED = 1, UNLOADED = 2;
    private static final int UNLOADED_RETRY = 20;
    private static final int FILL_ZONE = CgTrace.name("vfx.world.fill");
    private static final int SECTIONS_FILLED = CgTrace.name("vfx.world.sections-filled");
    private static final int GPU_DISTANCE = CgGpuTrace.name("vfx.world.distance");
    private static final String DISTANCE_KERNELS = "crystalgraphics:shaders/env/compute/vfx/world_distance.compute";
    /** The flood's steps, in blocks: from the field's reach down, then 1 again (JFA+1). */
    private static final int[] JUMPS = {8, 4, 2, 1, 1};
    /** A slot whose solid octants are not known: no section hashes to it. */
    private static final long UNHASHED = Long.MIN_VALUE;
    private static final CgVfxVoxelWindow INSTANCE = new CgVfxVoxelWindow();

    /** What a kernel reading the window declares in its {@code Properties}, all of which {@link #bind} sets. */
    public static final String PROPERTIES = """
                _World         ("The voxel window: a block a texel",    sampler3D) = "black"
                _WorldSections ("Which of its sections are filled",     sampler3D) = "black"
                _WorldBaseX    ("Its lowest block, absolute: x",        int)       = 0
                _WorldBaseY    ("y",                                    int)       = 0
                _WorldBaseZ    ("z",                                    int)       = 0
                _WorldWrapX    ("That block's texel: x",                int)       = 0
                _WorldWrapY    ("y",                                    int)       = 0
                _WorldWrapZ    ("z",                                    int)       = 0
                _WorldLive     ("1 with a level, else 0: read nothing", int)       = 0
                _WorldDistance ("Distance to solid over FX_WORLD_FAR",  sampler3D) = "white"
            """;

    private final CgGraphTexture volume = CgGraphTexture.requested("vfx.world",
            CgTextureDesc.volume(WIDTH, HEIGHT, DEPTH, CgFrameBufferFormat.builder("vfx.world").color(0, CgTextureType.RGBA8).build()));
    private final CgGraphTexture sections = CgGraphTexture.requested("vfx.world.sections",
            CgTextureDesc.volume(SECTIONS_X, SECTIONS_Y, SECTIONS_Z,
                    CgFrameBufferFormat.builder("vfx.world.sections").color(0, CgTextureType.R8).build()));
    /** What {@link #bind} gives a kernel with no level: the smallest volumes, never read. */
    private final CgGraphTexture noVolume = CgGraphTexture.requested("vfx.world.none",
            CgTextureDesc.volume(1, 1, 2, CgFrameBufferFormat.builder("vfx.world").color(0, CgTextureType.RGBA8).build()));
    private final CgGraphTexture noSections = CgGraphTexture.requested("vfx.world.sections.none",
            CgTextureDesc.volume(1, 1, 2, CgFrameBufferFormat.builder("vfx.world.sections").color(0, CgTextureType.R8).build()));
    /** The distance field and the one before it: a flood writes the other, keeping what it did not flood. */
    private final CgGraphTexture[] distances = {distanceVolume("vfx.world.distance"), distanceVolume("vfx.world.distance.next")};
    private final CgFrameBufferFormat seedFormat = CgFrameBufferFormat.builder("vfx.world.seeds").color(0, CgTextureType.R32UI).build();
    /** Per slot: a hash of its section's solid octants, so a refill that changes none floods nothing. */
    private final long[] solids = new long[SLOTS];
    /** The sections whose solid octants changed since the last flood, absolute, from and to, inclusive. */
    private final int[] changedLo = new int[3], changedHi = new int[3];
    /** A flood's blocks from the base: the domain it floods, from and its size, and those Settle writes anew. */
    private final int[] domainLo = new int[3], domainSize = new int[3], outLo = new int[3], outHi = new int[3];
    private int current;
    private boolean distanceDirty, floodWhole = true;
    private long distanceAt = Long.MIN_VALUE / 2, distanceUsedAt = Long.MIN_VALUE / 2;

    /** Per slot: the absolute section it holds (x, y, z), its state, and the frame it was filled or last tried. */
    private final int[] heldX = new int[SLOTS], heldY = new int[SLOTS], heldZ = new int[SLOTS];
    private final byte[] state = new byte[SLOTS];
    private final boolean[] stale = new boolean[SLOTS];
    private final long[] filledAt = new long[SLOTS];
    private final ByteBuffer section = ByteBuffer.allocateDirect(CgVfxVoxels.SECTION_BYTES).order(ByteOrder.nativeOrder());
    private final ByteBuffer known = ByteBuffer.allocateDirect(SLOTS).order(ByteOrder.nativeOrder());
    private final float[] boxes = new float[CgVfxVoxels.BOXES * 6];
    private int[] broken = new int[48];
    private int brokenCount;
    private int baseX, baseY, baseZ, epoch;
    private boolean live, knownChanged = true;
    private long frame, usedAt = Long.MIN_VALUE / 2;
    private CgRenderStage.Registration registration;

    private CgVfxVoxelWindow() {
        Arrays.fill(heldY, Integer.MIN_VALUE);
        Arrays.fill(solids, UNHASHED);
        clearChanged();
        CgWorldEvents.listen(new CgWorldEvents.Listener() {
            @Override
            public void blockBroken(int x, int y, int z, int surface, int color) {
                if (brokenCount * 3 == broken.length) broken = Arrays.copyOf(broken, broken.length * 2);
                broken[brokenCount * 3] = x;
                broken[brokenCount * 3 + 1] = y;
                broken[brokenCount * 3 + 2] = z;
                brokenCount++;
            }
        });
    }

    public static CgVfxVoxelWindow get() {
        return INSTANCE;
    }

    /** Keeps it filling: call it each frame something reads the window. The first call registers its recording. */
    public CgVfxVoxelWindow use() {
        usedAt = frame;
        if (registration == null) {
            registration = CgRenderStage.WORLD_OPAQUE.registerOncePerFrame(ORDER,
                    stage -> record(stage.recording(), stage.host().view()));
            CgCompute kernels = CgCompute.load(DISTANCE_KERNELS);
            kernels.kernel("Seed").prepare();
            kernels.kernel("Jump").prepare();
            kernels.kernel("Settle").prepare();
        }
        return this;
    }

    /**
     * Keeps the distance field flooding: call it, besides {@link #use}, each frame something reads {@code _WorldDistance}.
     * Unused, the field floods nothing; used again after {@link #IDLE_FRAMES}, it floods the whole window first.
     */
    public CgVfxVoxelWindow useDistance() {
        if (frame - distanceUsedAt > IDLE_FRAMES) floodWhole = distanceDirty = true;
        distanceUsedAt = frame;
        return use();
    }

    /** The volume and where it sits, on a dispatch of a kernel declaring {@link #PROPERTIES}. */
    public CgDispatch bind(CgDispatch d) {
        if (!live) {
            return d.texture("_World", noVolume).texture("_WorldSections", noSections)
                    .texture("_WorldDistance", noSections).set("_WorldLive", 0);
        }
        return place(d).texture("_WorldDistance", distances[current]).set("_WorldLive", 1);
    }

    /** The window's volumes and where it sits, without its distance field: what the flood itself reads. */
    private CgDispatch place(CgDispatch d) {
        return d.texture("_World", volume).texture("_WorldSections", sections)
                .set("_WorldBaseX", baseX).set("_WorldBaseY", baseY).set("_WorldBaseZ", baseZ)
                .set("_WorldWrapX", Math.floorMod(baseX, WIDTH)).set("_WorldWrapY", Math.floorMod(baseY, HEIGHT))
                .set("_WorldWrapZ", Math.floorMod(baseZ, DEPTH));
    }

    /** Whether the host has a level and the window has been placed in it. */
    public boolean live() {
        return live;
    }

    /** The window's lowest block on each axis (0 x, 1 y, 2 z), absolute: a multiple of {@link CgVfxVoxels#SECTION}. */
    public int base(int axis) {
        return axis == 0 ? baseX : axis == 1 ? baseY : baseZ;
    }

    public CgGraphTexture volume() {
        return volume;
    }

    public CgGraphTexture sections() {
        return sections;
    }

    /** Each block's distance to the nearest solid octant over 8 blocks (R8), addressed as {@link #volume()} is. */
    public CgGraphTexture distance() {
        return distances[current];
    }

    /**
     * Places the window around {@code view}'s camera and fills sections into {@code recording} for up to
     * {@link #BUDGET_NANOS}. Registered by {@link #use}; a check scene calls it where the stage does not fire.
     */
    public void record(CgRecording recording, CgHostView view) {
        frame++;
        if (frame - usedAt > IDLE_FRAMES) return;
        CgWorldQuery world = CgPlatform.get(CgWorldQuery.SERVICE);
        int now = world.levelEpoch();
        if (now != epoch) {
            epoch = now;
            Arrays.fill(state, UNKNOWN);
            Arrays.fill(heldY, Integer.MIN_VALUE);
            Arrays.fill(solids, UNHASHED);
            knownChanged = floodWhole = true;
        }
        live = now != 0;
        if (live) {
            place(view);
            takeBroken();
            fill(recording, world, view);
        }
        if (knownChanged) {
            for (int s = 0; s < SLOTS; s++) known.put(s, state[s] == FILLED ? (byte) 255 : 0);
            known.position(0).limit(SLOTS);
            recording.update(sections, 0, 0, 0, 0, SECTIONS_X, SECTIONS_Y, SECTIONS_Z, known);
            knownChanged = false;
        }
        if (live && distanceDirty && frame - distanceUsedAt <= IDLE_FRAMES && frame - distanceAt >= DISTANCE_FRAMES) {
            flood(recording);
        }
    }

    /**
     * The distance field, flooded again over the blocks within {@link #DISTANCE_REACH} of the changed sections (the
     * whole window when asked to): Seed and the jumps over those blocks and as far again, then Settle over the window,
     * keeping the rest of the field (world_distance.compute).
     */
    private void flood(CgRecording recording) {
        distanceDirty = false;
        distanceAt = frame;
        int[] size = {WIDTH, HEIGHT, DEPTH}, base = {baseX, baseY, baseZ};
        int s = CgVfxVoxels.SECTION, r = DISTANCE_REACH;
        for (int a = 0; a < 3; a++) {
            outLo[a] = floodWhole ? 0 : Math.max(0, Math.min(size[a], changedLo[a] * s - base[a] - r));
            outHi[a] = floodWhole ? size[a] : Math.max(0, Math.min(size[a], (changedHi[a] + 1) * s - base[a] + r));
        }
        floodWhole = false;
        clearChanged();
        if (outLo[0] >= outHi[0] || outLo[1] >= outHi[1] || outLo[2] >= outHi[2]) return;
        for (int a = 0; a < 3; a++) {
            int lo = Math.max(0, outLo[a] - r), hi = Math.min(size[a], outHi[a] + r);
            // Whole sections, so a flood's seed volumes come in few sizes.
            domainSize[a] = Math.min(size[a], (hi - lo + s - 1) / s * s);
            domainLo[a] = Math.max(0, Math.min(lo, size[a] - domainSize[a]));
        }
        CgTextureDesc seeds = CgTextureDesc.volume(domainSize[0], domainSize[1], domainSize[2], seedFormat);
        CgCompute kernels = CgCompute.load(DISTANCE_KERNELS);
        CgKernel jump = kernels.kernel("Jump");
        CgGraphTexture from = CgGraphTexture.transientTexture("vfx.world.seeds", seeds);
        CgGraphTexture to = CgGraphTexture.transientTexture("vfx.world.seeds.next", seeds);
        CgComputePass pass = recording.compute("vfx.world.distance").timed(GPU_DISTANCE).async();
        flooding(pass.dispatch(kernels.kernel("Seed"), domainSize[0], domainSize[1], domainSize[2])).image("SEEDS", from);
        for (int step : JUMPS) {
            flooding(pass.dispatch(jump, domainSize[0], domainSize[1], domainSize[2])).image("FROM", from).image("SEEDS", to)
                    .set("_Step", step);
            CgGraphTexture swap = from;
            from = to;
            to = swap;
        }
        flooding(pass.dispatch(kernels.kernel("Settle"), WIDTH, HEIGHT, DEPTH)).image("FROM", from)
                .image("PREVIOUS", distances[current]).image("DISTANCE", distances[1 - current]);
        pass.end();
        current = 1 - current;
    }

    /** A flood's dispatch: the window, the domain and what Settle writes anew. */
    private CgDispatch flooding(CgDispatch d) {
        return place(d).set("_Domain", domainLo[0], domainLo[1], domainLo[2], 0f)
                .set("_OutLo", outLo[0], outLo[1], outLo[2], 0f).set("_OutHi", outHi[0], outHi[1], outHi[2], 0f);
    }

    /** Section {@code slot}'s solid octants changed: the blocks around it flood again. */
    private void changed(int slot) {
        distanceDirty = true;
        changedLo[0] = Math.min(changedLo[0], heldX[slot]);
        changedLo[1] = Math.min(changedLo[1], heldY[slot]);
        changedLo[2] = Math.min(changedLo[2], heldZ[slot]);
        changedHi[0] = Math.max(changedHi[0], heldX[slot]);
        changedHi[1] = Math.max(changedHi[1], heldY[slot]);
        changedHi[2] = Math.max(changedHi[2], heldZ[slot]);
    }

    private void clearChanged() {
        Arrays.fill(changedLo, Integer.MAX_VALUE);
        Arrays.fill(changedHi, Integer.MIN_VALUE);
    }

    private static CgGraphTexture distanceVolume(String name) {
        return CgGraphTexture.requested(name, CgTextureDesc.volume(WIDTH, HEIGHT, DEPTH,
                CgFrameBufferFormat.builder(name).color(0, CgTextureType.R8).build()));
    }

    /** Centres the window on the camera's section, and gives each slot the section it now stands for. */
    private void place(CgHostView view) {
        int s = CgVfxVoxels.SECTION;
        baseX = (Math.floorDiv((int) Math.floor(view.x()), s) - SECTIONS_X / 2) * s;
        baseY = (Math.floorDiv((int) Math.floor(view.y()), s) - SECTIONS_Y / 2) * s;
        baseZ = (Math.floorDiv((int) Math.floor(view.z()), s) - SECTIONS_Z / 2) * s;
        int fx = baseX / s, fy = baseY / s, fz = baseZ / s;
        for (int z = 0; z < SECTIONS_Z; z++) {
            for (int y = 0; y < SECTIONS_Y; y++) {
                for (int x = 0; x < SECTIONS_X; x++) {
                    int slot = x + SECTIONS_X * (y + SECTIONS_Y * z);
                    int ax = fx + Math.floorMod(x - fx, SECTIONS_X), ay = fy + Math.floorMod(y - fy, SECTIONS_Y);
                    int az = fz + Math.floorMod(z - fz, SECTIONS_Z);
                    if (heldX[slot] == ax && heldY[slot] == ay && heldZ[slot] == az) continue;
                    heldX[slot] = ax;
                    heldY[slot] = ay;
                    heldZ[slot] = az;
                    if (state[slot] == FILLED) knownChanged = true;
                    state[slot] = UNKNOWN;
                    stale[slot] = false;
                    solids[slot] = UNHASHED;
                }
            }
        }
    }

    private void takeBroken() {
        int s = CgVfxVoxels.SECTION;
        for (int i = 0; i < brokenCount; i++) {
            int slot = slotOf(Math.floorDiv(broken[i * 3], s), Math.floorDiv(broken[i * 3 + 1], s),
                    Math.floorDiv(broken[i * 3 + 2], s));
            if (slot >= 0) stale[slot] = true;
        }
        brokenCount = 0;
    }

    /** The slot holding absolute section {@code (x, y, z)}, or -1 outside the window. */
    private int slotOf(int x, int y, int z) {
        int slot = Math.floorMod(x, SECTIONS_X) + SECTIONS_X * (Math.floorMod(y, SECTIONS_Y) + SECTIONS_Y * Math.floorMod(z, SECTIONS_Z));
        return heldX[slot] == x && heldY[slot] == y && heldZ[slot] == z ? slot : -1;
    }

    private void fill(CgRecording recording, CgWorldQuery world, CgHostView view) {
        long start = System.nanoTime();
        int filled = 0;
        try (CgTrace.Zone zone = CgTrace.zone(CgVfxTrace.CHANNEL, FILL_ZONE)) {
            while (filled == 0 || System.nanoTime() - start < BUDGET_NANOS) {
                int slot = next(view);
                if (slot < 0) break;
                int s = CgVfxVoxels.SECTION;
                section.clear();
                filledAt[slot] = frame;
                stale[slot] = false;
                if (!CgVfxVoxels.pack(world, heldX[slot] * s, heldY[slot] * s, heldZ[slot] * s, section, boxes)) {
                    if (state[slot] == FILLED) {
                        knownChanged = true;
                        changed(slot);
                    }
                    state[slot] = UNLOADED;
                    solids[slot] = UNHASHED;
                    continue;
                }
                long hash = solidHash(section);
                if (hash != solids[slot]) {
                    solids[slot] = hash;
                    changed(slot);
                }
                section.position(0).limit(CgVfxVoxels.SECTION_BYTES);
                recording.update(volume, 0, Math.floorMod(heldX[slot] * s, WIDTH), Math.floorMod(heldY[slot] * s, HEIGHT),
                        Math.floorMod(heldZ[slot] * s, DEPTH), s, s, s, section);
                if (state[slot] != FILLED) knownChanged = true;
                state[slot] = FILLED;
                filled++;
            }
        }
        if (filled > 0) CgVfxTrace.count(SECTIONS_FILLED, filled);
    }

    /** A hash of a packed section's solid octants: byte 0 of each block. Never {@link #UNHASHED}. */
    private static long solidHash(ByteBuffer section) {
        int h = 1;
        for (int at = 0; at < CgVfxVoxels.SECTION_BYTES; at += 4) h = h * 31 + section.get(at);
        return h;
    }

    /**
     * The slot to fill next: the nearest to the camera not known yet (an unloaded one once it has waited), else the
     * nearest with a broken block, else the oldest past {@link #REFRESH_FRAMES}; -1 for none.
     */
    private int next(CgHostView view) {
        int best = -1, bestRank = Integer.MAX_VALUE;
        double s = CgVfxVoxels.SECTION;
        for (int slot = 0; slot < SLOTS; slot++) {
            int rank;
            if (state[slot] == UNKNOWN || state[slot] == UNLOADED && frame - filledAt[slot] >= UNLOADED_RETRY) {
                rank = 0;
            } else if (stale[slot]) {
                rank = 1;
            } else if (state[slot] == FILLED && frame - filledAt[slot] >= REFRESH_FRAMES) {
                rank = 2;
            } else {
                continue;
            }
            double dx = (heldX[slot] + 0.5) * s - view.x(), dy = (heldY[slot] + 0.5) * s - view.y(), dz = (heldZ[slot] + 0.5) * s - view.z();
            // Rank first, then distance in whole blocks: nearer sections of one rank first; the oldest refresh first.
            long key = rank == 2 ? Math.max(0L, filledAt[slot]) : (long) Math.sqrt(dx * dx + dy * dy + dz * dz);
            int ranked = rank * (Integer.MAX_VALUE / 4) + (int) Math.min(key, Integer.MAX_VALUE / 4 - 1);
            if (ranked < bestRank) {
                bestRank = ranked;
                best = slot;
            }
        }
        return best;
    }
}
