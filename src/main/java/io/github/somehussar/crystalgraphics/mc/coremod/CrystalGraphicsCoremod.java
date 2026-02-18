package io.github.somehussar.crystalgraphics.mc.coremod;

import cpw.mods.fml.relauncher.IFMLLoadingPlugin;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URL;
import java.util.Locale;
import java.util.Map;

/**
 * Always-on FML coremod that installs CrystalGraphics' bytecode transformer.
 *
 * <p>This coremod registers {@link CrystalGraphicsTransformer} as an ASM class
 * transformer, which redirects OpenGL state-mutating calls
 * ({@code glBindFramebuffer}, {@code glUseProgram}, etc.) through the
 * CrystalGraphics state-tracking layer.</p>
 *
 * <h3>Angelica Gap-Only Mode</h3>
 * <p>When the Angelica mod is detected on the classpath (or forced via system
 * property), the coremod switches to <em>gap-only mode</em>. In this mode the
 * transformer only patches call sites that Angelica's GLStateManager does not
 * already cover, avoiding double-interception.</p>
 *
 * <h3>System Properties</h3>
 * <ul>
 *   <li>{@code crystalgraphics.redirector.disable} &mdash; if {@code true}, disables
 *       the transformer entirely (no bytecode changes are applied).</li>
 *   <li>{@code crystalgraphics.redirector.verbose} &mdash; if {@code true}, enables
 *       additional diagnostic logging during initialization.</li>
 *   <li>{@code crystalgraphics.redirector.forceAngelica} &mdash; overrides automatic
 *       Angelica detection.  Accepts {@code true}/{@code false},
 *       {@code 1}/{@code 0}, {@code yes}/{@code no}, {@code on}/{@code off}.
 *       When set, the detected value is ignored and this value is used
 *       instead.</li>
 * </ul>
 *
 * <p>This class is not thread-safe during {@link #injectData}, but the
 * volatile fields it publishes ({@link #angelicaPresent},
 * {@link #gapOnlyMode}) are safe to read from any thread after FML
 * initialization completes.</p>
 */
@IFMLLoadingPlugin.Name("CrystalGraphicsRedirector")
@IFMLLoadingPlugin.MCVersion("1.7.10")
@IFMLLoadingPlugin.SortingIndex(1001)
public final class CrystalGraphicsCoremod implements IFMLLoadingPlugin {

    /** Logger used for all redirector-related messages during early bootstrap. */
    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphicsRedirector");

    /** Fully-qualified class name of the ASM transformer registered by this coremod. */
    private static final String TRANSFORMER_CLASS = "io.github.somehussar.crystalgraphics.mc.coremod.CrystalGraphicsTransformer";

    /**
     * Classpath resource path used to detect Angelica's presence.
     * If this resource is loadable, Angelica's GLStateManager is assumed to be
     * on the classpath and gap-only mode is activated.
     */
    private static final String ANGELICA_GLSM_RESOURCE = "com/gtnewhorizons/angelica/glsm/GLStateManager.class";

    /**
     * When {@code true}, the transformer is not registered and no bytecode
     * modifications are applied.  Set via the
     * {@code crystalgraphics.redirector.disable} system property.
     */
    private static final boolean DISABLED = Boolean.getBoolean("crystalgraphics.redirector.disable");

    /**
     * When {@code true}, extra diagnostic messages are logged during
     * initialization and transformation.  Set via the
     * {@code crystalgraphics.redirector.verbose} system property.
     *
     * <p>Package-private so that {@link CrystalGraphicsTransformer} can read it
     * directly without a method-call indirection on every class load.</p>
     */
    static final boolean VERBOSE = Boolean.getBoolean("crystalgraphics.redirector.verbose");

    /**
     * Optional transformed-class prefix filter for verbose rewrite logging.
     *
     * <p>When {@link #VERBOSE} is enabled, the transformer can potentially log
     * rewrites for many classes. This property restricts per-class rewrite logs
     * to only classes whose transformed name starts with this prefix.</p>
     *
     * <p>System property: {@code crystalgraphics.redirector.verbosePrefix}
     * (example: {@code net.minecraft.}).</p>
     */
    static final String VERBOSE_PREFIX = System.getProperty("crystalgraphics.redirector.verbosePrefix");

