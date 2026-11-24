package com.crystalgraphics.render.host;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.gpu.resource.CgTexture2D;
import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.platform.rhi.CgDeviceSection;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.command.CgCommandList;
import com.crystalgraphics.platform.rhi.resource.CgGpuTexture;
import com.crystalgraphics.platform.rhi.resource.CgTextureRegion;
import com.crystalgraphics.render.shader.CgBindless;
import com.crystalgraphics.trace.CgGpuTrace;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;

/**
 * What the engine samples of a texture a host owns and draws into (a picture's page, a pocket level's view): on GL with
 * bindless, a texture of the engine's own the host's is copied into after each draw, since a handle freezes a texture
 * and Minecraft remakes and reconfigures its own ({@code docs/TEXTURES.md}); elsewhere the host's texture itself.
 *
 * <pre>{@code
 * CgTexture sampled = CgHostCopies.sampled(page);   // what an image names; the host's own where nothing is copied
 * host.drawPage(...);                               // the host draws into its texture
 * CgHostCopies.copy(page, x, y, w, h);              // that rect, in texels from the bottom left
 * CgHostCopies.flush();                             // after the host's draws: every copy in one device section
 * CgHostCopies.forget(page);                        // with the host's texture: its copy released
 * }</pre>
 *
 * <ul>
 *   <li>A copy is a GPU copy of the rect a host drew, no readback; a texture of the engine's own (the harness's stand-in
 *       levels) is sampled as it is.</li>
 *   <li>Copies land at {@link #flush}, before anything samples them; it opens its own device section, nesting in one
 *       already open.</li>
 *   <li>Render thread, but {@link #sampled}: any thread.</li>
 * </ul>
 */
public final class CgHostCopies {

    /** Each host texture's copy. Guarded by itself. */
    private static final Map<CgTexture, CgTexture2D> COPIES = new IdentityHashMap<>();
    private static final List<Pending> PENDING = new ArrayList<>();

    private CgHostCopies() {
    }

    /** Whether host textures are copied: where a bindless handle would freeze them (GL). */
    public static boolean active() {
        return CgBindless.hostTexturesBound();
    }

    /** What to sample of {@code host}: its copy, made at first ask, or {@code host} itself where nothing is copied. */
    public static CgTexture sampled(CgTexture host) {
        if (!copies(host)) return host;
        synchronized (COPIES) {
            CgTexture2D copy = COPIES.get(host);
            if (copy == null) {
                CgGpuTexture storage = host.rhiView().texture();
                COPIES.put(host, copy = CgTexture2D.create("host copy " + storage.desc().label(),
                        CgTextureDesc.of(storage.desc().width(), storage.desc().height(), storage.desc().format())));
            }
            return copy;
        }
    }

    /** As {@link #sampled}, for a 2D texture's callers. */
    public static CgTexture2D sampled(CgTexture2D host) {
        return (CgTexture2D) sampled((CgTexture) host);
    }

    /** {@code host}'s rect the host just drew, texels from the bottom left, copied at the next {@link #flush}. */
    public static void copy(CgTexture host, int x, int y, int width, int height) {
        if (width <= 0 || height <= 0 || !copies(host)) return;
        PENDING.add(new Pending(host, (CgTexture2D) sampled(host), x, y, width, height));
    }

    /** All of {@code host}, as {@link #copy}. */
    public static void copyWhole(CgTexture host) {
        if (!copies(host)) return;
        CgGpuTexture.Desc d = host.rhiView().texture().desc();
        copy(host, 0, 0, d.width(), d.height());
    }

    /** Every copy asked for since the last, in one device section. Render thread. */
    public static void flush() {
        if (PENDING.isEmpty()) return;
        long zone = CgTrace.begin(CgChannels.PICTURES, "hostCopies.flush");
        CgDeviceSection.begin();
        CgGpuTrace.begin("hostCopies");
        try {
            CgCommandList encoder = CgRHI.get().encoder();
            for (int i = 0; i < PENDING.size(); i++) {
                Pending p = PENDING.get(i);
                p.copy.getId();   // its storage, made where it is still queued
                CgGpuTexture from = p.host.rhiView().texture(), to = p.copy.rhiTexture();
                if (to == null) continue;
                CgTextureRegion region = CgTextureRegion.of2D(0, p.x, p.y, p.width, p.height);
                encoder.copyTexture(from, region, to, region);
            }
            CgTrace.add(CgChannels.PICTURES, "host-copies", PENDING.size());
        } finally {
            PENDING.clear();
            CgGpuTrace.end();
            CgDeviceSection.end();
            CgTrace.end(zone);
        }
    }

    /** {@code host} is let go: its copy released once the frames reading it retire. Render thread. */
    public static void forget(CgTexture host) {
        CgTexture2D copy;
        synchronized (COPIES) {
            copy = COPIES.remove(host);
        }
        if (copy == null) return;
        PENDING.removeIf(p -> p.host == host);
        copy.release();
    }

    // A host's texture where copies are made: one the engine borrowed, never its own
    private static boolean copies(CgTexture host) {
        return host instanceof CgTexture2D t && t.borrowed() && active() && host.rhiView() != null;
    }

    private record Pending(CgTexture host, CgTexture2D copy, int x, int y, int width, int height) {
    }
}
