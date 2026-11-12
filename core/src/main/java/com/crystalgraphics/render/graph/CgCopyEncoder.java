package com.crystalgraphics.render.graph;

import com.crystalgraphics.gpu.resource.CgReadback;
import com.crystalgraphics.gpu.resource.CgStreamBuffer;
import com.crystalgraphics.platform.rhi.CgDeviceNames;
import com.crystalgraphics.platform.rhi.CgDeviceSection;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.command.CgCommandList;
import com.crystalgraphics.platform.rhi.resource.CgGpuBuffer;
import com.crystalgraphics.platform.rhi.resource.CgGpuSampler;
import com.crystalgraphics.platform.rhi.resource.CgGpuTexture;
import com.crystalgraphics.platform.rhi.resource.CgTextureRegion;
import com.crystalgraphics.platform.rhi.resource.CgTextureView;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.CgBufferUtils;
import com.crystalgraphics.util.trace.CgChannels;

import javax.annotation.Nullable;

/**
 * One execution's copy, upload, buffer and readback steps on the device's command list, each inside a device section;
 * their transitions are the step's prologue. Unreal's copy passes, encoded on {@code FRHICommandList}.
 */
final class CgCopyEncoder {

    private static final int FILL = CgTrace.name("graph.fill"), UPDATE_STAGE = CgTrace.name("graph.update.stage"),
            UPDATE_COPY = CgTrace.name("graph.update.copy");

    private final CgExecutor execution;
    private final CgGraphDevice device;
    /** Where an update's bytes wait for the GPU's copy: graph storage takes no glBufferSubData. */
    @Nullable
    private CgStreamBuffer staging;

    CgCopyEncoder(CgExecutor execution, CgGraphDevice device) {
        this.execution = execution;
        this.device = device;
    }

    /** A fill, an update or a buffer copy: storage made and the update staged first, so nothing is made in the section. */
    void buffer(CgPass pass) {
        int dst = 0;
        CgGpuBuffer src = null;
        long at = 0;
        if (pass instanceof CgPass.BufferFill fill) {
            dst = device.bufferStorage(fill.buffer, true);
        } else if (pass instanceof CgPass.BufferUpdate update) {
            dst = device.bufferStorage(update.buffer, true);
            at = stage(update);
            src = staging.gpuBuffer();
        } else if (pass instanceof CgPass.BufferCopy copy) {
            int from = device.bufferStorage(copy.from, false);
            src = CgDeviceNames.buffer(from);
            dst = device.bufferStorage(copy.to, true);
        }
        CgDeviceSection.begin();
        try {
            buffer(pass, CgDeviceNames.buffer(dst), src, at);
        } finally {
            CgDeviceSection.end();
        }
    }

    /** Into {@code dst}, from {@code src} at {@code srcBase} (an update's staging ring, a copy's source) where it copies. */
    private static void buffer(CgPass pass, CgGpuBuffer dst, @Nullable CgGpuBuffer src, long srcBase) {
        CgCommandList list = CgRHI.get().encoder();
        if (pass instanceof CgPass.BufferFill fill) {
            try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL_DETAIL, FILL)) {
                list.fillBuffer(dst, fill.offset, fill.size, fill.value);
            }
            written(fill.buffer);
        } else if (pass instanceof CgPass.BufferUpdate update) {
            try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL_DETAIL, UPDATE_COPY)) {
                list.copyBuffer(src, srcBase, dst, update.offset, update.size);
            }
            written(update.buffer);
        } else if (pass instanceof CgPass.BufferCopy copy) {
            list.copyBuffer(src, srcBase + copy.fromOffset, dst, copy.toOffset, copy.size);
            written(copy.to);
        }
    }

    /** {@code update}'s bytes written into the staging ring; answers where. */
    private int stage(CgPass.BufferUpdate update) {
        int bytes = update.size;
        // 1 MB a region: a move's pools update about that in a frame, and each doubling is new storage (T10).
        if (staging == null) staging = CgStreamBuffer.create(CgStreamBuffer.Use.STAGING, Math.max(bytes, 1 << 20));
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL_DETAIL, UPDATE_STAGE)) {
            if (update.floats != null) {
                staging.mapFloats(bytes).put(update.floats, update.from, bytes / Float.BYTES);
            } else if (update.ints != null) {
                staging.mapInts(bytes).put(update.ints, update.from, bytes / Integer.BYTES);
            } else {
                staging.write(update.bytes, 0, bytes);
            }
            return staging.commit(bytes);
        }
    }

    /** A history written by a whole-buffer operation: the version written is its newest. */
    private static void written(CgGraphBuffer buffer) {
        if (buffer.kind() == CgGraphBuffer.Kind.HISTORY) buffer.advance();
    }

    /** {@code copy}'s blit between two storages. */
    void texture(CgPass.TextureCopy copy) {
        CgGraphStorage from = device.storage(copy.from), target = device.storage(copy.target);
        CgGpuTexture source = from.color(0);
        if (source == null) throw new IllegalStateException("copy " + copy.from.name() + " has no colour to read");
        execution.transitions.blitting(from, target);
        CgDeviceSection.begin();
        try {
            CgRHI.get().encoder().blit(CgTextureView.attachment(source, from.colorLevel(), from.colorLayer()), copy.x,
                    copy.y, copy.x + copy.w, copy.y + copy.h, CgTextureView.attachment(target.color(0), 0, 0), copy.tx,
                    copy.ty, copy.tx + copy.tw, copy.ty + copy.th,
                    copy.linear ? CgGpuSampler.Filter.LINEAR : CgGpuSampler.Filter.NEAREST);
        } finally {
            CgDeviceSection.end();
        }
        execution.transitions.blitted(from, target);
    }

    /** {@code upload}'s bytes written into its texture's colour 0. */
    void upload(CgPass.TextureUpload upload) {
        CgGraphStorage target = device.storage(upload.target);
        CgGpuTexture image = target.color(0);
        if (image == null) throw new IllegalStateException(upload.target + " has no colour texture to update");
        if (device.texels == null || device.texels.capacity() < upload.bytes.length) {
            device.texels = CgBufferUtils.createByteBuffer(Math.max(upload.bytes.length, 4096));
        }
        device.texels.clear();
        device.texels.put(upload.bytes).flip();
        boolean volume = target.isVolume();
        CgDeviceSection.begin();
        try {
            CgRHI.get().encoder().writeTexture(image, new CgTextureRegion(upload.level, upload.x, upload.y,
                    volume ? upload.z : 0, upload.w, upload.h, volume ? upload.d : 1), device.texels);
        } finally {
            CgDeviceSection.end();
        }
    }

    /** {@code r}'s copy; its sink hears from {@link CgReadback#poll} once the GPU has finished. */
    void readback(CgPass.Readback r) {
        r.delivering = true;
        if (r.buffer != null) {
            CgReadback.buffer(device.bufferStorage(r.buffer, false), r.offset, r.size, r);
            return;
        }
        CgGraphStorage storage = device.storage(r.texture);
        boolean slices = storage.isVolume() || storage.isArray();
        CgReadback.texture(storage.color(0), new CgTextureRegion(r.level, r.x, r.y, slices ? r.z : 0,
                r.w, r.h, slices ? r.d : 1), r);
    }

    void delete() {
        if (staging != null) staging.delete();
        staging = null;
    }
}
