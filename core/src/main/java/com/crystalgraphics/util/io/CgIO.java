package com.crystalgraphics.util.io;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgResourceService;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

public class CgIO {

    private static final String ASSETS_PREFIX = "/assets/";
    private static final String DEFAULT_DOMAIN = "crystalgraphics";

    /**
     * Source roots searched before the classpath — how an asset is hot-swapped outside Minecraft.
     *
     * <p><b>Several roots, not one</b>, because one is not enough to develop against. Every project in a
     * composite build keeps its own {@code src/main/resources}, so a single root can serve
     * {@code assets/crystalgui/**} or {@code assets/crystalgraphics/**} but never both — and the one it
     * cannot serve falls back to the classpath, which for a Gradle run is a <em>copy</em> that
     * {@code processResources} made at build time. Editing that project's sources then changes nothing
     * the running process can see, and a reload faithfully re-reads the stale copy and looks broken.</p>
     *
     * <p>Set either property to a {@link File#pathSeparator}-separated list; the roots are tried in order.
     * A single path is still a valid list, so the original spelling keeps working unchanged.</p>
     *
     * <ul>
     *   <li>{@code crystalgraphics.resourceOverrideDirs} — the generic name, preferred</li>
     *   <li>{@code crystalgraphics.shader.resourceOverrideDir} — the original, kept for compatibility.
     *       Never shader-specific despite the name; it has always been consulted for every resource.</li>
     * </ul>
     */
    private static final java.util.List<File> RESOURCE_OVERRIDE_DIRS = parseOverrideDirs();

    private static java.util.List<File> parseOverrideDirs() {
        String raw = System.getProperty("crystalgraphics.resourceOverrideDirs");
        if (raw == null || raw.trim().isEmpty()) {
            raw = System.getProperty("crystalgraphics.shader.resourceOverrideDir");
        }
        if (raw == null || raw.trim().isEmpty()) return java.util.Collections.emptyList();

        java.util.List<File> roots = new java.util.ArrayList<>();
        // File.pathSeparator, so a Windows "C:\a;C:\b" splits on ';' and leaves the drive colons alone.
        for (String part : raw.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            try {
                File dir = new File(trimmed);
                // Dropped rather than kept and probed per lookup: a root that does not exist is a typo or
                // a stale path, and silently missing every file is the confusing version of that.
                if (dir.isDirectory()) roots.add(dir);
                else System.err.println("[CgIO] resource override root is not a directory, ignoring: " + dir);
            } catch (Throwable ignored) {}
        }
        return roots;
    }

    /** For instant hotswap from filesystem in harness*/
    private static final String ASSETS_HARNESS = "assets/harness/";
    
    /**
     * Normalizes any supported path format to the canonical
     * {@code /assets/{domain}/{rest}} form.
     *
     * <p>Accepted inputs:</p>
     * <ul>
     *   <li>{@code /assets/{modid}/shader/foo.vert} — already canonical, returned as-is</li>
     *   <li>{@code assets/{modid}/shader/foo.vert} — missing leading slash, prepended</li>
     *   <li>{@code {modid}:shader/foo.vert} — ResourceLocation format, expanded</li>
     *   <li>{@code /shader/foo.vert} — domain-relative with leading slash, expanded with default domain (crystalgraphics)</li>
     *   <li>{@code shader/foo.vert} — domain-relative bare, expanded with default domain (crystalgraphics)</li>
     * </ul>
     */
    public static String normalizePath(String path) {
        // Already canonical: /assets/...
        if (path.startsWith(ASSETS_PREFIX)) return path;

        // Without leading slash: assets/...
        if (path.startsWith("assets/")) return "/" + path;

        // ResourceLocation format: domain:rest
        int colon = path.indexOf(':');
        if (colon > 0 && colon < path.length() - 1) {
            String domain = path.substring(0, colon);
            String rest = path.substring(colon + 1);
            return ASSETS_PREFIX + domain + "/" + rest;
        }

        // Domain-relative: /shader/foo.vert or shader/foo.vert
        String bare = path.startsWith("/") ? path.substring(1) : path;
        return ASSETS_PREFIX + DEFAULT_DOMAIN + "/" + bare;
    }
    
