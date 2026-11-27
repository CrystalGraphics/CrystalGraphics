package com.crystalgraphics.net.object;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.crystalgraphics.serialization.CgCodecs;
import com.crystalgraphics.serialization.CgObjectCodec;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** A type from a codec: its keys as fields, read from watched instances, written into each client's own. */
public class CgNetModelTest {

    /** Twenty keys. */
    public static class Wide {
        public int k00, k01, k02, k03, k04, k05, k06, k07, k08, k09, k10, k11, k12, k13, k14, k15, k16, k17, k18;
        public String label = "wide";
    }

    static final CgNetType<Wide> WIDE = CgNetType.define("test:model/wide", CgCodecs.fieldsOf(Wide.class).build()).build();

    public static class Stats {
        public int strength = 1;
        public int agility = 1;
        public static final CgObjectCodec<Stats> CODEC = CgCodecs.fieldsOf(Stats.class).build();
    }

    public static class Pet {
        public String name = "VISIBLE-NAME-4417";
        public String ownerNotes = "SECRET-NOTE-7731";
        public int health = 10;
        public int maxHealth = 20;
        public final List<String> tricks = new ArrayList<>();
        public final Stats stats = new Stats();
        public static final CgObjectCodec<Pet> CODEC = CgCodecs.fieldsOf(Pet.class)
                .serverOnly("ownerNotes").readOnly("health").range("maxHealth", 1, 100).build();
    }

    static final CgNetType<Pet> PET = CgNetType.define("test:model/pet", Pet.CODEC).build();

    /** Counts its getter's reads. */
    public static class Counted {
        int value;
        static final AtomicInteger READS = new AtomicInteger();
        static final CgObjectCodec<Counted> CODEC = CgCodecs.object(Counted::new)
                .field("value", CgCodecs.INT, c -> {
                    READS.incrementAndGet();
                    return c.value;
                }, (c, v) -> c.value = v)
                .build();
    }

    static final CgNetType<Counted> COUNTED = CgNetType.define("test:model/counted", Counted.CODEC).build();

    public static class Log {
        public final List<String> lines = new ArrayList<>();
    }

    static final CgNetType<Log> LOG = CgNetType.define("test:model/log", CgCodecs.fieldsOf(Log.class).build())
            .comparesOnTouch().build();

    private CgNetTestRig rig;
    private final List<String> edits = new ArrayList<>();

    @Before
    public void setUp() {
        rig = CgNetTestRig.of(2);
        PET.afterEdit((pet, player) -> edits.add(pet.name + " by " + (rig.client(0).id().equals(player.id()) ? 0 : 1)));
        rig.tick(3);
    }

    @After
    public void tearDown() {
        rig.close();
    }

    private <T> CgNetObject seen(int client, CgNetType<T> type) {
        List<CgNetObject> visible = rig.client(client).visible(type);
        assertEquals(type.name + " on client " + client, 1, visible.size());
        return visible.get(0);
    }

    @SuppressWarnings("unchecked")
    private static <V> void hear(CgNetType<?> type, String key, List<String> heard) {
        type.onChange((CgNetField<V>) type.field(key), (obj, v) -> heard.add(key));
    }

    @Test
    public void oneChangedKeyOfTwentySendsThatKey() {
        Wide wide = new Wide();
        WIDE.over(wide).watch(rig.client(0).id());
        rig.tick(2);
        Wide mine = WIDE.instance(seen(0, WIDE));
        assertEquals("wide", mine.label);

        List<String> heard = new ArrayList<>();
        for (String key : List.of("k00", "k07", "k18", "label")) hear(WIDE, key, heard);
        long before = rig.client(0).bytesReceived();
        wide.k07 = 42;
        rig.tick(2);
        assertEquals(List.of("k07"), heard);
        assertEquals(42, mine.k07);
        assertSame("the client's instance keeps its identity", mine, WIDE.instance(seen(0, WIDE)));
        System.out.println("[model] one key of twenty: " + (rig.client(0).bytesReceived() - before) + " B");
    }

