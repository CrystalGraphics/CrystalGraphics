package com.crystalgraphics.net.object;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.crystalgraphics.platform.service.CgStorageLayout;
import com.crystalgraphics.serialization.CgCodecs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Entity, player and chunk anchors, and per-holder attachments, on the rig. */
public class CgNetAnchorsTest {

    static final CgNetField<Integer> CURRENT = CgNetField.ofInt("current");

    static final CgNetType<CgNetObject> AURA = CgNetType.define("test:aura").fields(CURRENT).build();
    static final CgNetType<CgNetObject> QUEST = CgNetType.define("test:quest").fields(CURRENT).build();
    static final CgNetType<CgNetObject> BADGE = CgNetType.define("test:badge").fields(CURRENT).visibleToTrackers().build();
    static final CgNetType<CgNetObject> MANA = CgNetType.define("test:mana").fields(CURRENT).perHolder().persisted().copyOnDeath().build();
    static final CgNetType<CgNetObject> WARD = CgNetType.define("test:ward").fields(CURRENT).persisted().build();
    static final CgNetType<CgNetObject> WARDS = CgNetType.define("test:wards").fields(CURRENT).perHolder().persisted().build();
    static final CgNetType<CgNetObject> GUARD = CgNetType.define("test:guard").fields(CURRENT).perHolder().build();
    static final CgNetType<CgNetObject> FOCUS = CgNetType.define("test:focus").fields(CURRENT).perHolder().persisted().build();
    static final CgStorageLayout OWN = CgStorageLayout.of("ownmod");
    static {
        CgNetType.storage("ownmod", OWN);
    }
    static final CgNetType<CgNetObject> OWN_POOL = CgNetType.define("ownmod:pool").fields(CURRENT).perHolder().persisted().build();
    static final CgNetType<CgNetObject> OWN_SHRINE = CgNetType.define("ownmod:shrine").fields(CURRENT).persisted().build();

    @Rule
    public final TemporaryFolder world = new TemporaryFolder();

    private final List<String> heard = new ArrayList<>();
    private CgNetTestRig rig;

    @Before
    public void setUp() {
        rig = CgNetTestRig.of(3).saveTo(world.getRoot().toPath());
        for (CgNetType<?> type : new CgNetType[]{AURA, QUEST, BADGE, MANA, WARD, WARDS}) {
            type.onClient(obj -> new CgNetBehaviour() {
                @Override
                public void despawned(CgNetObject o, boolean killed) {
                    heard.add(o.type().name() + (killed ? " killed" : " despawned"));
                }
            });
        }
        rig.tick(3);
    }

    @After
    public void tearDown() {
        rig.close();
    }

    private int sees(int client, CgNetType<?> type) {
        return rig.client(client).visible(type).size();
    }

    @Test
    public void findAnswersAHoldersObjectOnEitherSideAndMakesNothing() {
        CgNetTestRig.Entity zombie = rig.entity(10, 64, 0);
        assertNull("the server's find makes nothing", GUARD.find(zombie));
        CgNetObject mana = MANA.of(rig.client(0).id());
        CgNetObject guard = GUARD.of(zombie);
        assertSame(mana, MANA.find(rig.client(0).id()));
        assertSame(guard, GUARD.find(zombie));
        rig.tick(1);
        assertEquals("a client finds its own by its player's entity", mana.id(), rig.client(0).find(MANA, 1).id());
        assertEquals("and an entity's it sees", guard.id(), rig.client(1).find(GUARD, zombie.id()).id());
        assertNull("another player's own it does not see", rig.client(1).find(MANA, 1));

        zombie.kill();
        rig.tick(1);
        assertNull("gone with its entity", rig.client(1).find(GUARD, zombie.id()));
    }

    @Test
    public void anEntitysObjectIsSeenByItsTrackersAndEndsWithIt() {
        CgNetTestRig.Entity zombie = rig.entity(10, 64, 0);
        rig.client(2).moveTo(500, 64, 0);
        AURA.spawn(CgAnchor.on(zombie)).start();
        rig.tick(1);
        assertEquals(1, sees(0, AURA));
        assertEquals("out of tracking range", 0, sees(2, AURA));
        assertEquals("clients find the entity by its network id", zombie.id(), rig.client(0).visible(AURA).get(0).entityId());

        zombie.moveTo(490, 64, 0);
        rig.tick(1);
        assertEquals(0, sees(0, AURA));
        assertEquals("it follows the entity", 1, sees(2, AURA));

        zombie.kill();
        rig.tick(1);
        assertEquals(0, sees(2, AURA));
        assertEquals(0, AURA.size());
        assertEquals("clients 0 and 1 lost it walking away; client 2 saw it end",
                List.of("test:aura killed", "test:aura killed", "test:aura despawned"), heard);
    }

