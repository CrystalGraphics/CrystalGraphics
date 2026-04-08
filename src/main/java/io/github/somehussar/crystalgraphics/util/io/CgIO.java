package io.github.somehussar.crystalgraphics.util.io;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.util.ResourceLocation;
import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;

public class CgIO {

    /**
     * For resolving paths outside of MC environment (essential for hot swapping resources)
     */
    private static final String RESOURCE_OVERRIDE_DIR = System.getProperty("crystalgraphics.shader.resourceOverrideDir");

    public static String loadSource(String path) throws Exception {
        // 1. Filesystem override (dev hotswap via system property)
        if (RESOURCE_OVERRIDE_DIR != null) {
            String fsPath = path.startsWith("/") ? path.substring(1) : path;
            File file = new File(RESOURCE_OVERRIDE_DIR, fsPath);
            if (file.isFile()) {
                FileInputStream fis = new FileInputStream(file);
                try {
                    return readStream(fis);
                } finally {
                    fis.close();
                }
            }
        }

        // 2. Minecraft resource manager (normal in-game path)
        try {
            IResourceManager resourceManager = Minecraft.getMinecraft().getResourceManager();
            if (resourceManager != null) {
                IResource resource = resourceManager.getResource(toResourceLocation(path));
                InputStream in = resource.getInputStream();
                try {
                    return IOUtils.toString(in, Charset.forName("UTF-8"));
                } finally {
                    IOUtils.closeQuietly(in);
                }
            }
        } catch (Throwable ignored) {
            // Minecraft not available (harness, tests, etc.) — fall through
        }

        // 3. Classpath fallback
        InputStream in = CgIO.class.getResourceAsStream(path);
        if (in == null && !path.startsWith("/")) in = CgIO.class.getResourceAsStream("/" + path);
        if (in == null) throw new Exception("Shader source not found on classpath: " + path);
        try {
            return readStream(in);
        } finally {
            in.close();
        }
    }

    private static String readStream(InputStream in) throws Exception {
        InputStreamReader reader = new InputStreamReader(in, Charset.forName("UTF-8"));
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[4096];
        int read;
        while ((read = reader.read(buf)) != -1) {
            sb.append(buf, 0, read);
        }
        return sb.toString();
    }

    public static ResourceLocation toResourceLocation(String path) {
        // Format: "/assets/{domain}/{rest}"
        if (path.startsWith("/assets/")) {
            String stripped = path.substring("/assets/".length());
            int slash = stripped.indexOf('/');
            if (slash > 0) {
                String domain = stripped.substring(0, slash);
                String rest = stripped.substring(slash + 1);
                return new ResourceLocation(domain, rest);
            }
        }
        // Format: "{domain}:{path}"
        if (path.indexOf(':') > 0) {
            return new ResourceLocation(path);
        }
        return null;
    }
}