    @Test
    public void aNestedLeafSendsTheLeafAndLandsInTheHeldPart() {
        Pet pet = new Pet();
        PET.over(pet).watch(rig.client(0).id());
        PET.part(pet, "stats").watch(rig.client(0).id());
        rig.tick(3);
        Pet mine = PET.instance(seen(0, PET));
        CgNetType<?> stats = CgNetType.named("test:model/pet/stats");
        assertEquals(1, rig.client(0).visible(stats).size());

        List<String> root = new ArrayList<>(), leaf = new ArrayList<>();
        for (String key : List.of("name", "maxHealth", "stats")) hear(PET, key, root);
        hear(stats, "strength", leaf);
        hear(stats, "agility", leaf);
        pet.stats.strength = 9;
        rig.tick(2);
        assertEquals("the root sends nothing", List.of(), root);
        assertEquals(List.of("strength"), leaf);
        assertEquals("into the part the client's instance holds", 9, mine.stats.strength);
    }

    @Test
    public void aServerOnlyKeyNeverLeavesTheServerAndEditsKeepTheirRules() {
        rig.client(0).record();
        Pet pet = new Pet();
        pet.tricks.add("roll");
        PET.over(pet).watch(rig.client(0).id());
        rig.tick(3);
        byte[] bytes = rig.client(0).recorded();
        assertTrue("the search sees what does leave", contains(bytes, "VISIBLE-NAME-4417"));
        assertFalse("serverOnly", contains(bytes, "SECRET-NOTE-7731"));

        CgNetObject mine = seen(0, PET);
        set(mine, PET.field("maxHealth"), 500);
        rig.tick(4);
        assertEquals("outside its range: refused", 20, pet.maxHealth);
        assertEquals("and put back", 20, mine.getInt(intField("maxHealth")));
        assertEquals(1, PET.field("maxHealth").command.refused());

        set(mine, PET.field("maxHealth"), 50);
        rig.tick(4);
        assertEquals("inside: stored into the instance", 50, pet.maxHealth);
        assertEquals(List.of("VISIBLE-NAME-4417 by 0"), edits);
        try {
            set(mine, PET.field("health"), 99);
            fail("readOnly is not editable");
        } catch (IllegalStateException expected) {
            assertEquals(10, pet.health);
        }

        PET.allowEdits((p, player) -> !player.id().equals(rig.client(0).id()));
        set(mine, PET.field("maxHealth"), 70);
        rig.tick(4);
        assertEquals("refused by its guard", 50, pet.maxHealth);
        PET.allowEdits((p, player) -> true);
    }

    @Test
    public void anUnwatchedModelReadsNoGetterAndTwoWatchersReadItOnce() {
        Counted.READS.set(0);
        Counted alone = new Counted();
        COUNTED.over(alone);
        rig.tick(5);
        assertEquals("nobody watched: nothing read", 0, Counted.READS.get());

        Counted both = new Counted();
        CgNetObject obj = COUNTED.over(both);
        obj.watch(rig.client(0).id());
        obj.watch(rig.client(1).id());
        rig.tick(2);
        int start = Counted.READS.get();
        rig.tick(10);
        assertEquals("once a tick, however many watch", 10, Counted.READS.get() - start);
        both.value = 3;
        rig.tick(2);
        assertEquals(3, COUNTED.instance(seen(1, COUNTED)).value);

        obj.unwatch(rig.client(0).id());
        obj.unwatch(rig.client(1).id());
        rig.tick(3);
        assertTrue("ended at its last unwatch", !obj.isLive());
        assertTrue(rig.client(0).visible(COUNTED).isEmpty());
    }

    @Test
    public void aTypeThatComparesOnTouchSendsOnlyWhatWasTouched() {
        Log log = new Log();
        LOG.over(log).watch(rig.client(0).id());
        rig.tick(2);
        Log mine = LOG.instance(seen(0, LOG));
        log.lines.add("one");
        rig.tick(2);
        assertTrue("untouched: not compared", mine.lines.isEmpty());
        LOG.touch(log);
        rig.tick(2);
        assertEquals(List.of("one"), mine.lines);
        assertNotNull(mine);
    }

    @SuppressWarnings("unchecked")
    private static void set(CgNetObject obj, CgNetField<?> field, Object value) {
        obj.set((CgNetField<Object>) field, value);
    }

    @SuppressWarnings("unchecked")
    private static CgNetField<Integer> intField(String key) {
        return (CgNetField<Integer>) PET.field(key);
    }

    private static boolean contains(byte[] haystack, String text) {
        byte[] needle = text.getBytes(StandardCharsets.UTF_8);
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) if (haystack[i + j] != needle[j]) continue outer;
            return true;
        }
        return false;
    }
}