    /**
     * Opens an InputStream for the given path using the same waterfall as
     * {@link #loadSource}: absolute path → harness shortcut → filesystem
     * override roots (in order, see {@link #RESOURCE_OVERRIDE_DIRS}) → MC
     * resource manager → classpath. Returns {@code null} if all resolution
     * strategies fail.
     *
     * @param path any supported path format (see {@link #normalizePath})
     * @return an open InputStream, or {@code null} on failure
     */
    public static InputStream openStream(String path) {
        // 0. Absolute filesystem path.
        //
        // The cheap predicates go FIRST, and that ordering is the whole point rather than tidiness:
        // isFile() is a filesystem stat, while isAbsolute() and startsWith() are answered from the string
        // in memory. Tested the other way round, every namespaced path in the engine -- which is nearly
        // every path -- paid a syscall to discover something already known. Measured on the icon set, this
        // one reorder is a third of the stat traffic in the whole waterfall.
        try {
            if (!path.startsWith(ASSETS_PREFIX)) {
                File absolute = new File(path);
                if (absolute.isAbsolute() && absolute.isFile()) return new FileInputStream(absolute);
            }
        } catch (Throwable ignored) {}

        // 0.1 Harness shortcut
        try {
            if (path.contains(ASSETS_HARNESS))
                return new FileInputStream("src/main/resources/" + path);
        } catch (Throwable ignored) {}

        String normalized = normalizePath(path);

        // 1. Filesystem override — every configured source root, in order.
        if (!RESOURCE_OVERRIDE_DIRS.isEmpty()) {
            String fsPath = normalized.startsWith("/") ? normalized.substring(1) : normalized;
            for (File root : RESOURCE_OVERRIDE_DIRS) {
                try {
                    File file = new File(root, fsPath);
                    if (file.isFile()) return new FileInputStream(file);
                } catch (Throwable ignored) {}
            }
        }

        // 2. Platform resource service (returns null before CgPlatform.register() — falls through to classpath)
        try {
            CgResourceService svc = CgPlatform.resources();
            if (svc != null) {
                String stripped = normalized.substring(ASSETS_PREFIX.length());
                int slash = stripped.indexOf('/');
                if (slash > 0) {
                    String domain = stripped.substring(0, slash);
                    String rest = stripped.substring(slash + 1);
                    InputStream stream = svc.openStream(domain, rest);
                    if (stream != null) return stream;
                }
            }
        } catch (Throwable ignored) {}

        // 3. Classpath fallback — THROUGH SEVERAL LOADERS, because ours is not the only module.
        //
        // `CgIO.class` lives in CrystalGraphics and nearly every asset it is asked for lives in a
        // CONSUMER (assets/crystalgui/**) -- and the two are not loaded by the same thing. Measured on a
        // Forge 1.20.1 client: graphicsCore.jar is on the legacy classpath, while the consumer's classes
        // and resources reach the game only through `-Dfml.modFolders`, which is the mod's own loader.
        // So CgIO's loader cannot see them at all, and the context loader is the one that can.
        //
        // Invisible for as long as step 2 covers the consumer's assets. Minecraft 1.20 is where it stops
        // covering them: ResourceLocation refuses any path character outside [a-z0-9_.-/], so every asset
        // with a CAPITAL in its name throws up there, is swallowed, and arrives here -- to be missed
        // again. Measured over one client run, the correlation was exact and had no exceptions:
        // JetBrainsMono-Regular.ttf, statusError.svg and dropdownGutter.svg all failed while show.svg,
        // markdown.svg and folder.svg all loaded. The editor's font is one of the casualties, so the
        // whole editor draws no text and no gutter -- which reads as a rendering fault rather than a
        // missing file.
        //
        // 1.7.10 never validated the case and the harness registers no resource service at all, so on
        // both of those every asset is found at this step and neither can see the gap.
        InputStream fromModule = CgIO.class.getResourceAsStream(normalized);
        if (fromModule != null) return fromModule;

        String relative = normalized.startsWith("/") ? normalized.substring(1) : normalized;
        for (ClassLoader loader : new ClassLoader[] {
                Thread.currentThread().getContextClassLoader(), CgIO.class.getClassLoader() }) {
            if (loader == null) continue;
            try {
                InputStream stream = loader.getResourceAsStream(relative);
                if (stream != null) return stream;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    public static String loadSource(String path) {
        try (InputStream in = openStream(path)) {
            if (in == null) return null;
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) != -1) baos.write(chunk, 0, n);
            return baos.toString("UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

}
