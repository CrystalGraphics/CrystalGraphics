package com.crystalgraphics.docs;

import com.crystalgraphics.animation.CgAnimInstance;
import com.crystalgraphics.animation.graph.CgAnimGraph;
import com.crystalgraphics.animation.graph.CgAnimGraphCodec;
import com.crystalgraphics.animation.graph.CgAnimVariable;
import com.crystalgraphics.api.animation.CgAnimPose;
import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.api.mesh.CgMeshLoader;
import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.asset.kind.CgBodyKinds;
import com.crystalgraphics.asset.load.CgBuiltInCompilers;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.mc.model.CgVanillaHumanoid;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** What the docs' lessons promise the reader sees, checked on the assets their examples ship. */
public class DocsExamplesTest {

    private static final Path EXAMPLES = Paths.get("..", "examples").toAbsolutePath().normalize();
    private static final Path LOOK = EXAMPLES.resolve("animation/look-at-the-player/src/main/resources/assets/zombiemod");

    @BeforeClass
    public static void kinds() {
        CgAssetKinds.register();
        CgBuiltInCompilers.register();
    }

    /** animation/first-steps: the wave clip plays on a vanilla zombie, its right arm up and waving. */
    @Test
    public void theWaveClipRaisesAZombiesRightArm() {
        Path file = EXAMPLES.resolve("animation/first-steps/src/main/resources/assets/zombiemod/anims/wave.glb");
        List<CgAnimSequence> clips = CgMeshLoader.sequences(file.toString());
        assertEquals(1, clips.size());
        CgAnimSequence wave = clips.get(0);
        assertEquals("wave", wave.name());
        assertEquals(1.2f, wave.duration(), 1e-3f);

        float low = armHeight(wave, 0f), high = armHeight(wave, 0.3f);
        assertTrue("the hand is above the shoulder: " + low, low > 0.4f);
        assertTrue("and higher half a wave later: " + high + " over " + low, high > low + 0.05f);
        assertEquals("it loops: the end is the start", low, armHeight(wave, wave.duration()), 1e-4f);
    }

    /**
     * animation/look-at-the-player: a waving zombie's head turns to the player, from the graph file, wherever the zombie
     * stands and faces. Placed as the engine places a posed vanilla mob: at its feet, turned by 180 - its body yaw.
     */
    @Test
    public void theLookAtGraphTurnsTheHeadToThePlayer() throws IOException {
        String yaml = new String(Files.readAllBytes(LOOK.resolve("anim_graphs/wave_and_look.yaml")), StandardCharsets.UTF_8);
        CgAnimSequence wave = CgMeshLoader.sequences(LOOK.resolve("anims/wave.glb").toString()).get(0);
        CgAnimGraph graph = CgAnimGraphCodec.compile(CgAssetData.of("zombiemod:wave_and_look", CgBodyKinds.ANIM_GRAPH,
                        (Map<?, ?>) CgAssetYaml.read(yaml), null), (kind, id) -> null)
                .graph(CgVanillaHumanoid.SKELETON, id -> "zombiemod:anims/wave".equals(id) ? wave : null);

        for (float bodyYaw : new float[] {0f, 90f, -135f}) {
            double x = 100, y = 64, z = -40;
            Matrix4d placed = new Matrix4d().translation(x, y, z).rotateY(Math.toRadians(180.0 - bodyYaw));
            // the player 5 blocks to the zombie's left, eyes at its eye level
            Vector3d facing = placed.transformDirection(new Vector3d(0, 0, -1)).normalize();
            Vector3d left = new Vector3d(facing.z, 0, -facing.x);
            Vector3d eyes = new Vector3d(x, y + 1.62, z).fma(5, left);

            CgAnimInstance zombie = new CgAnimInstance(CgVanillaHumanoid.MESH).graph(graph).placedBy(out -> out.set(placed));
            zombie.set(CgAnimVariable.floating("look_x"), (float) eyes.x).set(CgAnimVariable.floating("look_y"), (float) eyes.y)
                    .set(CgAnimVariable.floating("look_z"), (float) eyes.z);
            zombie.tick(0.1f);
            zombie.pose().resolve();
            Matrix4f head = zombie.pose().component(CgVanillaHumanoid.SKELETON.indexOf("head"), new Matrix4f());
            Vector3d face = placed.transformDirection(new Vector3d(head.transformDirection(new Vector3f(0, 0, -1)))).normalize();
            // 90 degrees off, clamped to 70: the face points 20 degrees short of the player
            assertEquals("body yaw " + bodyYaw + ": the face turned 70 degrees toward the player, " + face,
                    Math.cos(Math.toRadians(20)), face.dot(left), 0.03);
        }
    }

    /** The right hand's height over the right shoulder, metres, at {@code time}. */
    private static float armHeight(CgAnimSequence clip, float time) {
        CgAnimPose pose = new CgAnimPose(CgVanillaHumanoid.SKELETON);
        clip.sample(pose, time);
        pose.resolve();
        Vector3f shoulder = pose.component(CgVanillaHumanoid.SKELETON.indexOf("right_arm"), new Matrix4f()).getTranslation(new Vector3f());
        Vector3f hand = pose.component(CgVanillaHumanoid.SKELETON.indexOf("right_hand"), new Matrix4f()).getTranslation(new Vector3f());
        return hand.y - shoulder.y;
    }
}