    /**
     * Raw string value of the {@code crystalgraphics.redirector.forceAngelica}
     * system property, or {@code null} if not set.  Parsed by
     * {@link #parseBooleanOverride(String)} to override automatic Angelica
     * detection.
     */
    private static final String FORCE_ANGELICA = System.getProperty("crystalgraphics.redirector.forceAngelica");

    /**
     * Whether Angelica's GLStateManager was detected (or forced) on the
     * classpath.  Published with {@code volatile} so other threads can safely
     * read this flag after FML initialization completes.
     */
    private static volatile boolean angelicaPresent;

    /**
     * Whether the transformer operates in gap-only mode, patching only the
     * call sites that Angelica does not cover.  Currently mirrors
     * {@link #angelicaPresent} &mdash; when Angelica is present, gap-only mode is
     * active.  Published with {@code volatile} for cross-thread visibility.
     */
    private static volatile boolean gapOnlyMode;

    static {
        if (!DISABLED) {
            LOGGER.info("CrystalGraphicsRedirector: Initializing...");
        }
    }

    /**
     * Returns the ASM transformer class names to register.
     *
     * <p>If the redirector is disabled via system property, an empty array is
     * returned and no bytecode transformations are applied.</p>
     *
     * @return a single-element array containing the transformer class name,
     *         or an empty array if the redirector is disabled
     */
    @Override
    public String[] getASMTransformerClass() {
        if (DISABLED) {
            if (VERBOSE) {
                LOGGER.info("CrystalGraphicsRedirector: Disabled by system property");
            }
            return new String[0];
        }
        return new String[] { TRANSFORMER_CLASS };
    }

    /**
     * Returns the mod container class name for this coremod.
     *
     * <p>CrystalGraphics does not register a separate mod container through the
     * coremod &mdash; the mod container is registered via the standard
     * {@code @Mod} annotation on its main class.</p>
     *
     * @return {@code null}, as no mod container is provided by this coremod
     */
    @Override
    public String getModContainerClass() {
        return null;
    }

    /**
     * Returns the setup class for call-back during coremod initialization.
     *
     * <p>No additional setup is required beyond what {@link #injectData} performs.</p>
     *
     * @return {@code null}, as no setup class is needed
     */
    @Override
    public String getSetupClass() {
        return null;
    }

    /**
     * Receives FML injection data and performs Angelica detection.
     *
     * <p>This method detects whether Angelica is present on the classpath
     * (via {@link #detectAngelicaByResource()}), respects the
     * {@code forceAngelica} system property override, and determines whether
     * gap-only mode should be activated.  Results are published to the
     * volatile fields {@link #angelicaPresent} and {@link #gapOnlyMode},
     * and also stored into the {@code data} map for downstream consumers.</p>
     *
     * @param data mutable map of injection data provided by FML; this method
     *             adds keys {@code crystalgraphics.redirector.angelicaPresent}
     *             and {@code crystalgraphics.redirector.gapOnlyMode}
     */
    @Override
    public void injectData(Map<String, Object> data) {
        final boolean detectedAngelica = detectAngelicaByResource();
        final Boolean forcedAngelica = parseBooleanOverride(FORCE_ANGELICA);

        angelicaPresent = forcedAngelica != null ? forcedAngelica.booleanValue() : detectedAngelica;
        gapOnlyMode = angelicaPresent;

        data.put("crystalgraphics.redirector.angelicaPresent", Boolean.valueOf(angelicaPresent));
        data.put("crystalgraphics.redirector.gapOnlyMode", Boolean.valueOf(gapOnlyMode));

        if (DISABLED) {
            LOGGER.info("CrystalGraphicsRedirector: Disabled. Transformer not installed.");
            return;
        }

        if (forcedAngelica != null) {
            LOGGER.info(
                "CrystalGraphicsRedirector: forceAngelica property set to '{}', detected={}, effective={}",
                FORCE_ANGELICA,
                Boolean.valueOf(detectedAngelica),
                Boolean.valueOf(angelicaPresent)
            );
        } else if (VERBOSE) {
            LOGGER.info(
                "CrystalGraphicsRedirector: Angelica resource detected={}",
                Boolean.valueOf(detectedAngelica)
            );
        }

        LOGGER.info(
            "CrystalGraphicsRedirector: Selected mode={} (Angelica present={})",
            gapOnlyMode ? "gap-only" : "full redirect",
            Boolean.valueOf(angelicaPresent)
        );
    }

