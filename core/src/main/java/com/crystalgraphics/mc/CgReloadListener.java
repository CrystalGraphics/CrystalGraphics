package com.crystalgraphics.mc;

/**
 * Hook for code that caches assets but lives <em>outside</em> CrystalGraphics, so it can be dropped
 * by the same resource reload {@link CgAssetReloader} already coordinates.
 *
 * <p>CrystalGraphics' own caches — textures, shaders, materials, text layouts — are enumerated
 * directly inside {@code CgAssetReloader}. This interface exists for everything the engine cannot
 * know about: a consuming library (CrystalGUI's stylesheets and sprite packs), a mod's own asset
 * cache. It is the reload counterpart of {@code CgLifecycleListener}, and registered the same way.</p>
 *
 * <p>Listeners run <b>after</b> CrystalGraphics has reloaded its own assets, so a listener may read
 * a texture or material and get the new one. An exception thrown by one is logged and swallowed: a
 * consumer's failing cache must not stop the others from being dropped.</p>
 */
@FunctionalInterface
public interface CgReloadListener {

    /** Drop whatever was read from disk. Called on F3+T and on a resource pack change. */
    void onReload();
}
