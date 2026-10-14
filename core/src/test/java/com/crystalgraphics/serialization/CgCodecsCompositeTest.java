package com.crystalgraphics.serialization;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Maps and dispatched values through a codec, CrystalGraphics' binary format and back. */
public class CgCodecsCompositeTest {

    interface Shape {
        String kind();
    }

    record Circle(double radius) implements Shape {
        static final CgCodec<Circle> CODEC = CgCodecs.xmap(CgCodecs.DOUBLE, Circle::new, Circle::radius);

        public String kind() {
            return "circle";
        }
    }

    record Square(int side) implements Shape {
        static final CgCodec<Square> CODEC = CgCodecs.xmap(CgCodecs.INT, Square::new, Square::side);

        public String kind() {
            return "square";
        }
    }

    static final CgCodec<Shape> SHAPES = CgCodecs.dispatch("kind", CgCodecs.STRING, Shape::kind,
            kind -> kind.equals("circle") ? Circle.CODEC : kind.equals("square") ? Square.CODEC : null);

    private static <V> V throughBytes(CgCodec<V> codec, V value) {
        byte[] bytes = CgBinaryFormat.encode(codec.encode(CgPlainOps.INSTANCE, value));
        return codec.decode(CgPlainOps.INSTANCE, CgBinaryFormat.decode(bytes));
    }

    @Test
    public void aMapComesBackInTheOrderItWasWritten() {
        Map<String, Integer> levels = new LinkedHashMap<>();
        levels.put("zeta", 3);
        levels.put("alpha", 1);
        levels.put("mid", 2);
        Map<String, Integer> back = throughBytes(CgCodecs.mapOf(CgCodecs.STRING, CgCodecs.INT), levels);
        assertEquals(levels, back);
        assertEquals(Arrays.asList("zeta", "alpha", "mid"), new ArrayList<>(back.keySet()));
    }

    @Test
    public void aDispatchedValueComesBackAsItsOwnKind() {
        List<Shape> shapes = Arrays.asList(new Circle(1.5), new Square(4), new Circle(0.25));
        assertEquals(shapes, throughBytes(CgCodecs.listOf(SHAPES), shapes));
    }

    @Test
    public void aKindWithNoCodecFailsNamingIt() {
        Shape odd = () -> "hexagon";
        CgCodecException written = assertThrows(CgCodecException.class,
                () -> SHAPES.encode(CgPlainOps.INSTANCE, odd));
        assertTrue(written.getMessage(), written.getMessage().contains("kind hexagon"));

        Object tree = CgCodecs.map(CgPlainOps.INSTANCE).field("kind", CgCodecs.STRING, "hexagon")
                .field("value", CgCodecs.INT, 6).build();
        CgCodecException read = assertThrows(CgCodecException.class, () -> SHAPES.decode(CgPlainOps.INSTANCE, tree));
        assertTrue(read.getMessage(), read.getMessage().contains("kind hexagon"));
    }
}
