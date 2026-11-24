package com.crystalgraphics.world.actor;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.BeforeClass;
import org.junit.Test;

import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.asset.kind.CgBodyKinds;
import com.crystalgraphics.asset.load.CgVfxAssets;
import com.crystalgraphics.asset.registry.CgAssetRegistry;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.mc.item.CgItems;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.serialization.nbt.CgNbtCompound;
import com.crystalgraphics.world.CgEntityProperties;

/** An actor file: read into what a Java registration says, refused by name, registered at mount. */
public class CgActorFilesTest {

    @BeforeClass
    public static void kinds() {
        CgAssetKinds.register();
    }

    private static CgActorFiles.Definition compile(String id, String yaml) {
        return CgActorFiles.compile(CgAssetData.of(id, CgBodyKinds.ACTOR, (Map<?, ?>) CgAssetYaml.read("_v: 1\n" + yaml), null),
                (kind, ref) -> null);
    }

    @Test
    public void aFileReadsAsItsRegistration() {
        CgActorFiles.Definition d = compile("filetest:golem", """
                binds: { entities: [ mymod:golem, mymod:big_golem ] }
                prefab: mymod:golem
                anim_graph: mymod:golem_locomotion
                root_motion: body
                reactions:
                  - { event: hurt, sequence: mymod:flinch }
                  - { event: landed, sequence: mymod:land, slot: FullBody, when: { variable: entity.land_height, op: ">", value: 2 } }
                """);
        assertEquals(List.of("mymod:golem", "mymod:big_golem"), d.entities());
        assertFalse(d.localPlayer());
        assertEquals("mymod:golem_locomotion", d.graph());
        assertEquals("body", d.rootMotion());
        assertNull(d.rig());
        assertEquals(CgEntityProperties.HURT, d.reactions().get(0).event());
        assertEquals("DefaultSlot", d.reactions().get(0).slot());
        CgReaction land = d.reactions().get(1);
        assertEquals(CgEntityProperties.LANDED, land.event());
        assertEquals("FullBody", land.slot());
        assertTrue("its rule over the entity's properties", land.when() != null);
    }

    @Test
    public void aFileBindingNothingWaitsForCodeAndCarriesItsOutline() {
        CgActorFiles.Definition d = compile("filetest:hero", """
                prefab: mymod:hero
                outline: { render_custom_depth: true, custom_depth_stencil_value: 3 }
                post: [mymod:stage/chara_colour]
                """);
        assertFalse(d.localPlayer());
        assertTrue("bound by code or /cgc actor, never by type", d.entities().isEmpty());
        assertEquals(3, d.outline());
        assertEquals(List.of("mymod:stage/chara_colour"), d.post());
        assertTrue("none unless named", compile("filetest:plain", "prefab: mymod:hero\n").post().isEmpty());
        assertEquals("no outline unless its body writes custom depth", -1, compile("filetest:plain", "prefab: mymod:hero\n").outline());
    }

    @Test
    public void heldItemsChooseEachLayersGraphTheMainHandFirst() {
        CgPlatform.provide(CgItems.HOST, ITEMS);
        try {
            CgActorFiles.Definition d = compile("filetest:knight", """
                    binds: { entities: [ mymod:knight ] }
                    prefab: mymod:knight
                    item_layers:
                      - { item: "#minecraft:swords", layer: upper_body, graph: mymod:sword_arms }
                      - { item: minecraft:bow, layer: upper_body, graph: mymod:bow_arms }
                      - { item: minecraft:shield, layer: off_arm, graph: mymod:shield_arm }
                      - { item: mymod:never_registered, layer: off_arm, graph: mymod:ghost_arm }
                    """);
            List<CgItemLayer> layers = d.itemLayers();
            assertEquals(new CgItemLayer("#minecraft:swords", "upper_body", "mymod:sword_arms"), layers.get(0));
            assertArrayEquals("a sword by its tag, a shield in the other hand", new boolean[] {true, false, true, false},
                    CgEntityActor.chosen(layers, SWORD, SHIELD));
            assertArrayEquals("per layer the main hand wins", new boolean[] {false, true, false, false},
                    CgEntityActor.chosen(layers, BOW, SWORD));
            assertArrayEquals("else the off hand fills it", new boolean[] {true, false, false, false},
                    CgEntityActor.chosen(layers, NOTHING, SWORD));
            assertArrayEquals("empty hands link nothing", new boolean[4], CgEntityActor.chosen(layers, NOTHING, NOTHING));
        } finally {
            CgPlatform.provide(CgItems.HOST, null);
        }
    }

