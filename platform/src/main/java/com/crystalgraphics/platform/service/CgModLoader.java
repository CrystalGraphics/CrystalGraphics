package com.crystalgraphics.platform.service;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgService;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The mod loader the engine runs under: which loader, its version, whether this is a dev run, and every loaded mod
 * with its id, version, name and roots. Ask it anywhere, on either side; a host answers it through {@link Host}.
 *
 * <pre>{@code
 * if (CgModLoader.isLoaded("geckolib")) GeckoPoses.install();
 * CgModLoader.Mod rpg = CgModLoader.mod("rpgcore");                 // null where it is not loaded
 * LOGGER.info("on {} {}, rpgcore {}", CgModLoader.name(), CgModLoader.version(), rpg == null ? "-" : rpg.version());
 * for (Path root : CgModLoader.roots()) mount(root);                 // every mod's assets/ lives in one of these
 * }</pre>
 *
 * A host, once on both sides, as it registers the platform:
 *
 * <pre>{@code
 * CgPlatform.provide(CgModLoader.HOST, ModLoaderFabric.INSTANCE);
 * }</pre>
 *
 * <ul>
 *   <li>The list is the loader's after mod discovery, complete before any mod is constructed; it never changes.</li>
 *   <li>Without a host (the harness, a test) the loader is {@code "none"}, with no mods and no roots.</li>
 *   <li>A version the loader does not report is {@code ""}.</li>
 * </ul>
 */
public final class CgModLoader {

    /** What a host answers: its loader and its mods. One class per era, {@code ModLoader<Era>}. */
    public interface Host {
        /** The loader: {@code "forge"}, {@code "neoforge"}, {@code "fabric"}; {@code "none"} without one. */
        String name();

        /** The loader's version, as it reports it; {@code ""} where it reports none. */
        String version();

        /** Whether this is a dev run: names unmapped, mods from source folders. */
        boolean development();

        /** Every loaded mod, in the loader's order. Asked once and kept: answer what the loader discovered. */
        List<Mod> mods();

        /**
         * Every mod's root, where its {@code assets/} sits: a directory, a path inside a jar the loader holds open, or
         * the jar file, which the engine opens. Usually the mods' roots; a loader that cannot say which mod a root is
         * answers them here alone.
         */
        default List<Path> roots() {
            List<Path> out = new ArrayList<>();
            for (Mod m : mods()) out.addAll(m.roots());
            return out;
        }

        Host NONE = new Host() {
            @Override public String name() { return "none"; }
            @Override public String version() { return ""; }
            @Override public boolean development() { return false; }
            @Override public List<Mod> mods() { return Collections.emptyList(); }
        };
    }

    /** One loaded mod: its id, version and display name as the loader reports them, and its roots. */
    public record Mod(String id, String version, String name, List<Path> roots) {
    }

    /** The slot a host fills. */
    public static final CgService<Host> HOST = CgService.of("crystalgraphics:mod_loader", Host.NONE);

    private static volatile List<Mod> mods;

    private CgModLoader() {
    }

    private static Host host() {
        return CgPlatform.get(HOST);
    }

    /** The loader's name: {@code "forge"}, {@code "neoforge"}, {@code "fabric"}, or {@code "none"}. */
    public static String name() {
        return host().name();
    }

    /** The loader's version, or {@code ""}. */
    public static String version() {
        return host().version();
    }

    /** Whether this is a dev run. */
    public static boolean development() {
        return host().development();
    }

    /** Every loaded mod, asked of the host once. */
    public static List<Mod> mods() {
        List<Mod> m = mods;
        if (m != null) return m;
        Host h = host();
        m = Collections.unmodifiableList(new ArrayList<>(h.mods()));
        if (h != Host.NONE) mods = m;
        return m;
    }

    /** The loaded mod {@code id}, or null. */
    public static Mod mod(String id) {
        for (Mod m : mods()) if (m.id().equals(id)) return m;
        return null;
    }

    /** Whether mod {@code id} is loaded. */
    public static boolean isLoaded(String id) {
        return mod(id) != null;
    }

    /** Every mod's root, where its {@code assets/} sits. */
    public static List<Path> roots() {
        return host().roots();
    }
}
