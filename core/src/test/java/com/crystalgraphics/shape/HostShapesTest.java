package com.crystalgraphics.shape;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgBoxSink;
import com.crystalgraphics.platform.service.CgHostShapes;
import com.crystalgraphics.shape.bounds.CgAABB;
import com.sun.management.ThreadMXBean;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** {@link CgAABB} and {@link CgShape#of} over a host's shapes, here a fake host whose box is a record. */
public class HostShapesTest {

    /** The fake host's box: immutable, as a modern {@code AABB} is. */
    record HostBox(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
    }

    /** The fake host's block shape. */
    record HostShape(List<HostBox> boxes) {
    }

    static final class FakeHost implements CgHostShapes {
        @Override
        public void box(Object hostBox, CgBoxSink out) {
            HostBox b = (HostBox) hostBox;
            out.set(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ);
        }

        @Override
        public int boxes(Object hostShape, double[] out) {
            List<HostBox> boxes = hostShape instanceof HostShape s ? s.boxes : List.of((HostBox) hostShape);
            for (int i = 0; i < boxes.size() && i * 6 + 6 <= out.length; i++) {
                HostBox b = boxes.get(i);
                System.arraycopy(new double[] {b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ}, 0, out, i * 6, 6);
            }
            return boxes.size();
        }

        @Override
        public Object toHost(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            return new HostBox(minX, minY, minZ, maxX, maxY, maxZ);
        }

        @Override
        public boolean immutable(Object hostShape) {
            return true;
        }
    }

    @BeforeClass
    public static void provide() {
        CgPlatform.provide(CgHostShapes.SERVICE, new FakeHost());
    }

    @AfterClass
    public static void clear() {
        CgPlatform.provide(CgHostShapes.SERVICE, null);
    }

    @Test
    public void boxesRoundTrip() {
        CgAABB box = CgAABB.of(new HostBox(1, 2, 3, 4, 5, 6));
        assertEquals("CgAABB[1.0, 2.0, 3.0 .. 4.0, 5.0, 6.0]", box.toString());
        HostBox back = box.grow(1).toHost();
        assertEquals(new HostBox(0, 1, 2, 5, 6, 7), back);
    }

    @Test
    public void blockShapeIsACompoundInBlockSpace() {
        // a bottom slab and a post on it: a stair-like shape of two boxes
        HostShape stair = new HostShape(Arrays.asList(new HostBox(0, 0, 0, 1, 0.5, 1),
                new HostBox(0, 0.5, 0.5, 1, 1, 1)));
        CgShape shape = CgShape.of(stair);
        assertFalse(shape.isConvex());
        assertEquals("CgAABB[0.0, 0.0, 0.0 .. 1.0, 1.0, 1.0]", shape.bounds(new CgAABB()).toString());
        assertSame("an unchanging shape converts once", shape, CgShape.of(stair));
        assertNull("no boxes, no shape", CgShape.of(new HostShape(Collections.emptyList())));
    }

    @Test
    public void manyBoxesGrowTheBuffer() {
        HostBox[] boxes = new HostBox[20];
        for (int i = 0; i < boxes.length; i++) boxes[i] = new HostBox(i, 0, 0, i + 0.5, 1, 1);
        CgShape shape = CgShape.of(new HostShape(Arrays.asList(boxes)));
        assertEquals("CgAABB[0.0, 0.0, 0.0 .. 19.5, 1.0, 1.0]", shape.bounds(new CgAABB()).toString());
    }

    @Test
    public void conversionAllocatesNothing() {
        HostBox host = new HostBox(-1, 0, -1, 1, 2, 1);
        CgAABB box = new CgAABB();
        for (int i = 0; i < 200_000; i++) box.set(host);       // warm, so the JIT has compiled it
        ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().getId();
        long before = threads.getThreadAllocatedBytes(thread);
        long start = System.nanoTime();
        for (int i = 0; i < 1_000_000; i++) box.set(host);
        long nanos = System.nanoTime() - start;
        long allocated = threads.getThreadAllocatedBytes(thread) - before;
        System.out.printf("host box conversion: %.1f ns each%n", nanos / 1e6);
        assertTrue("allocated " + allocated + " bytes over a million conversions", allocated < 1024);
    }
}
