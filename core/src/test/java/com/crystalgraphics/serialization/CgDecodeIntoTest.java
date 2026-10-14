package com.crystalgraphics.serialization;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Decoding into a live object keeps it, and what it holds, while the data changes. */
public class CgDecodeIntoTest {

    static final class Stats {
        int power;
        static final CgObjectCodec<Stats> CODEC = CgCodecs.object(Stats::new)
                .field("power", CgCodecs.INT, s -> s.power, (s, v) -> s.power = v).build();
    }

    static class Character {
        String name = "";
        int level = 1;
        Stats best;
        final List<String> skills = new ArrayList<>();
        final Stats stats = new Stats();

        static final CgObjectCodec<Character> CODEC = CgCodecs.object(Character::new)
                .field("name", CgCodecs.STRING, c -> c.name, (c, v) -> c.name = v)
                .optional("level", CgCodecs.INT, c -> c.level, (c, v) -> c.level = v, 1)
                .field("best", Stats.CODEC, c -> c.best, (c, v) -> c.best = v)
                .into("skills", CgCodecs.listOf(CgCodecs.STRING), c -> c.skills)
                .into("stats", Stats.CODEC, c -> c.stats)
                .build();
    }

    static final class Saiyan extends Character {
        boolean tail = true;
        static final CgObjectCodec<Saiyan> CODEC = CgCodecs.object(Saiyan::new)
                .extend(Character.CODEC)
                .field("tail", CgCodecs.BOOL, s -> s.tail, (s, v) -> s.tail = v)
                .build();
    }

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private static Object encoded(String name, int level, int best, int power, String... skills) {
        Character c = new Character();
        c.name = name;
        c.level = level;
        c.best = new Stats();
        c.best.power = best;
        c.stats.power = power;
        c.skills.addAll(Arrays.asList(skills));
        return Character.CODEC.encode(CgPlainOps.INSTANCE, c);
    }

    @Test
    public void anObjectAndEverythingItHoldsKeepTheirIdentity() {
        Character live = Character.CODEC.decode(CgPlainOps.INSTANCE, encoded("Goku", 3, 10, 5, "kamehameha"));
        Stats best = live.best;
        Stats stats = live.stats;
        List<String> skills = live.skills;

        Character same = Character.CODEC.decodeInto(CgPlainOps.INSTANCE,
                encoded("Kakarot", 9, 20, 7, "kamehameha", "instant transmission"), live);

        assertSame(live, same);
        assertSame("a nested object with a setter is updated in place", best, live.best);
        assertSame(stats, live.stats);
        assertSame(skills, live.skills);
        assertEquals("Kakarot", live.name);
        assertEquals(9, live.level);
        assertEquals(20, live.best.power);
        assertEquals(7, live.stats.power);
        assertEquals(Arrays.asList("kamehameha", "instant transmission"), live.skills);
    }

    @Test
    public void anAbsentKeyKeepsItsValue() {
        Character live = Character.CODEC.decode(CgPlainOps.INSTANCE, encoded("Goku", 3, 10, 5));
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("name", "Kakarot");
        Character.CODEC.decodeInto(CgPlainOps.INSTANCE, patch, live);
        assertEquals("Kakarot", live.name);
        assertEquals(3, live.level);
        assertEquals(5, live.stats.power);

        Character fresh = Character.CODEC.decode(CgPlainOps.INSTANCE, patch);
        assertEquals("the constructor's default", 1, fresh.level);
    }

    @Test
    public void aSubclassWritesItsParentsFieldsFlat() {
        Saiyan s = new Saiyan();
        s.name = "Vegeta";
        s.tail = false;
        @SuppressWarnings("unchecked")
        Map<String, Object> tree = (Map<String, Object>) Saiyan.CODEC.encode(CgPlainOps.INSTANCE, s);
        assertEquals("Vegeta", tree.get("name"));
        assertEquals(false, tree.get("tail"));
        Saiyan back = Saiyan.CODEC.decode(CgPlainOps.INSTANCE, tree);
        assertEquals("Vegeta", back.name);
        assertEquals(false, back.tail);
    }

