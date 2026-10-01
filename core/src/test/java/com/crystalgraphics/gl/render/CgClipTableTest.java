package com.crystalgraphics.gl.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;

/**
 * {@link CgClipTable}'s two promises a shader cannot check for itself: an entry maps a fragment back into its box's
 * own space under any pose, and entries chain no deeper than {@link CgClipTable#MAX_DEPTH}.
 */
public class CgClipTableTest {

    private static final float TARGET_HEIGHT = 1080f;
    private final CgClipTable table = new CgClipTable();
    private static final float[] ZEROS = new float[4];

    @Test
    public void aFragmentMapsBackToTheLocalPointThatProducedIt() throws Exception {
        Matrix4f pose = new Matrix4f().translate(300f, 200f, 0f).rotateZ((float) Math.toRadians(30)).scale(2f, 1.5f, 1f);
        int entry = table.add(0, pose, TARGET_HEIGHT, 0f, 0f, 120f, 80f, ZEROS, ZEROS, null);

        Vector3f onTarget = pose.transformPosition(new Vector3f(40f, 25f, 0f));
        float[] local = toLocal(entry, onTarget.x, TARGET_HEIGHT - onTarget.y);

        assertEquals(40f, local[0], 1e-3f);
        assertEquals(25f, local[1], 1e-3f);
        assertEquals("a rotated edge is reconstructed wider", 1.5f, rows(entry)[7], 0f);
    }

    @Test
    public void anAxisAlignedPoseKeepsThePixelGridRamp() throws Exception {
        Matrix4f pose = new Matrix4f().translate(12.5f, 40f, 0f).scale(2f);
        int entry = table.add(0, pose, TARGET_HEIGHT, 0f, 0f, 50f, 50f, ZEROS, ZEROS, null);

        float[] local = toLocal(entry, 12.5f + 2f * 10f, TARGET_HEIGHT - (40f + 2f * 30f));

        assertEquals(10f, local[0], 1e-4f);
        assertEquals(30f, local[1], 1e-4f);
        assertEquals(1f, rows(entry)[7], 0f);
    }

    @Test
    public void clipsChainToMaxDepthAndUnwindThroughTheirParents() {
        Matrix4f pose = new Matrix4f();
        int parent = 0;
        int[] chain = new int[CgClipTable.MAX_DEPTH];
        for (int i = 0; i < chain.length; i++) {
            chain[i] = table.add(parent, pose, TARGET_HEIGHT, 0f, 0f, 10f, 10f, ZEROS, ZEROS, null);
            parent = chain[i];
        }

        assertEquals("one past the deepest a draw can carry", -1,
                table.add(parent, pose, TARGET_HEIGHT, 0f, 0f, 10f, 10f, ZEROS, ZEROS, null));
        for (int i = chain.length - 1; i > 0; i--) assertEquals(chain[i - 1], table.parent(chain[i]));
        assertEquals(0, table.parent(chain[0]));
    }

    @Test
    public void aPoseThatCollapsesTheBoxIsRefused() {
        Matrix4f flat = new Matrix4f().scale(1f, 0f, 1f);
        assertEquals(-1, table.add(0, flat, TARGET_HEIGHT, 0f, 0f, 10f, 10f, ZEROS, ZEROS, null));
    }

    /** The shader's mapping, over the table's own rows. */
    private float[] toLocal(int entry, float fragX, float fragY) throws Exception {
        float[] r = rows(entry);
        return new float[] {
                r[0] * fragX + r[1] * fragY + r[2],
                r[4] * fragX + r[5] * fragY + r[6],
        };
    }

    /** toLocal0 then toLocal1 of {@code entry}, as uploaded. */
    private float[] rows(int entry) throws Exception {
        Field field = CgClipTable.class.getDeclaredField("entries");
        field.setAccessible(true);
        float[] entries = (float[]) field.get(table);
        float[] rows = new float[8];
        System.arraycopy(entries, entry * 32, rows, 0, 8);
        return rows;
    }
}
