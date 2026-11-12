package com.crystalgraphics.render.graph;

import com.crystalgraphics.platform.rhi.command.CgAccess;
import com.crystalgraphics.platform.rhi.resource.CgGpuTexture;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;

import javax.annotation.Nullable;
import java.util.Arrays;

/**
 * One execution's transitions, issued from each storage's tracked state ({@link CgResourceStates}) on the device's
 * command list: a step's compiled prologue, the splits begun after a step and ended before their reader, a blit's moves,
 * and every image moved back to rest at the execution's end. Unreal's {@code FRDGBarrierBatch} replayed per pass.
 */
final class CgTransitions {

    private static final int SPLITS = CgTrace.name("graph.split-transitions");
    private static final int BARRIER_COUNT = CgTrace.name("graph.barriers");
    private static final int COMPUTE = CgAccess.COMPUTE_READ | CgAccess.COMPUTE_WRITE;

    private final CgExecutor execution;
    private final CgGraphDevice device;
    /**
     * Images it moved: key, storage or texture, levels, layers and whether it outlives the execution, each. Those that do
     * return to rest at its end; all of them before a callback's body, which finds every image at rest.
     */
    private long[] movedKeys = new long[16];
    private int[] movedLevels = new int[16], movedLayers = new int[16];
    /** Each moved storage, where it moved whole; else null. */
    private CgGraphStorage[] movedStorages = new CgGraphStorage[16];
    /** Each moved texture, where it moved by subresource; else null. */
    private CgGpuTexture[] movedImages = new CgGpuTexture[16];
    private boolean[] movedOutlives = new boolean[16];
    private int moved;
    /** Split transitions begun after a step and not yet ended: each one's id, and the step it ends before. */
    private int[] splitIds = new int[8], splitEnds = new int[8];
    private int splitCount;
    /** Where a blit's source storage was before it ({@link #blitting}), returned there after. */
    private int blitSourceWas;

    CgTransitions(CgExecutor execution, CgGraphDevice device) {
        this.execution = execution;
        this.device = device;
    }

    /** At an execution's start. */
    void begin() {
        moved = 0;
        splitCount = 0;
    }

    /** At its end: every split ended, and what outlives it back at rest. */
    void finish() {
        if (splitCount > 0) endSplits(Integer.MAX_VALUE);
        restMoved(false);
    }

    boolean splitsOpen() {
        return splitCount > 0;
    }

    /**
     * Step {@code s}'s compiled prologue, one batch: each buffer's barrier, and each texture moved to its access. A
     * raster step's attachments move to {@link CgAccess#COLOR_WRITE}, its target's other levels and layers to what it
     * reads of them. Storage that does not exist yet is left alone.
     */
    void prologue(CgFrame frame, int s) {
        if (!device.kernelsSeen && !execution.takes || !frame.prologued[s]) return;   // a run's first step stated its
        begin(device);   // one glMemoryBarrier for the step on GL, one transition on a device
        try {
            for (int e = frame.prologueFrom[s]; e < frame.prologueFrom[s + 1]; e++) {
                int bits = frame.prologueBits[e];
                if (frame.prologueView[e] instanceof CgGraphBuffer buffer) {
                    buffer(device.bufferStorage(buffer, (bits & CgResourceStates.WRITES) != 0), bits);
                } else {
                    move((CgGraphTexture) frame.prologueView[e], bits, frame.prologueLevel[e], frame.prologueLayer[e]);
                }
            }
        } finally {
            end(device);
        }
    }

    private static void begin(CgGraphDevice device) {
        device.transitions.begin();
    }

    private static void end(CgGraphDevice device) {
        device.transitions.end(device.compute.isOpen());
    }

    /** The barrier an access of {@code bits} to buffer {@code id} needs, issued. */
    void buffer(int id, int bits) {
        buffer(id, bits, 0);
    }

    /** {@link #buffer(int, int)} through overlap set {@code set} (0 for none). */
    void buffer(int id, int bits, int set) {
        int from = device.states.access(CgResourceStates.buffer(id), bits, set);
        if (!issues(from, bits)) return;
        leaveDeviceCompute();
        device.transitions.buffer(id, from, bits);
        CgTrace.add(CgChannels.GL, BARRIER_COUNT, 1);
    }

    /** Whether a transition from {@code from} to {@code to} is issued: every one on a device, a kernel's on GL. */
    private boolean issues(int from, int to) {
        return from != 0 && execution.computeBarriers && (execution.takes || ((from | to) & COMPUTE) != 0);
    }