    @Test
    public void aListIsRefilledAndAMapKeptByKey() {
        CgCodec<List<Stats>> list = CgCodecs.listOf(Stats.CODEC);
        List<Stats> live = new ArrayList<>(list.decode(CgPlainOps.INSTANCE, list.encode(CgPlainOps.INSTANCE,
                Arrays.asList(stats(1), stats(2), stats(3)))));
        Stats first = live.get(0);
        List<Stats> same = list.decodeInto(CgPlainOps.INSTANCE,
                list.encode(CgPlainOps.INSTANCE, Arrays.asList(stats(10), stats(20))), live);
        assertSame(live, same);
        assertSame("element 0 updated in place", first, live.get(0));
        assertEquals(2, live.size());
        assertEquals(20, live.get(1).power);

        CgCodec<Map<String, Stats>> map = CgCodecs.mapOf(CgCodecs.STRING, Stats.CODEC);
        Map<String, Stats> byName = new LinkedHashMap<>();
        byName.put("goku", stats(1));
        byName.put("vegeta", stats(2));
        Stats goku = byName.get("goku");
        Map<String, Stats> next = new LinkedHashMap<>();
        next.put("goku", stats(9));
        next.put("gohan", stats(4));
        map.decodeInto(CgPlainOps.INSTANCE, map.encode(CgPlainOps.INSTANCE, next), byName);
        assertSame(goku, byName.get("goku"));
        assertEquals(9, goku.power);
        assertEquals(Arrays.asList("goku", "gohan"), new ArrayList<>(byName.keySet()));
    }

    @Test
    public void aDispatchUpdatesInPlaceOnlyForItsOwnKey() {
        CgCodec<Stats> one = CgCodecs.dispatch("kind", CgCodecs.STRING, s -> "stats", kind -> Stats.CODEC);
        Stats live = stats(1);
        assertSame(live, one.decodeInto(CgPlainOps.INSTANCE, one.encode(CgPlainOps.INSTANCE, stats(5)), live));
        assertEquals(5, live.power);

        CgCodec<Stats> other = CgCodecs.dispatch("kind", CgCodecs.STRING, s -> "other", kind -> Stats.CODEC);
        assertNotSame(live, other.decodeInto(CgPlainOps.INSTANCE, one.encode(CgPlainOps.INSTANCE, stats(6)), live));
    }

    @Test
    public void whatCannotBeDoneSaysSo() {
        CgObjectCodec<Character> noFactory = CgCodecs.object(Character.class)
                .field("name", CgCodecs.STRING, c -> c.name, (c, v) -> c.name = v).build();
        CgCodecException e = assertThrows(CgCodecException.class,
                () -> noFactory.decode(CgPlainOps.INSTANCE, new LinkedHashMap<>()));
        assertTrue(e.getMessage(), e.getMessage().contains("Character"));

        CgObjectCodec<Character> heldString = CgCodecs.object(Character::new)
                .into("name", CgCodecs.STRING, c -> c.name).build();
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("name", "Goku");
        e = assertThrows(CgCodecException.class, () -> heldString.decodeInto(CgPlainOps.INSTANCE, in, new Character()));
        assertTrue(e.getMessage(), e.getMessage().contains("'name'"));

        assertThrows(IllegalArgumentException.class, () -> CgCodecs.object(Stats::new)
                .field("power", CgCodecs.INT, s -> s.power, (s, v) -> s.power = v)
                .field("power", CgCodecs.INT, s -> s.power, (s, v) -> s.power = v).build());
    }

    @Test
    public void aFileIsReadIntoTheLiveObjectAndADamagedOneTouchesNothing() throws IOException {
        Path file = folder.getRoot().toPath().resolve("goku.dat");
        Character live = new Character();
        assertSame("no file: as it was", live, CgFormats.readInto(file, Character.CODEC, live));

        Character saved = Character.CODEC.decode(CgPlainOps.INSTANCE, encoded("Goku", 4, 1, 2, "kaioken"));
        CgFormats.write(file, Character.CODEC, saved);
        List<String> skills = live.skills;
        assertSame(live, CgFormats.readInto(file, Character.CODEC, live));
        assertEquals("Goku", live.name);
        assertSame(skills, live.skills);
        assertEquals(Arrays.asList("kaioken"), live.skills);

        byte[] bytes = Files.readAllBytes(file);
        Files.write(file, Arrays.copyOf(bytes, bytes.length / 2));
        assertThrows(IOException.class, () -> CgFormats.readInto(file, Character.CODEC, live));
        assertEquals("Goku", live.name);
        assertEquals(Arrays.asList("kaioken"), live.skills);
    }

    private static Stats stats(int power) {
        Stats s = new Stats();
        s.power = power;
        return s;
    }
}
