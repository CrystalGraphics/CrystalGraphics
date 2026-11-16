package com.crystalgraphics.net.object;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.world.CgPlayers;
import com.crystalgraphics.serialization.CgCodecs;
import com.crystalgraphics.serialization.nbt.CgNbt;
import com.crystalgraphics.serialization.nbt.CgNbtCompound;
import com.crystalgraphics.world.CgEntitySaves;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Entity, player and chunk anchors, and per-holder attachments, on the rig. */
public class CgNetAnchorsTest {

    static final CgNetField<Integer> CURRENT = CgNetField.ofInt("current");

    static final CgNetType AURA = CgNetType.define("test:aura").fields(CURRENT).build();
    static final CgNetType QUEST = CgNetType.define("test:quest").fields(CURRENT).build();
    static final CgNetType BADGE = CgNetType.define("test:badge").fields(CURRENT).visibleToTrackers().build();
    static final CgNetType MANA = CgNetType.define("test:mana").fields(CURRENT).perHolder().persisted().copyOnDeath().build();
    static final CgNetType WARD = CgNetType.define("test:ward").fields(CURRENT).persisted().build();
    static final CgNetType WARDS = CgNetType.define("test:wards").fields(CURRENT).perHolder().persisted().build();
    static final CgNetType GUARD = CgNetType.define("test:guard").fields(CURRENT).perHolder().build();

    @Rule
    public final TemporaryFolder world = new TemporaryFolder();

    private final List<String> heard = new ArrayList<>();
    private CgNetTestRig rig;
    /** What the game saves with each player, by profile: their file outlives their entity, as playerdata does. */
    private final Map<Object, CgNbtCompound> playerSaves = new HashMap<>();

    @Before
    public void setUp() {
        CgPlatform.provide(CgEntitySaves.HOST, new CgEntitySaves.Host() {
            @Override public CgNbtCompound read(Object entity) { return playerSaves.get(key(entity)); }
            @Override public void write(Object entity, CgNbtCompound data) { playerSaves.put(key(entity), data); }
            @Override public boolean savesWhenSaved() { return false; }
            @Override public String[] savedPath() { return new String[] {"ForgeData", "crystalgraphics"}; }

            private Object key(Object entity) {
                UUID player = CgPlayers.idOf(entity);
                return player != null ? player : entity;
            }
        });
        rig = CgNetTestRig.of(3).saveTo(world.getRoot().toPath());
        for (CgNetType type : new CgNetType[]{AURA, QUEST, BADGE, MANA, WARD, WARDS}) {
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
        CgPlatform.provide(CgEntitySaves.HOST, null);
        rig.close();
    }

    private int sees(int client, CgNetType type) {
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
    public void aPlayersObjectEndsAtDeathUnlessCopiedAndWhenTheyLeaveSavedWithThemIfPersisted() {
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
        assertTrue("persisted: it leaves with them, saved in their own data", mana.isRemoved());
        assertEquals(40, (int) ((CgNbtCompound) playerSaves.get(rig.client(0).id()).get(MANA.name())).get("current", CgCodecs.INT));
        rig.client(0).connect();
        rig.tick(3);
        CgNetObject back = MANA.of(rig.client(0).id());
        assertEquals("read back as they return", 40, back.getInt(CURRENT));
        rig.tick(1);
        assertEquals(40, rig.client(0).visible(MANA).get(0).getInt(CURRENT));
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
        assertEquals("each comes back with its player", 20, MANA.of(rig.client(1).id()).getInt(CURRENT));
        assertEquals(2, MANA.size());
        rig.tick(1);
        assertEquals(70, rig.client(0).visible(MANA).get(0).getInt(CURRENT));
        assertEquals("theirs alone", 20, rig.client(1).visible(MANA).get(0).getInt(CURRENT));
    }

    @Test
    public void anOfflinePlayersObjectIsReadFromTheirFileAndWrittenBack() throws IOException {
        UUID away = rig.client(1).id();
        rig.client(1).disconnect();
        rig.tick(2);
        // their file, as the game keeps it in playerdata: the game's own data beside ours, under Forge's key
        CgNbtCompound mine = new CgNbtCompound().put(MANA.name(), new CgNbtCompound().put("current", 55));
        CgNbtCompound file = new CgNbtCompound().put("Health", 20f)
                .put("ForgeData", new CgNbtCompound().put("other_mod", 1).put("crystalgraphics", mine));
        Path data = world.getRoot().toPath().resolve("playerdata");
        Files.createDirectories(data);
        try (OutputStream out = Files.newOutputStream(data.resolve(away + ".dat"))) {
            CgNbt.writeBinary(out, file);
        }

        CgNetObject mana = MANA.of(away);
        assertEquals("read from their file while they are away", 55, mana.getInt(CURRENT));
        mana.setInt(CURRENT, 60);
        rig.tick(2);
        assertFalse("waits for them, not ended", mana.isRemoved());

        rig.restart().tick(1);
        CgNbtCompound written;
        try (InputStream in = Files.newInputStream(data.resolve(away + ".dat"))) {
            written = CgNbt.readBinary(in);
        }
        CgNbtCompound forge = (CgNbtCompound) written.get("ForgeData");
        assertEquals("written back into their file as the server stopped", 60,
                (int) ((CgNbtCompound) ((CgNbtCompound) forge.get("crystalgraphics")).get(MANA.name())).get("current", CgCodecs.INT));
        assertEquals("the rest of it as it was", 1, (int) forge.get("other_mod", CgCodecs.INT));
        assertEquals(20f, written.get("Health", CgCodecs.FLOAT), 0f);
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
