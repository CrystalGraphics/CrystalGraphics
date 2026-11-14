package com.crystalgraphics.render.draw;

import com.crystalgraphics.api.texture.CgSampler;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.gpu.resource.CgFallbackTextures;
import com.crystalgraphics.gpu.resource.CgStreamBuffer;
import com.crystalgraphics.platform.rhi.binding.CgBindingLayout;
import com.crystalgraphics.platform.rhi.binding.CgBindings;
import com.crystalgraphics.render.shader.CgBindingModel;

import javax.annotation.Nullable;
import java.nio.FloatBuffer;
import java.util.Arrays;

/**
 * Uniform blocks whose bindless texture indices are known only as their pass executes: a frame graph texture has
 * storage only inside the passes that use it. Each is written then, with that moment's indices, into a ring of its own
 * and bound as the pass's parameter, as Unreal creates a render-graph uniform buffer at its pass's execution
 * ({@code FRDGUniformBuffer}). One per encoder; render thread.
 *
 * <pre>{@code
 * if (executed.versions(table, id, parameters)) ...                        // a draw's per-frame material version
 * executed.entry(table, id, offsets, k);                                     // a dispatch's values and samplers
 * }</pre>
 *
 * <ul>
 *   <li>Only on the bindless path: bound, the textures are in the sets and nothing here is written.</li>
 *   <li>Inside the pass that reads the textures, where their storage is resolved.</li>
 * </ul>
 */
public final class CgExecutedBlocks {

    private final CgStreamBuffer ring = CgStreamBuffer.frameLocalBlocks(1 << 16);
    private float[] scratch = new float[64];

    /**
     * Entry {@code id}'s per-frame block versions, each written now with its textures' indices and put into {@code out}
     * at its slot. False where it holds none.
     */
    public boolean versions(CgFrameBindings table, int id, CgBindings out) {
        boolean any = false;
        for (int k = 0; k < table.blocks(id); k++) {
            CgBlockVersion v = table.blockVersion(id, k);
            if (v == null || !v.perFrame) continue;
            int floats = v.floats.length, bytes = Math.max(floats * 4, v.storageBytes);
            FloatBuffer into = ring.mapFloats(bytes);
            int at = into.position();
            into.put(v.floats);
            CgBlockVersion.Members m = v.members;
            for (int i = 0; i < m.count(); i++) {
                if (m.textures[i] != null) into.put(at + m.offsets[i], Float.intBitsToFloat(CgBindlessTextures.index(m.textures[i], m.samplers[i])));
            }
            int offset = ring.commit(bytes);
            int binding = CgBindingModel.bindingOf(table.blockBinding(id, k));
            if (v.storageBytes > 0) {   // an arena material's: one slot, read as slot 0
                out.buffer(binding, CgBindingLayout.Type.STORAGE_BUFFER, ring.gpuBuffer(), offset, v.storageBytes);
            } else {
                out.buffer(binding, CgBindingLayout.Type.UNIFORM_BUFFER, ring.gpuBuffer(), offset, std140(floats));
            }
            any = true;
        }
        return any;
    }

    /**
     * Entry {@code id}'s material block, as the frame copied it, with material texture {@code i}'s index written at float
     * {@code offsets[i]}, put into {@code out} at the block's slot: a dispatch's values and samplers. Nothing where it
     * holds no material block.
     */
    public void entry(CgFrameBindings table, int id, int[] offsets, CgBindings out) {
        int material = CgBindingModel.MATERIAL_BLOCK.packed();
        for (int k = 0; k < table.blocks(id); k++) {
            if (table.blockBinding(id, k) != material || table.blockVersion(id, k) != null) continue;
            int floats = table.blockFloatCount(id, k);
            if (scratch.length < floats) scratch = new float[Math.max(floats, scratch.length * 2)];
            table.copyBlock(id, k, scratch);
            for (int t = 0; t < table.textures(id); t++) {
                int i = CgBindingModel.bindingOf(table.textureUnit(id, t)) - CgBindingModel.FIRST_TEXTURE;
                if (CgBindingModel.setOf(table.textureUnit(id, t)) != CgBindingModel.MATERIAL || i < 0 || i >= offsets.length) continue;
                scratch[offsets[i]] = Float.intBitsToFloat(CgBindlessTextures.index(table.texture(id, t), table.sampler(id, t)));
            }
            int offset = ring.uploadFloats(scratch, floats);
            out.buffer(CgBindingModel.MATERIAL_BLOCK.binding(), CgBindingLayout.Type.UNIFORM_BUFFER, ring.gpuBuffer(), offset,
                    std140(floats));
            return;
        }
    }

    /**
     * {@code values[0, floats)} with texture {@code i}'s index written at float {@code offsets[i]}, put into {@code out}
     * at {@code binding}: a dispatch's values, or the view's indices. A null texture reads white.
     */
    public void values(@Nullable float[] values, int floats, int[] offsets, CgTexture[] textures,
                       @Nullable CgSampler[] samplers, int binding, CgBindings out) {
        if (scratch.length < floats) scratch = new float[Math.max(floats, scratch.length * 2)];
        if (values != null) System.arraycopy(values, 0, scratch, 0, floats);
        else Arrays.fill(scratch, 0, floats, 0f);
        for (int i = 0; i < offsets.length && i < textures.length; i++) {
            CgTexture t = textures[i] != null ? textures[i] : CgFallbackTextures.WHITE_1x1;
            scratch[offsets[i]] = Float.intBitsToFloat(CgBindlessTextures.index(t, samplers == null ? null : samplers[i]));
        }
        int offset = ring.uploadFloats(scratch, floats);
        out.buffer(binding, CgBindingLayout.Type.UNIFORM_BUFFER, ring.gpuBuffer(), offset, std140(floats));
    }

    /** A block of {@code floats} as std140 binds it: whole vec4s, at least one. */
    private static long std140(int floats) {
        return Math.max(16, (floats * 4L + 15) & ~15L);
    }
}
