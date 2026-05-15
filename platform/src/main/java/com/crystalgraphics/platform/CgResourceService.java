package com.crystalgraphics.platform;

import java.io.InputStream;

/**
 * Platform abstraction for loading game assets. Replaces direct IResourceManager coupling
 * in {@code CgIO}. Each platform implementation resolves assets through its own mechanism
 * (Minecraft resource packs, harness classpath, etc.).
 */
public interface CgResourceService {
    /**
     * Load a UTF-8 text resource by domain and path.
     *
     * @param domain the resource domain (e.g. "crystalgraphics", "mymod")
     * @param path   the resource path within the domain (e.g. "shaders/terrain.shader")
     * @return the file contents as a UTF-8 string, or {@code null} if not found
     */
    String loadText(String domain, String path);

    /**
     * Open a raw input stream for a resource. Caller is responsible for closing the stream.
     *
     * @param domain the resource domain
     * @param path   the resource path within the domain
     * @return an open {@link InputStream}, or {@code null} if not found
     */
    InputStream openStream(String domain, String path);
}
