package com.crystalgraphics.net.object;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** A keyed list that sends what changed in it, whole to a late viewer. */
public class CgNetListTest {

    record Offer(String id, int price) {
        static final CgCodec<Offer> CODEC = CgCodecs.record(
                CgCodecs.field("i", CgCodecs.STRING, Offer::id),
                CgCodecs.field("p", CgCodecs.INT, Offer::price),
                Offer::new);
    }

    static final CgNetList<Offer> OFFERS = CgNetList.of("offers", Offer.CODEC, Offer::id).persisted();
    static final CgNetType<CgNetObject> TRADER = CgNetType.define("test:trader").fields(OFFERS).range(32).build();

    @Rule
    public final TemporaryFolder world = new TemporaryFolder();
    private final List<String> heard = new ArrayList<>();
    private CgNetTestRig rig;

    @Before
    public void setUp() {
        rig = CgNetTestRig.of(2);
        rig.tick(3);   // the hellos
    }

    @After
    public void tearDown() {
        rig.close();
    }

    private List<Offer> seen(int client) {
        return rig.client(client).visible(TRADER).get(0).list(OFFERS).snapshot();
    }

    private void listen(int client) {
        rig.client(client).visible(TRADER).get(0).list(OFFERS).listen(new CgNetList.Listener<>() {
            @Override
            public void added(int index, Offer offer) {
                heard.add("added " + index + " " + offer.id());
            }

            @Override
            public void removed(int index, Offer offer) {
                heard.add("removed " + index + " " + offer.id());
            }

            @Override
            public void replaced(int index, Offer was, Offer now) {
                heard.add("replaced " + index + " " + was.price() + ">" + now.price());
            }

            @Override
            public void moved(int from, int to) {
                heard.add("moved " + from + ">" + to);
            }

            @Override
            public void reset() {
                heard.add("reset");
            }
        });
    }

    @Test
    public void eachOperationReachesTheClientInOrder() {
        CgNetObject trader = TRADER.spawn(rig.at(0, 0, 0)).start();
        trader.list(OFFERS).add(new Offer("a", 1)).add(new Offer("b", 2));
        rig.tick(2);
        assertEquals(List.of(new Offer("a", 1), new Offer("b", 2)), seen(0));
        listen(0);

        CgNetList.Entries<Offer> offers = trader.list(OFFERS);
        offers.add(new Offer("c", 3));
        offers.add(0, new Offer("d", 4));
        offers.set("a", new Offer("a", 9));
        offers.move("c", 1);
        offers.remove("b");
        rig.tick(2);
        assertEquals(List.of("added 2 c", "added 0 d", "replaced 1 1>9", "moved 3>1", "removed 3 b"), heard);
        assertEquals(offers.snapshot(), seen(0));
        assertEquals(List.of(new Offer("d", 4), new Offer("c", 3), new Offer("a", 9)), seen(0));

        heard.clear();
        offers.clear();
        rig.tick(2);
        assertEquals(List.of("reset"), heard);
        assertTrue(seen(0).isEmpty());
    }

    @Test
    public void aLateViewerGetsItWhole() {
        rig.client(1).moveTo(500, 0, 0);
        CgNetObject trader = TRADER.spawn(rig.at(0, 0, 0)).start();
        CgNetList.Entries<Offer> offers = trader.list(OFFERS);
        for (int i = 0; i < 20; i++) offers.add(new Offer("o" + i, i));
        rig.tick(2);
        offers.remove("o3").set("o7", new Offer("o7", 70)).move("o0", 19);
        rig.tick(2);
        assertTrue(rig.client(1).visible(TRADER).isEmpty());
        rig.client(1).moveTo(0, 0, 0);
        rig.tick(2);
        assertEquals("whole, as the server holds it", offers.snapshot(), seen(1));
        assertEquals(seen(0), seen(1));
    }

    @Test
    public void oneChangeInATenThousandEntryListSendsOneEntry() {
        CgNetObject trader = TRADER.spawn(rig.at(0, 0, 0)).start();
        CgNetList.Entries<Offer> offers = trader.list(OFFERS);
        for (int i = 0; i < 10_000; i++) offers.add(new Offer("o" + i, i));
        long before = rig.client(0).bytesReceived();
        rig.tick(4);
        long whole = rig.client(0).bytesReceived() - before;
        assertEquals(10_000, seen(0).size());

        before = rig.client(0).bytesReceived();
        offers.set("o5000", new Offer("o5000", -1));
        rig.tick(2);
        long one = rig.client(0).bytesReceived() - before;
        assertEquals(new Offer("o5000", -1), seen(0).get(5000));
        System.out.println("[net-list] 10,000 entries whole: " + whole + " B; one change: " + one + " B");
        assertTrue("one entry, not the list: " + one + " B", one < 64);
    }

    @Test
    public void aPersistedListComesBackAfterARestart() throws Exception {
        CgNetObject trader = TRADER.spawn(rig.at(0, 0, 0)).start();
        trader.list(OFFERS).add(new Offer("a", 1)).add(new Offer("b", 2));
        rig.tick(2);
        rig.saveTo(world.getRoot().toPath()).restart();
        rig.tick(4);
        List<CgNetObject> back = new ArrayList<>();
        TRADER.forEach(back::add);
        assertEquals(1, back.size());
        assertEquals(List.of(new Offer("a", 1), new Offer("b", 2)), back.get(0).list(OFFERS).snapshot());
        back.get(0).list(OFFERS).add(new Offer("c", 3));
        rig.tick(2);
        assertEquals(3, seen(0).size());
    }
}
