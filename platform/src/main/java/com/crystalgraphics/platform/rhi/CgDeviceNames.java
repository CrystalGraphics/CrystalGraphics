package com.crystalgraphics.platform.rhi;

import com.crystalgraphics.platform.rhi.resource.CgGpuBuffer;
import com.crystalgraphics.platform.rhi.resource.CgGpuTexture;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The ints the engine knows its device textures and buffers by: a texture's {@code getId()}, a graph buffer's, what a
 * binding table or a readback names. Minted the first time a resource is asked for, resolved back to it, and forgotten
 * when it is released. The same on every device.
 *
 * <pre>{@code
 * int id = CgDeviceNames.name(gpuTexture);          // the same int each ask
 * CgGpuTexture t = CgDeviceNames.texture(id);       // and back
 * CgDeviceNames.forget(gpuTexture);                 // before rhi.release(gpuTexture)
 * }</pre>
 *
 * <ul>
 *   <li>Names start at {@value #FIRST}, past any GL name, and are never reused while the process runs.</li>
 *   <li>Textures and buffers are named apart: a texture and a buffer may share an int.</li>
 *   <li>Render thread only.</li>
 * </ul>
 */
public final class CgDeviceNames {

    public static final int FIRST = 0x40000000;

    private static final Map<CgGpuTexture, Integer> TEXTURE_NAMES = new IdentityHashMap<>();
    private static final Map<CgGpuBuffer, Integer> BUFFER_NAMES = new IdentityHashMap<>();
    private static final List<CgGpuTexture> TEXTURES = new ArrayList<>();
    private static final List<CgGpuBuffer> BUFFERS = new ArrayList<>();

    private CgDeviceNames() {
    }

    /** {@code texture}'s name, the same each ask. */
    public static int name(CgGpuTexture texture) {
        Integer known = TEXTURE_NAMES.get(texture);
        if (known != null) return known;
        int name = FIRST + TEXTURES.size();
        TEXTURES.add(texture);
        TEXTURE_NAMES.put(texture, name);
        return name;
    }

    /** {@code buffer}'s name, the same each ask. */
    public static int name(CgGpuBuffer buffer) {
        Integer known = BUFFER_NAMES.get(buffer);
        if (known != null) return known;
        int name = FIRST + BUFFERS.size();
        BUFFERS.add(buffer);
        BUFFER_NAMES.put(buffer, name);
        return name;
    }

    /** The texture named {@code name}; throws for a name never given or forgotten. */
    public static CgGpuTexture texture(int name) {
        CgGpuTexture t = name >= FIRST && name - FIRST < TEXTURES.size() ? TEXTURES.get(name - FIRST) : null;
        if (t == null) throw new IllegalArgumentException("no texture is named " + name);
        return t;
    }

    /** The buffer named {@code name}; throws for a name never given or forgotten. */
    public static CgGpuBuffer buffer(int name) {
        CgGpuBuffer b = name >= FIRST && name - FIRST < BUFFERS.size() ? BUFFERS.get(name - FIRST) : null;
        if (b == null) throw new IllegalArgumentException("no buffer is named " + name);
        return b;
    }

    /** {@code texture} is being released: its name answers nothing from now on. */
    public static void forget(CgGpuTexture texture) {
        Integer name = TEXTURE_NAMES.remove(texture);
        if (name != null) TEXTURES.set(name - FIRST, null);
    }

    /** {@code buffer} is being released: its name answers nothing from now on. */
    public static void forget(CgGpuBuffer buffer) {
        Integer name = BUFFER_NAMES.remove(buffer);
        if (name != null) BUFFERS.set(name - FIRST, null);
    }

    /** Every name forgotten: the context is going, and its resources with it. The next names are new ones. */
    public static void releaseAll() {
        TEXTURE_NAMES.clear();
        BUFFER_NAMES.clear();
        TEXTURES.replaceAll(t -> null);
        BUFFERS.replaceAll(b -> null);
    }
}
