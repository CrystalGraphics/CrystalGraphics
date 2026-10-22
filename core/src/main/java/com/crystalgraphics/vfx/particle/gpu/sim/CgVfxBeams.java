package com.crystalgraphics.vfx.particle.gpu.sim;

import java.util.Arrays;

import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.compute.CgKernel;
import com.crystalgraphics.render.graph.CgBufferDesc;
import com.crystalgraphics.render.graph.CgBufferUsage;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.graph.CgGraphBuffer;
import com.crystalgraphics.render.graph.CgRecording;

/**
 * A pool's beams ({@code CgVfxModule.BeamSource} and the rest): a run of vec4s per beam particle of each open slot,
 * {@code env/compute/vfx/beam.compute}'s {@code Noise} writing them after every step from the step's records and the
 * last step's runs. A run is a header (a tag and the record's beam state), then {@link CgVfxShape#beamPoints()} current
 * noise points, then as many next ones. The beams' draws read {@link #runs()}.
 *
 * <ul>
 *   <li>A slot's runs are its own while it is open, cleared when given out, so a newborn never reads another's points.</li>
 *   <li>A run belongs to spawn ids alike modulo the slot's capacity, as a trail's ring does: where the capacity binds and
 *       two beams alive at once share one, the later takes it.</li>
 * </ul>
 */
final class CgVfxBeams {

    private static final String KERNELS = "crystalgraphics:shaders/env/compute/vfx/beam.compute";
    /** vec4s ahead of a run's noise points: {@code fx_beam.glsl}'s {@code FX_BEAM_HEADER}. */
    static final int HEADER = 7;

    private final CgVfxParticlePool pool;
    private final String runsName, lastName, tableName;
    /** vec4s a beam: its header, then its current and next points. */
    private final int per;
    private CgKernel noise, keep;
    private CgGraphBuffer runs, last, table;
    /** Per slot: its first vec4 and its beams (its capacity), 0 for none. */
    private int[] first = new int[0], beams = new int[0];
    private int end, freeCount, clearCount;
    private int[] free = new int[0], clears = new int[0], words = new int[0];

    CgVfxBeams(CgVfxParticlePool pool, String name) {
        this.pool = pool;
        this.per = HEADER + 2 * pool.shape().beamPoints();
        runsName = name + ".beams";
        lastName = name + ".beams.last";
        tableName = name + ".beamRuns";
    }

    /** Compiles its kernels ahead, with the pool's Step. */
    static void prepare() {
        CgCompute file = CgCompute.load(KERNELS);
        file.kernel("Noise").prepare();
        file.kernel("Keep").prepare();
    }

    /** Gives slot {@code slot} its runs: one a beam of {@code capacity}. */
    void open(int slot, int capacity) {
        if (first.length <= slot) {
            first = Arrays.copyOf(first, Math.max(8, slot * 2 + 2));
            beams = Arrays.copyOf(beams, first.length);
        }
        first[slot] = take(capacity * per);
        beams[slot] = capacity;
    }

    /** Takes slot {@code slot}'s runs back. */
    void close(int slot) {
        if (slot >= beams.length || beams[slot] == 0) return;
        give(first[slot], beams[slot] * per);
        beams[slot] = 0;
    }

    /** Before the pool steps in {@code recording}: its buffers grown to every run given out, new runs cleared, its table. */
    void reserve(CgRecording recording, int slots) {
        if (noise == null) {
            CgCompute file = CgCompute.load(KERNELS);
            noise = file.kernel("Noise");
            keep = file.kernel("Keep");
        }
        long bytes = Math.max(256L, end * 16L);
        if (runs == null || runs.size() < bytes) {
            long size = Long.highestOneBit(bytes - 1) << 1;
            CgGraphBuffer grownRuns = buffer(runsName, size), grownLast = buffer(lastName, size);
            recording.fill(grownRuns, 0);
            recording.fill(grownLast, 0);
            if (runs != null) {
                recording.copy(runs, 0, grownRuns, 0, runs.size());
                recording.copy(last, 0, grownLast, 0, last.size());
                recording.release(runs);
                recording.release(last);
            }
            runs = grownRuns;
            last = grownLast;
        }
        for (int i = 0; i < clearCount; i++) {
            recording.fill(runs, clears[i * 2] * 16L, clears[i * 2 + 1] * 16L, 0);
            recording.fill(last, clears[i * 2] * 16L, clears[i * 2 + 1] * 16L, 0);
        }
        clearCount = 0;
        if (words.length < slots * 4) words = new int[Math.max(16, slots * 8)];
        for (int s = 0; s < slots; s++) {
            boolean has = s < beams.length && beams[s] > 0;
            words[s * 4] = has ? first[s] : 0;
            words[s * 4 + 1] = has ? beams[s] : 0;
            words[s * 4 + 2] = per;
            words[s * 4 + 3] = 0;
        }
        long tableBytes = Math.max(256L, slots * 16L);
        if (table == null || table.size() < tableBytes) {
            if (table != null) recording.release(table);
            table = buffer(tableName, Long.highestOneBit(tableBytes - 1) << 1);
        }
        if (slots > 0) recording.update(table, 0, words, 0, slots * 4);
    }