    @Test
    public void anEntitysObjectEndsWhenItChangesDimension() {
        CgNetTestRig.Entity zombie = rig.entity(0, 64, 0);
        AURA.spawn(CgAnchor.on(zombie)).start();
        rig.tick(1);
        zombie.dimension("rig:nether");
        rig.tick(1);
        assertEquals(0, AURA.size());
    }

    @Test
    public void aPlayersEntityObjectIsSeenByThatPlayerToo() {
        CgNetTestRig.Client far = rig.client(2).moveTo(500, 64, 0);
        AURA.spawn(CgAnchor.on(far)).start();
        rig.tick(1);
        assertEquals("the player itself", 1, sees(2, AURA));
        assertEquals("out of its range", 0, sees(0, AURA));
    }

    @Test
    public void aPlayersObjectIsTheirsAloneAndFollowsThemAcrossDimensions() {
        QUEST.spawn(CgAnchor.player(rig.client(0).id())).start();
        BADGE.spawn(CgAnchor.player(rig.client(0).id())).start();
        rig.tick(1);
        assertEquals(1, sees(0, QUEST));
        assertEquals(0, sees(1, QUEST));
        assertTrue("its player owns it unless told otherwise", rig.client(0).visible(QUEST).get(0).isMine());
        assertEquals("visibleToTrackers", 1, sees(1, BADGE));

        rig.client(0).dimension("rig:nether");
        rig.tick(1);
        CgNetObject quest = rig.client(0).visible(QUEST).get(0);
        assertEquals("rig:nether", quest.dimension());
        assertEquals("the trackers it left behind lose it", 0, sees(1, BADGE));
        assertEquals(1, sees(0, BADGE));
    }

    @Test
    public void aPlayersObjectEndsAtDeathUnlessCopiedAndLeavesWithThemIntoTheirFile() {
        QUEST.spawn(CgAnchor.player(rig.client(0).id())).start();
        CgNetObject mana = MANA.of(rig.client(0).id()).setInt(CURRENT, 40);
        rig.tick(1);
        rig.client(0).die();
        rig.tick(1);
        assertEquals(0, QUEST.size());
        assertFalse("copyOnDeath", mana.isRemoved());
        rig.client(0).respawn();
        rig.tick(1);
        assertEquals(40, rig.client(0).visible(MANA).get(0).getInt(CURRENT));

        QUEST.spawn(CgAnchor.player(rig.client(0).id())).start();
        rig.tick(1);
        rig.client(0).disconnect();
        rig.tick(1);
        assertEquals(0, QUEST.size());
        assertTrue("persisted: it leaves with them", mana.isRemoved());
        assertEquals("written to their file as they left", 40, saved(0, MANA));
        rig.client(0).connect();
        rig.tick(3);
        assertEquals("made from their file as they join", 40, rig.client(0).visible(MANA).get(0).getInt(CURRENT));
    }

    @Test
    public void anAttachmentIsOnePerHolderAndSurvivesARestart() {
        CgNetObject mana = MANA.of(rig.client(0).id());
        assertSame(mana, MANA.of(rig.client(0).id()));
        mana.setInt(CURRENT, 70);
        MANA.of(rig.client(1).id()).setInt(CURRENT, 20);
        assertThrows(IllegalStateException.class, () -> MANA.spawn(CgAnchor.global()));
        assertThrows(IllegalStateException.class, () -> QUEST.of(rig.client(0).id()));
        rig.tick(1);

        rig.restart().tick(3);
        CgNetObject again = MANA.of(rig.client(0).id());
        assertEquals("loaded, not made anew", 70, again.getInt(CURRENT));
        assertEquals(2, MANA.size());
        assertEquals(70, rig.client(0).visible(MANA).get(0).getInt(CURRENT));
        assertEquals("theirs alone", 20, rig.client(1).visible(MANA).get(0).getInt(CURRENT));
    }

    @Test
    public void anAbsentPlayersObjectIsReadFromTheirFileAndWrittenBack() {
        UUID away = rig.client(1).id();
        MANA.of(away).setInt(CURRENT, 55);
        rig.tick(1);
        rig.client(1).disconnect();
        rig.tick(2);

        CgNetObject mana = MANA.of(away);
        assertEquals("read from their file while they are away", 55, mana.getInt(CURRENT));
        mana.setInt(CURRENT, 60);
        rig.tick(2);
        assertFalse("waits for them", mana.isRemoved());
        assertTrue(mana.isDormant());

        rig.restart().tick(1);
        assertEquals("written to their file as the server stopped", 60, saved(1, MANA));
        rig.client(1).connect();
        rig.tick(3);
        assertEquals("theirs as they join", 60, rig.client(1).visible(MANA).get(0).getInt(CURRENT));
    }

