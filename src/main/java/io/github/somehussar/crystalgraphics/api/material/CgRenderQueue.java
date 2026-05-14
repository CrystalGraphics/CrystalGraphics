package io.github.somehussar.crystalgraphics.api.material;

import java.util.Locale;

/**
 * Render queue ordering constants for CrystalShader materials.
 *
 * <p>Integer values mirror Unity's canonical queue numbers for ecosystem familiarity.
 * Any integer value is valid — the named constants are the common anchor points:</p>
 * <ul>
 *   <li>{@link #BACKGROUND} (1000) — skyboxes and other far-background draws</li>
 *   <li>{@link #GEOMETRY} (2000) — standard opaque geometry (default)</li>
 *   <li>{@link #ALPHA_TEST} (2450) — alpha-tested (cut-out) geometry</li>
 *   <li>{@link #TRANSPARENT} (3000) — blended transparent geometry</li>
 *   <li>{@link #OVERLAY} (4000) — UI, lens flares, post overlays</li>
 * </ul>
 *
 * <p>Pass routing uses the threshold constants, not exact equality — any queue value
 * {@code >= TRANSPARENT_THRESHOLD} is treated as transparent, allowing authors to use
 * intermediate values like {@code 2600} without special-casing.</p>
 */
public final class CgRenderQueue {

    private CgRenderQueue() {}

    // ── Named queue anchors (Unity-compatible) ────────────────────────────────

    public static final int BACKGROUND  = 1000;
    public static final int GEOMETRY    = 2000;
    public static final int ALPHA_TEST  = 2450;
    public static final int TRANSPARENT = 3000;
    public static final int OVERLAY     = 4000;

    // ── Threshold constants for pass-bucket routing ───────────────────────────

    /**
     * Queue values {@code >= ALPHA_TEST_THRESHOLD} and {@code < TRANSPARENT_THRESHOLD}
     * are routed to the alpha-test pass bucket.
     */
    public static final int ALPHA_TEST_THRESHOLD  = 2450;

    /**
     * Queue values {@code >= TRANSPARENT_THRESHOLD} and {@code < OVERLAY_THRESHOLD}
     * are routed to the transparent pass bucket.
     * Matches Unity's opaque/transparent boundary.
     */
    public static final int TRANSPARENT_THRESHOLD = 2500;

    /**
     * Queue values {@code >= OVERLAY_THRESHOLD} are routed to the overlay pass bucket.
     */
    public static final int OVERLAY_THRESHOLD     = 4000;

    // ── Lookup utilities ──────────────────────────────────────────────────────

    /**
     * Maps a queue name to its integer value, accepting both the Unity-style PascalCase
     * variant ({@code "AlphaTest"}) and the SCREAMING_SNAKE_CASE form ({@code "ALPHA_TEST"}).
     * The lookup is case-insensitive.
     *
     * @param name the queue name; must not be null
     * @return the corresponding int queue value
     * @throws IllegalArgumentException if {@code name} does not match any named constant
     */
    public static int fromName(String name) {
        if (name == null) throw new IllegalArgumentException("CgRenderQueue name must not be null");
        String upper = name.toUpperCase(Locale.ROOT).replace('-', '_');
        // Direct SCREAMING_SNAKE_CASE match
        switch (upper) {
            case "BACKGROUND":  return BACKGROUND;
            case "GEOMETRY":    return GEOMETRY;
            case "ALPHA_TEST":  return ALPHA_TEST;
            case "TRANSPARENT": return TRANSPARENT;
            case "OVERLAY":     return OVERLAY;
            default: break;
        }
        // Also accept PascalCase variants like "AlphaTest" → "ALPHA_TEST"
        String spacedUpper = splitCamel(name).toUpperCase(Locale.ROOT).replace(' ', '_');
        switch (spacedUpper) {
            case "BACKGROUND":  return BACKGROUND;
            case "GEOMETRY":    return GEOMETRY;
            case "ALPHA_TEST":  return ALPHA_TEST;
            case "TRANSPARENT": return TRANSPARENT;
            case "OVERLAY":     return OVERLAY;
            default:
                throw new IllegalArgumentException("Unknown render queue: '" + name + "'");
        }
    }

    /**
     * Identity function — returns {@code value} unchanged. Provided for call-site
     * compatibility with code that previously called {@code CgRenderQueue.fromValue(int)}.
     *
     * @param value the numeric queue value
     * @return the same value
     */
    public static int fromValue(int value) {
        return value;
    }

    /** Inserts spaces before each uppercase letter that follows a lowercase letter. */
    private static String splitCamel(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (i > 0 && Character.isUpperCase(c) && Character.isLowerCase(s.charAt(i - 1))) {
                sb.append(' ');
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
