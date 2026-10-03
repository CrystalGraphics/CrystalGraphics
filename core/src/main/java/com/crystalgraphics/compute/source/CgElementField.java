package com.crystalgraphics.compute.source;

/**
 * One field of a buffer's element, where std430 puts it: a struct's member, or the whole element when it is a scalar,
 * vector or matrix (its name then empty). What a tier below compute packs and unpacks, and what a CPU body addresses.
 *
 * <pre>{@code
 * struct Particle { vec4 positionLife; vec4 velocitySeed; };
 * CgElementField v = buffer.field("velocitySeed");   // type vec4, offset 16, size 16: words 4 to 7
 * }</pre>
 *
 * @param type   the GLSL type of one entry, without array dimensions
 * @param offset bytes from the element's start
 * @param size   bytes it covers, every array entry included
 */
public record CgElementField(String name, String type, int offset, int size) {

    /** The first 4-byte word it covers. */
    public int word() {
        return offset / 4;
    }

    public int words() {
        return size / 4;
    }
}
