package com.crystalgraphics.msdfgen;

/**
 * Native library loader for msdfgen-jni with fallback mechanism.
 * <p>
 * Loading order:
 * <ol>
 *   <li>Classpath natives (extracted to temp directory)</li>
 *   <li>System library path ({@code java.library.path})</li>
 *   <li>Explicit path via system property {@code msdfgen.library.path}</li>
 * </ol>
 * <p>
 * Compatible with Java 8+. No LWJGL 3.x dependency.
 *
 * @since 1.0.0
 */
public final class NativeLoader {

    private static final String LIBRARY_NAME = "msdfgen-jni";
    private static volatile boolean loaded = false;
    private static volatile Throwable loadError = null;

    private NativeLoader() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Ensures the native library is loaded. Safe to call multiple times.
     *
     * @throws UnsatisfiedLinkError if the native library cannot be loaded from any source
     */
    public static synchronized void load() {
        if (loaded) {
            return;
        }
        if (loadError != null) {
            throw new UnsatisfiedLinkError("Native library previously failed to load: " + loadError.getMessage());
        }

        Throwable explicitPathError = null;
        Throwable classpathError = null;

        // Strategy 1: Explicit path via system property
        String explicitPath = System.getProperty("msdfgen.library.path");
        if (explicitPath != null && !explicitPath.isEmpty()) {
            try {
                System.load(explicitPath);
                loaded = true;
                return;
            } catch (UnsatisfiedLinkError e) {
                explicitPathError = e;
            }
        }

        // Strategy 2: Extract from classpath (embedded natives)
        try {
            loadFromClasspath();
            loaded = true;
            return;
        } catch (Throwable e) {
            classpathError = e;
        }

        // Strategy 3: System library path
        try {
            System.loadLibrary(LIBRARY_NAME);
            loaded = true;
            return;
        } catch (UnsatisfiedLinkError e) {
            loadError = e;
            String platform = getOsName() + "-" + getArchName();
            StringBuilder msg = new StringBuilder();
            msg.append("Failed to load native library '").append(LIBRARY_NAME)
               .append("' for platform '").append(platform).append("'.\n");
            msg.append("Tried:\n");
            if (explicitPath != null) {
                msg.append("  1. Explicit path: ").append(explicitPath);
                if (explicitPathError != null) msg.append(" -> ").append(explicitPathError.getMessage());
                msg.append("\n");
            }
            msg.append("  2. Classpath: /natives/").append(platform).append("/").append(mapLibraryName(LIBRARY_NAME));
            if (classpathError != null) msg.append(" -> ").append(classpathError.getMessage());
            msg.append("\n");
            msg.append("  3. System library path -> ").append(e.getMessage()).append("\n");
            msg.append("Set -Dmsdfgen.library.path=/path/to/").append(mapLibraryName(LIBRARY_NAME)).append(" to specify explicitly.");
            throw new UnsatisfiedLinkError(msg.toString());
        }
    }

    /**
     * Returns whether the native library has been successfully loaded.
     */
    public static boolean isLoaded() {
        return loaded;
    }

    private static void loadFromClasspath() {
        String osName = getOsName();
        String archName = getArchName();
        String libFileName = mapLibraryName(LIBRARY_NAME);
        String resourcePath = "/natives/" + osName + "-" + archName + "/" + libFileName;

        java.io.InputStream is = NativeLoader.class.getResourceAsStream(resourcePath);
        if (is == null) {
            throw new UnsatisfiedLinkError("Native library not found in classpath: " + resourcePath);
        }

        java.io.File tempDir = null;
        java.io.File tempFile = null;
        try {
            // Create a temp directory that will be cleaned up on JVM exit
            tempDir = createTempDirectory("msdfgen-natives");
            tempDir.deleteOnExit();

            tempFile = new java.io.File(tempDir, libFileName);
            tempFile.deleteOnExit();

            java.io.FileOutputStream fos = new java.io.FileOutputStream(tempFile);
            try {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                }
            } finally {
                fos.close();
            }

            System.load(tempFile.getAbsolutePath());
        } catch (java.io.IOException e) {
            throw new UnsatisfiedLinkError("Failed to extract native library: " + e.getMessage());
        } finally {
            try {
                is.close();
            } catch (java.io.IOException ignored) {
                // Best effort close
            }
        }
    }
    private static java.io.File createTempDirectory(String prefix) throws java.io.IOException {
        java.io.File tempFile = java.io.File.createTempFile(prefix, "");
        if (!tempFile.delete()) {
            throw new java.io.IOException("Could not delete temp file: " + tempFile.getAbsolutePath());
        }
        if (!tempFile.mkdir()) {
            throw new java.io.IOException("Could not create temp directory: " + tempFile.getAbsolutePath());
        }
        return tempFile;
    }

    /**
     * Returns the OS identifier used for native library paths.
     */
    static String getOsName() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (os.contains("win")) {
            return "windows";
        } else if (os.contains("mac") || os.contains("darwin")) {
            return "macos";
        } else if (os.contains("linux") || os.contains("nix") || os.contains("nux")) {
            return "linux";
        }
        return "unknown";
    }

    /**
     * Returns the architecture identifier used for native library paths.
     */
    static String getArchName() {
        String arch = System.getProperty("os.arch", "").toLowerCase(java.util.Locale.ROOT);
        if (arch.equals("amd64") || arch.equals("x86_64")) {
            return "x64";
        } else if (arch.equals("aarch64") || arch.equals("arm64")) {
            return "aarch64";
        } else if (arch.equals("x86") || arch.equals("i386") || arch.equals("i686")) {
            return "x86";
        }
        return arch;
    }

    /**
     * Maps a library name to its platform-specific filename.
     */
    static String mapLibraryName(String name) {
        String os = getOsName();
        if ("windows".equals(os)) {
            return name + ".dll";
        } else if ("macos".equals(os)) {
            return "lib" + name + ".dylib";
        } else {
            return "lib" + name + ".so";
        }
    }
}
