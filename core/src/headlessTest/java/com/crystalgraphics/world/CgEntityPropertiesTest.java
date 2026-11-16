package com.crystalgraphics.world;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.joml.Vector3f;
import org.junit.BeforeClass;
import org.junit.Test;

import com.crystalgraphics.animation.graph.CgAnimGraph;
import com.crystalgraphics.animation.graph.CgAnimGraphCodec;
import com.crystalgraphics.animation.graph.CgAnimVariable;
import com.crystalgraphics.animation.graph.CgRefPoseNode;
import com.crystalgraphics.api.animation.CgSkeleton;
import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.asset.kind.CgBodyKinds;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.net.object.CgNetField;
import com.crystalgraphics.net.object.CgNetType;
import com.crystalgraphics.serialization.CgCodecs;

/**
 * An entity's properties named by a graph: the engine's as entity.*, a mod's as type#field, written, read and refused at
 * load by name.
 */
public class CgEntityPropertiesTest {

    private static final CgAnimVariable<Float> SPEED = CgAnimVariable.floating("speed");
    private static final CgAnimVariable<Boolean> ON_GROUND = CgAnimVariable.bool("on_ground");
    private static final CgAnimVariable<Integer> ARM = CgAnimVariable.integer("arm");
    private static final CgAnimVariable<Float> LANDED = CgAnimVariable.floating("landed");
    private static final CgAnimVariable<Float> KI = CgAnimVariable.floating("ki");

    static final CgNetField<Float> CURRENT = CgNetField.ofFloat("current");
    static final CgNetField<String> LABEL = CgNetField.of("label", CgCodecs.STRING);
    static final CgNetType MANA = CgNetType.define("propertiestest:mana").fields(CURRENT, LABEL)
            .onEntities(type -> true).build();
    static final CgNetType AURA = CgNetType.define("propertiestest:aura").fields(CgNetField.ofFloat("heat")).build();

    private static final CgSkeleton BONES = CgSkeleton.builder().bone("root", null, new Vector3f()).build();

    @BeforeClass
    public static void kinds() {
        CgAssetKinds.register();
    }

    private static CgAssetData asset(String yaml) {
        return CgAssetData.of("test:hero", CgBodyKinds.ANIM_GRAPH, (Map<?, ?>) CgAssetYaml.read(yaml), null);
    }

    private static CgAnimGraph hero() {
        return CgAnimGraph.builder("test:hero", BONES).variables(SPEED, ON_GROUND, ARM, LANDED, KI)
                .entity(SPEED, CgEntityProperties.SPEED)
                .entity(ON_GROUND, CgEntityProperties.GROUNDED)
                .entity(ARM, CgEntityProperties.MAIN_ARM)
                .entity(LANDED, CgEntityProperties.LANDED)     // a float takes a bool
                .entity(KI, MANA, CURRENT)
                .output(CgRefPoseNode.create()).build();
    }

    @Test
    public void theEnginesAreEntityDotAndAModsIsTypeHashField() {
        CgAnimGraph graph = hero();
        assertEquals("entity.speed", graph.entity().get("speed").name());
        assertEquals("propertiestest:mana#current", graph.entity().get("ki").name());
        String yaml = CgAssetYaml.write(CgAnimGraphCodec.toTree(graph), CgBodyKinds.ANIM_GRAPH);
        assertTrue(yaml, yaml.contains("entity.speed") && yaml.contains("propertiestest:mana#current"));
        CgAnimGraph read = CgAnimGraphCodec.compile(asset(yaml), null).graph(BONES, id -> null);
        assertEquals(graph.entity(), read.entity());
    }

    @Test
    public void aBindingFailsAtLoadNamingIt() {
        String head = """
                _v: 1
                variables:
                  speed: { type: float }
                  flag: { type: bool }
                output: pose
                nodes:
                  pose: { kind: ref_pose }
                """;
        String[][] cases = {
                {"entity:\n  speed: entity.sped\n", "has no field sped"},
                {"entity:\n  speed: speed\n", "is no entity property"},
                {"entity:\n  speed: propertiestest:none#current\n", "no networked type propertiestest:none"},
                {"entity:\n  speed: propertiestest:mana#max\n", "has no field max"},
                {"entity:\n  speed: propertiestest:mana#label\n", "no number or bool"},
                {"entity:\n  speed: propertiestest:aura#heat\n", "not per holder"},
                {"entity:\n  flag: entity.speed\n", "cannot take"}};
        for (String[] c : cases) {
            CgAssetException e = assertThrows(c[1], CgAssetException.class,
                    () -> CgAnimGraphCodec.compile(asset(head + c[0]), null).graph(BONES, id -> null));
            assertTrue(e.getMessage(), e.getMessage().contains(c[1]) && e.getMessage().contains("entity."));
        }
    }

    @Test
    public void everyEngineFieldIsLocalAndNamedOnce() {
        Set<String> seen = new HashSet<>();
        for (CgNetField<?> field : CgEntityProperties.MOTION.fields()) {
            assertTrue(field.name(), field.isLocal() && seen.add(field.name()));
            assertEquals(field, CgAnimGraph.EntityBinding.named("entity." + field.name()).field());
        }
        assertTrue("never sent: its own objects on each side", CgEntityProperties.MOTION.isLocal());
        assertTrue(seen.size() >= 50);
    }

    @Test
    public void aFieldAloneNamesTheOneTypeListingIt() {
        assertEquals(MANA, CgAnimGraph.EntityBinding.of(CURRENT).type());
        assertEquals(CgEntityProperties.MOTION, CgAnimGraph.EntityBinding.of(CgEntityProperties.SPEED).type());
        CgNetField<Float> shared = CgNetField.ofFloat("shared");
        CgNetType.define("propertiestest:one").fields(shared).perHolder().build();
        CgNetType.define("propertiestest:two").fields(shared).perHolder().build();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> CgAnimGraph.EntityBinding.of(shared));
        assertTrue(e.getMessage(), e.getMessage().contains("name the type"));
    }
}
