package io.github.somehussar.crystalgraphics.api.material;

/**
 * Render queue ordering values for CrystalShader materials.
 *
 * <p>Values mirror Unity's canonical queue numbers for ecosystem familiarity:</p>
 * <ul>
 *   <li>{@link #BACKGROUND} (1000) — skyboxes and other far-background draws</li>
 *   <li>{@link #GEOMETRY} (2000) — standard opaque geometry (default)</li>
 *   <li>{@link #ALPHA_TEST} (2450) — alpha-tested (cut-out) geometry</li>
 *   <li>{@link #TRANSPARENT} (3000) — blended transparent geometry</li>
 *   <li>{@link #OVERLAY} (4000) — UI, lens flares, post overlays</li>
 * </ul>
 *
 * <p>TODO: A future {@code CgFrameRenderer} orchestrator will use
 * {@code CgMaterial.getRenderQueue()} to sort draw calls globally
 * (opaque → alpha-test → transparent → overlay).
 * Currently this value is parsed and stored but has no automatic sorting effect.</p>
 */
public enum CgRenderQueue {

    BACKGROUND(1000),
    GEOMETRY(2000),
    ALPHA_TEST(2450),
    TRANSPARENT(3000),
    OVERLAY(4000);

    private final int value;

    CgRenderQueue(int value) {
        this.value = value;
    }

    /** Returns the numeric queue priority for this slot. */
    public int getValue() {
        return value;
    }

    /**
     * Looks up a queue by its name, case-insensitively.
     * Accepts both {@code "AlphaTest"} and {@code "ALPHA_TEST"} spellings
     * (the enum name and the Unity-style camel variant are both tried).
     *
     * @param name the queue name to look up
     * @return the matching enum constant
     * @throws IllegalArgumentException if no constant matches {@code name}
     */
    public static CgRenderQueue fromName(String name) {
        if (name == null) throw new IllegalArgumentException("CgRenderQueue name must not be null");
        String upper = name.toUpperCase(java.util.Locale.ROOT).replace('-', '_');
        for (CgRenderQueue q : values()) {
            if (q.name().equals(upper)) return q;
        }
        // Also accept compact PascalCase variants like "AlphaTest" → "ALPHA_TEST"
        String spacedUpper = splitCamel(name).toUpperCase(java.util.Locale.ROOT).replace(' ', '_');
        for (CgRenderQueue q : values()) {
            if (q.name().equals(spacedUpper)) return q;
        }
        throw new IllegalArgumentException("Unknown render queue: '" + name + "'");
    }

    /**
     * Returns the queue whose {@link #getValue()} is closest to {@code value}.
     * On a tie, the lower-valued queue wins.
     *
     * @param value the numeric queue value
     * @return the closest named queue constant
     */
    public static CgRenderQueue fromValue(int value) {
        CgRenderQueue best = GEOMETRY;
        int bestDist = Math.abs(GEOMETRY.value - value);
        for (CgRenderQueue q : values()) {
            int dist = Math.abs(q.value - value);
            if (dist < bestDist) {
                best = q;
                bestDist = dist;
            }
        }
        return best;
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
