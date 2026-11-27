package com.crystalgraphics.world.actor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.joml.Vector3f;
import org.junit.Test;

import com.crystalgraphics.animation.CgAnimInstance;
import com.crystalgraphics.animation.graph.CgAnimGraph;
import com.crystalgraphics.animation.graph.CgAnimVariable;
import com.crystalgraphics.animation.graph.CgRefPoseNode;
import com.crystalgraphics.api.animation.CgSkeleton;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgSkeletalMesh;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.net.object.CgNetField;
import com.crystalgraphics.net.object.CgNetObject;
import com.crystalgraphics.net.object.CgNetType;
import com.crystalgraphics.world.CgEntityProperties;

/** A graph's entity bindings set from the actor's entity's properties each frame: the engine's and a mod's own. */
public class EntityFeedTest {

    static final CgNetField<Integer> GEAR = CgNetField.ofInt("gear").local();
    static final CgNetType<CgNetObject> FLIGHT = CgNetType.define("feedtest:flight").fields(GEAR).perHolder().build();

    private static final CgSkeleton BONES = CgSkeleton.builder().bone("root", null, new Vector3f()).build();
    private static final CgSkeletalMesh MESH = CgSkeletalMesh.builder(CgMesh.build(CgVertexFormat.SKINNED, m -> {
        int p = m.vertex().position(0, 0, 0).normal(0, 0, 1).uv(0, 0).bone(0, 1f).end();
        int q = m.vertex().position(1, 0, 0).normal(0, 0, 1).uv(1, 0).bone(0, 1f).end();
        int r = m.vertex().position(0, 1, 0).normal(0, 0, 1).uv(0, 1).bone(0, 1f).end();
        m.triangle(p, q, r);
    }), BONES).build();

    @Test
    public void aGraphReadsTheEntitysPropertiesOnItsSide() {
        CgAnimVariable<Float> speed = CgAnimVariable.floating("speed");
        CgAnimVariable<Boolean> landed = CgAnimVariable.bool("landed");
        CgAnimVariable<Integer> gear = CgAnimVariable.integer("gear");
        CgAnimGraph graph = CgAnimGraph.builder("test:feed", BONES).variables(speed, landed, gear)
                .entity(speed, CgEntityProperties.SPEED)
                .entity(landed, CgEntityProperties.LANDED)
                .entity(gear, FLIGHT, GEAR)
                .output(CgRefPoseNode.create()).build();
        CgEntity entity = new CgEntity(4242, "test:walker");           // a client's: read by network id
        CgAnimInstance anim = new CgAnimInstance(MESH).graph(graph);
        anim.onUpdate(new EntityFeed(entity));

        CgEntityProperties.MOTION.of(4242).setFloat(CgEntityProperties.SPEED, 3.5f).setBool(CgEntityProperties.LANDED, true);
        FLIGHT.of(4242).setInt(GEAR, 2);
        anim.tick(0.05f);
        assertEquals(3.5f, anim.graph().get(speed), 0f);
        assertTrue(anim.graph().is(landed));
        assertEquals(2f, anim.graph().get(gear), 0f);

        CgEntityProperties.MOTION.of(4242).setFloat(CgEntityProperties.SPEED, 1f);
        anim.tick(0.05f);
        assertEquals("read each frame", 1f, anim.graph().get(speed), 0f);
    }
}
