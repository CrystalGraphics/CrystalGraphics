package com.crystalgraphics.animation;

import com.crystalgraphics.api.animation.CgAnimSection;
import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.api.animation.CgPose;
import com.crystalgraphics.api.animation.CgSkeleton;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshLoader;
import com.crystalgraphics.api.mesh.CgSkeletalMesh;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.easing.CgEasings;
import com.crystalgraphics.job.CgJob;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * B3's gate: clips sampled, sections mapped and weighted, and poses blended as Unreal does (each case's numbers worked
 * by hand from the cited formula), and on real clips a seek landing bit for bit on the pose playing reaches.
 *
 * <ul>
 *   <li>{@code -PskinnedMeshes=<folder>,...}: every .glb there plays each clip it holds (Kurama's puppets).</li>
 *   <li>{@code -PskinnedClips=<mesh.glb>=<folder of clips>,...}: a mesh plays clips from files of their own, bound
 *       to its skeleton by bone name ignoring case (Kakarot's Goku).</li>
 * </ul>
 */
public class CgAnimationTest {

    private static final float EPS = 1e-6f;

    /** A root and one child; the child's rotation keyed over five frames at 4 a second, about z from 0 to 90 degrees. */
    private static CgSkeleton arm() {
        return CgSkeleton.builder().bone("root", null, new Vector3f()).bone("Elbow", "root", new Vector3f(0, 1, 0)).build();
    }

    private static CgAnimSequence bend() {
        float[] rotations = new float[5 * 4];
        for (int k = 0; k < 5; k++) {
            Quaternionf q = new Quaternionf().rotateZ((float) Math.toRadians(22.5 * k));
            rotations[k * 4] = q.x;
            rotations[k * 4 + 1] = q.y;
            rotations[k * 4 + 2] = q.z;
            rotations[k * 4 + 3] = q.w;
        }
        float[] translations = new float[5 * 3];
        for (int k = 0; k < 5; k++) translations[k * 3 + 1] = 1f + k;
        return CgAnimSequence.builder("bend", 4, 5).track("elbow", translations, rotations, new float[]{1, 1, 1}).build();
    }

    @Test
    public void sampleAtAKeyIsTheKeyAndBetweenIsUnrealsLerp() {
        CgPose pose = new CgPose(arm());
        CgAnimSequence bend = bend();
        Vector3f t = new Vector3f();
        Quaternionf q = new Quaternionf();
        for (int k = 0; k < 5; k++) {
            bend.sample(pose.rest(), k / 4f);
            assertEquals("frame " + k + " translation", 1f + k, pose.translation(1, t).y, 0f);
            Quaternionf key = bend.rotation(0, k, new Quaternionf());
            pose.rotation(1, q);
            assertEquals(key.z, q.z, 0f);
            assertEquals(key.w, q.w, 0f);
        }
        // 0.3 s: key 1.2, between keys 1 and 2 at 0.2 (AnimEncoding::TimeToIndex)
        bend.sample(pose.rest(), 0.3f);
        assertEquals(2.2f, pose.translation(1, t).y, EPS);
        Quaternionf a = bend.rotation(0, 1, new Quaternionf()), b = bend.rotation(0, 2, new Quaternionf());
        Quaternionf fast = new Quaternionf(b.x * 0.2f + a.x * 0.8f, b.y * 0.2f + a.y * 0.8f, b.z * 0.2f + a.z * 0.8f,
                b.w * 0.2f + a.w * 0.8f).normalize();   // FQuat::FastLerp, then normalised
        pose.rotation(1, q);
        assertEquals(fast.z, q.z, EPS);
        assertEquals(fast.w, q.w, EPS);
        // Before the first key and past the last: clamped
        bend.sample(pose.rest(), -1f);
        assertEquals(1f, pose.translation(1, t).y, 0f);
        bend.sample(pose.rest(), 9f);
        assertEquals(5f, pose.translation(1, t).y, 0f);
    }

    @Test
    public void tracksBindIgnoringCaseAndAnUnanimatedBoneRests() {
        CgPose pose = new CgPose(arm());
        bend().sample(pose.rest(), 0.5f);                       // the track says "elbow", the skeleton "Elbow"
        assertEquals(3f, pose.translation(1, new Vector3f()).y, 0f);
        assertEquals(0f, pose.translation(0, new Vector3f()).y, 0f);
    }

    @Test
    public void aSectionMapsTimeAsSequencer() {
        CgAnimSequence bend = bend();                            // 1 s long
        CgAnimSection once = CgAnimSection.of(bend).at(2.0).build();
        assertEquals(3.0, once.end(), 0);
        assertEquals(0.25f, once.animationTime(2.25), EPS);
        assertTrue(!once.loops());

        CgAnimSection looping = CgAnimSection.of(bend).at(0).until(10).build();       // outlasts its clip: loops
        assertTrue(looping.loops());
        assertEquals(0.5f, looping.animationTime(3.5), EPS);

        CgAnimSection fast = CgAnimSection.of(bend).at(0).until(0.4).playRate(2f).build();   // clamped at its end
        assertEquals(0.6f, fast.animationTime(0.3), EPS);
        assertEquals(1.0f, fast.animationTime(0.6), EPS);

        CgAnimSection trimmed = CgAnimSection.of(bend).at(0).until(5).startOffset(0.25f).endOffset(0.25f).build();
        assertEquals(0.25f, trimmed.animationTime(0), EPS);                           // plays [0.25, 0.75)
        assertEquals(0.35f, trimmed.animationTime(0.6), EPS);                         // 0.85 wrapped by the 0.5 span

        CgAnimSection backwards = CgAnimSection.of(bend).at(0).playRate(-1f).build();
        assertEquals(1.0f, backwards.animationTime(0), EPS);                          // from the end
        assertEquals(0.75f, backwards.animationTime(0.25), EPS);

        CgAnimSection reversed = CgAnimSection.of(bend).at(0).reverse().build();
        assertEquals(0.75f, reversed.animationTime(0.25), EPS);
    }

    @Test
    public void aSectionWeighsByItsEasing() {
        CgAnimSection s = CgAnimSection.of(bend()).at(0).until(4).easeIn(1f).easeOut(1f, CgEasings.LINEAR).weight(0.8f).build();
        assertEquals(0f, s.weight(-0.1), 0f);
        assertEquals(0.8f * 0.0625f, s.weight(0.25), EPS);     // CubicInOut at 1/4: 0.5 * 0.5^3
        assertEquals(0.8f * 0.5f, s.weight(0.5), EPS);
        assertEquals(0.8f, s.weight(2), EPS);
        assertEquals(0.8f * 0.5f, s.weight(3.5), EPS);          // linear ease-out half way
        assertEquals(0f, s.weight(4), 0f);
    }

    @Test
    public void sectionsCrossFadeNormalisedAndALoneOneNeverFadesFromRest() {
        CgSkeleton arm = arm();
        CgAnimSequence bend = bend();
        CgAnimSequence still = CgAnimSequence.builder("still", 4, 1)
                .track("elbow", new float[]{0, 7, 0}, new float[]{0, 0, 0, 1}, new float[]{1, 1, 1}).build();
        CgPose a = new CgPose(arm), b = new CgPose(arm), out = new CgPose(arm);
        bend.sample(a.rest(), 0f);                               // y 1
        still.sample(b.rest(), 0f);                              // y 7
        out.blend(new CgPose[]{a, b}, new float[]{0.25f, 0.75f}, 2);
        assertEquals(5.5f, out.translation(1, new Vector3f()).y, EPS);

        CgAnimSection easing = CgAnimSection.of(still).at(0).until(2).easeIn(1f).build();
        assertEquals(0.5f, easing.weight(0.5), EPS);
        // Alone, its weight normalises to 1: the pose is the clip's, not half way from the rest pose
        CgAnimInstance alone = new CgAnimInstance(armMesh(arm)).play(easing).seek(0.5).evaluate();
        assertEquals(7f, alone.pose().translation(1, new Vector3f()).y, 0f);
        // Beside a full-weight section, it takes a third: 0.5 / (1 + 0.5)
        CgAnimInstance both = new CgAnimInstance(armMesh(arm)).play(easing)
                .play(CgAnimSection.of(bend).at(0).until(2).build()).seek(0.5).evaluate();
        float bendY = 3f;                                         // bend at 0.5 s: key 2
        assertEquals(bendY * (2f / 3f) + 7f / 3f, both.pose().translation(1, new Vector3f()).y, EPS);
    }

    /** One triangle on the elbow: an instance of {@code arm} with something to skin. */
    private static CgSkeletalMesh armMesh(CgSkeleton arm) {
        CgMesh triangle = CgMesh.build(CgVertexFormat.SKINNED, m -> {
            int p = m.vertex().position(0, 0, 0).normal(0, 0, 1).uv(0, 0).bone(1, 1f).end();
            int q = m.vertex().position(1, 0, 0).normal(0, 0, 1).uv(1, 0).bone(1, 1f).end();
            int r = m.vertex().position(0, 1, 0).normal(0, 0, 1).uv(0, 1).bone(1, 1f).end();
            m.triangle(p, q, r);
        });
        return CgSkeletalMesh.builder(triangle, arm).build();
    }

    /**
     * Kurama's puppets play every clip they hold for three seconds a frame at a time, and an instance seeked to each
     * frame's time lands on the same pose and skinning, bit for bit; every rotation stays unit length.
     */
    @Test
    public void realClipsPlayAndASeekLandsWherePlayingReaches() {
        String folders = System.getProperty("crystalgraphics.skinnedMeshes");
        String pairs = System.getProperty("crystalgraphics.skinnedClips");
        Assume.assumeTrue("-PskinnedMeshes or -PskinnedClips names the files", folders != null || pairs != null);
        int clips = 0;
        if (folders != null) {
            for (String folder : folders.split(",")) {
                File[] files = new File(folder).listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".glb"));
                if (files == null) continue;
                for (File file : files) {
                    CgSkeletalMesh mesh = CgMeshLoader.skeletal(file.getAbsolutePath());
                    for (CgAnimSequence clip : mesh.sequences()) clips += playAndSeek(mesh, clip);
                }
            }
        }
        if (pairs != null) {
            for (String pair : pairs.split(",")) {
                String[] parts = pair.split("=");
                CgSkeletalMesh mesh = CgMeshLoader.skeletal(new File(parts[0]).getAbsolutePath());
                File[] files = new File(parts[1]).listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".glb"));
                if (files == null) continue;
                for (File file : files) {
                    for (CgAnimSequence clip : CgMeshLoader.sequences(file.getAbsolutePath())) clips += playAndSeek(mesh, clip);
                }
            }
        }
        assertTrue("no clip played", clips > 0);
        System.out.println("[b3] " + clips + " clips played and seeked bit for bit");
    }

    private static int playAndSeek(CgSkeletalMesh mesh, CgAnimSequence clip) {
        int bound = 0;
        for (int t = 0; t < clip.trackCount(); t++) if (mesh.skeleton().indexOfIgnoringCase(clip.trackBone(t)) >= 0) bound++;
        assertTrue(clip.name() + " binds none of " + clip.trackCount() + " tracks to " + mesh, bound > 0 || clip.trackCount() == 0);
        CgAnimSection section = CgAnimSection.of(clip).until(Double.POSITIVE_INFINITY).build();
        CgAnimInstance played = new CgAnimInstance(mesh).play(section);
        CgAnimInstance seeked = new CgAnimInstance(mesh).play(section);
        Quaternionf q = new Quaternionf();
        Matrix4f m = new Matrix4f();
        for (int frame = 0; frame < 180; frame++) {
            played.advance(1.0 / 60).evaluate();
            seeked.seek(played.time()).evaluate();
            for (int bone = 0; bone < mesh.skeleton().boneCount(); bone++) {
                float length = played.pose().rotation(bone, q).lengthSquared();
                assertEquals(clip.name() + " bone " + bone + " rotation", 1f, length, 1e-5f);
                assertEquals(clip.name() + " frame " + frame + " bone " + bone, played.skinning()[bone], seeked.skinning()[bone]);
                assertEquals(played.pose().component(bone, m), seeked.pose().component(bone));
            }
        }
        // Back to an earlier time: the pose there, whatever came before
        CgAnimInstance fresh = new CgAnimInstance(mesh).play(section).seek(0.7).evaluate();
        played.seek(0.7).evaluate();
        for (int bone = 0; bone < mesh.skeleton().boneCount(); bone++) assertEquals(fresh.skinning()[bone], played.skinning()[bone]);
        return 1;
    }

    /** What one evaluation of a puppet costs, to place {@link CgAnimation#PARALLEL_AT}: printed, never asserted. */
    @Test
    public void evaluationCost() {
        String folders = System.getProperty("crystalgraphics.skinnedMeshes");
        Assume.assumeTrue(folders != null);
        List<CgAnimInstance> instances = new ArrayList<>();
        for (String folder : folders.split(",")) {
            File[] files = new File(folder).listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".glb"));
            if (files == null) continue;
            for (File file : files) {
                CgSkeletalMesh mesh = CgMeshLoader.skeletal(file.getAbsolutePath());
                if (mesh.sequences().isEmpty()) continue;
                instances.add(new CgAnimInstance(mesh).play(CgAnimSection.of(mesh.sequences().get(0)).loop().build()));
            }
        }
        Assume.assumeTrue(!instances.isEmpty());
        for (CgAnimInstance instance : instances) {
            for (int i = 0; i < 2000; i++) instance.advance(1.0 / 60).evaluate();                    // warm
            long start = System.nanoTime();
            for (int i = 0; i < 5000; i++) instance.advance(1.0 / 60).evaluate();
            double us = (System.nanoTime() - start) / 5000.0 / 1000.0;
            System.out.printf(Locale.ROOT, "[b3] %s, %d bones: %.2f us an evaluation%n", instance.mesh(),
                    instance.mesh().skeleton().boneCount(), us);
        }
        // n of the largest, serial against CgJob.forEach: where splitting starts to pay
        CgAnimInstance largest = instances.get(0);
        for (CgAnimInstance i : instances) if (i.mesh().skeleton().boneCount() > largest.mesh().skeleton().boneCount()) largest = i;
        for (int n : new int[]{1, 2, 4, 8, 16, 32}) {
            CgAnimInstance[] set = new CgAnimInstance[n];
            for (int i = 0; i < n; i++) set[i] = new CgAnimInstance(largest.mesh()).play(largest.sections().get(0));
            IntConsumer each = i -> set[i].advance(1.0 / 60).evaluate();
            double serial = 0, split = 0;
            for (int round = 0; round < 2; round++) {
                long start = System.nanoTime();
                for (int f = 0; f < 2000; f++) for (int i = 0; i < n; i++) each.accept(i);
                serial = (System.nanoTime() - start) / 2000.0 / 1000.0;
                start = System.nanoTime();
                for (int f = 0; f < 2000; f++) CgJob.forEach(n, each);
                split = (System.nanoTime() - start) / 2000.0 / 1000.0;
            }
            System.out.printf(Locale.ROOT, "[b3] %d puppets: serial %.1f us, forEach %.1f us a frame%n", n, serial, split);
        }
    }
}
