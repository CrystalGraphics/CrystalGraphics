package com.crystalgraphics.render.graph;

import com.crystalgraphics.compute.source.CgBufferAccess;
import com.crystalgraphics.compute.source.CgBufferDecl;
import com.crystalgraphics.compute.source.CgElementField;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

/** {@link CgBufferInspector}'s decoding: each field by its GLSL type, at its element's place. GL-free. */
public class CgBufferInspectorTest {

    private static final CgElementField POSITION = new CgElementField("position", "vec2", 0, 8);
    private static final CgElementField INDEX = new CgElementField("index", "int", 8, 4);
    private static final CgElementField FLAGS = new CgElementField("flags", "uint", 12, 4);
    private static final CgElementField WEIGHT = new CgElementField("weight", "double", 16, 8);
    private static final CgElementField INNER = new CgElementField("inner", "Inner", 24, 8);

    @Test
    public void decodesEachFieldByItsType() {
        ByteBuffer data = ByteBuffer.allocate(64).order(ByteOrder.nativeOrder());
        element(data, 32, 7);
        CgBufferInspector.Read read = new CgBufferInspector.Read(site(), 10, 2, data);

        assertEquals("1.5, -2.0", read.value(0, POSITION));
        assertEquals("-3", read.value(0, INDEX));
        assertEquals("4294967295", read.value(0, FLAGS));
        assertEquals("0.25", read.value(0, WEIGHT));
        assertEquals("0xabc, 0x7", read.value(0, INNER));
        assertEquals("1.5, -2.0", read.value(1, POSITION));
        assertEquals("0xabc, 0x8", read.value(1, INNER));
    }

    @Test(expected = IndexOutOfBoundsException.class)
    public void refusesAnElementNotRead() {
        ByteBuffer data = ByteBuffer.allocate(64).order(ByteOrder.nativeOrder());
        new CgBufferInspector.Read(site(), 0, 2, data).value(2, INDEX);
    }

    @Test
    public void countsWholeElements() {
        assertEquals(2, site().elements());
    }

    /** Two 32-byte elements: the second's last word one more than the first's. */
    private static void element(ByteBuffer data, int stride, int last) {
        for (int e = 0; e < 2; e++) {
            int at = e * stride;
            data.putFloat(at, 1.5f).putFloat(at + 4, -2f).putInt(at + 8, -3).putInt(at + 12, -1).putDouble(at + 16, 0.25)
                    .putInt(at + 24, 0xABC).putInt(at + 28, last + e);
        }
    }

    private static CgBufferInspector.Site site() {
        List<CgElementField> fields = Arrays.asList(POSITION, INDEX, FLAGS, WEIGHT, INNER);
        CgBufferDecl decl = new CgBufferDecl("STATE", "State", "Record", CgBufferAccess.READWRITE, 32, false, false, 0, fields);
        return new CgBufferInspector.Site("state", "step", "test:shaders/t.compute", "Step", decl, 0, 70, 1);
    }
}