    /** After a step: every live beam's run written from the step's records and the last step's runs, then kept. */
    void step(CgComputePass pass) {
        if (runs == null || end == 0) return;
        CgVfxShape shape = pool.shape();
        CgVfxRecord record = shape.record();
        int elements = pool.storage() * per;
        pass.dispatch(noise, elements).bind("RECORDS", pool.records()).bind("LIVE", pool.live())
                .bind("PARAMS", pool.params()).bind("RUNS", table).bind("LAST", last).bind("NEXT", runs)
                .set("_RecordVectors", record.vectors()).set("_BeamAt", record.word(CgVfxRecord.Tail.BEAM))
                .set("_ParamRow", shape.paramRowVectors()).set("_NoiseAt", shape.beamNoiseAt())
                .set("_Points", shape.beamPoints());
        // Keep copies every run given out: what the next step reads as the last.
        pass.dispatch(keep, end).bind("WRITTEN", runs).bind("KEPT", last);
    }

    /** Every beam's run as the newest step wrote it: what a beam's strip reads. */
    CgGraphBuffer runs() {
        return runs;
    }

    /** Each slot's runs, a uvec4 a slot: its first vec4, its beams, vec4s a beam, 0. */
    CgGraphBuffer table() {
        return table;
    }

    /** The context is gone: its buffers with it, made again at the next reserve; the runs given out stay. */
    void forget() {
        runs = last = table = null;
        noise = keep = null;
        clearCount = 0;
    }

    /** Releases its buffers in {@code recording}. */
    void release(CgRecording recording) {
        if (runs != null) recording.release(runs);
        if (last != null) recording.release(last);
        if (table != null) recording.release(table);
        runs = last = table = null;
    }

    private static CgGraphBuffer buffer(String name, long bytes) {
        return CgGraphBuffer.persistent(name, CgBufferDesc.of(bytes, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
    }

    /** A run of {@code n} vec4s: the first free run it fits, else past the end; cleared at the next reserve. */
    private int take(int n) {
        int start = -1;
        for (int i = 0; i < freeCount && start < 0; i++) {
            int at = free[i * 2], length = free[i * 2 + 1];
            if (length < n) continue;
            start = at;
            if (length == n) {
                System.arraycopy(free, (i + 1) * 2, free, i * 2, (freeCount - i - 1) * 2);
                freeCount--;
            } else {
                free[i * 2] = at + n;
                free[i * 2 + 1] = length - n;
            }
        }
        if (start < 0) {
            start = end;
            end += n;
        }
        if (clears.length < (clearCount + 1) * 2) clears = Arrays.copyOf(clears, Math.max(8, clearCount * 4));
        clears[clearCount * 2] = start;
        clears[clearCount * 2 + 1] = n;
        clearCount++;
        return start;
    }

    /** Gives back a run, joined to the free runs it touches. */
    private void give(int start, int n) {
        int at = 0;
        while (at < freeCount && free[at * 2] < start) at++;
        if (free.length < (freeCount + 1) * 2) free = Arrays.copyOf(free, Math.max(8, freeCount * 4));
        System.arraycopy(free, at * 2, free, (at + 1) * 2, (freeCount - at) * 2);
        free[at * 2] = start;
        free[at * 2 + 1] = n;
        freeCount++;
        for (int i = freeCount - 2; i >= 0; i--) {
            if (free[i * 2] + free[i * 2 + 1] == free[(i + 1) * 2]) {
                free[i * 2 + 1] += free[(i + 1) * 2 + 1];
                System.arraycopy(free, (i + 2) * 2, free, (i + 1) * 2, (freeCount - i - 2) * 2);
                freeCount--;
            }
        }
    }
}