    /**
     * Returns the access transformer configuration class.
     *
     * <p>CrystalGraphics does not use access transformers through this
     * coremod entry point.</p>
     *
     * @return {@code null}, as no access transformer class is provided
     */
    @Override
    public String getAccessTransformerClass() {
        return null;
    }

    /**
     * Returns whether Angelica's GLStateManager was detected (or forced) on the
     * classpath.
     *
     * <p>This value is only reliable after {@link #injectData} has been called
     * by FML during bootstrap.</p>
     *
     * @return {@code true} if Angelica is present, {@code false} otherwise
     */
    public static boolean isAngelicaPresent() {
        return angelicaPresent;
    }

    /**
     * Returns whether the transformer is operating in gap-only mode.
     *
     * <p>In gap-only mode, only call sites not already intercepted by Angelica's
     * GLStateManager are patched.  This value is only reliable after
     * {@link #injectData} has been called by FML during bootstrap.</p>
     *
     * @return {@code true} if gap-only mode is active, {@code false} for full
     *         redirect mode
     */
    public static boolean isGapOnlyMode() {
        return gapOnlyMode;
    }

    /**
     * Detects Angelica's presence by attempting to load its GLStateManager
     * class as a classpath resource.
     *
     * <p>This approach works even during early coremod bootstrap, before
     * classes have been loaded or transformed, because it queries the raw
     * classpath rather than triggering class loading.</p>
     *
     * @return {@code true} if the Angelica GLStateManager resource is found
     *         on the classpath, {@code false} otherwise
     */
    private static boolean detectAngelicaByResource() {
        final ClassLoader loader = CrystalGraphicsCoremod.class.getClassLoader();
        final URL resource = loader != null ? loader.getResource(ANGELICA_GLSM_RESOURCE)
            : ClassLoader.getSystemResource(ANGELICA_GLSM_RESOURCE);
        return resource != null;
    }

    /**
     * Parses a string value as a lenient boolean override.
     *
     * <p>Accepts several common boolean representations (case-insensitive,
     * trimmed):</p>
     * <ul>
     *   <li>Truthy: {@code "true"}, {@code "1"}, {@code "yes"}, {@code "on"}</li>
     *   <li>Falsy:  {@code "false"}, {@code "0"}, {@code "no"}, {@code "off"}</li>
     * </ul>
     *
     * <p>If the value is {@code null}, returns {@code null} (no override).
     * If the value is non-null but not recognized, a warning is logged and
     * {@code null} is returned.</p>
     *
     * @param value the raw string to parse, or {@code null}
     * @return {@link Boolean#TRUE} or {@link Boolean#FALSE} if recognized,
     *         or {@code null} if no valid override was provided
     */
    private static Boolean parseBooleanOverride(String value) {
        if (value == null) {
            return null;
        }

        final String normalized = value.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized) || "on".equals(normalized)) {
            return Boolean.TRUE;
        }
        if ("false".equals(normalized) || "0".equals(normalized) || "no".equals(normalized) || "off".equals(normalized)) {
            return Boolean.FALSE;
        }

        LOGGER.warn(
            "CrystalGraphicsRedirector: Ignoring invalid forceAngelica value '{}'. Use true/false.",
            value
        );
        return null;
    }
}
