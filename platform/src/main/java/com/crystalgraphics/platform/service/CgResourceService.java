package com.crystalgraphics.platform.service;

import java.io.InputStream;

/**
 * Platform abstraction for loading game assets. Replaces direct IResourceManager coupling
 * in {@code CgIO}. Each platform implementation resolves assets through its own mechanism
 * (Minecraft resource packs, harness classpath, etc.).
 */
public interface CgResourceService {
    /**
     * Open a raw stream for the resource at {@code domain:path}.
     * Returns {@code null} if not found. Caller must close the stream.
     *
     * @param domain the resource domain (e.g. "crystalgraphics", "mymod")
     * @param path   the resource path within the domain (e.g. "shaders/terrain.shader")
     * @return an open {@link InputStream}, or {@code null} if not found
     */
    InputStream openStream(String domain, String path);
}
