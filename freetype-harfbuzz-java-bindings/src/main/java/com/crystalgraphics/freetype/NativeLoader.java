package com.crystalgraphics.freetype;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Smart native library loader for FreeType and HarfBuzz JNI bindings.
 *
 * <h3>Loading Strategy (waterfall):</h3>
 * <ol>
 *   <li><strong>System property</strong>: {@code -Dfreetype.harfbuzz.native.path=/path/to/dir}</li>
 *   <li><strong>Classpath natives</strong>: Extracted from JAR resources under {@code /natives/{platform}/}</li>
 *   <li><strong>System library path</strong>: {@code java.library.path} / OS default locations</li>
 * </ol>
 *
 * <h3>Platform Detection:</h3>
 * <p>Automatically detects OS and architecture to load the correct native binary.
 * Supports: Windows x64, Linux x64/aarch64, macOS x64/aarch64 (Apple Silicon).</p>
 *
 * <h3>Thread Safety:</h3>
 * <p>Loading is synchronized and idempotent. Multiple calls to {@link #ensureLoaded()}
 * are safe from any thread.</p>
 */
public final class NativeLoader {

    /** Library name without platform prefix/suffix */
    private static final String LIBRARY_NAME = "freetype_harfbuzz_jni";

    /** Whether the native library has been successfully loaded */
    private static final AtomicBoolean loaded = new AtomicBoolean(false);

    /** Lock object for synchronized loading */
    private static final Object loadLock = new Object();

    /** Stores the first load error for diagnostic purposes */
    private static Throwable loadError = null;

    private NativeLoader() {
    }

    /**
     * Ensures the native library is loaded. Idempotent and thread-safe.
     *
     * @throws UnsatisfiedLinkError if the native library cannot be found or loaded
     *         on any search path
     */
    public static void ensureLoaded() {
        if (loaded.get()) {
            return;
        }
        synchronized (loadLock) {
            if (loaded.get()) {
                return;
            }
            loadNativeLibrary();
        }
    }

    /**
     * Returns whether the native library has been successfully loaded.
     *
     * @return {@code true} if natives are available
     */
    public static boolean isLoaded() {
        return loaded.get();
    }

    /**
     * Returns the platform identifier string used for native library resolution.
     * Format: {@code {os}-{arch}} (e.g., "windows-x64", "linux-aarch64", "macos-aarch64")
     *
     * @return the platform identifier
     */
    public static String getPlatformIdentifier() {
        String os = detectOS();
        String arch = detectArch();
        return os + "-" + arch;
    }

    /**
     * Returns the platform-specific library filename.
     * Examples: "freetype_harfbuzz_jni.dll", "libfreetype_harfbuzz_jni.so",
     * "libfreetype_harfbuzz_jni.dylib"
     *
     * @return the library filename for the current platform
     */
    public static String getLibraryFileName() {
        String os = detectOS();
        switch (os) {
            case "windows":
                return LIBRARY_NAME + ".dll";
            case "linux":
                return "lib" + LIBRARY_NAME + ".so";
            case "macos":
                return "lib" + LIBRARY_NAME + ".dylib";
            default:
                throw new UnsupportedOperationException("Unsupported OS: " + os);
        }
    }

    private static final String LOG_PREFIX = "[CrystalGraphics NativeLoader] ";

    private static void loadNativeLibrary() {
        String platform = getPlatformIdentifier();
        System.err.println(LOG_PREFIX + "Platform: " + platform);
        System.err.println(LOG_PREFIX + "Library: " + getLibraryFileName());
        System.err.println(LOG_PREFIX + "os.name=" + System.getProperty("os.name")
                + " os.arch=" + System.getProperty("os.arch"));

        // Strategy 1: System property override
        String customPath = System.getProperty("freetype.harfbuzz.native.path");
        if (customPath != null && !customPath.isEmpty()) {
            File libFile = new File(customPath, getLibraryFileName());
            System.err.println(LOG_PREFIX + "Strategy 1: custom path " + libFile.getAbsolutePath()
                    + " exists=" + libFile.exists());
            if (libFile.exists()) {
                try {
                    System.load(libFile.getAbsolutePath());
                    loaded.set(true);
                    System.err.println(LOG_PREFIX + "Loaded via custom path");
                    return;
                } catch (UnsatisfiedLinkError e) {
                    loadError = e;
                    System.err.println(LOG_PREFIX + "Strategy 1 FAILED: " + e.getMessage());
                }
            }
        } else {
            System.err.println(LOG_PREFIX + "Strategy 1: skipped (property not set)");
        }

        // Strategy 2: Extract from classpath (JAR resources)
        try {
            loadFromClasspath();
            loaded.set(true);
            return;
        } catch (UnsatisfiedLinkError e) {
            loadError = e;
            System.err.println(LOG_PREFIX + "Strategy 2 FAILED: " + e.getMessage());
        } catch (IOException e) {
            loadError = e;
            System.err.println(LOG_PREFIX + "Strategy 2 FAILED (IO): " + e.getMessage());
        }

        // Strategy 3: System library path
        try {
            System.err.println(LOG_PREFIX + "Strategy 3: System.loadLibrary('" + LIBRARY_NAME + "')");
            System.loadLibrary(LIBRARY_NAME);
            loaded.set(true);
            System.err.println(LOG_PREFIX + "Loaded via system library path");
            return;
        } catch (UnsatisfiedLinkError e) {
            loadError = e;
            System.err.println(LOG_PREFIX + "Strategy 3 FAILED: " + e.getMessage());
        }

        // All strategies failed
        StringBuilder msg = new StringBuilder();
        msg.append("Failed to load native library '").append(LIBRARY_NAME)
           .append("' for platform '").append(platform).append("'.\n\n");
        msg.append("Attempted loading strategies:\n");
        msg.append("  1. System property 'freetype.harfbuzz.native.path': ");
        msg.append(customPath != null ? customPath : "(not set)").append("\n");
        msg.append("  2. Classpath resource: /natives/").append(platform)
           .append("/").append(getLibraryFileName()).append("\n");
        msg.append("  3. System library path (java.library.path): ")
           .append(System.getProperty("java.library.path")).append("\n\n");
        msg.append("To resolve this, either:\n");
        msg.append("  - Ensure the native binaries are included in your JAR\n");
        msg.append("  - Set -Dfreetype.harfbuzz.native.path=/path/to/natives/").append(platform).append("\n");
        msg.append("  - Install FreeType and HarfBuzz development libraries and rebuild\n");

        if (loadError != null) {
            msg.append("\nLast error: ").append(loadError.getMessage());
        }

        throw new UnsatisfiedLinkError(msg.toString());
    }

    private static void loadFromClasspath() throws IOException {
        String platform = getPlatformIdentifier();
        String resourcePath = "/natives/" + platform + "/" + getLibraryFileName();
        System.err.println(LOG_PREFIX + "Strategy 2: classpath resource " + resourcePath);

        InputStream in = NativeLoader.class.getResourceAsStream(resourcePath);
        if (in == null) {
            System.err.println(LOG_PREFIX + "Resource NOT FOUND: " + resourcePath);
            throw new UnsatisfiedLinkError(
                    "Native library not found in classpath: " + resourcePath);
        }
        System.err.println(LOG_PREFIX + "Resource FOUND, extracting...");

        File tempDir = createTempDirectory();
        File tempFile = new File(tempDir, getLibraryFileName());
        tempFile.deleteOnExit();
        tempDir.deleteOnExit();

        FileOutputStream out = null;
        int totalBytes = 0;
        try {
            out = new FileOutputStream(tempFile);
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                totalBytes += bytesRead;
            }
        } finally {
            if (out != null) {
                out.close();
            }
            in.close();
        }
        System.err.println(LOG_PREFIX + "Extracted " + totalBytes + " bytes to " + tempFile.getAbsolutePath());
        System.err.println(LOG_PREFIX + "File size on disk: " + tempFile.length());

        try {
            System.load(tempFile.getAbsolutePath());
            System.err.println(LOG_PREFIX + "System.load() SUCCEEDED");
        } catch (UnsatisfiedLinkError e) {
            System.err.println(LOG_PREFIX + "System.load() FAILED: " + e.getMessage());
            throw e;
        }
    }
    private static File createTempDirectory() throws IOException {
        File tempFile = File.createTempFile("freetype_harfbuzz_jni_", "");
        if (!tempFile.delete()) {
            throw new IOException("Could not delete temp file: " + tempFile);
        }
        if (!tempFile.mkdir()) {
            throw new IOException("Could not create temp directory: " + tempFile);
        }
        return tempFile;
    }

    /**
     * Detects the operating system.
     *
     * @return "windows", "linux", or "macos"
     */
    static String detectOS() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return "windows";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "macos";
        }
        if (os.contains("linux") || os.contains("nux")) {
            return "linux";
        }
        throw new UnsupportedOperationException(
                "Unsupported operating system: " + System.getProperty("os.name"));
    }

    /**
     * Detects the CPU architecture.
     *
     * @return "x64" or "aarch64"
     */
    static String detectArch() {
        String arch = System.getProperty("os.arch", "").toLowerCase();
        if (arch.equals("amd64") || arch.equals("x86_64")) {
            return "x64";
        }
        if (arch.equals("aarch64") || arch.equals("arm64")) {
            return "aarch64";
        }
        throw new UnsupportedOperationException(
                "Unsupported architecture: " + System.getProperty("os.arch")
                + ". Supported: x64 (amd64/x86_64), aarch64 (arm64)");
    }
}
