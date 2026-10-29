package com.crystalgraphics.platform.service;

import java.io.InputStream;
import java.util.List;
import java.util.Set;

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

    /**
     * Every pack's copy of {@code domain:path}, the lowest pack first: what a file merged across packs is read from,
     * as Minecraft merges {@code sounds.json}. Empty where none has it; the caller closes each.
     */
    List<InputStream> openStack(String domain, String path);

    /** The namespaces the host's resources hold; empty where it cannot say. */
    Set<String> namespaces();
}
