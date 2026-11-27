package com.crystalgraphics.serialization;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.Test;

import com.crystalgraphics.serialization.format.CgFiles;
import com.crystalgraphics.serialization.nbt.CgNbt;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** A codec from a class's fields: the hand-written one's output, through every format, with the builder's rules. */
public class CgFieldsOfTest {

    enum Mood { CALM, ANGRY }

    /** Held for good and updated in place. */
    public static final class Stats {
        public int strength = 1;
        public static final CgObjectCodec<Stats> CODEC = CgCodecs.object(Stats::new)
                .field("strength", CgCodecs.INT, s -> s.strength, (s, v) -> s.strength = v).build();
    }

    /** A generic superclass: its {@code T} is read as the subclass binds it. */
    public static class Base<T> {
        public T payload;
        public List<T> history = new ArrayList<>();
    }

    public static class Everything extends Base<String> {
        public int i;
        public long l;
        public float f;
        public double d;
        public boolean b;
        public short s;
        public byte by;
        public char c = 'x';
        public String text = "";
        public Mood mood = Mood.CALM;
        public UUID id = new UUID(1, 2);
        public byte[] bytes = new byte[0];
        public int[] ints = new int[0];
        public float[] floats = new float[0];
        public String[] words = new String[0];
        public Set<Integer> seen = new LinkedHashSet<>();
        public Map<String, Integer> scores = new LinkedHashMap<>();
        public final List<Integer> levels = new ArrayList<>();
        public final Stats stats = new Stats();
        public transient Object entity = new Object();
        public static int counter;
    }

    /** The same codec, written out: the derived one must produce exactly this. */
    static final CgObjectCodec<Everything> TWIN = CgCodecs.object(Everything::new)
            .field("b", CgCodecs.BOOL, e -> e.b, (e, v) -> e.b = v)
            .field("by", CgCodecs.xmap(CgCodecs.INT, Integer::byteValue, Byte::intValue), e -> e.by, (e, v) -> e.by = v)
            .field("bytes", CgCodecs.BYTES, e -> e.bytes, (e, v) -> e.bytes = v)
            .field("c", CgCodecs.xmap(CgCodecs.STRING, x -> x.charAt(0), String::valueOf), e -> e.c, (e, v) -> e.c = v)
            .field("d", CgCodecs.DOUBLE, e -> e.d, (e, v) -> e.d = v)
            .field("f", CgCodecs.FLOAT, e -> e.f, (e, v) -> e.f = v)
            .field("floats", CgCodecs.xmap(CgCodecs.listOf(CgCodecs.FLOAT), x -> new float[0], a -> {
                List<Float> out = new ArrayList<>();
                for (float v : a) out.add(v);
                return out;
            }), e -> e.floats, (e, v) -> { })
            .field("history", CgCodecs.listOf(CgCodecs.STRING), e -> e.history, (e, v) -> e.history = v)
            .field("i", CgCodecs.INT, e -> e.i, (e, v) -> e.i = v)
            .field("id", CgCodecs.UUID, e -> e.id, (e, v) -> e.id = v)
            .field("ints", CgCodecs.xmap(CgCodecs.listOf(CgCodecs.INT), x -> new int[0], a -> {
                List<Integer> out = new ArrayList<>();
                for (int v : a) out.add(v);
                return out;
            }), e -> e.ints, (e, v) -> { })
            .field("l", CgCodecs.LONG, e -> e.l, (e, v) -> e.l = v)
            .into("levels", CgCodecs.listOf(CgCodecs.INT), e -> e.levels)
            .field("mood", CgCodecs.enumOf(Mood.class), e -> e.mood, (e, v) -> e.mood = v)
            .field("payload", CgCodecs.STRING, e -> e.payload, (e, v) -> e.payload = v)
            .field("s", CgCodecs.xmap(CgCodecs.INT, Integer::shortValue, Short::intValue), e -> e.s, (e, v) -> e.s = v)
            .field("scores", CgCodecs.mapOf(CgCodecs.STRING, CgCodecs.INT), e -> e.scores, (e, v) -> e.scores = v)
            .field("seen", CgCodecs.xmap(CgCodecs.listOf(CgCodecs.INT), LinkedHashSet::new, ArrayList::new), e -> e.seen,
                    (e, v) -> e.seen = v)
            .into("stats", Stats.CODEC, e -> e.stats)
            .field("text", CgCodecs.STRING, e -> e.text, (e, v) -> e.text = v)
            .field("words", CgCodecs.xmap(CgCodecs.listOf(CgCodecs.STRING), x -> x.toArray(new String[0]), Arrays::asList),
                    e -> e.words, (e, v) -> e.words = v)
            .build();

