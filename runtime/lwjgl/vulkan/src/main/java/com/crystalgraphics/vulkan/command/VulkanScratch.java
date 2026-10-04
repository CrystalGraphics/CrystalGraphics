package com.crystalgraphics.vulkan.command;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Native memory a recording thread fills Vulkan structs in, for the calls made per dispatch, draw or barrier: written
 * through a {@link ByteBuffer} and handed to LWJGL's raw {@code n} functions. LWJGL's struct setters on Java 25 write
 * through FFM, and each write C2 does not inline into its caller builds a {@code MemorySegment}.
 *
 * <pre>{@code
 * VulkanScratch s = VulkanScratch.get(VkMemoryBarrier.SIZEOF);
 * s.bytes.putInt(VkMemoryBarrier.STYPE, VK_STRUCTURE_TYPE_MEMORY_BARRIER).putLong(VkMemoryBarrier.PNEXT, 0L)
 *         .putInt(VkMemoryBarrier.SRCACCESSMASK, src).putInt(VkMemoryBarrier.DSTACCESSMASK, dst);
 * nvkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, 1, s.address, 0, 0L, 0, 0L);
 * }</pre>
 *
 * <ul>
 *   <li>Valid until the thread's next {@link #get}: fill it and make the call, which copies what it reads.</li>
 *   <li>Write every field the call reads, {@code pNext} included: the block holds whatever the last struct left.</li>
 * </ul>
 */
final class VulkanScratch {

    private static final ThreadLocal<VulkanScratch> OF = ThreadLocal.withInitial(() -> new VulkanScratch(16 << 10));

    final ByteBuffer bytes;
    final long address;

    private VulkanScratch(int size) {
        bytes = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
        address = MemoryUtil.memAddress(bytes);
    }

    /** This thread's block, at least {@code size} bytes. */
    static VulkanScratch get(int size) {
        VulkanScratch s = OF.get();
        if (s.bytes.capacity() < size) {
            s = new VulkanScratch(Integer.highestOneBit(size) << 1);
            OF.set(s);
        }
        return s;
    }

    /** Its first {@code size} bytes zeroed, as {@code calloc} would leave them: for structs with fields left unset. */
    ByteBuffer zero(int size) {
        int i = 0;
        for (; i + 8 <= size; i += 8) bytes.putLong(i, 0L);
        for (; i < size; i++) bytes.put(i, (byte) 0);
        return bytes;
    }
}
