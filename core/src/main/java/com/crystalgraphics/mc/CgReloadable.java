package com.crystalgraphics.mc;

/**
 * A loaded asset that reloads itself when a file it read changes: {@link CgAssetReloader} keeps which files each one
 * read ({@link CgAssetReloader#track}), and refreshes only those that read an edited file. Material shaders, kernels,
 * raw shaders and cached textures track themselves; a mod's own asset does the same.
 *
 * <pre>{@code
 * final class Palette implements CgReloadable {
 *     Palette(String path) {
 *         this.path = path;
 *         refresh();
 *     }
 *
 *     public void refresh() {
 *         try (CgIO.Reads reads = CgIO.recordReads()) {    // every file CgIO opens in here
 *             colours = parse(CgIO.loadSource(path));
 *             CgAssetReloader.track(this, reads.paths());
 *         }
 *     }
 * }
 * }</pre>
 *
 * <ul>
 *   <li>Held weakly: an asset nothing else holds drops out of the index. Call {@link CgAssetReloader#untrack} when one
 *       is deleted before then.</li>
 *   <li>Track again after every load: a load that reads different includes replaces the files it is found by.</li>
 *   <li>Tracked by identity: never override {@code equals} on one.</li>
 * </ul>
 */
@FunctionalInterface
public interface CgReloadable {

    /**
     * Reads its files again, now or at its next use. Render thread. A failure throws, and the asset keeps its last good
     * version: the reloader logs the exception and goes on to the next.
     */
    void refresh();
}
