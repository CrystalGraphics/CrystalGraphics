package com.crystalgraphics.net.object;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.crystalgraphics.job.reply.CgReply;
import com.crystalgraphics.job.reply.CgReplyError;
import com.crystalgraphics.serialization.CgCodecs;
import com.crystalgraphics.serialization.CgObjectCodec;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** One request: every command and call answers; caps per sender; an NPC-sized object; a map loaded on demand. */
public class CgNetRequestTest {

    // ── A shop: commands and calls ──────────────────────────────────────────────────────────────

    public static class Shop {
        public String owner = "";
        public int stock = 3;
        public String name = "Kame House";
    }

    static final CgNetCommand<String> RENAME = CgNetCommand.of("rename", CgCodecs.STRING);
    static final CgNetCommand<String> SHOUT = CgNetCommand.of("shout", CgCodecs.STRING).perSecond(5).maxBytes(64);
    static final CgNetCall<Integer, Integer> BUY = CgNetCall.of("buy", CgCodecs.INT, CgCodecs.INT);
    static final CgNetCall<Integer, Integer> SLOW = CgNetCall.of("slow", CgCodecs.INT, CgCodecs.INT);
    static final CgNetType<Shop> SHOP = CgNetType.define("test:request/shop", CgCodecs.fieldsOf(Shop.class).build())
            .commands(RENAME, SHOUT).calls(BUY, SLOW).build();

    // ── An NPC: nine parts, 160 keys, a 50 KB text ───────────────────────────────────────────────

    public static class Stats {
        public int k00, k01, k02, k03, k04, k05, k06, k07, k08, k09, k10, k11, k12, k13, k14, k15, k16, k17, k18, k19, k20, k21, k22, k23, k24, k25;
        public static final CgObjectCodec<Stats> CODEC = CgCodecs.fieldsOf(Stats.class).build();
    }
    public static class Display {
        public int k00, k01, k02, k03, k04, k05, k06, k07, k08, k09, k10, k11, k12, k13, k14, k15, k16, k17, k18, k19, k20, k21, k22, k23, k24, k25;
        public static final CgObjectCodec<Display> CODEC = CgCodecs.fieldsOf(Display.class).build();
    }
    public static class Ai {
        public int k00, k01, k02, k03, k04, k05, k06, k07, k08, k09, k10, k11, k12, k13, k14, k15, k16, k17, k18, k19, k20, k21, k22, k23, k24, k25;
        public static final CgObjectCodec<Ai> CODEC = CgCodecs.fieldsOf(Ai.class).build();
    }
    public static class Combat {
        public int k00, k01, k02, k03, k04, k05, k06, k07, k08, k09, k10, k11, k12, k13, k14, k15, k16, k17, k18, k19, k20, k21, k22, k23, k24, k25;
        public static final CgObjectCodec<Combat> CODEC = CgCodecs.fieldsOf(Combat.class).build();
    }
    public static class Loot {
        public int k00, k01, k02, k03, k04, k05, k06, k07, k08, k09, k10, k11, k12, k13, k14, k15, k16, k17, k18, k19, k20, k21, k22, k23, k24, k25;
        public static final CgObjectCodec<Loot> CODEC = CgCodecs.fieldsOf(Loot.class).build();
    }
    public static class Sounds {
        public int k00, k01, k02, k03, k04, k05, k06, k07, k08, k09, k10, k11, k12, k13, k14, k15, k16, k17, k18, k19, k20, k21, k22, k23, k24, k25;
        public static final CgObjectCodec<Sounds> CODEC = CgCodecs.fieldsOf(Sounds.class).build();
    }

    public static class Dialog {
        public String text = "";
        public static final CgObjectCodec<Dialog> CODEC = CgCodecs.fieldsOf(Dialog.class).build();
    }

    public static class Quests {
        public final Map<String, Integer> progress = new LinkedHashMap<>();
        public static final CgObjectCodec<Quests> CODEC = CgCodecs.fieldsOf(Quests.class).build();
    }

    public static class Roles {
        public String role = "trader";
        public static final CgObjectCodec<Roles> CODEC = CgCodecs.fieldsOf(Roles.class).build();
    }

