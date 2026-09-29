package com.crystalgraphics.platform.gl.tracked;

import com.crystalgraphics.platform.gl.CgGL;

import java.nio.ByteBuffer;

/**
 * GL buffer objects and their binding points. The element-array binding is not here: it belongs to the bound
 * vertex array ({@link TrackedVertexArrays}), as in GL.
 */
final class TrackedBuffers {

    static final int GL_MAP_WRITE_BIT = 0x2, GL_MAP_INVALIDATE_BUFFER_BIT = 0x8, GL_MAP_UNSYNCHRONIZED_BIT = 0x20;
    static final int GL_MAP_PERSISTENT_BIT = 0x40;
    private static final int GL_STATIC_READ = 0x88E5, GL_STATIC_COPY = 0x88E6;
    private static final int GL_PIXEL_PACK_BUFFER_BINDING = 0x88ED, GL_PIXEL_UNPACK_BUFFER_BINDING = 0x88EF;
    static final int INDEXED = 64;

    /** One GL buffer object. */
    static final class GlBuffer {
        final int name;
        final CgTrackedBuffer storage;
        boolean mapped;

        GlBuffer(int name, CgTrackedBuffer storage) {
            this.name = name;
            this.storage = storage;
        }
    }

    private final CgTracker tracker;
    private final TrackedGlErrors errors;
    private final TrackedVertexArrays vaos;
    private final GlNames<GlBuffer> names = new GlNames<>("Buffer");

    int array, uniform, storage, texture, copyRead, copyWrite, pixelPack, pixelUnpack;
    final int[] uniformName = new int[INDEXED], storageName = new int[INDEXED];
    final long[] uniformOffset = new long[INDEXED], uniformSize = new long[INDEXED];
    final long[] storageOffset = new long[INDEXED], storageSize = new long[INDEXED];

    TrackedBuffers(CgTracker tracker, TrackedGlErrors errors, TrackedVertexArrays vaos) {
        this.tracker = tracker;
        this.errors = errors;
        this.vaos = vaos;
    }

    int gen() {
        int name = names.next();
        return names.add(new GlBuffer(name, new CgTrackedBuffer(tracker, "buffer " + name)));
    }

    GlBuffer get(int name) {
        return name == 0 ? null : names.get(name);
    }

    void bind(int target, int name) {
        if (name != 0 && !names.exists(name)) {
            errors.invalidOperation("glBindBuffer: buffer " + name + " was never generated");
            return;
        }
        switch (target) {
            case CgGL.GL_ARRAY_BUFFER:          array = name; break;
            case CgGL.GL_ELEMENT_ARRAY_BUFFER:  vaos.current().elementBuffer = name; break;
            case CgGL.GL_UNIFORM_BUFFER:        uniform = name; break;
            case CgGL.GL_SHADER_STORAGE_BUFFER: storage = name; break;
            case CgGL.GL_TEXTURE_BUFFER:        texture = name; break;
            case CgGL.GL_COPY_READ_BUFFER:      copyRead = name; break;
            case CgGL.GL_COPY_WRITE_BUFFER:     copyWrite = name; break;
            case CgGL.GL_PIXEL_PACK_BUFFER:     pixelPack = name; break;
            case CgGL.GL_PIXEL_UNPACK_BUFFER:   pixelUnpack = name; break;
            default: errors.invalidEnum("glBindBuffer", target);
        }
    }

    void bindIndexed(int target, int index, int name, long offset, long size) {
        if (index < 0 || index >= INDEXED) {
            errors.invalidValue("glBindBufferRange index " + index);
            return;
        }
        bind(target, name);
        if (target == CgGL.GL_UNIFORM_BUFFER) {
            uniformName[index] = name; uniformOffset[index] = offset; uniformSize[index] = size;
        } else if (target == CgGL.GL_SHADER_STORAGE_BUFFER) {
            storageName[index] = name; storageOffset[index] = offset; storageSize[index] = size;
        } else {
            errors.invalidEnum("glBindBufferRange", target);
        }
    }

