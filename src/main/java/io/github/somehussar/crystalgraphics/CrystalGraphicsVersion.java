package io.github.somehussar.crystalgraphics;

import com.github.bsideup.jabel.Desugar;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;
import io.github.somehussar.crystalgraphics.api.CgCapabilities;
import io.github.somehussar.crystalgraphics.mc.OpenGLVersionMismatchException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Public CrystalGraphics API for declaring mod OpenGL requirements.
 *
 * <p>Dependent mods call {@link #setMinimumRequiredOpenGL(int, int)} from
 * their pre-init hook to register the minimum OpenGL version they need.
 * CrystalGraphics aggregates all registrations and validates them against the
 * current client GL version when {@link #processAllRequirements()}
 * is invoked.</p>
 *
 * <p>If any requirement is not met, this class throws a client-side
 * {@code CustomModLoadingErrorDisplayException} implementation so Forge shows
 * the native loading error GUI rather than a raw crash screen.</p>
 */
public final class CrystalGraphicsVersion {

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");
    private static final Map<String, ModOpenGlRequirement> REGISTERED_REQUIREMENTS = new LinkedHashMap<>();

    private CrystalGraphicsVersion() {
        throw new AssertionError("No instances");
    }

    /**
     * Registers the minimum OpenGL version required by the calling mod.
     *
     * <p>If called multiple times by the same mod, the highest requirement
     * wins. On dedicated server this method is a no-op.</p>
     *
     * @param major required major version (for example 3)
     * @param minor required minor version (for example 0)
     */
    public static void setMinimumRequiredOpenGL(int major, int minor) {
        if (major < 1 || minor < 0) {
            throw new IllegalArgumentException("Invalid OpenGL version: " + major + "." + minor);
        }

        if (FMLCommonHandler.instance().getSide().isServer()) {
            LOGGER.debug("setMinimumRequiredOpenGL({}.{}) ignored on dedicated server", major, minor);
            return;
        }

        ModOpenGlRequirement currentMod = resolveCallerMod();
        REGISTERED_REQUIREMENTS.put(currentMod.modKey,
                new ModOpenGlRequirement(currentMod.modKey, currentMod.modName, major, minor, currentMod.sourceFileName));
    }

    /**
     * String convenience overload for OpenGL requirement registration.
     *
     * <p>Accepts values like {@code "3.0"} or the beginning of a GL version
     * string and parses them using {@link CgCapabilities#parseGLVersion(String)}.</p>
     *
     * @param version string version expression
     */
    public static void setMinimumRequiredOpenGL(String version) {
        if (version == null || version.isEmpty()) {
            throw new IllegalArgumentException("OpenGL version string must not be null or empty");
        }
        int[] parsed = CgCapabilities.parseGLVersion(version);
        if (parsed[0] < 1) {
            throw new IllegalArgumentException("Cannot parse OpenGL version from: " + version);
        }
        setMinimumRequiredOpenGL(parsed[0], parsed[1]);
    }

    /**
     * Validates all registered requirements against the current client GL version.
     *
     * <p>Call this once after mods have had a chance to register requirements.
     * On mismatch, throws a Forge custom loading error exception that produces
     * a user-readable incompatibility screen.</p>
     */
    public static void processAllRequirements() {
        if (FMLCommonHandler.instance().getSide().isServer() || REGISTERED_REQUIREMENTS.isEmpty()) {
            return;
        }

        List<ModOpenGlRequirement> requirements = new ArrayList<>(REGISTERED_REQUIREMENTS.values());

        String glVersionString = GL11.glGetString(GL11.GL_VERSION);
        int[] parsedDetected = CgCapabilities.parseGLVersion(glVersionString);
        int detectedMajor = parsedDetected[0];
        int detectedMinor = parsedDetected[1];

        List<ModOpenGlRequirement> failures = new ArrayList<>();
        for (ModOpenGlRequirement requirement : requirements) {
            boolean sufficient = !isVersionGreater(requirement.requiredMajor, requirement.requiredMinor, detectedMajor,
                    detectedMinor);
            if (!sufficient)
                failures.add(requirement);
        }

        if (!failures.isEmpty()) {
            throwClientOpenGLError(glVersionString, detectedMajor, detectedMinor, failures);
        }
    }

    private static boolean isVersionGreater(int leftMajor, int leftMinor, int rightMajor, int rightMinor) {
        if (leftMajor != rightMajor) {
            return leftMajor > rightMajor;
        }
        return leftMinor > rightMinor;
    }

    private static ModOpenGlRequirement resolveCallerMod() {
        try {
            ModContainer active = Loader.instance().activeModContainer();
            if (active != null) {
                String modId = active.getModId();
                String name = active.getName();
                String modKey = (modId != null && !modId.isEmpty()) ? modId : name;
                if (modKey == null || modKey.isEmpty()) {
                    modKey = "unknown";
                }
                if (name == null || name.isEmpty()) {
                    name = modKey;
                }
                String sourceFileName = resolveSourceFileName(active);
                return new ModOpenGlRequirement(modKey, name, 0, 0, sourceFileName);
            }
        } catch (Exception e) {
            LOGGER.debug("Could not resolve calling mod container", e);
        }
        return new ModOpenGlRequirement("unknown", "Unknown Mod", 0, 0, null);
    }

    /**
     * Extracts a short, human-readable source file/jar label from a
     * {@link ModContainer#getSource()} path.
     *
     * @param container the mod container to inspect
     * @return the file name (e.g. {@code "SomeMod-1.2.3.jar"}), or {@code null}
     *         if the source cannot be determined
     */
    private static String resolveSourceFileName(ModContainer container) {
        try {
            java.io.File source = container.getSource();
            if (source != null) {
                return source.getName();
            }
        } catch (Exception e) {
            LOGGER.debug("Could not resolve source file for mod container: {}", container.getModId(), e);
        }
        return null;
    }

    private static void throwClientOpenGLError(String glVersionString, int detectedMajor, int detectedMinor,
                                               List<ModOpenGlRequirement> failures) {
        throw new OpenGLVersionMismatchException(glVersionString, detectedMajor, detectedMinor, failures);
    }

    @Desugar
    public record ModOpenGlRequirement(String modKey, String modName, int requiredMajor, int requiredMinor,
                                       String sourceFileName) {
    }
}
