package com.crystalgraphics.platform.service;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Where a product keeps what it writes: one {@code <product>/} tree under a base directory, stated once. CrystalGraphics
 * is one product ({@code crystalgraphics}), a consumer another; each gets the same shape.
 *
 * <pre>{@code
 * CgStorageLayout layout = CgStorageLayout.of("mymod");
 * Path config = layout.configIn(CgGameDirectory.game());     // <game>/mymod/workspace-config   durable
 * Path cache  = layout.cacheIn(CgGameDirectory.game());      // <game>/mymod/cache              disposable
 * }</pre>
 *
 * <ul>
 *   <li>{@code cache/} is disposable: deleting it at any moment must lose nothing.</li>
 *   <li>A product adds its own folders under {@code <product>/}; the layout names only these two.</li>
 *   <li>The same tree sits at every base (a game directory, a world, a dedicated server's directory).</li>
 *   <li>Holds no state and names no host, so a dedicated server can use it.</li>
 * </ul>
 */
public final class CgStorageLayout {

    /** Durable: preferences, arrangements, sessions, backups, history. */
    public static final String CONFIG = "workspace-config";

    /** Derived: compiled output, indexes, thumbnails. Deletable at any moment. */
    public static final String CACHE = "cache";

    private final String root;

    private CgStorageLayout(String root) {
        this.root = Objects.requireNonNull(root, "product");
    }

    /** The layout of {@code product}, the folder name it writes under a base. */
    public static CgStorageLayout of(String product) {
        return new CgStorageLayout(product);
    }

    /** The product's folder name. */
    public String root() {
        return root;
    }

    /** {@code <base>/<product>}. */
    public Path rootIn(Path base) {
        return base.resolve(root);
    }

    /** {@code <base>/<product>/workspace-config}. */
    public Path configIn(Path base) {
        return rootIn(base).resolve(CONFIG);
    }

    /** {@code <base>/<product>/cache}. */
    public Path cacheIn(Path base) {
        return rootIn(base).resolve(CACHE);
    }
}
