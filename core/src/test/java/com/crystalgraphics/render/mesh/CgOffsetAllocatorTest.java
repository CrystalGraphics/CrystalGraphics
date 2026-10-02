package com.crystalgraphics.render.mesh;

import org.junit.Test;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.*;

/** Ranges never overlap, frees merge back to one range, and the size classes round as the original's. */
public class CgOffsetAllocatorTest {

    @Test
    public void sizeClassesRoundUpForARequestAndDownForAFreeRange() {
        assertEquals(7, CgOffsetAllocator.roundUp(7));
        assertEquals(8, CgOffsetAllocator.roundUp(8));
        assertEquals(9, CgOffsetAllocator.roundUp(9));
        assertEquals(17, CgOffsetAllocator.roundUp(17));
        assertEquals(18, floatToUint(17));
        for (int size = 1; size < 1 << 20; size = size * 3 / 2 + 1) {
            assertTrue(size + " fits its class", floatToUint(CgOffsetAllocator.roundUp(size)) >= size);
            assertTrue(size + " holds its class", floatToUint(CgOffsetAllocator.roundDown(size)) <= size);
        }
    }

    /** The original's {@code SmallFloat::floatToUint}: a class's smallest size. */
    private static int floatToUint(int value) {
        int exponent = value >>> 3, mantissa = value & 7;
        return exponent == 0 ? mantissa : (mantissa | 8) << (exponent - 1);
    }

    @Test
    public void randomAllocationsNeverOverlapAndFreeingEverythingLeavesOneRange() {
        int size = 1 << 16;
        CgOffsetAllocator allocator = new CgOffsetAllocator(size);
        Random random = new Random(11);
        List<Integer> nodes = new ArrayList<>();
        BitSet taken = new BitSet(size);
        for (int round = 0; round < 20_000; round++) {
            if (!nodes.isEmpty() && random.nextInt(3) == 0) {
                int node = nodes.remove(random.nextInt(nodes.size()));
                taken.clear(allocator.offset(node), allocator.offset(node) + allocator.count(node));
                allocator.free(node);
                continue;
            }
            int count = 1 + random.nextInt(random.nextBoolean() ? 16 : 900);
            int node = allocator.allocate(count);
            if (node == CgOffsetAllocator.NO_SPACE) continue;
            int at = allocator.offset(node);
            assertTrue("inside the space", at >= 0 && at + count <= size);
            assertTrue("no overlap at " + at, taken.get(at, at + count).isEmpty());
            taken.set(at, at + count);
            nodes.add(node);
        }
        for (int node : nodes) allocator.free(node);
        assertEquals(size, allocator.freeStorage());
        int whole = allocator.allocate(size);
        assertNotEquals("merged back into one range", CgOffsetAllocator.NO_SPACE, whole);
        assertEquals(0, allocator.offset(whole));
    }

    @Test
    public void aSpaceOfTheFittingSizeServesItsRequestWhole() {
        for (int count : new int[]{1, 7, 100, 4097, 70_001, 1_000_003}) {
            CgOffsetAllocator allocator = new CgOffsetAllocator(CgOffsetAllocator.fittingSize(count));
            assertNotEquals("count " + count, CgOffsetAllocator.NO_SPACE, allocator.allocate(count));
        }
        assertEquals("a size between classes is refused", CgOffsetAllocator.NO_SPACE, new CgOffsetAllocator(100).allocate(100));
    }

    @Test
    public void aFullSpaceRefusesAndADoubleFreeThrows() {
        CgOffsetAllocator allocator = new CgOffsetAllocator(64);
        int a = allocator.allocate(64);
        assertEquals(CgOffsetAllocator.NO_SPACE, allocator.allocate(1));
        allocator.free(a);
        assertThrows(IllegalStateException.class, () -> allocator.free(a));
    }
}