    public static class Npc {
        public String name = "Bulma";
        public String title = "Capsule Corp";
        public int level = 10;
        public int faction = 2;
        public final Stats stats = new Stats();
        public final Display display = new Display();
        public final Ai ai = new Ai();
        public final Combat combat = new Combat();
        public final Loot loot = new Loot();
        public final Sounds sounds = new Sounds();
        public final Dialog dialog = new Dialog();
        public final Quests quests = new Quests();
        public final Roles roles = new Roles();
    }

    static final CgNetType<Npc> NPC = CgNetType.define("test:request/npc",
            CgCodecs.fieldsOf(Npc.class).readOnly("level").build()).build();

    // ── Versions: the server's type, and the two a client may have ──────────────────────────────

    public static class PetNow {
        public int a = 1, b = 2, c = 3;
    }

    public static class PetBehind {
        public int a, b;
    }

    public static class PetAhead {
        public int a, b, c, d = 44;
    }

    static final CgNetCommand<Integer> TRICK = CgNetCommand.of("trick", CgCodecs.INT);
    static final CgNetType<PetNow> PET_NOW = CgNetType.define("test:request/pet", CgCodecs.fieldsOf(PetNow.class).build()).build();
    static final CgNetType<PetBehind> PET_BEHIND = CgNetType.define("test:request/pet_behind",
            CgCodecs.fieldsOf(PetBehind.class).build()).build();
    static final CgNetType<PetAhead> PET_AHEAD = CgNetType.define("test:request/pet_ahead",
            CgCodecs.fieldsOf(PetAhead.class).build()).commands(TRICK).build();

    private CgNetTestRig rig;
    private final List<String> heard = new ArrayList<>();

    @Before
    public void setUp() {
        rig = CgNetTestRig.of(2);
        SHOP.onCommand(RENAME, (shop, player, name) -> shop.name = name);
        SHOP.allow(RENAME, (shop, player) -> shop.owner.equals(player.id().toString()));
        SHOP.onCommand(SHOUT, (shop, player, text) -> heard.add(text));
        SHOP.onCall(BUY, (shop, player, count, reply) -> {
            if (count > shop.stock) {
                reply.fail("test/out_of_stock");
                return;
            }
            shop.stock -= count;
            reply.ok(shop.stock);
        });
        SHOP.onCall(SLOW, (shop, player, count, reply) -> { });    // never answers
        NPC.allowEdits((npc, player) -> true);
        rig.tick(3);
    }

    @After
    public void tearDown() {
        rig.close();
    }

    private CgNetObject seen(int client, CgNetType<?> type) {
        List<CgNetObject> visible = rig.client(client).visible(type);
        assertEquals(type.name() + " on client " + client, 1, visible.size());
        return visible.get(0);
    }

    /** What a reply came to: "ok <value>" or "refused <code>"; null while it waits. */
    private static <T> String[] outcome(CgReply<T> reply) {
        String[] seen = new String[1];
        reply.then(v -> seen[0] = "ok " + v).onError(e -> seen[0] = "refused " + e.code());
        return seen;
    }

    @SuppressWarnings("unchecked")
    private static <V> CgNetField<V> field(CgNetType<?> type, String key) {
        return (CgNetField<V>) type.field(key);
    }

    @Test
    public void everyRequestAnswersDoneOrRefusedWithItsReason() {
        Shop shop = new Shop();
        shop.owner = rig.client(0).id().toString();
        CgNetObject server = SHOP.over(shop);
        server.watch(rig.client(0).id());
        server.watch(rig.client(1).id());
        rig.tick(2);

        String[] renamed = outcome(seen(0, SHOP).ask(RENAME, "Capsule Corp"));
        String[] stranger = outcome(seen(1, SHOP).ask(RENAME, "Mine now"));
        String[] bought = outcome(seen(0, SHOP).call(BUY, 2));
        String[] tooMany = outcome(seen(1, SHOP).call(BUY, 5));
        rig.tick(3);
        assertEquals("ok null", renamed[0]);
        assertEquals("refused " + CgNetCommand.REFUSED_NOT_ALLOWED, stranger[0]);
        assertEquals("Capsule Corp", shop.name);
        assertEquals("ok 1", bought[0]);
        assertEquals("the handler's own reason", "refused test/out_of_stock", tooMany[0]);

        String[] slow = outcome(seen(0, SHOP).call(SLOW, 1));
        rig.tick((int) ClientObjects.REQUEST_TIMEOUT_TICKS + 2);
        assertEquals("every request ends", "refused " + CgReplyError.TIMEOUT, slow[0]);
    }

