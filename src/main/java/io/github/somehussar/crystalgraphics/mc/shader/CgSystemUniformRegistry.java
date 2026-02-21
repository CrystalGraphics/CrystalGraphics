package io.github.somehussar.crystalgraphics.mc.shader;

import io.github.somehussar.crystalgraphics.api.shader.CgShader;
import io.github.somehussar.crystalgraphics.api.shader.CgUniformInjector;
import net.minecraft.client.Minecraft;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Global singleton registry for per-uniform shader injectors that apply to all shaders.
 *
 * <p>Each {@link CgUniformInjector} is registered under one or more uniform names. On every
 * {@link CgShader#bind()} call, all registered injectors run (via {@link #applyAll}) in
 * ascending priority order before per-shader bindings, so shaders can still override system
 * uniforms if needed.</p>
 *
 * <p><strong>Registration example:</strong></p>
 * <pre>{@code
 * // Register a custom uniform
 * CgSystemUniformRegistry.getInstance().register(
 *     CgUniformName.of("u_myCustom"),
 *     (shader, name) -> {
 *         int loc = shader.getUniformLocation(name);
 *         if (loc >= 0) shader.getProgram().setUniform1f(loc, myValue());
 *     }
 * );
 *
 * // Register with aliases — single lambda handles both names
 * CgSystemUniformRegistry.getInstance().register(
 *     CgUniformName.of("u_time", "time"),
 *     0,
 *     (shader, name) -> {
 *         int loc = shader.getUniformLocation(name);
 *         if (loc >= 0) shader.getProgram().setUniform1f(loc, CrystalGraphics.getTimeSinceStart());
 *     }
 * );
 *
 * // Replace an existing registration (force = true)
 * CgSystemUniformRegistry.getInstance().register(
 *     CgUniformName.of("u_time"),
 *     0,
 *     customTimeInjector,
 *     true
 * );
 *
 * // Unregister a single name
 * CgSystemUniformRegistry.getInstance().unregister("u_myCustom");
 * }</pre>
 *
 * <p><strong>Default uniforms provided out of the box:</strong></p>
 * <ul>
 *   <li>{@code u_time} / {@code time} — seconds since game start</li>
 *   <li>{@code u_viewportResolution} / {@code u_resolution} — OpenGL viewport size in pixels</li>
 * </ul>
 *
 * <p><strong>Thread safety:</strong> Not thread-safe. All operations must occur on the render thread.</p>
 *
 * <p><strong>Collision policy:</strong> When {@code force = false} and a name is already registered,
 * that name is skipped and a warning is logged. Other names in the same registration that are free will
 * still be registered.</p>
 */
public final class CgSystemUniformRegistry {

    private static final CgSystemUniformRegistry INSTANCE = new CgSystemUniformRegistry();

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");

    /** Monotonic start time captured at class-load; used to compute seconds-since-start without allocations. */
    private static final long START_NANOS = System.nanoTime();

    /** Internal registry mapping uniform names to their corresponding injector entries.
     * Uses LinkedHashMap to preserve registration order for consistent priority sorting.
     */ 
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();

    /** Lazily-built sorted snapshot of {@link #entries}; set to {@code null} when entries change. */
    private List<Entry> sortedCache = null;

    /**
     * Private constructor. Default injectors are registered here at singleton instantiation.
     */
    private CgSystemUniformRegistry() {
        registerDefaults();
    }

    /**
     * Returns the singleton instance of this registry.
     *
     * @return the singleton registry
     */
    public static CgSystemUniformRegistry getInstance() {
        return INSTANCE;
    }

    // -------------------------------------------------------------------------
    // Defaults
    // -------------------------------------------------------------------------

    /**
     * Registers the built-in default system uniform injectors.
     */
    private void registerDefaults() {
        // u_time / time — seconds since game start
        register(CgUniformName.of("u_time", "time"), (shader, name) -> {
            int loc = shader.getUniformLocation(name);
            if (loc >= 0)
                shader.getProgram().setUniform1f(loc, (System.nanoTime() - START_NANOS) / 1_000_000_000f);
        });

        // u_viewportResolution / u_resolution — GL viewport dimensions in pixels
        register(CgUniformName.of("u_resolution", "u_viewportResolution"), (shader, name) -> {
            int loc = shader.getUniformLocation(name);
            if (loc >= 0)
                shader.getProgram()
                      .setUniform2f(loc, Minecraft.getMinecraft().displayWidth, Minecraft.getMinecraft().displayHeight);
        });
    }

    // -------------------------------------------------------------------------
    // Registration
    // -------------------------------------------------------------------------

    /**
     * Registers an injector under the names in the given uniform name group,
     * with explicit priority and force control.
     *
     * <p>All names in the group share the same injector and priority.
     * The injector receives the specific uniform name it was invoked for, so
     * a single lambda can serve multiple names without duplicating logic.</p>
     *
     * <p>If {@code force} is {@code false} and a name in the group is already
     * registered, that name is skipped and a warning is logged. Other names in
     * the group that are free will still be registered.</p>
     *
     * <p>If {@code force} is {@code true}, any existing registration for each
     * name in the group is replaced silently.</p>
     *
     * @param uniformNames the uniform name group (primary + optional aliases)
     * @param priority     execution priority; lower values run first
     * @param injector     the injector to invoke; receives the matched uniform name
     * @param force        {@code true} to replace existing registrations; {@code false}
     *                     to skip already-registered names with a warning
     */
    public void register(CgUniformName uniformNames, int priority, CgUniformInjector injector, boolean force) {
        for (String name : uniformNames.names) {
            if (!force && entries.containsKey(name)) {
                LOGGER.warn("[CrystalGraphics] Uniform '{}' is already registered. Use force=true to replace.", name);
                continue;
            }
            entries.put(name, new Entry(name, priority, injector));
        }
        sortedCache = null;
    }

    /**
     * Registers an injector under the names in the given uniform name group, with explicit priority.
     * Does not replace existing registrations ({@code force = false}).
     *
     * @param uniformNames the uniform name group (primary + optional aliases)
     * @param priority     execution priority; lower values run first
     * @param injector     the injector to invoke; receives the matched uniform name
     */
    public void register(CgUniformName uniformNames, int priority, CgUniformInjector injector) {
        register(uniformNames, priority, injector, false);
    }

    /**
     * Registers an injector under the names in the given uniform name group,
     * with default priority {@code 0}.
     * Does not replace existing registrations ({@code force = false}).
     *
     * @param uniformNames the uniform name group (primary + optional aliases)
     * @param injector     the injector to invoke; receives the matched uniform name
     */
    public void register(CgUniformName uniformNames, CgUniformInjector injector) {
        register(uniformNames, 0, injector, false);
    }

    /**
     * Unregisters the injector for the given uniform name.
     *
     * <p>Only the exact name provided is removed. Aliases registered under different
     * names are unaffected. No-op if the name is not registered.</p>
     *
     * @param uniformName the uniform name to unregister
     */
    public void unregister(String uniformName) {
        entries.remove(uniformName);
        sortedCache = null;
    }

    // -------------------------------------------------------------------------
    // Application
    // -------------------------------------------------------------------------

    /**
     * Runs all registered injectors against the given shader, in ascending priority order.
     *
     * <p>Each injector receives the exact uniform name it was registered under, allowing
     * a single lambda to handle multiple aliases. Errors thrown by individual injectors
     * are caught and logged; they do not interrupt subsequent injectors.</p>
     *
     * <p>Called internally by {@link CgShaderImpl} on every bind. The shader is
     * guaranteed to be compiled when this method is invoked.</p>
     *
     * @param shader the bound managed shader (never null, always compiled)
     */
    void applyAll(CgShader shader) {
        if (entries.isEmpty()) {
            return;
        }
        List<Entry> sorted = sortedCache;
        if (sorted == null) {
            sorted = new ArrayList<>(entries.values());
            Collections.sort(sorted, Comparator.comparingInt(a -> a.priority));
            sortedCache = sorted;
        }
        for (Entry entry : sorted) {
            try {
                entry.injector.inject(shader, entry.uniformName);
            } catch (Exception e) {
                LOGGER.warn("[CrystalGraphics] CgUniformInjector for '{}' threw during bind; skipping",
                        entry.uniformName, e);
            }
        }
    }


    // -------------------------------------------------------------------------
    // Internal types
    // -------------------------------------------------------------------------

    /**
     * Container that groups a primary uniform name with its aliases.
     * All names in the group share the same injector and priority when registered.
     *
     * <p>Create instances using the static factory {@link #of(String, String...)}.</p>
     */
    public static final class CgUniformName {

        /** All names in registration order: {@code [primary, alias1, alias2, ...]}. */
        final String[] names;

        private CgUniformName(String primary, String... aliases) {
            int aliasCount = (aliases != null) ? aliases.length : 0;
            names = new String[1 + aliasCount];
            names[0] = primary;
            if (aliases != null) {
                System.arraycopy(aliases, 0, names, 1, aliases.length);
            }
        }

        /**
         * Creates a uniform name group with the given primary name and optional aliases.
         *
         * <p>All names in the group will be registered together with the same injector.</p>
         *
         * @param primary the primary uniform name (e.g., {@code "u_time"})
         * @param aliases optional additional names (e.g., {@code "time"})
         * @return a new uniform name group
         */
        public static CgUniformName of(String primary, String... aliases) {
            return new CgUniformName(primary, aliases);
        }
    }

    /**
     * Internal entry representing a single registered uniform injector binding.
     */
    private static final class Entry {

        final String uniformName;
        final int priority;
        final CgUniformInjector injector;

        Entry(String uniformName, int priority, CgUniformInjector injector) {
            this.uniformName = uniformName;
            this.priority = priority;
            this.injector = injector;
        }
    }
}
