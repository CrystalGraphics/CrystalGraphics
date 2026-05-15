package com.crystalgraphics.util.io;

import com.crystalgraphics.CrystalGraphics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.util.ResourceLocation;
import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;

public class CgIO {

    private static final String ASSETS_PREFIX = "/assets/";
    private static final String DEFAULT_DOMAIN = CrystalGraphics.MODID;

    /** For resolving paths outside of MC environment (essential for hot swapping resources)*/
    private static final String RESOURCE_OVERRIDE_DIR = System.getProperty("crystalgraphics.shader.resourceOverrideDir");

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
     * override → MC resource manager → classpath. Returns {@code null} if
     * all resolution strategies fail.
     *
     * @param path any supported path format (see {@link #normalizePath})
     * @return an open InputStream, or {@code null} on failure
     */
    public static InputStream openStream(String path) {
        // 0. Absolute filesystem path
        try {
            File absolute = new File(path);
            if (absolute.isFile() && absolute.isAbsolute() && !path.startsWith(ASSETS_PREFIX))
                return new FileInputStream(absolute);
        } catch (Throwable ignored) {}

        // 0.1 Harness shortcut
        try {
            if (path.contains(ASSETS_HARNESS))
                return new FileInputStream("src/main/resources/" + path);
        } catch (Throwable ignored) {}

        String normalized = normalizePath(path);

        // 1. Filesystem override
        if (RESOURCE_OVERRIDE_DIR != null) {
            try {
                String fsPath = normalized.startsWith("/") ? normalized.substring(1) : normalized;
                File file = new File(RESOURCE_OVERRIDE_DIR, fsPath);
                if (file.isFile()) return new FileInputStream(file);
            } catch (Throwable ignored) {}
        }

        // 2. Minecraft resource manager
        try {
            IResourceManager rm = Minecraft.getMinecraft().getResourceManager();
            if (rm != null) {
                IResource res = rm.getResource(toResourceLocation(normalized));
                if (res != null) return res.getInputStream();
            }
        } catch (Throwable ignored) {}

        // 3. Classpath fallback
        return CgIO.class.getResourceAsStream(normalized);
    }

    public static String loadSource(String path) throws Exception {
        InputStream in = openStream(path);
        if (in == null) return null;
        try {
            return IOUtils.toString(in, Charset.forName("UTF-8"));
        } catch (Throwable t) {
            return null;
        } finally {
            IOUtils.closeQuietly(in);
        }
    }

    public static ResourceLocation toResourceLocation(String path) {
        String normalized = normalizePath(path);
        // After normalization, path is always /assets/{domain}/{rest}
        String stripped = normalized.substring(ASSETS_PREFIX.length());
        int slash = stripped.indexOf('/');
        if (slash > 0) {
            String domain = stripped.substring(0, slash);
            String rest = stripped.substring(slash + 1);
            return new ResourceLocation(domain, rest);
        }
        // Fallback for edge cases — shouldn't happen after normalization
        return new ResourceLocation(path);
    }
}
