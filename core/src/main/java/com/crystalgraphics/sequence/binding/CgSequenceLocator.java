package com.crystalgraphics.sequence.binding;

import javax.annotation.Nullable;

import org.joml.Quaternionf;
import com.crystalgraphics.sequence.CgSequenceTarget;

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

    private static final int TRANSLATION = 0, ROTATION = 1, SCALE = 2, EULER_DEGREES = 3;
    private static final float TO_RADIANS = (float) (Math.PI / 180);

    private final String id;
    private final float[] translation = new float[3], rotation = new float[3], scale = {1, 1, 1};
    /** Its file's turn where its units write quaternions, which no track keys; null where its angles are its turn. */
    @Nullable
    private Quaternionf restTurn;

    public CgSequenceLocator(String id) {
        this.id = id;
    }

    /** Its rest, from its file, in host units: where it stands until a transform track keys it. */
    public void rest(float[] t, float[] r, float[] s) {
        System.arraycopy(t, 0, translation, 0, 3);
        System.arraycopy(r, 0, rotation, 0, 3);
        System.arraycopy(s, 0, scale, 0, 3);
    }

    /** Its turn fixed at {@code turn}, a quaternion its file wrote: its rotation is then keyed by no track. */
    public void restTurn(Quaternionf turn) {
        restTurn = turn;
    }

    /** Its fixed turn, or null where its {@link #rotation()} angles are its turn. */
    @Nullable
    Quaternionf fixedTurn() {
        return restTurn;
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
            case "rotation" -> restTurn == null ? ROTATION : -1;
            // CC2's node rotation in degrees, as a prefab's node answers it.
            case "rotationEulerDeg" -> restTurn == null ? EULER_DEGREES : -1;
            case "scale" -> SCALE;
            default -> -1;
        };
    }

    @Override
    public void set(int slot, float[] values, int mask) {
        float[] to = slot == TRANSLATION ? translation : slot == ROTATION || slot == EULER_DEGREES ? rotation : scale;
        float k = slot == EULER_DEGREES ? TO_RADIANS : 1f;
        for (int i = 0; i < 3; i++) if ((mask & 1 << i) != 0) to[i] = values[i] * k;
    }

    @Override
    public void get(int slot, float[] out) {
        System.arraycopy(slot == TRANSLATION ? translation : slot == ROTATION || slot == EULER_DEGREES ? rotation : scale, 0, out, 0, 3);
        if (slot == EULER_DEGREES) for (int i = 0; i < 3; i++) out[i] /= TO_RADIANS;
    }

    @Override
    public String toString() {
        return "locator " + id;
    }
}