    @Test
    public void aFloodIsDroppedAtTheRateAndSizeCapsAndCounted() {
        Shop shop = new Shop();
        CgNetObject server = SHOP.over(shop);
        server.watch(rig.client(0).id());
        rig.tick(2);
        CgNetObject mine = seen(0, SHOP);
        long refusedBefore = SHOUT.refused(), droppedBefore = SHOUT.dropped();

        List<String[]> asked = new ArrayList<>();
        for (int i = 0; i < 50; i++) asked.add(outcome(mine.ask(SHOUT, "kame" + i)));
        String[] huge = outcome(mine.ask(SHOUT, "ha".repeat(200)));
        rig.tick(3);
        int done = 0, rate = 0;
        for (String[] a : asked) {
            if ("ok null".equals(a[0])) done++;
            if (("refused " + CgNetCommand.REFUSED_RATE).equals(a[0])) rate++;
        }
        assertEquals("a second's worth at once", 5, done);
        assertEquals(45, rate);
        assertEquals("refused " + CgNetCommand.REFUSED_TOO_LARGE, huge[0]);
        assertEquals(5, heard.size());
        assertEquals(46, SHOUT.dropped() - droppedBefore);
        assertEquals(46, SHOUT.refused() - refusedBefore);
    }

    @Test
    public void anNpcOpensAPartAtATimeAndEditsSendOneValue() {
        Npc npc = new Npc();
        npc.dialog.text = "kamehameha ".repeat(4800);                       // 52 KB
        npc.dialog.text = "DIALOG-MARK" + npc.dialog.text;
        rig.client(0).record();
        CgNetObject root = NPC.over(npc);
        root.watch(rig.client(0).id());
        root.watch(rig.client(1).id());
        NPC.part(npc, "stats").watch(rig.client(0).id());
        NPC.part(npc, "stats").watch(rig.client(1).id());
        rig.tick(3);
        long opened = rig.client(0).bytesReceived();
        assertFalse("the dialog part was not opened", contains(rig.client(0).recorded(), "DIALOG-MARK"));
        assertTrue("one part, not the 52 KB dialog: " + opened + " B", opened < 4_000);
        Npc mine = NPC.instance(seen(0, NPC));
        assertEquals("Bulma", mine.name);

        CgNetType<?> stats = CgNetType.named("test:request/npc/stats");
        CgNetType<?> display = CgNetType.named("test:request/npc/display");
        long before = rig.client(1).bytesReceived();
        String[] edit = outcome(seen(0, stats).edit(field(stats, "k07"), 70));
        String[] other = outcome(seen(1, stats).edit(field(stats, "k19"), 190));
        rig.tick(3);
        assertEquals("ok null", edit[0]);
        assertEquals("ok null", other[0]);
        assertEquals("two editors of different keys both land", 70, npc.stats.k07);
        assertEquals(190, npc.stats.k19);
        assertEquals("and reach the other editor", 70, NPC.instance(seen(1, NPC)).stats.k07);
        System.out.println("[request] npc: one part opened " + opened + " B; an edit reached the other editor in "
                + (rig.client(1).bytesReceived() - before) + " B");

        String[] readOnly = outcome(seen(0, NPC).edit(field(NPC, "level"), 99));
        assertEquals("refused " + CgNetCommand.REFUSED_READ_ONLY, readOnly[0]);

        NPC.allowEdits(Display.CODEC, (n, player) -> false);
        NPC.part(npc, "display").watch(rig.client(0).id());
        rig.tick(3);
        CgNetObject shown = seen(0, display);
        assertFalse("told with the part: not editable", shown.isEditable());
        assertTrue(seen(0, stats).isEditable());
        long refusedBefore = display.field("k01").command.refused();
        String[] refused = outcome(shown.edit(field(display, "k01"), 5));
        rig.tick(3);
        assertEquals("refused " + CgNetCommand.REFUSED_NOT_ALLOWED, refused[0]);
        assertEquals("nothing changed", 0, npc.display.k01);
        assertEquals("and put back here", 0, (int) shown.get(field(display, "k01")));
        assertEquals(1, display.field("k01").command.refused() - refusedBefore);

        NPC.part(npc, "dialog").watch(rig.client(0).id());
        rig.tick(4);
        assertEquals(npc.dialog.text, NPC.instance(seen(0, NPC)).dialog.text);

        // nine child objects open: what a quiet tick of reading them costs the server
        for (String part : List.of("ai", "combat", "loot", "sounds", "quests", "roles")) NPC.part(npc, part).watch(rig.client(0).id());
        rig.tick(4);
        long start = System.nanoTime();
        rig.tick(200);
        System.out.println("[request] npc with nine parts watched: " + (System.nanoTime() - start) / 200 / 1000
                + " us a rig tick, 160 keys and a 52 KB text compared by encoding");
    }

