package com.crystalgraphics.animation.retarget;

import com.crystalgraphics.animation.rig.CgRig;
import com.crystalgraphics.animation.rig.CgRigAdapters;
import com.crystalgraphics.animation.rig.CgRigs;
import com.crystalgraphics.api.animation.CgAnimPose;
import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.api.animation.CgSkeleton;
import com.crystalgraphics.api.animation.CgSkeletonDefinition;
import com.crystalgraphics.api.animation.CgSocket;
import com.crystalgraphics.api.mesh.CgSkeletalMesh;
import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.mc.model.CgVanillaHumanoid;
import com.crystalgraphics.object.value.CgUnits;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
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

    @Test
    public void everyBoneAndSocketOfGokusResolvesOnTheHumanoidAndHisDummiesKeepTheirMotion() throws IOException {
        CgSkeletalMesh gokuMesh = CgMeshLoader.skeletal(KK.resolve("models/Art/MDL/Cpl/Cpl001/Model/Cpl001p1c01s1.glb")
                .toAbsolutePath().toString());
        CgSkeleton goku = gokuMesh.skeleton();
        Map<?, ?> units = (Map<?, ?>) CgAssetYaml.read(Files.readString(KK.resolve("units.yaml")));
        Map<?, ?> tree = (Map<?, ?>) CgAssetYaml.read(Files.readString(KK.resolve("skeletons/Art/MDL/Cpl/Cpl001/Model/Cpl001p1_Skeleton.yaml")));
        List<CgSocket> sockets = CgSkeletonDefinition.compile("kk:goku", tree, CgUnits.of(units.get("units"), CgUnits.HOST)).socketsOn(goku);
        Path geo = KK.resolve("humanoid/geo/model.geo.json");
        CgSkeletalMesh humanoidMesh = CgGeckoModel.read(Files.readString(geo), geo.toString()).mesh();
        CgRig from = CgRigs.detect(goku, CgRigAdapters.CRYSTALGRAPHICS).toBuilder()
                .carried("AttachPoint").carried("AttachPoint_l").carried("AttachPoint_r").build();
        CgRig to = CgRigs.detect(humanoidMesh.skeleton(), CgRigAdapters.GECKOLIB);
        CgRetargetProcessor p = new CgRetargetProcessor(CgRetargeter.auto(goku, from, humanoidMesh.skeleton(), to), goku,
                humanoidMesh.skeleton());
        CgSkeletalMesh target = p.mesh(humanoidMesh, sockets);

        // what a move names is one of his bones or his sockets: each found on the target by name
        List<String> names = new ArrayList<>();
        for (int b = 0; b < goku.boneCount(); b++) names.add(goku.name(b));
        for (CgSocket s : sockets) names.add(s.name());
        for (String name : names) {
            assertTrue(name + " resolves", target.skeleton().indexOfIgnoringCase(name) >= 0 || target.socket(name) != null);
        }

        // a carried dummy keeps its reach from the hand through every frame, scaled
        CgSkeleton tgt = target.skeleton();
        int srcPoint = goku.indexOf("AttachPoint_r"), srcHand = goku.indexOf("hand_r");
        int point = tgt.indexOf("AttachPoint_r"), hand = tgt.indexOf("right_hand_locator");
        assertTrue("added on the hand", tgt.parent(point) == hand);
        CgAnimPose in = new CgAnimPose(goku), out = new CgAnimPose(tgt);
        Vector3f a = new Vector3f(), b = new Vector3f(), c = new Vector3f();
        float worst = 0f;
        int frames = 0;
        try (Stream<Path> s = Files.list(KK.resolve("models/Art/ANM/cpl/C001A/BTL"))) {
            for (Path f : s.filter(f -> f.toString().endsWith(".glb")).sorted().toList()) {
                for (CgAnimSequence clip : CgMeshLoader.sequences(f.toAbsolutePath().toString())) {
                    for (int k = 0; k < clip.frameCount(); k += 4) {
                        clip.sample(in, (float) (k / clip.frameRate()));
                        p.run(in, out);
                        float want = in.resolve().component(srcPoint).getTranslation(a).distance(in.component(srcHand).getTranslation(b))
                                * p.heightRatio();
                        float got = at(out, point, a).distance(at(out, hand, c));
                        worst = Math.max(worst, Math.abs(got - want));
                        frames++;
                    }
                }
            }
        }
        System.out.printf("[retarget] %d names resolve; AttachPoint_r's reach off by at most %.4f m over %d frames%n",
                names.size(), worst, frames);
        assertTrue("the dummy's reach kept: " + worst, worst < 0.002f);
    }

    @Test
    public void gokusBattleClipsBakeAtLoadOntoTheHumanoidAndAVanillaZombieAndAnOwnClipWins() throws IOException {
        CgSkeleton goku = CgMeshLoader.skeletal(KK.resolve("models/Art/MDL/Cpl/Cpl001/Model/Cpl001p1c01s1.glb").toAbsolutePath()
                .toString()).skeleton();
        CgRig from = CgRigs.detect(goku, CgRigAdapters.CRYSTALGRAPHICS);
        Map<String, CgAnimSequence> clips = new LinkedHashMap<>();
        try (Stream<Path> s = Files.list(KK.resolve("models/Art/ANM/cpl/C001A/BTL"))) {
            for (Path f : s.filter(f -> f.toString().endsWith(".glb")).sorted().toList()) {
                for (CgAnimSequence clip : CgMeshLoader.sequences(f.toAbsolutePath().toString())) clips.put(clip.name(), clip);
            }
        }
        List<String> names = new ArrayList<>(clips.keySet());
        String replaced = names.get(0);

        Path geo = KK.resolve("humanoid/geo/model.geo.json");
        CgGeckoModel model = CgGeckoModel.read(Files.readString(geo), geo.toString());
        CgSkeleton humanoid = model.mesh().skeleton();
        CgAnimSequence idle = model.animations(KK.resolve("humanoid/animations/core/styles/default/idle.animation.json")
                .toAbsolutePath().toString()).get(0).sequence();
        CgSkeleton zombie = CgVanillaHumanoid.SKELETON;

        CgRetargetBake.clear();
        for (CgSkeleton target : List.of(humanoid, zombie)) {
            CgRig to = CgRigs.detect(target, target == zombie ? CgRigAdapters.VANILLA : CgRigAdapters.GECKOLIB);
            CgRetargetProcessor p = new CgRetargetProcessor(CgRetargeter.auto(goku, from, target, to), goku, target);
            // the humanoid has its own animation for one move: a mod's GeckoLib clip in its place
            Map<String, CgAnimSequence> own = target == humanoid ? Map.of(replaced, idle) : Map.of();
            CgClipResolver resolver = new CgClipResolver(own::get, clips::get, p);
            resolver.bakeAll(names);   // what a prepare step runs on workers before READY
            for (String name : names) {
                if (own.containsKey(name)) continue;
                assertTrue(name + " baked before play", CgRetargetBake.isCached(p, clips.get(name)));
            }
            for (String name : names) assertNotNull(name + " binds", resolver.resolve(name));
            if (target == humanoid) assertSame("its own clip plays in place of the retargeted one", idle, resolver.resolve(replaced));
            assertThrows(IllegalArgumentException.class, () -> resolver.resolve("no_such_move"));
        }

        // the zombie's rigid arms point from the shoulder where Goku's hands are
        CgRetargetProcessor p = new CgRetargetProcessor(CgRetargeter.auto(goku, from, zombie, CgVanillaHumanoid.rig()), goku, zombie);
        int[] srcHand = {goku.indexOf("hand_l"), goku.indexOf("hand_r")}, srcShoulder = {goku.indexOf("upperarm_l"), goku.indexOf("upperarm_r")};
        int[] hand = {zombie.indexOf("left_hand"), zombie.indexOf("right_hand")}, shoulder = {zombie.indexOf("left_arm"), zombie.indexOf("right_arm")};
        CgAnimPose in = new CgAnimPose(goku), out = new CgAnimPose(zombie);
        Vector3f a = new Vector3f(), b = new Vector3f(), c = new Vector3f(), d = new Vector3f();
        float worst = 0f;
        for (CgAnimSequence clip : clips.values()) {
            for (int k = 0; k < clip.frameCount(); k += 2) {
                clip.sample(in, (float) (k / clip.frameRate()));
                p.run(in, out);
                for (int h = 0; h < 2; h++) {
                    Vector3f tgt = at(out, hand[h], a).sub(at(out, shoulder[h], b), c);
                    Vector3f src = p.sourcePosition(srcHand[h], a).sub(p.sourcePosition(srcShoulder[h], b), d);
                    if (src.length() > 0.05f) worst = Math.max(worst, (float) Math.toDegrees(tgt.angle(src)));
                }
            }
        }
        System.out.printf("[retarget] %d clips bound on both bodies; the zombie's arms point within %.2f deg of Goku's%n",
                names.size(), worst);
        assertTrue("the zombie's arms point as Goku's: " + worst, worst < 3f);
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