    static final CgObjectCodec<Everything> DERIVED = CgCodecs.fieldsOf(Everything.class).build();

    static Everything filled() {
        Everything e = new Everything();
        e.i = 7;
        e.l = 1L << 40;
        e.f = 1.5f;
        e.d = 2.25;
        e.b = true;
        e.s = 300;
        e.by = 9;
        e.c = 'q';
        e.text = "Goku";
        e.mood = Mood.ANGRY;
        e.id = new UUID(3, 4);
        e.bytes = new byte[] {1, 2, 3};
        e.ints = new int[] {4, 5};
        e.floats = new float[] {0.5f};
        e.words = new String[] {"ka", "me"};
        e.seen.add(8);
        e.scores.put("kills", 12);
        e.levels.add(3);
        e.stats.strength = 42;
        e.payload = "senzu";
        e.history.add("earth");
        return e;
    }

    @Test
    public void everySupportedTypeEncodesAsItsHandWrittenTwinThroughEveryFormat() {
        Everything e = filled();
        assertEquals(CgCodecs.keysOf(TWIN), CgCodecs.keysOf(DERIVED));
        assertEquals("NBT", CgNbt.encode(TWIN, e).toString(), CgNbt.encode(DERIVED, e).toString());
        assertArrayEquals("binary", CgBinaryFormat.encode(TWIN.encode(CgPlainOps.INSTANCE, e)),
                CgBinaryFormat.encode(DERIVED.encode(CgPlainOps.INSTANCE, e)));
        assertEquals("TOML", CgFiles.TOML.toText(TWIN, e), CgFiles.TOML.toText(DERIVED, e));
    }

    @Test
    public void itRoundTripsThroughEveryFormat() {
        Everything e = filled();
        for (Everything back : List.of(
                CgNbt.decode(DERIVED, CgNbt.encode(DERIVED, e)),
                DERIVED.decode(CgPlainOps.INSTANCE, CgBinaryFormat.decode(CgBinaryFormat.encode(DERIVED.encode(CgPlainOps.INSTANCE, e)))),
                CgFiles.TOML.fromText(DERIVED, CgFiles.TOML.toText(DERIVED, e)))) {
            assertEquals(7, back.i);
            assertEquals(1L << 40, back.l);
            assertEquals(1.5f, back.f, 0f);
            assertEquals(300, back.s);
            assertEquals('q', back.c);
            assertEquals(Mood.ANGRY, back.mood);
            assertEquals(new UUID(3, 4), back.id);
            assertArrayEquals(new int[] {4, 5}, back.ints);
            assertArrayEquals(new float[] {0.5f}, back.floats, 0f);
            assertArrayEquals(new String[] {"ka", "me"}, back.words);
            assertEquals(Set.of(8), back.seen);
            assertEquals(Map.of("kills", 12), back.scores);
            assertEquals(List.of(3), back.levels);
            assertEquals(42, back.stats.strength);
            assertEquals("the superclass's T, read as String", "senzu", back.payload);
            assertEquals(List.of("earth"), back.history);
        }
    }

    @Test
    public void aFinalFieldIsDecodedIntoInPlace() {
        Everything live = new Everything();
        List<Integer> held = live.levels;
        Stats stats = live.stats;
        DERIVED.decode(CgPlainOps.INSTANCE, DERIVED.encode(CgPlainOps.INSTANCE, filled()), live);
        assertSame(held, live.levels);
        assertSame(stats, live.stats);
        assertEquals(42, live.stats.strength);
    }

