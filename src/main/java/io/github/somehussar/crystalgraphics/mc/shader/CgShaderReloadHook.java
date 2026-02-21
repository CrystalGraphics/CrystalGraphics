package io.github.somehussar.crystalgraphics.mc.shader;

import io.github.somehussar.crystalgraphics.api.shader.CgShaderManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IResourceManagerReloadListener;
import net.minecraft.client.resources.IReloadableResourceManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Client-side resource reload hook that triggers shader recompilation
 * whenever Minecraft's resource manager reloads (e.g. resource pack change,
 * F3+T).
 *
 * <p>Registers an {@link IResourceManagerReloadListener} with Minecraft's
 * {@link IReloadableResourceManager} to call {@link CgShaderManager#reloadAll()}
 * on every tracked manager. Actual recompilation is deferred to each shader's
 * next bind (lazy compile-on-bind).</p>
 *
 * <p>This class is idempotent: calling {@link #register()} multiple times
 * has no additional effect after the first successful registration.</p>
 *
 * <p>Multiple {@link CgShaderManager} instances can be tracked via
 * {@link #trackManager(CgShaderManager)}. On reload, each tracked manager
 * is reloaded independently; a failure in one does not prevent others from
 * reloading.</p>
 *
 * <h3>Thread Safety</h3>
 * <p>The tracked-managers set is guarded by a synchronized block on the set
 * itself, so {@link #trackManager(CgShaderManager)} may be called from any
 * thread. The reload listener itself runs on the client/render thread.</p>
 */
public final class CgShaderReloadHook {

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");

    /** Guard flag to ensure we only register once. */
    private static volatile boolean registered = false;

    /**
     * Identity-based set of tracked managers.
     * Uses {@link Collections#newSetFromMap} with an {@link IdentityHashMap}
     * to avoid accidental equals/hashCode collisions between manager instances.
     */
    private static final Set<CgShaderManager> managers =
            Collections.newSetFromMap(new IdentityHashMap<CgShaderManager, Boolean>());

    /** The singleton reload listener instance. */
    private static final IResourceManagerReloadListener LISTENER = new IResourceManagerReloadListener() {
        @Override
        public void onResourceManagerReload(IResourceManager resourceManager) {
            Set<CgShaderManager> snapshot;
            synchronized (managers) {
                if (managers.isEmpty()) {
                    return;
                }
                // Snapshot to avoid holding lock during reload
                snapshot = Collections.newSetFromMap(new IdentityHashMap<CgShaderManager, Boolean>());
                snapshot.addAll(managers);
            }

            LOGGER.info("Resource manager reload detected — reloading {} shader manager(s)", snapshot.size());

            for (CgShaderManager manager : snapshot) {
                try {
                    manager.reloadAll();
                } catch (Exception e) {
                    LOGGER.error("Failed to reload shader manager: {}", manager, e);
                }
            }
        }
    };

    private CgShaderReloadHook() {
        // static utility — no instances
    }

    /**
     * Tracks a {@link CgShaderManager} so it will be reloaded on resource
     * manager reloads.
     *
     * <p>No-op if {@code manager} is {@code null}. Identity-based: the same
     * instance will only be tracked once regardless of how many times this
     * method is called.</p>
     *
     * @param manager the manager to track, or {@code null} (ignored)
     */
    public static void trackManager(CgShaderManager manager) {
        if (manager == null) {
            return;
        }
        synchronized (managers) {
            managers.add(manager);
        }
        LOGGER.debug("Tracked shader manager: {}", manager);
    }

    /**
     * Registers the shader reload listener with Minecraft's resource manager.
     *
     * <p>Safe to call multiple times; only the first invocation performs
     * the actual registration. If the current resource manager is not an
     * {@link IReloadableResourceManager} (unexpected), logs a warning and
     * returns without registering.</p>
     */
    public static void register() {
        if (registered) {
            return;
        }

        IResourceManager resourceManager = Minecraft.getMinecraft().getResourceManager();
        if (resourceManager instanceof IReloadableResourceManager) {
            ((IReloadableResourceManager) resourceManager).registerReloadListener(LISTENER);
            registered = true;
            LOGGER.info("CgShaderReloadHook registered with IReloadableResourceManager");
        } else {
            LOGGER.warn("Resource manager is not IReloadableResourceManager ({}), " +
                    "shader reload hook NOT registered",
                    resourceManager != null ? resourceManager.getClass().getName() : "null");
        }
    }
}
