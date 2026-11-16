package com.crystalgraphics.animation.graph;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Vector3f;
import org.junit.BeforeClass;
import org.junit.Test;

import com.crystalgraphics.animation.CgAnimInstance;
import com.crystalgraphics.animation.state.CgAnimStateMachineNode;
import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.api.animation.CgSkeleton;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgSkeletalMesh;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgBodyKinds;
import com.crystalgraphics.asset.load.CgBuiltInCompilers;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.asset.tree.CgAssetException;

/**
 * The shipped humanoid template: a child graph giving its clips plays Minecraft's set of a humanoid's states from the
 * entity's own values, each state reached and left by its rule, the higher ones first.
 */
public class CgHumanoidTemplateTest {

    private static final List<String> STATES = List.of("idle", "walk", "run", "sneak_idle", "sneak_walk", "fall", "swim",
            "crawl", "glide", "ride", "sleep", "dead");
    private static final CgSkeleton HUMANOID = CgSkeleton.builder().bone("root", null, new Vector3f())
            .bone("pelvis", "root", new Vector3f(0, 1, 0)).bone("spine_01", "pelvis", new Vector3f(0, 0.2f, 0))
            .bone("head", "spine_01", new Vector3f(0, 0.5f, 0)).bone("upperarm_r", "spine_01", new Vector3f(-0.2f, 0.4f, 0))
            .bone("hand_r", "upperarm_r", new Vector3f(0, -0.6f, 0)).build();
    private static final CgSkeletalMesh MESH = CgSkeletalMesh.builder(CgMesh.build(CgVertexFormat.SKINNED, m -> {
    }), HUMANOID).build();

    @BeforeClass
    public static void kinds() {
        CgBuiltInCompilers.register();
    }

    private static CgAssetData template() throws IOException {
        try (InputStream in = CgHumanoidTemplateTest.class.getResourceAsStream("/assets/crystalgraphics/anim_graphs/humanoid.yaml")) {
            String yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return CgAssetData.of("crystalgraphics:humanoid", CgBodyKinds.ANIM_GRAPH, (Map<?, ?>) CgAssetYaml.read(yaml), null);
        }
    }

    /** Each state's clip its own pelvis height, the swing its own. */
    private static CgAnimSequence clip(String name, float height) {
        return CgAnimSequence.builder(name, 30, 1).track("pelvis", new float[] {0, height, 0}, new float[] {0, 0, 0, 1},
                new float[] {1, 1, 1}).build();
    }

    @Test
    public void aChildGivesItsClipsAndEveryStatePlaysByTheEntitysValues() throws IOException {
        StringBuilder child = new StringBuilder("_v: 1\nparent: crystalgraphics:humanoid\nnodes:\n");
        Map<String, CgAnimSequence> clips = new HashMap<>();
        for (int i = 0; i < STATES.size(); i++) {
            String s = STATES.get(i);
            child.append("  ").append(s).append(": { clip: hero/").append(s).append(" }\n");
            clips.put("hero/" + s, clip(s, i));
        }
        child.append("  swing: { clip: hero/swing }\n");
        clips.put("hero/swing", clip("swing", 99));
        CgAssetData hero = CgAssetData.of("test:hero", CgBodyKinds.ANIM_GRAPH, (Map<?, ?>) CgAssetYaml.read(child.toString()), template());
        CgAnimGraph graph = CgAnimGraphCodec.compile(hero, null).graph(HUMANOID, clips::get);
        assertTrue("the entity's values bound by name", graph.entity().containsKey("speed") && graph.entity().containsKey("swing"));

        CgAnimInstance anim = new CgAnimInstance(MESH).graph(graph);
        CgAnimStateMachineNode.Machine machine = machine(graph).in(anim.graph());
        anim.tick(0.05f);
        assertEquals("idle", machine.currentState());
        expect(anim, machine, "walk", Map.of("speed", 4.3f));
        expect(anim, machine, "run", Map.of("speed", 5.6f, "sprinting", 1f));
        expect(anim, machine, "sneak_walk", Map.of("speed", 1.3f, "sneaking", 1f));
        expect(anim, machine, "sneak_idle", Map.of("sneaking", 1f));
        expect(anim, machine, "fall", Map.of("airborne", 1f, "speed", 3f));
        expect(anim, machine, "swim", Map.of("swimming", 1f, "speed", 2f));
        expect(anim, machine, "crawl", Map.of("crawling", 1f));
        expect(anim, machine, "glide", Map.of("gliding", 1f, "airborne", 1f));
        expect(anim, machine, "ride", Map.of("riding", 1f, "speed", 6f));
        expect(anim, machine, "sleep", Map.of("sleeping", 1f));
        expect(anim, machine, "dead", Map.of("dead", 1f, "airborne", 1f, "riding", 1f));
        expect(anim, machine, "idle", Map.of());
    }

    @Test
    public void theTemplateAloneFailsNamingAClipNoBodyHas() throws IOException {
        CgAnimGraphDefinition d = CgAnimGraphCodec.compile(template(), null);
        CgAssetException e = assertThrows(CgAssetException.class, () -> d.graph(HUMANOID, id -> null));
        assertTrue(e.getMessage(), e.getMessage().contains("crystalgraphics:humanoid/"));
    }

    private static final List<String> BOOLS = List.of("sprinting", "sneaking", "airborne", "swimming", "crawling", "gliding",
            "riding", "sleeping", "dead");

    /** Sets {@code values} (every other variable back to none), ticks a second, and finds the machine in {@code state}. */
    private static void expect(CgAnimInstance anim, CgAnimStateMachineNode.Machine machine, String state, Map<String, Float> values) {
        anim.set(CgAnimVariable.floating("speed"), values.getOrDefault("speed", 0f));
        for (String b : BOOLS) anim.set(CgAnimVariable.bool(b), values.getOrDefault(b, 0f) != 0f);
        for (int i = 0; i < 20; i++) anim.tick(0.05f);
        assertEquals("with " + values, state, machine.currentState());
    }

    private static CgAnimStateMachineNode machine(CgAnimGraph graph) {
        CgAnimStateMachineNode[] found = {null};
        find(graph.output(), found);
        return found[0];
    }

    private static void find(CgAnimNode node, CgAnimStateMachineNode[] found) {
        if (node instanceof CgAnimStateMachineNode m) found[0] = m;
        node.forEachChild(c -> {
            if (found[0] == null) find(c, found);
        });
    }
}
