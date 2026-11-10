package com.crystalgraphics.platform.service;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgService;

import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * The host's resources: every enabled resource pack over the game's own assets, as the host resolves them. What
 * {@code CgIO} opens a path through before the classpath, and what a file merged across packs is read from.
 *
 * <pre>{@code
 * try (InputStream in = CgHostResources.open("mymod", "textures/hud/icon.png")) {   // the top pack's copy, or null
 *     if (in != null) decode(in);
 * }
 * for (String namespace : CgHostResources.namespaces()) {
 *     for (InputStream in : CgHostResources.openStack(namespace, "sounds.json")) merge(in);   // lowest pack first
 * }
 * }</pre>
 *
 * A host, once, from its client install:
 *
 * <pre>{@code
 * CgPlatform.provide(CgHostResources.HOST, HostResourcesModern.INSTANCE);
 * }</pre>
 *
 * <ul>
 *   <li>The caller closes every stream it is handed.</li>
 *   <li>Without a host (the harness, a test, a dedicated server) nothing is found: {@link #open} answers null and the
 *       rest are empty, so {@code CgIO} reads the classpath.</li>
 *   <li>A pack change reaches the next call: nothing here caches.</li>
 * </ul>
 */
public final class CgHostResources {

    /** What a host answers: its resource manager. One class per era, {@code HostResources<Era>}. Client only. */
    public interface Host {
        /** The top pack's copy of {@code namespace:path}, or null where none has it. */
        InputStream open(String namespace, String path);

        /** Every pack's copy of {@code namespace:path}, the lowest first; empty where none has it. */
        List<InputStream> openStack(String namespace, String path);

        /** The namespaces the packs hold; empty where the host cannot say. */
        Set<String> namespaces();

        Host NONE = new Host() {
            @Override public InputStream open(String namespace, String path) { return null; }
            @Override public List<InputStream> openStack(String namespace, String path) { return Collections.emptyList(); }
            @Override public Set<String> namespaces() { return Collections.emptySet(); }
        };
    }

    /** The slot a host fills. */
    public static final CgService<Host> HOST = CgService.of("crystalgraphics:host_resources", Host.NONE);

    private CgHostResources() {
    }

    /** What the host provided, or {@link Host#NONE}: for a reader handed resources to read, as a test hands a fake. */
    public static Host host() {
        return CgPlatform.get(HOST);
    }

    /** The top pack's copy of {@code namespace:path}, or null. */
    public static InputStream open(String namespace, String path) {
        return host().open(namespace, path);
    }

    /** Every pack's copy of {@code namespace:path}, the lowest first. */
    public static List<InputStream> openStack(String namespace, String path) {
        return host().openStack(namespace, path);
    }

    /** The namespaces the packs hold. */
    public static Set<String> namespaces() {
        return host().namespaces();
    }
}
