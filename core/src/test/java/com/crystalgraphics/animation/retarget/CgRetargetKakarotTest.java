package com.crystalgraphics.animation.retarget;

import com.crystalgraphics.animation.rig.CgRig;
import com.crystalgraphics.animation.rig.CgRigAdapters;
import com.crystalgraphics.animation.rig.CgRigs;
import com.crystalgraphics.api.animation.CgAnimPose;
import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.api.animation.CgSkeleton;
import com.crystalgraphics.api.mesh.CgMeshLoader;
import com.crystalgraphics.api.mesh.geckolib.CgGeckoModel;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Kakarot's Goku retargeted onto RPG-Core's GeckoLib humanoid: every frame of every clip of his battle folder keeps
 * each hand where his is, by direction from the shoulder and by place within the two bodies' difference in build.
 */
public class CgRetargetKakarotTest {

    private static final Path KK = Paths.get("src/main/resources/assets/kk");
    /** For the hands' own placing: the IK's reach, 1 cm. */
    private static final float SLACK = 0.01f;

    @Test
    public void gokusBattleClipsKeepTheirHandsOnTheHumanoid() throws IOException {
        CgSkeleton goku = CgMeshLoader.skeletal(KK.resolve("models/Art/MDL/Cpl/Cpl001/Model/Cpl001p1c01s1.glb").toAbsolutePath()
                .toString()).skeleton();
        Path geo = KK.resolve("humanoid/geo/model.geo.json");
        CgSkeleton humanoid = CgGeckoModel.read(Files.readString(geo), geo.toString()).mesh().skeleton();
        CgRig from = CgRigs.detect(goku, CgRigAdapters.CRYSTALGRAPHICS), to = CgRigs.detect(humanoid, CgRigAdapters.GECKOLIB);
        assertNotNull("Goku is a standard humanoid", from);
        assertNotNull("RPG-Core's model is GeckoLib's humanoid", to);
        CgRetargetProcessor p = new CgRetargetProcessor(CgRetargeter.auto(goku, from, humanoid, to), goku, humanoid);

        int[] srcHand = {goku.indexOf("hand_l"), goku.indexOf("hand_r")}, srcShoulder = {goku.indexOf("upperarm_l"), goku.indexOf("upperarm_r")};
        int[] tgtHand = {humanoid.indexOf("left_hand_locator"), humanoid.indexOf("right_hand_locator")},
                tgtShoulder = {humanoid.indexOf("left_arm"), humanoid.indexOf("right_arm")};
        int srcHips = goku.indexOf("thigh_l"), tgtHips = humanoid.indexOf("left_leg");
        CgAnimPose in = new CgAnimPose(goku), out = new CgAnimPose(humanoid);
        Vector3f a = new Vector3f(), b = new Vector3f(), c = new Vector3f(), d = new Vector3f();

        // the tolerance: how far the two builds differ at rest (hips to shoulder, shoulder width, shoulder to hand) scaled
        // by the height ratio, plus each frame's curl: how far the source's shoulders sit off the line its lower spine
        // turned to, which a one-bone torso cannot follow
        int spine01 = goku.indexOf("spine_01"), spine03 = goku.indexOf("spine_03");
        p.run(in, out);
        Vector3f spineRest = p.sourcePosition(spine03, new Vector3f()).sub(p.sourcePosition(spine01, a));
        Vector3f shouldersRest = shoulders(p, srcShoulder, new Vector3f()).sub(p.sourcePosition(spine01, a));
        float tgtTorso = at(out, tgtShoulder[0], a).distance(at(out, tgtHips, b));
        Quaternionf swing = new Quaternionf();
        float ratio = p.heightRatio();
        float torso = at(out, tgtShoulder[0], a).distance(at(out, tgtHips, b))
                - p.sourcePosition(srcShoulder[0], c).distance(p.sourcePosition(srcHips, d)) * ratio;
        float width = Math.abs(at(out, tgtShoulder[0], a).x) - Math.abs(p.sourcePosition(srcShoulder[0], c).x) * ratio;
        float arm = at(out, tgtHand[0], a).distance(at(out, tgtShoulder[0], b))
                - p.sourcePosition(srcHand[0], c).distance(p.sourcePosition(srcShoulder[0], d)) * ratio;
        float builds = Math.abs(torso) + Math.abs(width) + Math.abs(arm);

        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.list(KK.resolve("models/Art/ANM/cpl/C001A/BTL"))) {
            s.filter(f -> f.toString().endsWith(".glb")).sorted().forEach(files::add);
        }
        float worstGoal = 0f, worstAngle = 0f, worstPlace = 0f, worstCurl = 0f;
        String worstAt = "";
        int frames = 0;
        for (Path f : files) {
            for (CgAnimSequence clip : CgMeshLoader.sequences(f.toAbsolutePath().toString())) {
                for (int k = 0; k < clip.frameCount(); k++) {
                    clip.sample(in, (float) (k / clip.frameRate()));
                    p.run(in, out);
                    out.resolve();
                    Vector3f spine = p.sourcePosition(spine03, c).sub(p.sourcePosition(spine01, d));
                    swing.rotationTo(spineRest, spine);
                    Vector3f rigid = swing.transform(shouldersRest, new Vector3f());
                    Vector3f real = shoulders(p, srcShoulder, c).sub(p.sourcePosition(spine01, d));
                    float curl = 2f * tgtTorso * (float) Math.sin(rigid.angle(real) / 2f);
                    worstCurl = Math.max(worstCurl, curl);
                    for (int h = 0; h < 2; h++) {
                        Vector3f hand = at(out, tgtHand[h], a);
                        p.goal(h == 0 ? "arm_l" : "arm_r", b);
                        worstGoal = Math.max(worstGoal, hand.distance(b));
                        Vector3f tgtDir = hand.sub(at(out, tgtShoulder[h], b), c);
                        Vector3f srcDir = p.sourcePosition(srcHand[h], d).sub(p.sourcePosition(srcShoulder[h], b));
                        if (srcDir.length() > 0.05f) worstAngle = Math.max(worstAngle, (float) Math.toDegrees(tgtDir.angle(srcDir)));
                        float place = at(out, tgtHand[h], a).distance(p.sourcePosition(srcHand[h], b).mul(ratio));
                        assertTrue("a hand off by " + place + " m, past the builds and the curl at " + clip.name() + " frame " + k,
                                place < builds + curl + SLACK);
                        if (place > worstPlace) {
                            worstPlace = place;
                            worstAt = clip.name() + " frame " + k;
                        }
                    }
                    frames++;
                }
            }
        }
        System.out.printf("[retarget] %d frames, height ratio %.3f: hand to goal %.4f m, hand direction %.2f deg, hand place %.3f m "
                + "at %s (builds %.3f m, curl up to %.3f m)%n", frames, ratio, worstGoal, worstAngle, worstPlace, worstAt, builds, worstCurl);
        assertTrue(frames > 0);
        assertTrue("every hand reaches its goal: " + worstGoal, worstGoal < 0.01f);
        assertTrue("every hand points from its shoulder as the source's: " + worstAngle, worstAngle < 3f);
    }

    /** The middle of the source's shoulders. */
    private static Vector3f shoulders(CgRetargetProcessor p, int[] shoulder, Vector3f out) {
        Vector3f other = p.sourcePosition(shoulder[1], new Vector3f());
        return p.sourcePosition(shoulder[0], out).add(other).mul(0.5f);
    }

    private static Vector3f at(CgAnimPose pose, int bone, Vector3f out) {
        return pose.resolve().component(bone).getTranslation(out);
    }
}
