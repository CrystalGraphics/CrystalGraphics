package com.crystalgraphics.platform.device.pipeline;

import com.crystalgraphics.platform.device.command.CgRenderPass;
import com.crystalgraphics.platform.device.format.CgFormat;
import com.crystalgraphics.platform.device.resource.CgGpuBuffer;
import com.crystalgraphics.platform.device.resource.CgGpuSampler;
import com.crystalgraphics.platform.device.resource.CgTextureView;

import java.util.Arrays;

/**
 * The resources one draw reads, pushed with {@link CgRenderPass#pushBindings}. Reused: fill it, push it, clear it
 * for the next draw; the device copies what it needs, so it allocates nothing per draw.
 *
 * <pre>{@code
 * bindings.clear()
 *         .buffer(0, CgBindingLayout.Type.UNIFORM_BUFFER, frameBlock, 0, 256)
 *         .texture(2, atlasView, linearSampler);
 * pass.pushBindings(bindings);
 * }</pre>
 */
public final class CgBindings {

    private int count;
    private int[] binding = new int[8];
    private CgBindingLayout.Type[] type = new CgBindingLayout.Type[8];
    private CgGpuBuffer[] buffer = new CgGpuBuffer[8];
    private long[] offset = new long[8];
    private long[] size = new long[8];
    private CgFormat[] texelFormat = new CgFormat[8];
    private CgTextureView[] view = new CgTextureView[8];
    private CgGpuSampler[] sampler = new CgGpuSampler[8];

    public CgBindings clear() {
        Arrays.fill(buffer, 0, count, null);
        Arrays.fill(view, 0, count, null);
        Arrays.fill(sampler, 0, count, null);
        count = 0;
        return this;
    }

    /** A uniform or storage buffer range. */
    public CgBindings buffer(int slot, CgBindingLayout.Type kind, CgGpuBuffer buf, long off, long bytes) {
        int i = next(slot, kind);
        buffer[i] = buf;
        offset[i] = off;
        size[i] = bytes;
        return this;
    }

    /** A buffer range read through {@code samplerBuffer} as texels of {@code format}. */
    public CgBindings texel(int slot, CgGpuBuffer buf, long off, long bytes, CgFormat format) {
        int i = next(slot, CgBindingLayout.Type.TEXEL_BUFFER);
        buffer[i] = buf;
        offset[i] = off;
        size[i] = bytes;
        texelFormat[i] = format;
        return this;
    }

    public CgBindings texture(int slot, CgTextureView tex, CgGpuSampler smp) {
        int i = next(slot, CgBindingLayout.Type.SAMPLED_TEXTURE);
        view[i] = tex;
        sampler[i] = smp;
        return this;
    }

    /** One mip level of a texture a kernel reads and writes with {@code imageLoad} and {@code imageStore}. */
    public CgBindings image(int slot, CgTextureView level) {
        int i = next(slot, CgBindingLayout.Type.STORAGE_IMAGE);
        view[i] = level;
        return this;
    }

    public int count() { return count; }

    public int binding(int i) { return binding[i]; }

    public CgBindingLayout.Type type(int i) { return type[i]; }

    public CgGpuBuffer buffer(int i) { return buffer[i]; }

    public long offset(int i) { return offset[i]; }

    public long size(int i) { return size[i]; }

    public CgFormat texelFormat(int i) { return texelFormat[i]; }

    public CgTextureView view(int i) { return view[i]; }

    public CgGpuSampler sampler(int i) { return sampler[i]; }

    /** The index holding {@code slot}, or -1. */
    public int indexOf(int slot) {
        for (int i = 0; i < count; i++) {
            if (binding[i] == slot) return i;
        }
        return -1;
    }

    private int next(int slot, CgBindingLayout.Type kind) {
        if (count == binding.length) grow();
        binding[count] = slot;
        type[count] = kind;
        return count++;
    }

    private void grow() {
        int n = binding.length * 2;
        binding = Arrays.copyOf(binding, n);
        type = Arrays.copyOf(type, n);
        buffer = Arrays.copyOf(buffer, n);
        offset = Arrays.copyOf(offset, n);
        size = Arrays.copyOf(size, n);
        texelFormat = Arrays.copyOf(texelFormat, n);
        view = Arrays.copyOf(view, n);
        sampler = Arrays.copyOf(sampler, n);
    }
}
