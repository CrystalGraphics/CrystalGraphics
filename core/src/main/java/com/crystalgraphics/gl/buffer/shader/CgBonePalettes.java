package com.crystalgraphics.gl.buffer.shader;

import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.buffer.CgBufferFormat;
import com.crystalgraphics.gl.buffer.CgFrameRing;
import com.crystalgraphics.render.graph.CgBufferDesc;
import com.crystalgraphics.render.graph.CgBufferUsage;
import com.crystalgraphics.render.graph.CgGraphBuffer;
import com.crystalgraphics.render.graph.CgRecording;
import org.joml.Matrix4fc;

import java.util.Arrays;

import static com.crystalgraphics.api.buffer.CgBufferFormat.MemoryLayout.STD430;

/**
 * Every skinned draw's bone matrices for a frame: one buffer of vec4s, three a bone, the rows of the 3×4 transform
 * that takes a vertex from the mesh's bind pose to the bone's pose now (Unreal's {@code FBoneMatrix}, stored transposed
 * for packing as Unreal stores it). A skinned program reads the palette starting where its draw's object record says.
 *
 * <pre>{@code
 * // each frame, per skinned draw: pose(bone) * inverseBind(bone) for every bone, then the draw names where it starts
 * int base = CgBonePalettes.write(skinning, skeleton.boneCount());
 * world.draw(kurama.mesh(), material).bones(base).at(x, y, z).submit();
 * }</pre>
 *
 * <ul>
 *   <li>A base is valid for the ring frame it was written in: the frame's first write drops the last frame's, and the
 *       world renderer records the upload once, in the frame's first world stage.</li>
 *   <li>Render thread, or whichever thread runs the client world's schedule, before the world records.</li>
 *   <li>Allocates nothing once its array has grown to a frame's bones.</li>
 * </ul>
 */
public final class CgBonePalettes {

    /** The {@code attach()} macro the skinned factory reads bones through. */
    public static final String MACRO_NAME = "BONES_DATA";

    /** The buffer's block (or sampler) name in a skinned program: a constant, so wiring by it loads nothing. */
    public static final String BLOCK_NAME = "CgBonePalettes";

    public static final CgBufferFormat FORMAT = CgBufferFormat.builder("CgBoneRow", STD430).vec4("row").build();

    /** Floats one bone takes: three rows of four. */
    public static final int BONE_FLOATS = 12;

    private static float[] data = new float[BONE_FLOATS * 256];
    private static int bones;
    private static long frame = -1;
    /** The frame's palettes on the GPU, grown to the most bones a frame has written; null before the first. */
    private static CgGraphBuffer palettes;
    private static int capacity;
    /** The program's declaration, and what the binding holds between skinned draws: one identity bone. */
    private static CgShaderBuffer standIn;

    private CgBonePalettes() {
    }

    /**
     * The declaration a skinned program is compiled against, written once with one identity bone: what the executor
     * binds back after a skinned draw read the frame's palettes in its place.
     */
    public static synchronized CgShaderBuffer buffer() {
        if (standIn == null) {
            standIn = CgShaderBufferRegistry.get().getOrCreateInternal(BLOCK_NAME, FORMAT, CgBindingPoints.BONE_PALETTES);
            standIn.beginWrite(3);
            standIn.writer().beginRecord().vec4("row", 1f, 0f, 0f, 0f);
            standIn.endRecord();
            standIn.writer().beginRecord().vec4("row", 0f, 1f, 0f, 0f);
            standIn.endRecord();
            standIn.writer().beginRecord().vec4("row", 0f, 0f, 1f, 0f);
            standIn.endRecord();
            standIn.endWrite();
        }
        return standIn;
    }

    /**
     * Appends {@code count} bones' skinning matrices, {@code pose × inverseBind} each, for this frame.
     *
     * @return the first bone's index in the frame's palettes: what the draw's {@code bones(base)} names
     */
    public static int write(Matrix4fc[] skinning, int count) {
        startFrame();
        int base = bones;
        int need = (bones + count) * BONE_FLOATS;
        if (need > data.length) data = Arrays.copyOf(data, Math.max(need, data.length * 2));
        for (int b = 0; b < count; b++) put(skinning[b], (base + b) * BONE_FLOATS);
        bones += count;
        return base;
    }

    /** The bones written this frame. */
    public static int written() {
        startFrame();
        return bones;
    }

    /** Drops the last ring frame's bones at this frame's first touch, as the world renderer drops its draws. */
    private static void startFrame() {
        long now = CgFrameRing.frame();
        if (now != frame) {
            frame = now;
            bones = 0;
        }
    }

    /**
     * Records this frame's palettes into {@code recording}, grown first where the frame wrote more bones than any
     * before, and answers the buffer its skinned draws read. The array is read when the recording executes.
     */
    public static CgGraphBuffer record(CgRecording recording) {
        startFrame();
        if (palettes == null || bones > capacity) {
            if (palettes != null) recording.release(palettes);
            capacity = Math.max(256, Integer.highestOneBit(Math.max(bones, 1) - 1) << 1);
            palettes = CgGraphBuffer.persistent("cg.bonePalettes",
                    CgBufferDesc.of((long) capacity * BONE_FLOATS * 4, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
        }
        if (bones > 0) recording.update(palettes, 0, data, 0, bones * BONE_FLOATS);
        return palettes;
    }

    /** A matrix's top three rows: JOML holds it by column, {@code mCR}. */
    private static void put(Matrix4fc m, int at) {
        data[at] = m.m00();
        data[at + 1] = m.m10();
        data[at + 2] = m.m20();
        data[at + 3] = m.m30();
        data[at + 4] = m.m01();
        data[at + 5] = m.m11();
        data[at + 6] = m.m21();
        data[at + 7] = m.m31();
        data[at + 8] = m.m02();
        data[at + 9] = m.m12();
        data[at + 10] = m.m22();
        data[at + 11] = m.m32();
    }
}
