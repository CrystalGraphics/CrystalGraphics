package com.crystalgraphics.mc.v1710.platform.gl;

import com.crystalgraphics.lwjgl2.Lwjgl2GLBackend;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL20;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.nio.charset.StandardCharsets;

/**
 * The 1.7.10 GL backend: {@link Lwjgl2GLBackend}, with the six calls Angelica cannot take routed
 * through ones it can.
 *
 * <p>Angelica rewrites every GL call site in every class to its own {@code GLStateManager}, by name
 * and keeping the descriptor, and that class declares only some overloads. Each call here would be a
 * {@code NoSuchMethodError} the first time it ran:</p>
 *
 * <ul>
 *   <li>the FloatBuffer and ShortBuffer texture uploads, which take the ByteBuffer overload instead —
 *       {@code type} already names the element, so the bytes mean the same thing;</li>
 *   <li>{@code glGetActiveUniform}'s four-argument form, which takes the six-argument one;</li>
 *   <li>{@code glReadPixels} into a pack buffer, which Angelica has no form of, so it is reached
 *       through a method handle its rewrite cannot see. A read changes no GL state, so Angelica's
 *       tracking misses nothing.</li>
 * </ul>
 *
 * <p>Always used on 1.7.10, Angelica or not: the cost is a copy on a float upload.</p>
 */
public final class GLBackend1710 extends Lwjgl2GLBackend {

    // GL thread only, like every call in a backend.
    private static ByteBuffer scratch = BufferUtils.createByteBuffer(4096);

    private static final MethodHandle READ_PIXELS_TO_PACK_BUFFER = readPixelsToPackBuffer();

    @Override
    public void glTexImage3D(int target, int level, int internalFormat,
                              int width, int height, int depth, int border,
                              int format, int type, FloatBuffer pixels) {
        GL12.glTexImage3D(target, level, internalFormat, width, height, depth, border, format, type, asBytes(pixels));
    }

    @Override
    public void glTexSubImage2D(int target, int level,
                                 int xOffset, int yOffset, int width, int height,
                                 int format, int type, FloatBuffer pixels) {
        GL11.glTexSubImage2D(target, level, xOffset, yOffset, width, height, format, type, asBytes(pixels));
    }

    @Override
    public void glTexSubImage3D(int target, int level,
                                 int xOffset, int yOffset, int zOffset,
                                 int width, int height, int depth,
                                 int format, int type, FloatBuffer pixels) {
        GL12.glTexSubImage3D(target, level, xOffset, yOffset, zOffset,
                width, height, depth, format, type, asBytes(pixels));
    }

    @Override
    public void glTexSubImage3D(int target, int level,
                                 int xOffset, int yOffset, int zOffset,
                                 int width, int height, int depth,
                                 int format, int type, ShortBuffer pixels) {
        GL12.glTexSubImage3D(target, level, xOffset, yOffset, zOffset,
                width, height, depth, format, type, asBytes(pixels));
    }

    @Override
    public String glGetActiveUniform(int program, int index, int maxLength, IntBuffer sizeTypeBuf) {
        IntBuffer length = BufferUtils.createIntBuffer(1);
        IntBuffer size = BufferUtils.createIntBuffer(1);
        IntBuffer type = BufferUtils.createIntBuffer(1);
        ByteBuffer name = BufferUtils.createByteBuffer(maxLength);
        GL20.glGetActiveUniform(program, index, length, size, type, name);
        sizeTypeBuf.put(sizeTypeBuf.position(), size.get(0));
        sizeTypeBuf.put(sizeTypeBuf.position() + 1, type.get(0));
        byte[] chars = new byte[length.get(0)];
        name.get(chars);
        return new String(chars, StandardCharsets.UTF_8);
    }

    @Override
    public void glReadPixels(int x, int y, int width, int height, int format, int type, long packOffset) {
        try {
            READ_PIXELS_TO_PACK_BUFFER.invokeExact(x, y, width, height, format, type, packOffset);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    private static ByteBuffer asBytes(FloatBuffer pixels) {
        if (pixels == null) return null;
        ByteBuffer bytes = scratch(pixels.remaining() * 4);
        bytes.asFloatBuffer().put(pixels.duplicate());
        return bytes;
    }

    private static ByteBuffer asBytes(ShortBuffer pixels) {
        if (pixels == null) return null;
        ByteBuffer bytes = scratch(pixels.remaining() * 2);
        bytes.asShortBuffer().put(pixels.duplicate());
        return bytes;
    }

    private static ByteBuffer scratch(int size) {
        if (scratch.capacity() < size) scratch = BufferUtils.createByteBuffer(Math.max(size, scratch.capacity() * 2));
        Buffer view = scratch;   // Buffer's methods: ByteBuffer's covariant ones are Java 9+
        view.clear();
        view.limit(size);
        return scratch;
    }

    private static MethodHandle readPixelsToPackBuffer() {
        try {
            return MethodHandles.publicLookup().findStatic(GL11.class, "glReadPixels", MethodType.methodType(
                    void.class, int.class, int.class, int.class, int.class, int.class, int.class, long.class));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
