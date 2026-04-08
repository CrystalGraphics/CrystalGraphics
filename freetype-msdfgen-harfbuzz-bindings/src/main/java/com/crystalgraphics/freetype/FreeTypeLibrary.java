package com.crystalgraphics.freetype;

import com.crystalgraphics.NativeLoader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * FreeType library instance. Entry point for all FreeType operations.
 * Wraps the native FT_Library pointer. Must be explicitly destroyed via {@link #destroy()}.
 *
 * <p><strong>Lifecycle constraint:</strong> All FTFace objects created from this library
 * must be destroyed BEFORE this library is destroyed.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * FreeTypeLibrary ft = FreeTypeLibrary.create();
 * FTFace face = ft.newFace("/path/to/font.ttf", 0);
 * face.setPixelSizes(0, 24);
 * // ... use face ...
 * face.destroy();
 * ft.destroy();
 * }</pre>
 *
 * <p>Loading from InputStream:</p>
 * <pre>{@code
 * FreeTypeLibrary ft = FreeTypeLibrary.create();
 * InputStream is = getClass().getResourceAsStream("/fonts/myfont.ttf");
 * FTFace face = ft.newFaceFromStream(is, 0);
 * // ... use face ...
 * face.destroy();
 * ft.destroy();
 * }</pre>
 */
public final class FreeTypeLibrary {

    private long nativePtr;
    private final Object lock = new Object();
    private final List<WeakReference<FTFace>> faces = new ArrayList<WeakReference<FTFace>>();

    private FreeTypeLibrary(long nativePtr) {
        this.nativePtr = nativePtr;
    }

    /**
     * Initializes a new FreeType library instance.
     * Loads native JNI library on first call.
     *
     * @return a new FreeTypeLibrary
     * @throws FreeTypeException if FT_Init_FreeType fails
     */
    public static FreeTypeLibrary create() {
        NativeLoader.ensureLoaded();
        long ptr = nInitFreeType();
        if (ptr == 0) {
            throw new FreeTypeException(FTErrors.FT_Err_Cannot_Open_Resource,
                    "Failed to initialize FreeType library");
        }
        return new FreeTypeLibrary(ptr);
    }

    /**
     * Opens a font face from a file path.
     *
     * @param filePath path to the font file (.ttf, .otf, etc.)
     * @param faceIndex face index within the font file (0 for most single-face fonts)
     * @return a new FTFace
     * @throws FreeTypeException if the font cannot be loaded
     * @throws IllegalStateException if this library has been destroyed
     */
    public FTFace newFace(String filePath, int faceIndex) {
        checkNotDestroyed();
        if (filePath == null) {
            throw new IllegalArgumentException("filePath must not be null");
        }
        long facePtr;
        synchronized (lock) {
            facePtr = nNewFace(nativePtr, filePath, faceIndex);
        }
        if (facePtr == 0) {
            throw new FreeTypeException(FTErrors.FT_Err_Cannot_Open_Resource,
                    "Failed to load font face: " + filePath);
        }
        FTFace face = new FTFace(facePtr, this);
        synchronized (faces) {
            faces.add(new WeakReference<FTFace>(face));
        }
        return face;
    }

    /**
     * Opens a font face from an InputStream. The stream is fully read into memory
     * and then loaded via {@link #newFaceFromMemory(byte[], int)}.
     * The stream is NOT closed by this method.
     *
     * @param stream the input stream containing font data (.ttf, .otf, etc.)
     * @param faceIndex face index within the font file (0 for most single-face fonts)
     * @return a new FTFace
     * @throws IOException if reading the stream fails
     * @throws FreeTypeException if the font cannot be loaded
     * @throws IllegalStateException if this library has been destroyed
     */
    public FTFace newFaceFromStream(InputStream stream, int faceIndex) throws IOException {
        checkNotDestroyed();
        if (stream == null) {
            throw new IllegalArgumentException("stream must not be null");
        }
        byte[] data = readAllBytes(stream);
        return newFaceFromMemory(data, faceIndex);
    }

    /**
     * Opens the first font face from an InputStream. Equivalent to
     * {@code newFaceFromStream(stream, 0)}.
     *
     * @param stream the input stream containing font data
     * @return a new FTFace (face index 0)
     * @throws IOException if reading the stream fails
     * @throws FreeTypeException if the font cannot be loaded
     */
    public FTFace newFaceFromStream(InputStream stream) throws IOException {
        return newFaceFromStream(stream, 0);
    }

