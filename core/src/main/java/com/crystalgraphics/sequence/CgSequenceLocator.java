package com.crystalgraphics.sequence;

/**
 * A point a playing sequence owns: its locator's transform relative to its parent, as its transform tracks key it.
 * What a spawnable placed {@code at: { locator }} follows.
 *
 * <pre>{@code
 * CgSequenceLocator ball = player.locator(0);
 * float x = ball.translation()[0];        // after this frame's evaluation, in the host's units
 * }</pre>
 *
 * <ul>
 *   <li>The arrays are its own and live: read them, never keep them past the frame.</li>
 *   <li>Its rest, from the file, is where it starts; a transform track moves it from there.</li>
 * </ul>
 */
public final class CgSequenceLocator implements CgSequenceTarget {

    private static final int TRANSLATION = 0, ROTATION = 1, SCALE = 2;

    private final String id;
    private final float[] translation = new float[3], rotation = new float[3], scale = {1, 1, 1};

    CgSequenceLocator(String id) {
        this.id = id;
    }

    /** Its locator's id. */
    public String id() {
        return id;
    }

    public float[] translation() {
        return translation;
    }

    /** In radians, in its sequence's rotation convention. */
    public float[] rotation() {
        return rotation;
    }

    public float[] scale() {
        return scale;
    }

    @Override
    public int slot(String name) {
        return switch (name) {
            case "translation" -> TRANSLATION;
            case "rotation" -> ROTATION;
            case "scale" -> SCALE;
            default -> -1;
        };
    }

    @Override
    public void set(int slot, float[] values, int mask) {
        float[] to = slot == TRANSLATION ? translation : slot == ROTATION ? rotation : scale;
        for (int i = 0; i < 3; i++) if ((mask & 1 << i) != 0) to[i] = values[i];
    }

    @Override
    public String toString() {
        return "locator " + id;
    }
}
