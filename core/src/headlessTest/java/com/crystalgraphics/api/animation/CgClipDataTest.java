package com.crystalgraphics.api.animation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.Test;

import com.crystalgraphics.asset.tree.CgAssetException;

/** A clip's data beyond its keys: sync markers, root motion, additive poses, and the {@code clip} asset. */
public class CgClipDataTest {

    private static final CgSkeleton SKELETON = CgSkeleton.builder()
            .bone("root", null, new Vector3f())
            .bone("arm", "root", new Vector3f(0, 1, 0))
            .build();

    /** One second at 2 keys a second: the root walks 2 blocks along x, the arm turns 90° about z. */
    private static CgAnimSequence walk(CgClipSettings settings) {
        Quaternionf turned = new Quaternionf().rotateZ((float) Math.PI / 2);
        return CgAnimSequence.builder("walk", 2, 3)
                .track("root", new float[]{0, 0, 0, 1, 0, 0, 2, 0, 0}, new float[]{0, 0, 0, 1}, new float[]{1, 1, 1})
                .track("arm", new float[]{0, 1, 0}, new float[]{0, 0, 0, 1, 0, 0, 0, 1, turned.x, turned.y, turned.z, turned.w},
                        new float[]{1, 1, 1})
                .settings(settings).build();
    }

    @Test
    public void markersArePassedInUnrealsHalfOpenRangeWithTheirDelta() {
        CgAnimSequence run = walk(CgClipSettings.NONE.withMarkers(List.of(new CgSyncMarker("foot_r", 0.5f),
                new CgSyncMarker("foot_l", 0.1f))));
        assertEquals("sorted by time", "foot_l", run.settings().markers().get(0).name());
        List<String> heard = new ArrayList<>();
        run.markersPassed(0.45f, 0.55f, 0.1f, (name, delta) -> heard.add(name + "@" + Math.round(delta * 100)));
        assertEquals(List.of("foot_r@5"), heard);
        heard.clear();
        run.markersPassed(0.1f, 0.5f, 0.4f, (name, delta) -> heard.add(name));
        assertEquals("from included, to excluded", List.of("foot_l"), heard);
    }

    @Test
    public void aMarkerPastTheEndFails() {
        assertThrows(IllegalArgumentException.class,
                () -> walk(CgClipSettings.NONE.withMarkers(List.of(new CgSyncMarker("late", 1.5f)))));
    }

    @Test
    public void rootMotionIsTheRootsMoveAndWrapsWhenLooping() {
        CgAnimSequence walk = walk(CgClipSettings.NONE.withRootMotion(CgRootMotion.of(CgRootMotion.Lock.REF_POSE)));
        Vector3f t = new Vector3f();
        walk.extractRootMotion(SKELETON, 0f, 0.5f, false, new Matrix4f()).getTranslation(t);
        assertEquals(1f, t.x, 1e-5f);
        walk.extractRootMotion(SKELETON, 0.75f, 0.5f, true, new Matrix4f()).getTranslation(t);
        assertEquals("0.25 s to the end, then 0.25 s from the start", 1f, t.x, 1e-5f);
        walk.extractRootMotion(SKELETON, 0.75f, 0.5f, false, new Matrix4f()).getTranslation(t);
        assertEquals("stops at the end", 0.5f, t.x, 1e-5f);
        walk(CgClipSettings.NONE).extractRootMotion(SKELETON, 0f, 0.5f, false, new Matrix4f()).getTranslation(t);
        assertEquals("none where root motion is off", 0f, t.x, 0f);
    }

    @Test
    public void theRootIsLockedOnlyWhileItsMotionIsExtracted() {
        CgAnimSequence walk = walk(CgClipSettings.NONE.withRootMotion(CgRootMotion.of(CgRootMotion.Lock.REF_POSE)));
        CgAnimPose pose = new CgAnimPose(SKELETON);
        Vector3f t = new Vector3f();
        walk.sample(pose.rest(), 0.5f, false);
        assertEquals("as keyed", 1f, pose.translation(0, t).x, 1e-5f);
        walk.sample(pose.rest(), 0.5f, true);
        assertEquals("held at its rest", 0f, pose.translation(0, t).x, 1e-5f);
        CgAnimSequence forced = walk(CgClipSettings.NONE.withRootMotion(new CgRootMotion(false, null, CgRootMotion.Lock.ZERO, true)));
        forced.sample(pose.rest(), 0.5f, false);
        assertEquals("forced", 0f, pose.translation(0, t).x, 1e-5f);
    }

    @Test
    public void anAdditiveAddedToItsBaseGivesTheClipBack() {
        CgAnimSequence walk = walk(CgClipSettings.NONE.withAdditive(CgAdditive.local(CgAdditive.Base.REF_POSE, null, 0)));
        CgAnimPose delta = new CgAnimPose(SKELETON), scratch = new CgAnimPose(SKELETON), body = new CgAnimPose(SKELETON), clip = new CgAnimPose(SKELETON);
        walk.sampleAdditive(delta, scratch, 0.5f);
        body.rest().accumulateAdditive(delta, 1f);
        walk.sample(clip.rest(), 0.5f);
        for (int b = 0; b < 2; b++) {
            Quaternionf q = body.rotation(b, new Quaternionf()), r = clip.rotation(b, new Quaternionf());
            assertEquals("bone " + b, 1f, Math.abs(q.dot(r)), 1e-5f);
            assertEquals(clip.translation(b, new Vector3f()).x, body.translation(b, new Vector3f()).x, 1e-5f);
        }
        // Half weight: half the turn, as Unreal blends from the additive identity
        body.rest().accumulateAdditive(delta, 0.5f);
        float angle = body.rotation(1, new Quaternionf()).angle();
        float full = clip.rotation(1, new Quaternionf()).angle();
        assertEquals(full / 2, angle, 0.02f);
    }

    @Test
    public void theClipAssetCompilesAndRefusesMeshSpace() {
        CgClipDefinition d = CgClipDefinition.compile("mymod:hero/lunge", Map.of(
                "sync_markers", List.of(Map.of("name", "foot_l", "at", 0.1)),
                "root_motion", Map.of("enabled", true, "lock", "anim_first_frame"),
                "additive", Map.of("type", "local_space", "base", "frame", "base_frame", 2)));
        CgClipSettings s = d.settings(id -> null);
        assertEquals("foot_l", s.markers().get(0).name());
        assertTrue(s.rootMotion().enabled());
        assertEquals(CgRootMotion.Lock.ANIM_FIRST_FRAME, s.rootMotion().lock());
        assertEquals(CgAdditive.Base.FRAME, s.additive().base());
        assertThrows(CgAssetException.class, () -> CgClipDefinition.compile("mymod:aim",
                Map.of("additive", Map.of("type", "mesh_space"))));
        assertThrows(CgAssetException.class, () -> CgClipDefinition.compile("mymod:flinch",
                Map.of("additive", Map.of("type", "local_space", "base", "clip_frame", "base_clip", "mymod:idle"))).settings(id -> null));
    }
}
