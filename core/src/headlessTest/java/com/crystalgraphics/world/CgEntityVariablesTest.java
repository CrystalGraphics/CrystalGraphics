package com.crystalgraphics.world;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import java.util.function.IntConsumer;

import org.joml.Vector3f;
import org.junit.BeforeClass;
import org.junit.Test;

import com.crystalgraphics.animation.CgAnimInstance;
import com.crystalgraphics.animation.graph.CgAnimGraph;
import com.crystalgraphics.animation.graph.CgAnimGraphCodec;
import com.crystalgraphics.animation.graph.CgAnimVariable;
import com.crystalgraphics.animation.graph.CgRefPoseNode;
import com.crystalgraphics.api.animation.CgSkeleton;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgSkeletalMesh;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.asset.kind.CgBodyKinds;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.platform.world.CgBoxSink;
import com.crystalgraphics.platform.world.CgEntityFacts;
import com.crystalgraphics.platform.world.CgEntityQuery;
import com.crystalgraphics.platform.world.CgWorldQuery;

/** A graph's {@code entity:} bindings: fed from an entity's state each frame, written and read, refused at load. */
public class CgEntityVariablesTest {

    private static final CgAnimVariable<Float> SPEED = CgAnimVariable.floating("speed");
    private static final CgAnimVariable<Boolean> ON_GROUND = CgAnimVariable.bool("on_ground");
    private static final CgAnimVariable<Integer> ARM = CgAnimVariable.integer("arm");
    private static final CgAnimVariable<Float> LANDED = CgAnimVariable.floating("landed");

    private static final CgSkeleton BONES = CgSkeleton.builder().bone("root", null, new Vector3f()).build();
    private static final CgSkeletalMesh MESH = CgSkeletalMesh.builder(CgMesh.build(CgVertexFormat.SKINNED, m -> {
        int p = m.vertex().position(0, 0, 0).normal(0, 0, 1).uv(0, 0).bone(0, 1f).end();
        int q = m.vertex().position(1, 0, 0).normal(0, 0, 1).uv(1, 0).bone(0, 1f).end();
        int r = m.vertex().position(0, 1, 0).normal(0, 0, 1).uv(0, 1).bone(0, 1f).end();
        m.triangle(p, q, r);
    }), BONES).build();

    @BeforeClass
    public static void kinds() {
        CgAssetKinds.register();
    }

    private static CgAnimGraph hero() {
        return CgAnimGraph.builder("test:hero", BONES).variables(SPEED, ON_GROUND, ARM, LANDED)
                .entity(SPEED, CgEntityVariables.SPEED)
                .entity(ON_GROUND, CgEntityVariables.GROUNDED)
                .entity(ARM, CgEntityVariables.MAIN_ARM)
                .entity(LANDED, CgEntityVariables.LANDED)       // a float takes a bool, as Unreal's float takes a count
                .output(CgRefPoseNode.create()).build();
    }

    @Test
    public void theGraphIsFedFromTheEntityEachFrame() {
        Script host = new Script();
        CgEntityState state = new CgEntityState();
        double[] pose = new double[CgEntityQuery.POSE_LENGTH];
        CgAnimInstance anim = new CgAnimInstance(MESH).graph(hero());
        anim.onUpdate(CgEntityVariables.feed(state));

        host.facts.bits = CgEntityFacts.LIVING | CgEntityFacts.GROUNDED;
        host.facts.moveX = 0.25;
        host.facts.item[CgEntityFacts.MAIN] = 7;
        host.facts.bits |= CgEntityFacts.USING;
        host.facts.use[CgEntityFacts.MAIN] = CgEntityFacts.USE_BOW;
        state.update(host, CgWorldQuery.NONE, 1, 1f, 0.05f, pose);
        anim.tick(0.05f);
        assertEquals(5f, anim.graph().get(SPEED), 1e-5);
        assertTrue(anim.graph().is(ON_GROUND));
        assertEquals((float) CgEntityState.ARM_BOW, anim.graph().get(ARM), 0f);
        assertEquals(0f, anim.graph().get(LANDED), 0f);

        host.facts.bits = CgEntityFacts.LIVING;
        state.update(host, CgWorldQuery.NONE, 1, 1f, 0.05f, pose);
        host.facts.bits = CgEntityFacts.LIVING | CgEntityFacts.GROUNDED;
        state.update(host, CgWorldQuery.NONE, 1, 1f, 0.05f, pose);
        anim.tick(0.05f);
        assertEquals("an event, true for its frame", 1f, anim.graph().get(LANDED), 0f);
        state.update(host, CgWorldQuery.NONE, 1, 1f, 0.05f, pose);
        anim.tick(0.05f);
        assertEquals(0f, anim.graph().get(LANDED), 0f);
    }

