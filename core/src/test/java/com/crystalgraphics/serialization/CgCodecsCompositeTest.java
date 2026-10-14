package com.crystalgraphics.serialization;

import com.crystalgraphics.serialization.format.CgFormats;
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
    public void numberKeysAreWrittenAsTextAndReadFromEither() {
        Map<Integer, String> layers = new LinkedHashMap<>();
        layers.put(3, "outline");
        layers.put(-1, "base");
        CgCodec<Map<Integer, String>> codec = CgCodecs.mapOf(CgCodecs.INT, CgCodecs.STRING);
        Map<?, ?> tree = (Map<?, ?>) codec.encode(CgPlainOps.INSTANCE, layers);
        assertEquals(Arrays.asList("3", "-1"), new ArrayList<>(tree.keySet()));
        assertEquals(layers, throughBytes(codec, layers));
        assertEquals(layers, CgFormats.JSON.fromText(codec, CgFormats.JSON.toText(codec, layers)));
        Map<Object, Object> numbered = new LinkedHashMap<>();
        numbered.put(3, "outline");
        numbered.put(-1L, "base");
        assertEquals("a save from before keys were text", layers, codec.decode(CgPlainOps.INSTANCE, numbered));
    }

    @Test
    public void aPlainTreeIsCarriedWholeThroughEveryFormatThatCanReadOne() {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("heat", 0.5);
        tree.put("ammo", 12);
        tree.put("tags", Arrays.asList("hot", true));
        tree.put("inner", new LinkedHashMap<>(Map.of("name", "x")));
        assertEquals(tree, throughBytes(CgCodecs.PLAIN, tree));
        assertEquals(tree, CgFormats.JSON.fromText(CgCodecs.PLAIN, CgFormats.JSON.toText(CgCodecs.PLAIN, tree)));
        assertEquals(tree, CgFormats.YAML.fromText(CgCodecs.PLAIN, CgFormats.YAML.toText(CgCodecs.PLAIN, tree)));
        assertThrows(CgCodecException.class, () -> CgCodecs.PLAIN.encode(CgPlainOps.INSTANCE, new Object()));
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
