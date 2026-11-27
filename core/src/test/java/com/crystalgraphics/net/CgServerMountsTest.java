package com.crystalgraphics.net;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.crystalgraphics.net.object.CgNetTestRig;
import com.crystalgraphics.util.io.CgIO;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** A server's files mounted on a client without its mod: fetched by hash, cached, checked, and gone with the connection. */
public class CgServerMountsTest {

    @Rule
    public final TemporaryFolder temp = new TemporaryFolder();

    /** Ticks for a hello (resent each second until answered), an index and its bodies. */
    private static final int SETTLE = 30;

    private Path served;
    private CgNetTestRig rig;

    @Before
    public void setUp() throws IOException {
        served = temp.newFolder("served").toPath();
        write("lang/en_us.json", "{\"shop.title\": \"Stall\"}");
        write("lang/fr_fr.json", "{\"shop.title\": \"Echoppe\"}");
        write("textures/coin.png", "not really a png");
        write("net_types/stall.yaml", "fields:\n  price: { kind: int }\n");
        write("shaders/glow.vert", "void main() {}");                  // a kind no client takes: never offered
        CgServerMounts.cacheIn(temp.newFolder("cache").toPath());
        CgServerMounts.cacheCap(256L * 1024 * 1024);
    }

    @After
    public void tearDown() {
        if (rig != null) rig.close();
        CgServerMounts.withdraw("shop");
    }

    private void write(String path, String text) throws IOException {
        Path to = served.resolve(path);
        Files.createDirectories(to.getParent());
        Files.write(to, text.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(String id) throws IOException {
        try (InputStream in = CgIO.openStream(id)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    public void aMountIsFetchedOnceServedFromTheCacheAfterAndGoneWithItsConnection() throws IOException {
        CgServerMounts.offer("shop", served);
        long before = CgServerMounts.fetched();
        rig = CgNetTestRig.of(1);
        rig.tick(SETTLE);
        assertTrue(CgServerMounts.mounted().contains("shop"));
        assertEquals("{\"shop.title\": \"Stall\"}", read("shop:lang/en_us.json"));
        assertNull("the player's language and en_us alone", read("shop:lang/fr_fr.json"));
        assertNull("a kind off the list is never offered", read("shop:shaders/glow.vert"));
        assertEquals("three files fetched", 3, CgServerMounts.fetched() - before);

        rig.client(0).disconnect();
        rig.tick(2);
        assertFalse("unmounted with its connection", CgServerMounts.mounted().contains("shop"));
        assertNull(read("shop:lang/en_us.json"));

        rig.client(0).connect();
        rig.tick(SETTLE);
        assertTrue(CgServerMounts.mounted().contains("shop"));
        assertEquals("the second join is served from the cache", 3, CgServerMounts.fetched() - before);
    }

    @Test
    public void aFileTheServerWritesIsMountedAsAFileItsFolderHolds() throws IOException {
        CgServerMounts.offer("shop", served)
                .generated("net_types/stall.yaml", () -> "fields:\n  price: { kind: long }\n".getBytes(StandardCharsets.UTF_8))
                .generated("lang/de_de.json", () -> "{}".getBytes(StandardCharsets.UTF_8));
        rig = CgNetTestRig.of(1);
        rig.tick(SETTLE);
        assertEquals("the written one in place of the folder's", "fields:\n  price: { kind: long }\n", read("shop:net_types/stall.yaml"));
    }

    @Test
    public void theCacheKeepsUnderItsCapLeastRecentlyUsedFirstOut() throws IOException {
        CgServerMounts.cacheCap(40);
        CgServerMounts.offer("shop", served);
        rig = CgNetTestRig.of(1);
        rig.tick(SETTLE);
        long total = 0;
        Path store = cacheStore();
        try (Stream<Path> files = Files.list(store)) {
            for (Path p : (Iterable<Path>) files::iterator) total += Files.size(p);
        }
        assertTrue("past the cap the oldest go: " + total + " B kept", total <= 40);
    }

    @Test
    public void aBodyThatDoesNotHashToItsNameIsDroppedAndCounted() throws IOException {
        CgServerMounts.Offer offer = CgServerMounts.offer("shop", served);
        String named = null;
        for (CgServerMounts.Entry e : offer.manifest().entries()) if (e.path().equals("lang/en_us.json")) named = e.hash();
        write("lang/en_us.json", "{\"shop.title\": \"Changed after naming\"}");   // the server now serves other bytes
        long mismatched = CgServerMounts.mismatched();
        rig = CgNetTestRig.of(1);
        rig.tick(SETTLE);
        assertEquals(1, CgServerMounts.mismatched() - mismatched);
        assertFalse("never stored under the hash it was named", Files.exists(cacheStore().resolve(named)));
    }

    @Test
    public void aKindOffTheListIsRefusedByName() {
        CgServerMounts.allowKind("vertex_shader", path -> path.endsWith(".vert"));
        CgServerMounts.Offer offer = CgServerMounts.offer("shop", served);
        offer.manifest();
        CgServerMounts.KINDS.remove("vertex_shader");      // the client does not take what the server listed
        long refused = CgServerMounts.refusedKinds();
        rig = CgNetTestRig.of(1);
        rig.tick(SETTLE);
        assertEquals(1, CgServerMounts.refusedKinds() - refused);
        assertTrue(CgServerMounts.mounted().contains("shop"));
    }

    @Test
    public void aClientBelowTheFloorIsRefusedAndSentNoBody() {
        CgServerMounts.offer("shop", served).floor("test.format", 3);
        CgServerMounts.supports("test.format", 2);
        long before = CgServerMounts.fetched();
        rig = CgNetTestRig.of(1);
        rig.tick(SETTLE);
        assertFalse(CgServerMounts.mounted().contains("shop"));
        assertEquals("no body", 0, CgServerMounts.fetched() - before);
    }

    @Test
    public void nothingIsSentToAClientThatHasTheNamespace() {
        CgServerMounts.offer("crystalgraphics", served);   // a namespace this client declares messages in
        long before = CgServerMounts.fetched();
        rig = CgNetTestRig.of(1);
        rig.tick(SETTLE);
        assertEquals(0, CgServerMounts.fetched() - before);
        assertFalse(CgServerMounts.mounted().contains("crystalgraphics"));
        CgServerMounts.withdraw("crystalgraphics");
    }

    @Test
    public void anEditToADevSourceIsSeenByAConnectedClient() throws IOException {
        CgServerMounts.offer("shop", served);
        rig = CgNetTestRig.of(1);
        rig.tick(SETTLE);
        write("lang/en_us.json", "{\"shop.title\": \"Edited\"}");
        rig.tick(30);
        assertEquals("{\"shop.title\": \"Edited\"}", read("shop:lang/en_us.json"));
    }

    private Path cacheStore() {
        return temp.getRoot().toPath().resolve("cache").resolve("bodies");
    }
}