    /**
     * Before a barrier while a pass dispatches on the device: a batch opens, recorded inside the compute pass when the
     * next dispatch or the pass's end closes it.
     */
    private void leaveDeviceCompute() {
        if (!device.computing || device.barrierBatch) return;
        begin(device);
        device.barrierBatch = true;
    }

    /** Closes the batch {@link #leaveDeviceCompute} opened. */
    void endDeviceBarriers() {
        if (!device.barrierBatch) return;
        device.barrierBatch = false;
        end(device);
    }

    /**
     * Moves graph texture {@code texture} to {@code to}, stating what must move: whole, or where {@code attachedLevel}
     * is not -1, its subresource at that level and {@code attachedLayer} to {@link CgAccess#COLOR_WRITE} and the rest
     * to {@code to} (none where 0). A single-level, single-layer texture moves as one storage, depth with colour; any
     * other by each colour texture's subresources.
     */
    void move(CgGraphTexture texture, int to, int attachedLevel, int attachedLayer) {
        boolean outlives = texture.kind() != CgGraphTexture.Kind.TRANSIENT;
        // A requested texture's or a history's storage is made at its first use: this statement, before its bind.
        CgGraphStorage storage = outlives ? device.storage(texture) : texture.storage();
        if (storage == null) return;
        int levels = storage.levels(), layers = storage.layers();
        if (levels == 1 && layers == 1 && storage.depthSlices() <= 1) {
            moveStorage(storage, attachedLevel >= 0 ? CgAccess.COLOR_WRITE : to, outlives);
            return;
        }
        for (int slot = 0; slot < storage.colorSlots(); slot++) {
            CgGpuTexture color = storage.color(slot);
            if (color == null) continue;
            long key = CgGraphStorage.key(color);
            int[] subs = device.states.image(key, levels * layers);
            noteMoved(key, null, color, levels, layers, outlives);
            for (int level = 0; level < levels; level++) {
                int layer = 0;
                while (layer < layers) {
                    int want = level == attachedLevel && layer == attachedLayer ? CgAccess.COLOR_WRITE : to;
                    int from = subs[level * layers + layer], end = layer + 1;
                    while (end < layers && subs[level * layers + end] == from && (level != attachedLevel
                            || (end != attachedLayer && layer != attachedLayer))) end++;
                    if (want != 0) {
                        boolean moves = CgResourceStates.moves(from, want);
                        if (moves && issues(from == 0 ? CgAccess.RESTING : from, want)) {
                            leaveDeviceCompute();
                            device.transitions.texture(color, level, 1, layer, end - layer,
                                    from == 0 ? CgAccess.RESTING : from, want);
                            CgTrace.add(CgChannels.GL, BARRIER_COUNT, 1);
                        }
                        for (int l = layer; l < end; l++) subs[level * layers + l] = moves ? want : from | want;
                    }
                    layer = end;
                }
            }
        }
    }

    /** Moves {@code storage}'s textures to {@code to}; answers where they were, 0 at rest. */
    private int moveStorage(CgGraphStorage storage, int to, boolean outlives) {
        long key = storage.key();
        int[] subs = device.states.image(key, 1);
        int from = subs[0];
        noteMoved(key, storage, null, 0, 0, outlives);
        boolean moves = CgResourceStates.moves(from, to);
        if (moves && issues(from == 0 ? CgAccess.RESTING : from, to)) {
            leaveDeviceCompute();
            device.transitions.storage(storage, from == 0 ? CgAccess.RESTING : from, to);
            CgTrace.add(CgChannels.GL, BARRIER_COUNT, 1);
        }
        subs[0] = moves ? to : from | to;
        return from;
    }

    /** {@code key} was moved, and with {@code outlives} returns to rest at the execution's end; {@code levels} 0 for a storage. */
    private void noteMoved(long key, @Nullable CgGraphStorage storage, @Nullable CgGpuTexture image, int levels, int layers,
                           boolean outlives) {
        for (int m = 0; m < moved; m++) {
            if (movedKeys[m] != key) continue;
            movedOutlives[m] |= outlives;
            return;
        }
        if (moved == movedKeys.length) {
            movedKeys = Arrays.copyOf(movedKeys, moved * 2);
            movedStorages = Arrays.copyOf(movedStorages, moved * 2);
            movedImages = Arrays.copyOf(movedImages, moved * 2);
            movedLevels = Arrays.copyOf(movedLevels, moved * 2);
            movedLayers = Arrays.copyOf(movedLayers, moved * 2);
            movedOutlives = Arrays.copyOf(movedOutlives, moved * 2);
        }
        movedOutlives[moved] = outlives;
        movedKeys[moved] = key;
        movedStorages[moved] = storage;
        movedImages[moved] = image;
        movedLevels[moved] = levels;
        movedLayers[moved++] = layers;
    }