    private static final int NOTHING = 0, SWORD = 1, BOW = 2, SHIELD = 3;

    /** Three items by the game's raw ids, a sword in the swords tag. */
    private static final CgItems.Host ITEMS = new CgItems.Host() {
        @Override public boolean isStack(Object o) { return false; }
        @Override public CgNbtCompound save(Object stack) { throw new UnsupportedOperationException(); }
        @Override public Object load(CgNbtCompound saved) { throw new UnsupportedOperationException(); }
        @Override public int maxDamage(Object stack) { return 0; }
        @Override public int maxStackSize(Object stack) { return 64; }
        @Override public int stamp(Object stack) { return 0; }
        @Override public CgItems.Shape shape() { return CgItems.Shape.COMPONENTS; }
        @Override public String name(int itemId) {
            return switch (itemId) {
                case SWORD -> "minecraft:diamond_sword";
                case BOW -> "minecraft:bow";
                case SHIELD -> "minecraft:shield";
                default -> null;
            };
        }
        @Override public boolean inTag(int itemId, String tag) { return itemId == SWORD && tag.equals("minecraft:swords"); }
    };

    @Test
    public void aFileFailsAtLoadByName() {
        String[][] cases = {
                {"filetest:both", "binds: { local_player: true, entities: [ mymod:golem ] }\nprefab: mymod:golem\n", "binds"},
                {"filetest:overlay", "prefab: mymod:golem\noutline: { render_custom_depth: true, overlay: mymod:ink }\n", "outline.overlay"},
                {"filetest:event", "binds: { local_player: true }\nprefab: mymod:golem\nreactions: [ { event: sneezed, sequence: mymod:s } ]\n",
                        "reactions[0]"},
                {"filetest:rule", "binds: { local_player: true }\nprefab: mymod:golem\n"
                        + "reactions: [ { event: hurt, sequence: mymod:s, when: { variable: entity.nothing, op: '>', value: 1 } } ]\n",
                        "entity.nothing"}};
        for (String[] c : cases) {
            CgAssetException e = assertThrows(c[2], CgAssetException.class, () -> compile(c[0], c[1]));
            assertTrue(e.getMessage(), e.getMessage().contains(c[0]) && e.getMessage().contains(c[2]));
        }
    }

    @Test
    public void mountingRegistersEveryFilesActorAndRefusesWhatItCannotFind() throws IOException {
        Path ns = Files.createTempDirectory("cg-actors").resolve("assets").resolve("actortest");
        write(ns, "prefabs/golem.yaml", "_v: 1\nnodes:\n  - { id: n1, name: body }\n");
        write(ns, "actors/golem.yaml", "_v: 1\nbinds: { entities: [ actortest:golem ] }\nprefab: actortest:golem\n");
        write(ns, "actors/ghost.yaml", "_v: 1\nbinds: { entities: [ actortest:ghost ] }\nprefab: actortest:nowhere\n");
        write(ns, "actors/twin.yaml", "_v: 1\nbinds: { entities: [ actortest:golem ] }\nprefab: actortest:golem\n");
        CgAssetRegistry registry = new CgAssetRegistry();
        registry.registerEngine();
        registry.scanResources(ns.getParent().getParent().toFile());
        CgActorFiles.declare(new CgVfxAssets(registry));
        assertTrue("registered at mount, before any entity enters", CgEntityActors.registered("actortest:golem"));
        assertFalse("a prefab nobody declares: refused, logged by name", CgEntityActors.registered("actortest:ghost"));
    }

    private static void write(Path ns, String file, String text) throws IOException {
        Path p = ns.resolve(file);
        Files.createDirectories(p.getParent());
        Files.write(p, text.getBytes(StandardCharsets.UTF_8));
    }
}