    /** The buffer bound at {@code target}, or {@code null} with a GL error when none is. */
    GlBuffer bound(int target, String call) {
        int name;
        switch (target) {
            case CgGL.GL_ARRAY_BUFFER:          name = array; break;
            case CgGL.GL_ELEMENT_ARRAY_BUFFER:  name = vaos.current().elementBuffer; break;
            case CgGL.GL_UNIFORM_BUFFER:        name = uniform; break;
            case CgGL.GL_SHADER_STORAGE_BUFFER: name = storage; break;
            case CgGL.GL_TEXTURE_BUFFER:        name = texture; break;
            case CgGL.GL_COPY_READ_BUFFER:      name = copyRead; break;
            case CgGL.GL_COPY_WRITE_BUFFER:     name = copyWrite; break;
            case CgGL.GL_PIXEL_PACK_BUFFER:     name = pixelPack; break;
            case CgGL.GL_PIXEL_UNPACK_BUFFER:   name = pixelUnpack; break;
            default: errors.invalidEnum(call, target); return null;
        }
        if (name == 0) {
            errors.invalidOperation(call + " with no buffer bound to 0x" + Integer.toHexString(target));
            return null;
        }
        return names.get(name);
    }

    void data(int target, long size, ByteBuffer data, int usage) {
        GlBuffer b = bound(target, "glBufferData");
        if (b == null) return;
        boolean hostVisible = usage != CgGL.GL_STATIC_DRAW && usage != GL_STATIC_READ && usage != GL_STATIC_COPY;
        b.storage.data(size, data, hostVisible);
    }

    void subData(int target, long offset, ByteBuffer data) {
        GlBuffer b = bound(target, "glBufferSubData");
        if (b != null) b.storage.subData(offset, data);
    }

    void storage(int target, long size, int flags) {
        GlBuffer b = bound(target, "glBufferStorage");
        if (b != null) b.storage.storage(size, null, (flags & GL_MAP_PERSISTENT_BIT) != 0);
    }

    ByteBuffer map(int target, long offset, long length, int access) {
        GlBuffer b = bound(target, "glMapBufferRange");
        if (b == null) return null;
        b.mapped = true;
        return b.storage.map(offset, length, (access & GL_MAP_WRITE_BIT) == 0,
                (access & GL_MAP_INVALIDATE_BUFFER_BIT) != 0,
                (access & (GL_MAP_UNSYNCHRONIZED_BIT | GL_MAP_PERSISTENT_BIT)) != 0);
    }

    boolean unmap(int target) {
        GlBuffer b = bound(target, "glUnmapBuffer");
        if (b == null || !b.mapped) {
            errors.invalidOperation("glUnmapBuffer of a buffer that is not mapped");
            return false;
        }
        b.mapped = false;
        b.storage.unmap();
        return true;
    }

    void delete(int name) {
        GlBuffer b = names.remove(name);
        if (b == null) return;
        b.storage.release();
        if (array == name) array = 0;
        if (uniform == name) uniform = 0;
        if (storage == name) storage = 0;
        if (texture == name) texture = 0;
        if (copyRead == name) copyRead = 0;
        if (copyWrite == name) copyWrite = 0;
        if (pixelPack == name) pixelPack = 0;
        if (pixelUnpack == name) pixelUnpack = 0;
        for (int i = 0; i < INDEXED; i++) {
            if (uniformName[i] == name) uniformName[i] = 0;
            if (storageName[i] == name) storageName[i] = 0;
        }
        vaos.bufferDeleted(name);
    }

    int query(int pname, double[] out) {
        switch (pname) {
            case CgGL.GL_ARRAY_BUFFER_BINDING:          return TrackedRenderState.one(out, array);
            case CgGL.GL_ELEMENT_ARRAY_BUFFER_BINDING:  return TrackedRenderState.one(out, vaos.current().elementBuffer);
            case CgGL.GL_UNIFORM_BUFFER_BINDING:        return TrackedRenderState.one(out, uniform);
            case CgGL.GL_SHADER_STORAGE_BUFFER_BINDING: return TrackedRenderState.one(out, storage);
            case GL_PIXEL_PACK_BUFFER_BINDING:          return TrackedRenderState.one(out, pixelPack);
            case GL_PIXEL_UNPACK_BUFFER_BINDING:        return TrackedRenderState.one(out, pixelUnpack);
            default: return -1;
        }
    }
}