    /**
     * Every image it moved that outlives the execution, or with {@code all} every one, back where it rests: where the
     * host and GL streams find it. A transient left out keeps its state with its storage.
     */
    private void restMoved(boolean all) {
        if (moved == 0) return;
        begin(device);
        try {
            for (int m = 0; m < moved; m++) {
                if (!all && !movedOutlives[m]) continue;
                int levels = movedLevels[m], layers = movedLayers[m];
                int[] subs = device.states.image(movedKeys[m], Math.max(1, levels * layers));
                if (levels == 0) {
                    if (subs[0] != 0) device.transitions.storage(movedStorages[m], subs[0], CgAccess.RESTING);
                    subs[0] = 0;
                    continue;
                }
                for (int i = 0; i < levels * layers; i++) {
                    if (subs[i] == 0) continue;
                    device.transitions.texture(movedImages[m], i / layers, 1, i % layers, 1, subs[i], CgAccess.RESTING);
                    subs[i] = 0;
                }
            }
        } finally {
            end(device);
        }
        Arrays.fill(movedStorages, 0, moved, null);
        Arrays.fill(movedImages, 0, moved, null);
        moved = 0;
    }

    /**
     * After step {@code s}: each access whose next is a read two or more steps on begins its transition now, ended
     * before that step ({@link #endSplits}), as Unreal RDG splits a transition from its producer's epilogue to its
     * consumer's prologue. Compiled ({@link CgFrameCompiler#transitions}).
     */
    void beginSplits(CgFrame frame, int s) {
        if (!frame.splits[s]) return;
        begin(device);
        int began;
        try {
            for (int e = frame.splitFrom[s]; e < frame.splitFrom[s + 1]; e++) {
                int next = frame.splitAccess[e], bits = frame.accessBits[next];
                if (frame.accessView[next] instanceof CgGraphBuffer buffer) buffer(device.bufferStorage(buffer, false), bits);
                else move((CgGraphTexture) frame.accessView[next], bits, -1, -1);
            }
        } finally {
            began = device.transitions.endSplit(device.compute.isOpen());
        }
        if (began == 0) return;
        if (splitCount == splitIds.length) {
            splitIds = Arrays.copyOf(splitIds, splitCount * 2);
            splitEnds = Arrays.copyOf(splitEnds, splitCount * 2);
        }
        splitIds[splitCount] = began;
        splitEnds[splitCount++] = frame.splitEnd[s];
        CgTrace.add(CgChannels.GL, SPLITS, 1);
    }

    /** Ends every split due by step {@code s}. */
    void endSplits(int s) {
        for (int k = 0; k < splitCount; ) {
            if (splitEnds[k] > s) {
                k++;
                continue;
            }
            device.transitions.split(splitIds[k], device.compute.isOpen());
            splitIds[k] = splitIds[--splitCount];
            splitEnds[k] = splitEnds[splitCount];
        }
    }

    /** Before a blit of {@code source} into {@code dest} inside a pass: the source read, the destination written. */
    void blitting(CgGraphStorage source, CgGraphStorage dest) {
        if (!execution.takes) return;
        begin(device);
        try {
            blitSourceWas = moveStorage(source, CgAccess.COPY_READ, source.external() != null);
            moveStorage(dest, CgAccess.COPY_WRITE, false);
        } finally {
            end(device);
        }
    }

    /** After it: the source where it was, the copy sampled. */
    void blitted(CgGraphStorage source, CgGraphStorage dest) {
        if (!execution.takes) return;
        begin(device);
        try {
            if (blitSourceWas != 0) {
                moveStorage(source, blitSourceWas, false);
            } else {
                // Found at rest: back to rest.
                device.transitions.storage(source, CgAccess.COPY_READ, CgAccess.RESTING);
                device.states.image(source.key(), 1)[0] = 0;
            }
            moveStorage(dest, CgAccess.SAMPLED_READ, false);
        } finally {
            end(device);
        }
    }
}