    @Test
    public void theFeedFollowsItsInstanceToANewGraph() {
        Script host = new Script();
        CgEntityState state = new CgEntityState();
        CgAnimInstance anim = new CgAnimInstance(MESH).graph(hero());
        anim.onUpdate(CgEntityVariables.feed(state));
        anim.tick(0.05f);
        CgAnimVariable<Float> pace = CgAnimVariable.floating("pace");
        anim.graph(CgAnimGraph.builder("test:other", BONES).variables(pace).entity(pace, CgEntityVariables.SPEED)
                .output(CgRefPoseNode.create()).build());
        host.facts.moveZ = 0.1;
        state.update(host, CgWorldQuery.NONE, 1, 1f, 0.05f, new double[CgEntityQuery.POSE_LENGTH]);
        anim.tick(0.05f);
        assertEquals(2f, anim.graph().get(pace), 1e-5);
    }

    @Test
    public void aBuilderRefusesWhatCannotBeFed() {
        assertThrows("undeclared", IllegalArgumentException.class, () -> CgAnimGraph.builder("test:x", BONES)
                .entity(SPEED, CgEntityVariables.SPEED).output(CgRefPoseNode.create()).build());
        assertThrows("a bool cannot take a float", IllegalArgumentException.class, () -> CgAnimGraph.builder("test:x", BONES)
                .variables(ON_GROUND).entity(ON_GROUND, CgEntityVariables.SPEED).output(CgRefPoseNode.create()).build());
    }

    private static CgAssetData asset(String yaml) {
        return CgAssetData.of("test:hero", CgBodyKinds.ANIM_GRAPH, (Map<?, ?>) CgAssetYaml.read(yaml), null);
    }

    @Test
    public void theFileFormRoundTrips() {
        CgAnimGraph java = hero();
        String yaml = CgAssetYaml.write(CgAnimGraphCodec.toTree(java), CgBodyKinds.ANIM_GRAPH);
        assertTrue(yaml, yaml.contains("entity:") && yaml.contains("speed: entity.speed"));
        CgAnimGraph data = CgAnimGraphCodec.compile(asset(yaml), null).graph(BONES, id -> null);
        assertEquals(java.entity(), data.entity());
        assertEquals(yaml, CgAssetYaml.write(CgAnimGraphCodec.toTree(data), CgBodyKinds.ANIM_GRAPH));
    }

    @Test
    public void aFileFailsAtLoadNamingTheBinding() {
        String head = """
                _v: 1
                variables:
                  speed: { type: float }
                  grounded: { type: bool }
                output: pose
                nodes:
                  pose: { kind: ref_pose }
                """;
        String[][] cases = {
                {"entity:\n  speed: entity.sped\n", "entity.sped is no entity variable"},
                {"entity:\n  pace: entity.speed\n", "declares no variable pace"},
                {"entity:\n  grounded: entity.speed\n", "cannot take entity.speed"}};
        for (String[] c : cases) {
            CgAssetException e = assertThrows(c[1], CgAssetException.class,
                    () -> CgAnimGraphCodec.compile(asset(head + c[0]), null).graph(BONES, id -> null));
            assertTrue(e.getMessage(), e.getMessage().contains(c[1]) && e.getMessage().contains("entity."));
        }
    }

    @Test
    public void everyNameIsUniqueAndUnderEntity() {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (CgEntityVariable v : CgEntityVariables.all()) {
            assertTrue(v.name(), v.name().startsWith("entity.") && seen.add(v.name()));
            assertEquals(v, CgEntityVariables.of(v.name()));
        }
        assertTrue(seen.size() >= 40);
    }

    /** A host whose one entity does what the test sets. */
    private static final class Script implements CgEntityQuery {
        final CgEntityFacts facts = new CgEntityFacts().clear();

        @Override
        public boolean facts(int id, float partialTick, CgEntityFacts out) {
            out.bits = facts.bits;
            out.moveX = facts.moveX;
            out.moveY = facts.moveY;
            out.moveZ = facts.moveZ;
            out.useHand = facts.useHand;
            System.arraycopy(facts.item, 0, out.item, 0, 2);
            System.arraycopy(facts.use, 0, out.use, 0, 2);
            return true;
        }

        @Override public int localPlayer() { return -1; }
        @Override public int cameraEntity() { return -1; }
        @Override public boolean pose(int id, float partialTick, double[] out) { return false; }
        @Override public int flags(int id) { return 0; }
        @Override public int kind(int id) { return KIND_OTHER; }
        @Override public boolean box(int id, float partialTick, CgBoxSink out) { return false; }
        @Override public int parent(int id) { return -1; }
        @Override public int rootVehicle(int id) { return -1; }
        @Override public void within(double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                                     IntConsumer visitor) { }
    }
}