    /**
     * Opens a font face from a byte array (in-memory font data).
     *
     * @param data the font file data
     * @param faceIndex face index (0 for most fonts)
     * @return a new FTFace
     * @throws FreeTypeException if the font cannot be loaded
     * @throws IllegalStateException if this library has been destroyed
     */
    public FTFace newFaceFromMemory(byte[] data, int faceIndex) {
        checkNotDestroyed();
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("data must not be null or empty");
        }
        long facePtr;
        synchronized (lock) {
            facePtr = nNewFaceFromMemory(nativePtr, data, data.length, faceIndex);
        }
        if (facePtr == 0) {
            throw new FreeTypeException(FTErrors.FT_Err_Cannot_Open_Resource,
                    "Failed to load font face from memory (" + data.length + " bytes)");
        }
        FTFace face = new FTFace(facePtr, this);
        synchronized (faces) {
            faces.add(new WeakReference<FTFace>(face));
        }
        return face;
    }

    /**
     * Returns the FreeType library version as {major, minor, patch}.
     *
     * @return three-element array [major, minor, patch]
     */
    public int[] getVersion() {
        checkNotDestroyed();
        synchronized (lock) {
            return nGetVersion(nativePtr);
        }
    }

    /**
     * Returns the FreeType version as a "major.minor.patch" string.
     */
    public String getVersionString() {
        int[] v = getVersion();
        return v[0] + "." + v[1] + "." + v[2];
    }

    public void destroy() {
        if (nativePtr != 0) {
            if (hasActiveFaces()) {
                throw new IllegalStateException(
                    "Cannot destroy FreeTypeLibrary while FTFace objects are still alive. "
                    + "Destroy all faces first.");
            }
            synchronized (lock) {
                nDoneFreeType(nativePtr);
            }
            nativePtr = 0;
        }
    }

    public boolean hasActiveFaces() {
        synchronized (faces) {
            Iterator<WeakReference<FTFace>> it = faces.iterator();
            while (it.hasNext()) {
                FTFace face = it.next().get();
                if (face == null || face.isDestroyed()) {
                    it.remove();
                    continue;
                }
                return true;
            }
            return false;
        }
    }

    public boolean isDestroyed() {
        return nativePtr == 0;
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void finalize() throws Throwable {
        try {
            if (nativePtr != 0) {
                System.err.println("[CrystalGraphics] WARNING: FreeTypeLibrary was not destroyed! "
                    + "Call destroy() explicitly to avoid native memory leaks.");
                try {
                    nDoneFreeType(nativePtr);
                } catch (Throwable t) {
                    // Best-effort cleanup during finalization
                }
                nativePtr = 0;
            }
        } finally {
            super.finalize();
        }
    }

    long getNativePtr() {
        return nativePtr;
    }

    Object getLock() {
        return lock;
    }

    private void checkNotDestroyed() {
        if (nativePtr == 0) {
            throw new IllegalStateException("FreeTypeLibrary has been destroyed");
        }
    }

    /**
     * Returns the number of faces in the given font data.
     * Loads face index 0 temporarily, reads {@code num_faces}, then closes it.
     *
     * @param data font file bytes
     * @return number of faces (>= 1 for valid fonts)
     * @throws FreeTypeException if the font data is invalid
     */
    public int getFaceCount(byte[] data) {
        checkNotDestroyed();
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("data must not be null or empty");
        }
        synchronized (lock) {
            return nGetFaceCountFromMemory(nativePtr, data, data.length);
        }
    }

    /**
     * Returns the number of faces in the given font file.
     *
     * @param filePath path to the font file
     * @return number of faces (>= 1 for valid fonts)
     * @throws FreeTypeException if the font file is invalid
     */
    public int getFaceCount(String filePath) {
        checkNotDestroyed();
        if (filePath == null) {
            throw new IllegalArgumentException("filePath must not be null");
        }
        synchronized (lock) {
            return nGetFaceCount(nativePtr, filePath);
        }
    }

    /**
     * Returns the number of faces in font data read from a stream.
     * The stream is NOT closed by this method.
     *
     * @param stream input stream containing font data
     * @return number of faces (>= 1 for valid fonts)
     * @throws IOException if reading the stream fails
     * @throws FreeTypeException if the font data is invalid
     */
    public int getFaceCount(InputStream stream) throws IOException {
        if (stream == null) {
            throw new IllegalArgumentException("stream must not be null");
        }
        return getFaceCount(readAllBytes(stream));
    }

    static byte[] readAllBytes(InputStream stream) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = stream.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    // --- JNI native methods ---
    private static native long nInitFreeType();
    private static native void nDoneFreeType(long libraryPtr);
    private static native long nNewFace(long libraryPtr, String filePath, int faceIndex);
    private static native long nNewFaceFromMemory(long libraryPtr, byte[] data, int dataLen, int faceIndex);
    private static native int[] nGetVersion(long libraryPtr);
    private static native int nGetFaceCount(long libraryPtr, String filePath);
    private static native int nGetFaceCountFromMemory(long libraryPtr, byte[] data, int dataLen);
}
