package com.crystalgraphics.util;

import java.nio.*;

/**
 * Pure-Java direct buffer allocation. Drop-in replacement for {@code org.lwjgl.BufferUtils}.
 * Zero LWJGL dependency.
 */
public final class CgBufferUtils {

    private CgBufferUtils() {}

	/**
	 * Construct a direct native-ordered bytebuffer with the specified size.
	 * @param size The size, in bytes
	 * @return a ByteBuffer
	 */
	public static ByteBuffer createByteBuffer(int size) {
		return ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
	}

	/**
	 * Construct a direct native-order shortbuffer with the specified number
	 * of elements.
	 * @param size The size, in shorts
	 * @return a ShortBuffer
	 */
	public static ShortBuffer createShortBuffer(int size) {
		return createByteBuffer(size << 1).asShortBuffer();
	}

	/**
	 * Construct a direct native-order charbuffer with the specified number
	 * of elements.
	 * @param size The size, in chars
	 * @return an CharBuffer
	 */
	public static CharBuffer createCharBuffer(int size) {
		return createByteBuffer(size << 1).asCharBuffer();
	}

	/**
	 * Construct a direct native-order intbuffer with the specified number
	 * of elements.
	 * @param size The size, in ints
	 * @return an IntBuffer
	 */
	public static IntBuffer createIntBuffer(int size) {
		return createByteBuffer(size << 2).asIntBuffer();
	}

	/**
	 * Construct a direct native-order longbuffer with the specified number
	 * of elements.
	 * @param size The size, in longs
	 * @return an LongBuffer
	 */
	public static LongBuffer createLongBuffer(int size) {
		return createByteBuffer(size << 3).asLongBuffer();
	}

	/**
	 * Construct a direct native-order floatbuffer with the specified number
	 * of elements.
	 * @param size The size, in floats
	 * @return a FloatBuffer
	 */
	public static FloatBuffer createFloatBuffer(int size) {
		return createByteBuffer(size << 2).asFloatBuffer();
	}

	/**
	 * Construct a direct native-order doublebuffer with the specified number
	 * of elements.
	 * @param size The size, in floats
	 * @return a FloatBuffer
	 */
	public static DoubleBuffer createDoubleBuffer(int size) {
		return createByteBuffer(size << 3).asDoubleBuffer();
	}

}
