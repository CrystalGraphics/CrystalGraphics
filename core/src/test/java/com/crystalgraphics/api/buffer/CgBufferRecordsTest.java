package com.crystalgraphics.api.buffer;

import com.crystalgraphics.compute.source.CgBufferAccess;
import com.crystalgraphics.compute.source.CgBufferDecl;
import com.crystalgraphics.compute.source.CgElementField;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class CgBufferRecordsTest {

    private static final CgBufferLayout SPARK = CgBufferLayout.std430("Spark").vec4("positionLife").vec3("velocity")
            .float_("seed").build();

    private static CgBufferDecl decl(String element, int stride, CgElementField... fields) {
        return new CgBufferDecl("SPARKS", "Sparks", element, CgBufferAccess.READONLY, stride, true, false, 0, List.of(fields));
    }

    @Test
    public void recordsAreWrittenByNameAndTheRestZeroed() {
        CgBufferRecords records = new CgBufferRecords(SPARK);
        records.begin(3);
        records.next().vec4("positionLife", 1, 2, 3, 4).float_("seed", 9);
        records.next().vec3("velocity", 5, 6, 7);
        int floats = records.end();
        assertEquals(3 * 8, floats);
        float[] f = records.floats();
        assertEquals(4f, f[3], 0f);
        assertEquals("seed sits in velocity's fourth word", 9f, f[7], 0f);
        assertEquals(6f, f[8 + 5], 0f);
        for (int i = 16; i < 24; i++) assertEquals("the third record is zero", 0f, f[i], 0f);
    }

    @Test
    public void aRecordPastTheCountOrAnUnknownFieldThrows() {
        CgBufferRecords records = new CgBufferRecords(SPARK);
        records.begin(1);
        assertThrows(IllegalArgumentException.class, () -> records.next().vec4("colour", 0, 0, 0, 0));
        assertThrows(IllegalStateException.class, records::next);
    }

    @Test
    public void aLayoutIsCheckedAgainstTheShadersStruct() {
        CgElementField position = new CgElementField("positionLife", "vec4", 0, 16);
        CgElementField velocity = new CgElementField("velocity", "vec3", 16, 12);
        CgElementField seed = new CgElementField("seed", "float", 28, 4);
        decl("Spark", 32, position, velocity, seed).check(SPARK);

        IllegalArgumentException stride = assertThrows(IllegalArgumentException.class,
                () -> decl("Spark", 48, position, velocity, seed).check(SPARK));
        assertTrue(stride.getMessage(), stride.getMessage().contains("strides differ"));
        IllegalArgumentException moved = assertThrows(IllegalArgumentException.class,
                () -> decl("Spark", 32, position, new CgElementField("velocity", "vec4", 16, 16), seed).check(SPARK));
        assertTrue(moved.getMessage(), moved.getMessage().contains("velocity is vec3 at 16 here, vec4 at 16 there"));
        assertThrows(IllegalArgumentException.class, () -> decl("Spark", 32, position, velocity).check(SPARK));
    }
}