    @Test
    public void anEditByHandIsReadAndItsCommentsKept() throws Exception {
        UUID away = rig.client(1).id();
        MANA.of(away).setInt(CURRENT, 10);
        rig.tick(1);
        rig.client(1).disconnect();
        rig.tick(2);
        Path file = PlayerFiles.path(new PlayerFiles.File(away, CgNetType.DEFAULT_STORAGE));
        assertTrue("written as they left", Files.readString(file).contains("10"));

        // an admin's fix: a comment, a new value, a trailing comma
        Files.writeString(file, """
                {
                  // raised by an admin
                  "test:mana": { "current": 99, },
                }
                """);
        CgNetObject mana = MANA.of(away);
        assertEquals("their edit, read", 99, mana.getInt(CURRENT));
        mana.setInt(CURRENT, 100);
        rig.restart().tick(1);
        String text = Files.readString(file);
        assertTrue(text, text.contains("// raised by an admin"));
        assertTrue(text, text.contains("100"));
        assertTrue("the last one kept", Files.isRegularFile(file.resolveSibling(file.getFileName() + ".old")));
    }

    @Test
    public void anObjectEndedByDeathLeavesTheirFile() {
        UUID id = rig.client(0).id();
        FOCUS.of(id).setInt(CURRENT, 5);
        rig.tick(1);
        PlayerFiles.writeAll();
        assertEquals(5, saved(0, FOCUS));
        rig.client(0).die();
        rig.tick(1);
        PlayerFiles.writeAll();
        assertFalse("ended at death: not brought back", file(id, CgNetType.DEFAULT_STORAGE).containsKey(FOCUS.name()));
        rig.client(0).respawn();
        rig.tick(1);
        assertEquals(0, FOCUS.of(id).getInt(CURRENT));
    }

    @Test
    public void aNamespaceWithItsOwnLayoutSavesUnderItsOwnFolder() {
        UUID id = rig.client(0).id();
        OWN_POOL.of(id).setInt(CURRENT, 7);
        MANA.of(id).setInt(CURRENT, 3);
        OWN_SHRINE.spawn(rig.at(0, 64, 0)).start().setInt(CURRENT, 9);
        rig.tick(1);
        rig.restart().tick(1);

        Path world = this.world.getRoot().toPath();
        assertTrue(Files.isRegularFile(world.resolve("ownmod").resolve("players").resolve(id + ".jsonc")));
        assertTrue(Files.isRegularFile(world.resolve("ownmod").resolve("objects").resolve("shrine.jsonc")));
        assertFalse("its own file alone", file(id, CgNetType.DEFAULT_STORAGE).containsKey(OWN_POOL.name()));
        assertFalse(file(id, OWN).containsKey(MANA.name()));
        assertEquals(3, saved(0, MANA));
        assertEquals("back from its own file", 7, OWN_POOL.of(id).getInt(CURRENT));
        int[] shrine = {0};
        OWN_SHRINE.forEach(obj -> shrine[0] = obj.getInt(CURRENT));
        assertEquals("back from its own folder", 9, shrine[0]);
    }

    private int saved(int client, CgNetType<?> type) {
        Map<?, ?> file = file(rig.client(client).id(), CgNetType.storageOf(type.namespace()));
        return ((Number) ((Map<?, ?>) file.get(type.name())).get("current")).intValue();
    }

    /** The player's file as it is on disk. */
    private static Map<?, ?> file(UUID player, CgStorageLayout layout) {
        Object read = SaveFiles.read(PlayerFiles.path(new PlayerFiles.File(player, layout)), CgCodecs.PLAIN);
        return read instanceof Map ? (Map<?, ?>) read : Map.of();
    }

    @Test
    public void aChunksObjectIsDormantWhileTheChunkIsUnloaded() {
        rig.client(2).moveTo(500, 64, 0);
        CgNetObject ward = WARD.spawn(CgAnchor.chunk(CgNetTestRig.DIMENSION, 1, -1)).start();
        rig.tick(1);
        assertEquals(1, sees(0, WARD));
        assertEquals("beyond the view distance", 0, sees(2, WARD));
        assertEquals(-1, rig.client(0).visible(WARD).get(0).chunkZ());

        rig.unloadChunk(1, -1).tick(1);
        assertTrue(ward.isDormant());
        assertEquals(0, sees(0, WARD));
        assertEquals("kept, not ended", 1, WARD.size());
        rig.loadChunk(1, -1).tick(1);
        assertEquals(1, sees(0, WARD));
    }

    @Test
    public void aChunkAttachmentIsFoundAgainAfterARestart() {
        WARDS.ofChunk(CgNetTestRig.DIMENSION, 2, 3).setInt(CURRENT, 5);
        rig.tick(1);
        rig.restart().tick(3);
        assertEquals(5, WARDS.ofChunk(CgNetTestRig.DIMENSION, 2, 3).getInt(CURRENT));
        assertEquals(1, WARDS.size());
        assertEquals(1, sees(0, WARDS));
    }

    @Test
    public void aPersistedTypeRefusesAnEntityAnchor() {
        CgNetTestRig.Entity zombie = rig.entity(0, 64, 0);
        assertThrows(IllegalArgumentException.class, () -> WARD.spawn(CgAnchor.on(zombie)));
    }
}