    public static class Unsupported {
        public Thread worker;
    }

    @Test
    public void anUnsupportedTypeThrowsAtBuildNamingTheFieldAndBothFixes() {
        try {
            CgCodecs.fieldsOf(Unsupported.class).build();
            fail("built");
        } catch (IllegalArgumentException expected) {
            String message = expected.getMessage();
            assertTrue(message, message.contains("worker") && message.contains("with(") && message.contains("skip(\"worker\")"));
        }
        CgObjectCodec<Unsupported> skipped = CgCodecs.fieldsOf(Unsupported.class).skip("worker").build();
        assertTrue(CgCodecs.keysOf(skipped).isEmpty());
        CgObjectCodec<Unsupported> given = CgCodecs.fieldsOf(Unsupported.class)
                .with(Thread.class, CgCodecs.xmap(CgCodecs.STRING, Thread::new, Thread::getName)).build();
        assertEquals(List.of("worker"), CgCodecs.keysOf(given));
    }

    /** Private fields, reached through the class's own lookup. */
    static final class Secret {
        private int code = 1;
        private String owner = "";

        static CgObjectCodec<Secret> codec() {
            return CgCodecs.fieldsOf(Secret.class, MethodHandles.lookup()).build();
        }
    }

    @Test
    public void aPrivateFieldWorksThroughALookup() {
        Secret s = new Secret();
        s.code = 77;
        s.owner = "Vegeta";
        Secret back = Secret.codec().decode(CgPlainOps.INSTANCE, Secret.codec().encode(CgPlainOps.INSTANCE, s));
        assertEquals(77, back.code);
        assertEquals("Vegeta", back.owner);
        try {
            CgCodecs.fieldsOf(Secret.class).build();
            fail("a private field without a lookup");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("lookup"));
        }
    }

    public record Spell(String name) {
    }

    public static class Npc {
        public String name = "";
        public int health = 20;
        public int gold;
        public String note = "";
    }

    @Test
    public void theBuildersRulesApply() {
        try {
            CgCodecs.fieldsOf(Spell.class);
            fail("a record");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("CgCodecs.record"));
        }
        CgObjectCodec<Npc> codec = CgCodecs.fieldsOf(Npc.class)
                .key("health", "hp").range("hp", 0, 100).readOnly("gold").serverOnly("note")
                .field("name", CgCodecs.STRING, n -> n.name.toUpperCase(), (n, v) -> n.name = v)   // written out: replaces
                .build();
        assertEquals(List.of("gold", "hp", "note", "name"), CgCodecs.keysOf(codec));
        Npc npc = new Npc();
        npc.name = "piccolo";
        Object tree = codec.encode(CgPlainOps.INSTANCE, npc);
        assertEquals("PICCOLO", ((Map<?, ?>) tree).get("name"));
        Map<String, Object> edited = new LinkedHashMap<>();
        edited.put("hp", 500);
        edited.put("gold", 99);
        Npc read = codec.decode(CgPlainOps.INSTANCE, edited);
        assertEquals("clamped to its range", 100, read.health);
        assertEquals("read only: not read back", 0, read.gold);
        assertTrue(codec.policy("note").serverOnly());
    }

    @Test
    public void encodeMeasuredAgainstTheHandWrittenTwin() {
        Everything e = filled();
        for (int warm = 0; warm < 20_000; warm++) {
            TWIN.encode(CgPlainOps.INSTANCE, e);
            DERIVED.encode(CgPlainOps.INSTANCE, e);
        }
        long twin = time(() -> TWIN.encode(CgPlainOps.INSTANCE, e)), derived = time(() -> DERIVED.encode(CgPlainOps.INSTANCE, e));
        System.out.println("[fieldsOf] encode, 50,000 times: hand-written " + twin / 1_000_000 + " ms, derived "
                + derived / 1_000_000 + " ms (" + String.format("%.2f", (double) derived / twin) + "x)");
    }

    private static long time(Runnable work) {
        long start = System.nanoTime();
        for (int n = 0; n < 50_000; n++) work.run();
        return System.nanoTime() - start;
    }
}
