package com.crystalgraphics.render.shader;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.crystalgraphics.job.progress.CgProgress;
import com.crystalgraphics.platform.service.CgCacheDirectory;
import com.crystalgraphics.storage.cache.CgCacheFiles;
import com.crystalgraphics.storage.download.CgDownloads;

/**
 * The shader toolchain's jars for this machine, fetched once into the cache and kept: LWJGL core, shaderc and spvc at
 * the version the Vulkan module is built against, with this platform's natives. What a host whose own LWJGL ships no
 * shader compiler hands {@code CgShaderToolchain.load}.
 *
 * <pre>{@code
 * CgJob.lane(CgJobLane.BACKGROUND).run(() -> fetched = CgShaderToolchainFiles.fetch(CgProgress.NONE));   // a worker
 * // the render thread, each frame until it has landed:
 * if (fetched != null) install(fetched);
 * }</pre>
 *
 * <p>Never a loading-screen task: offline it fails only after its retries and timeouts.</p>
 *
 * <ul>
 *   <li>Empty when this platform has no pinned natives or the fetch failed, with why logged: the host stays where it is.</li>
 *   <li>Pinned in {@code assets/crystalgraphics/download/locations.json} under {@code shader-toolchain/}: the shared
 *       jars at the top, each platform's natives under {@code <platform>/}. Every file is checked against its pin.</li>
 *   <li>Kept in CrystalGraphics' cache, or in {@code -Dcrystalgraphics.shaderToolchain.dir}: one folder many
 *       clients share (a checkout's dev runs, {@code CrystalGraphics/build/shader-toolchain}; prodSmoke's, one per launcher).</li>
 *   <li>Blocking and on the network: never the render thread.</li>
 * </ul>
 */
public final class CgShaderToolchainFiles {

    private static final Logger LOGGER = LogManager.getLogger(CgShaderToolchainFiles.class);
    private static final String PREFIX = "shader-toolchain/";
    private static final String DIRECTORY_PROPERTY = "crystalgraphics.shaderToolchain.dir";

    private CgShaderToolchainFiles() {
    }

    /** The jars, in id order, every one verified on disk; empty when they cannot be had. */
    public static List<Path> fetch(CgProgress progress) {
        String platform = platform();
        List<String> ids = new ArrayList<>();
        boolean natives = false;
        for (String id : CgDownloads.LOCATIONS.idsUnder(PREFIX)) {
            String rest = id.substring(PREFIX.length());
            if (rest.indexOf('/') < 0) {
                ids.add(id);
            } else if (platform != null && rest.startsWith(platform + "/")) {
                ids.add(id);
                natives = true;
            }
        }
        if (!natives) {
            LOGGER.warn("[device] no shader toolchain is pinned for {} {}", System.getProperty("os.name"),
                    System.getProperty("os.arch"));
            return Collections.emptyList();
        }
        String shared = System.getProperty(DIRECTORY_PROPERTY);
        Path directory = shared != null ? Paths.get(shared) : CgCacheDirectory.of("shader-toolchain");
        List<Path> jars = new ArrayList<>(ids.size());
        List<CgDownloads.Artifact> missing = new ArrayList<>();
        try {
            for (String id : ids) {
                String fileName = id.substring(id.lastIndexOf('/') + 1);
                Path target = directory.resolve(fileName);
                jars.add(target);
                CgDownloads.Request request = CgDownloads.located(id);
                if (!CgCacheFiles.isValid(target, request.digest())) missing.add(new CgDownloads.Artifact(fileName, request));
            }
        } catch (IOException e) {
            LOGGER.warn("[device] the shader toolchain's locations are unreadable: {}", CgDownloads.describe(e));
            return Collections.emptyList();
        }
        if (missing.isEmpty()) return jars;
        CgDownloads.Batch.Result fetched = CgDownloads.batch(missing).named("Downloading the shader toolchain")
                .reporting(progress).into(directory);
        if (!fetched.complete()) {
            LOGGER.warn("[device] the shader toolchain was not fetched ({})", fetched);
            return Collections.emptyList();
        }
        return jars;
    }

    /**
     * LWJGL's name for this machine's natives ({@code windows}, {@code windows-arm64}, {@code linux},
     * {@code linux-arm64}, {@code macos}, {@code macos-arm64}); null for any other.
     */
    public static @Nullable String platform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean arm64 = arch.equals("aarch64") || arch.equals("arm64");
        boolean x64 = arch.equals("amd64") || arch.equals("x86_64");
        if (!arm64 && !x64) return null;
        String name = os.startsWith("windows") ? "windows"
                : os.startsWith("linux") ? "linux"
                : os.startsWith("mac") || os.startsWith("darwin") ? "macos" : null;
        if (name == null) return null;
        return arm64 ? name + "-arm64" : name;
    }
}
