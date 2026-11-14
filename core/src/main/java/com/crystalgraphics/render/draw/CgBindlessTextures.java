package com.crystalgraphics.render.draw;

import com.crystalgraphics.api.texture.CgSampler;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.gpu.resource.CgFallbackTextures;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.resource.CgGpuSampler;
import com.crystalgraphics.platform.rhi.resource.CgTextureView;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Each texture's index in the device's bindless heap, as a shader reads it from a block: an engine texture's
 * reference, which keeps its index while its storage moves (Unreal's {@code FRHITextureReference}), or a frame graph
 * texture's pooled storage's own index, which may change every frame (the render graph's {@code UBMT_RDG_TEXTURE}).
 *
 * <pre>{@code
 * int index = CgBindlessTextures.index(albedo, null);   // as a block naming it is placed
 * CgBindlessTextures.index(albedo, null);               // each frame it is drawn: its reference follows its storage
 * CgBindlessTextures.forget(albedo);                         // deleted: its references free once their frames retire
 * }</pre>
 *
 * <ul>
 *   <li>An engine texture's index never changes while it lives, so a block holding it is written once.</li>
 *   <li>One with no storage yet reads white until it has some, under the same index.</li>
 *   <li>Render thread.</li>
 * </ul>
 */
public final class CgBindlessTextures {

    /** Each engine texture's references, one per device sampler it is read with. */
    private static final Map<CgTexture, Reference[]> REFERENCES = new IdentityHashMap<>();
    /** The device its references are on; forgotten with it as it closes. */
    private static CgRHI device;

    private CgBindlessTextures() {}

    private static final class Reference {
        final CgGpuSampler sampler;
        final int slot;
        CgTextureView view;

        Reference(CgGpuSampler sampler, int slot, CgTextureView view) {
            this.sampler = sampler;
            this.slot = slot;
            this.view = view;
        }
    }

    /**
     * {@code texture}'s index read through {@code sampler} (null for its own sampling): its reference, made or pointed at
     * its storage now, for an engine texture; its storage's own for a graph texture.
     */
    public static int index(CgTexture texture, @Nullable CgSampler sampler) {
        if (device == null) {
            device = CgRHI.get();
            device.onClose(CgBindlessTextures::closed);
        }
        CgGpuSampler gpu = CgDeviceSamplers.of(device, sampler, texture);
        CgTextureView view = texture.rhiView();
        if (view == null) view = CgFallbackTextures.WHITE_1x1.rhiView();
        if (!texture.keepsStorage()) return device.bindlessIndex(view, gpu);
        Reference[] held = REFERENCES.get(texture);
        if (held != null) {
            for (Reference r : held) {
                if (r.sampler != gpu) continue;
                if (r.view != view) {
                    device.updateBindlessReference(r.slot, view, gpu);
                    r.view = view;
                }
                return r.slot;
            }
        }
        Reference made = new Reference(gpu, device.bindlessReference(view, gpu), view);
        if (held == null) {
            REFERENCES.put(texture, new Reference[] {made});
        } else {
            Reference[] more = Arrays.copyOf(held, held.length + 1);
            more[held.length] = made;
            REFERENCES.put(texture, more);
        }
        return made.slot;
    }

    /** {@code texture}'s references given back, once their frames retire: it is deleted. Render thread. */
    public static void forget(CgTexture texture) {
        Reference[] held = REFERENCES.remove(texture);
        if (held == null || device == null) return;
        for (Reference r : held) device.releaseBindlessReference(r.slot);
    }

    /** Its device closed: every reference went with it. */
    private static void closed() {
        REFERENCES.clear();
        device = null;
    }
}