    @Test
    public void aMapOfFiveThousandOpensWithItsKeysAndLoadsAnEntryWhenAsked() {
        Npc npc = new Npc();
        for (int i = 0; i < 5000; i++) npc.quests.progress.put("q" + i, i);
        CgNetObject root = NPC.over(npc);
        for (int c = 0; c < 2; c++) {
            root.watch(rig.client(c).id());
            NPC.part(npc, "quests").watch(rig.client(c).id());
        }
        long before = rig.client(0).bytesReceived();
        rig.tick(3);
        CgNetType<?> quests = CgNetType.named("test:request/npc/quests");
        Npc mine = NPC.instance(seen(0, NPC));
        List<?> keys = seen(0, quests).get(field(quests, "progress"));
        assertEquals("its keys", 5000, keys.size());
        assertTrue("and no entry", mine.quests.progress.isEmpty());
        System.out.println("[request] 5,000 keys opened in " + (rig.client(0).bytesReceived() - before) + " B");

        @SuppressWarnings("unchecked") CgNetType<Quests> typed = (CgNetType<Quests>) quests;
        String[] opened = outcome(typed.open(seen(0, quests), "progress", "q42"));
        rig.tick(3);
        assertEquals("ok true", opened[0]);
        assertEquals(Integer.valueOf(42), mine.quests.progress.get("q42"));
        assertEquals(1, mine.quests.progress.size());

        String[] put = outcome(typed.put(seen(1, quests), "progress", "q-new", 7));
        rig.tick(3);
        assertEquals("ok true", put[0]);
        assertEquals(Integer.valueOf(7), npc.quests.progress.get("q-new"));
        List<?> now = seen(0, quests).get(field(quests, "progress"));
        assertTrue("the other editor sees the new key", now.contains("q-new"));
    }

    @Test
    public void aClientAFieldBehindAndAFieldAheadBothPlay() {
        rig.client(0).readsAs("test:request/pet", "test:request/pet_behind");
        rig.client(1).readsAs("test:request/pet", "test:request/pet_ahead");
        PetNow pet = new PetNow();
        CgNetObject server = PET_NOW.over(pet);
        server.watch(rig.client(0).id());
        server.watch(rig.client(1).id());
        pet.b = 20;
        rig.tick(3);
        PetBehind behind = PET_BEHIND.instance(seen(0, PET_BEHIND));
        PetAhead ahead = PET_AHEAD.instance(seen(1, PET_AHEAD));
        assertEquals("a field it lacks is ignored", 20, behind.b);
        assertEquals(20, ahead.b);
        assertEquals(3, ahead.c);
        assertEquals("a field the server lacks keeps its default", 44, ahead.d);
        String[] trick = outcome(seen(1, PET_AHEAD).ask(TRICK, 1));
        assertEquals("a command the server lacks is refused with its reason", "refused " + CgNetCommand.REFUSED_UNKNOWN, trick[0]);
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
