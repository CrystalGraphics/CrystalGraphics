package com.crystalgraphics.api.buffer;

/**
 * Buffers of your own, kept across frames. For data rewritten every frame, upload it into the frame instead
 * ({@code rec.upload}).
 *
 * <pre>{@code
 * static final CgBufferLayout HEIGHT = CgBufferLayout.std430("Height").float_("height").build();
 * CgBuffer heights = CgBuffers.create("mymod.heights", HEIGHT, cells);
 * heights.write(0, cells, records -> { for (float h : field) records.next().float_("height", h); });
 * }</pre>
 */
public final class CgBuffers {

    private CgBuffers() {}

    /**
     * {@code count} records of {@code layout}, zeroed until written: its maker's to {@link CgBuffer#release}. Any thread.
     *
     * @param name what the device and the logs call it
     */
    public static CgBuffer create(String name, CgBufferLayout layout, int count) {
        return new CgBuffer(name, layout, count);
    }
}
