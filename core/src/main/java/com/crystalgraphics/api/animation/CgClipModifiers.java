package com.crystalgraphics.api.animation;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Data baked into a clip from its own motion, once at load: root motion for a clip authored in place, foot sync
 * markers, and the distance curve distance matching plays by. What an animator's modifiers write into a clip in an
 * editor.
 *
 * <pre>{@code
 * // an in-place run loop made to travel as its planted feet say, then marked at each foot's swing
 * CgAnimSequence run = CgClipModifiers.rootMotionFromFeet(raw, skeleton, true, "root", "foot_l", "foot_r");
 * run = CgClipModifiers.footSyncMarkers(run, skeleton, "root", "foot_l", "foot_l", true);
 * run = CgClipModifiers.footSyncMarkers(run, skeleton, "root", "foot_r", "foot_r", true);
 * float speed = CgClipModifiers.speed(run, skeleton);           // metres a second it covers at rate 1
 *
 * // a stop, distance matched to where the character stops
 * CgAnimSequence stop = CgClipModifiers.distanceCurve(CgClipModifiers.rootMotionFromFeet(rawStop, skeleton, false, "root",
 *         "foot_l", "foot_r"), skeleton, CgClipModifiers.DISTANCE, true);
 * }</pre>
 *
 * <ul>
 *   <li>Framed clips only: a keyed (GeckoLib) clip throws. Bake it first.</li>
 *   <li>Horizontal is the skeleton's x and z: y is up.</li>
 *   <li>Each call answers a new clip; the one handed in is unchanged.</li>
 * </ul>
 */
public final class CgClipModifiers {

    /** The distance curve's usual name, which distance matching reads by default. */
    public static final String DISTANCE = "Distance";
    /** Below this speed, metres a second, a clip is taken to have stopped: 5 cm/s. */
    public static final float STOP_SPEED = 0.05f;

    private CgClipModifiers() {
    }

    /**
     * {@code clip} given root motion from its feet. A step lasts while one foot is the lowest; over it the root covers
     * what that foot sweeps backward (planted, it is left behind as the body travels), at an even pace, as a captured
     * root moves. The root's own track is replaced and root motion turned on, locked at the root's rest.
     *
     * <pre>{@code
     * CgAnimSequence run = CgClipModifiers.rootMotionFromFeet(raw, goku, true, "root", "foot_l", "foot_r");
     * }</pre>
     *
     * <ul>
     *   <li>{@code looping}: the step across the loop's seam is one step. A loop read as not looping has a short last
     *       step, and a stride warped by it jumps once a loop.</li>
     *   <li>For a clip authored in place: one whose root already moves throws, naming it.</li>
     *   <li>The motion is carried on the root's parent's axes as the first frame poses them.</li>
     * </ul>
     *
     * @throws IllegalArgumentException where a bone is not in the skeleton, or the root already moves
     */
    public static CgAnimSequence rootMotionFromFeet(CgAnimSequence clip, CgSkeleton skeleton, boolean looping, String rootBone, String... feet) {
        if (feet.length == 0) throw new IllegalArgumentException(clip.name() + ": no feet");
        int root = bone(skeleton, rootBone, clip);
        int[] foot = new int[feet.length];
        for (int i = 0; i < feet.length; i++) foot[i] = bone(skeleton, feet[i], clip);
        int track = clip.track(skeleton.name(root));
        if (track < 0) track = clip.track(rootBone);
        int n = clip.frameCount();
        Vector3f t = new Vector3f(), u = new Vector3f();
        Quaternionf r = new Quaternionf();
        if (track >= 0) {
            clip.translation(track, 0, t);
            for (int f = 1; f < n; f++) {
                if (clip.translation(track, f, u).distance(t) > 1e-4f) {
                    throw new IllegalArgumentException(clip.name() + ": its root " + rootBone + " already moves");
                }
            }
        }
        CgAnimPose pose = new CgAnimPose(skeleton);
        float[][] at = new float[n][feet.length * 3];
        Matrix4f m = new Matrix4f(), parent = new Matrix4f();
        float dt = (float) (1.0 / clip.frameRate());
        for (int f = 0; f < n; f++) {
            pose.rest();
            clip.sample(pose, Math.min(f * dt, clip.duration()));
            pose.resolve();
            if (f == 0) {
                int p = skeleton.parent(root);
                if (p >= 0) parent.set(pose.component(p)).invertAffine();
            }
            for (int i = 0; i < foot.length; i++) {
                pose.component(foot[i], m).getTranslation(u);
                at[f][i * 3] = u.x;
                at[f][i * 3 + 1] = u.y;
                at[f][i * 3 + 2] = u.z;
            }
        }
        Vector3f start = track >= 0 ? clip.translation(track, 0, new Vector3f()) : skeleton.restTranslation(root, new Vector3f());
        // each frame's move of the lowest foot, from the frame before
        float[] sx = new float[n], sz = new float[n];
        int[] low = new int[n];
        float travelX = 0f, travelZ = 0f;
        for (int f = 1; f < n; f++) {
            for (int i = 1; i < foot.length; i++) {
                if (at[f - 1][i * 3 + 1] + at[f][i * 3 + 1] < at[f - 1][low[f] * 3 + 1] + at[f][low[f] * 3 + 1]) low[f] = i;
            }
            sx[f] = at[f][low[f] * 3] - at[f - 1][low[f] * 3];
            sz[f] = at[f][low[f] * 3 + 2] - at[f - 1][low[f] * 3 + 2];
            travelX -= sx[f];
            travelZ -= sz[f];
        }
        // a step lasts while one foot stays lowest; the body covers what that foot sweeps backward (planted, left behind
        // as the body travels) at an even pace over the step: a smooth root, as a captured one is
        // a loop's step across its seam is one step: the walk starts where a step does and wraps
        float[] vx = new float[n], vz = new float[n];
        int steps = n - 1, begin = 0;
        if (looping) for (int j = 1; j < steps; j++) if (low[j + 1] != low[j]) {
            begin = j;
            break;
        }
        for (int j = 0; j < steps; ) {
            int first = 1 + (begin + j) % steps, end = j;
            float dx = 0f, dz = 0f;
            while (end < steps && low[1 + (begin + end) % steps] == low[first]) {
                int f = 1 + (begin + end) % steps;
                if (sx[f] * travelX + sz[f] * travelZ < 0f) {
                    dx -= sx[f];
                    dz -= sz[f];
                }
                end++;
            }
            for (int k = j; k < end; k++) {
                int f = 1 + (begin + k) % steps;
                vx[f] = dx / (end - j);
                vz[f] = dz / (end - j);
            }
            j = end;
        }
        float[] translations = new float[n * 3];
        float x = 0f, z = 0f;
        for (int f = 0; f < n; f++) {
            x += vx[f];
            z += vz[f];
            parent.transformDirection(u.set(x, 0f, z));
            translations[f * 3] = start.x + u.x;
            translations[f * 3 + 1] = start.y + u.y;
            translations[f * 3 + 2] = start.z + u.z;
        }
        float[] rotations, scales;
        if (track >= 0) {
            rotations = new float[n * 4];
            scales = new float[n * 3];
            for (int f = 0; f < n; f++) {
                clip.rotation(track, f, r);
                rotations[f * 4] = r.x;
                rotations[f * 4 + 1] = r.y;
                rotations[f * 4 + 2] = r.z;
                rotations[f * 4 + 3] = r.w;
                clip.scale(track, f, u);
                scales[f * 3] = u.x;
                scales[f * 3 + 1] = u.y;
                scales[f * 3 + 2] = u.z;
            }
        } else {
            skeleton.restRotation(root, r);
            rotations = new float[]{r.x, r.y, r.z, r.w};
            skeleton.restScale(root, u);
            scales = new float[]{u.x, u.y, u.z};
        }
        CgClipSettings settings = clip.settings().withRootMotion(new CgRootMotion(true, skeleton.name(root), CgRootMotion.Lock.REF_POSE, false));
        return clip.withTrack(skeleton.name(root), translations, rotations, scales).with(settings);
    }

    /**
     * {@code clip} with its travel moved onto its root: each frame the root goes horizontally where {@code source}
     * (the pelvis, usually) is, keeping its own height and turn, and the root's children are placed back where they
     * were, so the pose is unchanged and the travel becomes root motion. For a clip whose author moved the pelvis
     * instead of the root: a rush, a dash, a teleport strike.
     *
     * <pre>{@code
     * CgAnimSequence rush = CgClipModifiers.encodeRootBone(rawRush, goku, "root", "pelvis");
     * float covered = rush.extractRootMotion(goku, 0f, rush.duration(), false, new Matrix4f()).getTranslation(new Vector3f()).length();
     * }</pre>
     *
     * <ul>
     *   <li>The root's direct children are given a track each, every frame keyed.</li>
     *   <li>Root motion is turned on, locked at the root's rest.</li>
     * </ul>
     *
     * @throws IllegalArgumentException where a bone is not in the skeleton
     */
    public static CgAnimSequence encodeRootBone(CgAnimSequence clip, CgSkeleton skeleton, String rootBone, String source) {
        int root = bone(skeleton, rootBone, clip), from = bone(skeleton, source, clip), parent = skeleton.parent(root);
        List<Integer> children = new ArrayList<>();
        for (int b = 0; b < skeleton.boneCount(); b++) if (skeleton.parent(b) == root) children.add(b);
        int n = clip.frameCount();
        float[] rootT = new float[n * 3], rootR = new float[n * 4], rootS = new float[n * 3];
        float[][] childT = new float[children.size()][n * 3], childR = new float[children.size()][n * 4], childS = new float[children.size()][n * 3];
        CgAnimPose pose = new CgAnimPose(skeleton);
        Matrix4f rootNew = new Matrix4f(), m = new Matrix4f(), inv = new Matrix4f();
        Vector3f t = new Vector3f(), p = new Vector3f(), s = new Vector3f();
        Quaternionf q = new Quaternionf();
        float dt = (float) (1.0 / clip.frameRate());
        for (int f = 0; f < n; f++) {
            pose.rest();
            clip.sample(pose, Math.min(f * dt, clip.duration()));
            pose.resolve();
            pose.component(from, m).getTranslation(p);
            rootNew.set(pose.component(root));
            rootNew.getTranslation(t);
            rootNew.setTranslation(p.x, t.y, p.z);
            // the root's local: under its parent as posed
            if (parent >= 0) m.set(pose.component(parent)).invertAffine().mul(rootNew);
            else m.set(rootNew);
            write(m, f, rootT, rootR, rootS, t, q, s);
            rootNew.invertAffine(inv);
            for (int c = 0; c < children.size(); c++) {
                m.set(inv).mul(pose.component(children.get(c)));
                write(m, f, childT[c], childR[c], childS[c], t, q, s);
            }
        }
        CgAnimSequence out = clip.withTrack(skeleton.name(root), rootT, rootR, rootS);
        for (int c = 0; c < children.size(); c++) out = out.withTrack(skeleton.name(children.get(c)), childT[c], childR[c], childS[c]);
        return out.with(out.settings().withRootMotion(new CgRootMotion(true, skeleton.name(root), CgRootMotion.Lock.REF_POSE, false)));
    }

    private static void write(Matrix4f m, int f, float[] t, float[] r, float[] s, Vector3f tv, Quaternionf q, Vector3f sv) {
        m.getTranslation(tv);
        m.getNormalizedRotation(q);
        m.getScale(sv);
        t[f * 3] = tv.x;
        t[f * 3 + 1] = tv.y;
        t[f * 3 + 2] = tv.z;
        r[f * 4] = q.x;
        r[f * 4 + 1] = q.y;
        r[f * 4 + 2] = q.z;
        r[f * 4 + 3] = q.w;
        s[f * 3] = sv.x;
        s[f * 3 + 1] = sv.y;
        s[f * 3 + 2] = sv.z;
    }

    /**
     * {@code clip} with a distance curve named {@code curve}: each frame's horizontal root motion distance from the
     * clip's stop, negative before it (approaching) and positive after (leaving). The stop is the clip's end where
     * {@code stopAtEnd} (a stop clip), else its slowest moment (a pivot; a start's is its first frame).
     *
     * <pre>{@code
     * CgAnimSequence start = CgClipModifiers.distanceCurve(runStart, skeleton, CgClipModifiers.DISTANCE, false);
     * CgAnimSequence stop = CgClipModifiers.distanceCurve(runStop, skeleton, CgClipModifiers.DISTANCE, true);
     * }</pre>
     *
     * @throws IllegalArgumentException where the clip has no root motion
     */
    public static CgAnimSequence distanceCurve(CgAnimSequence clip, CgSkeleton skeleton, String curve, boolean stopAtEnd) {
        if (!clip.settings().rootMotion().enabled()) throw new IllegalArgumentException(clip.name() + ": no root motion to measure");
        float length = clip.duration(), stop = length;
        Matrix4f m = new Matrix4f();
        Vector3f t = new Vector3f();
        if (!stopAtEnd) {
            float step = 1f / 120f, min = STOP_SPEED * STOP_SPEED;
            stop = 0f;
            for (int s = 0, steps = (int) (length / step); s < steps; s++) {
                clip.extractRootMotion(skeleton, s * step, step, false, m).getTranslation(t);
                float speed = (t.x * t.x + t.z * t.z) / (step * step);
                if (speed < min) {
                    min = speed;
                    stop = s * step;
                }
            }
        }
        int n = clip.frameCount();
        float[] keys = new float[n];
        float dt = (float) (1.0 / clip.frameRate());
        for (int f = 0; f < n; f++) {
            float time = Math.min(f * dt, length);
            clip.extractRootMotion(skeleton, stop, time - stop, false, m).getTranslation(t);
            keys[f] = (time < stop ? -1f : 1f) * (float) Math.sqrt(t.x * t.x + t.z * t.z);
        }
        return clip.withCurve(curve, keys, CgAnimSequence.DRIVES_NONE);
    }

    /**
     * {@code clip} with a sync marker named {@code marker} wherever {@code foot} passes forward through
     * {@code referenceBone}'s plane of travel (its own move between two samples, at 60 a second): mid-swing, the same
     * point of every stride. Loops of different speeds marked alike blend in step ({@code CgBlendSpace} syncs by
     * markers), the left foot of one never over the right of another.
     *
     * <pre>{@code
     * CgAnimSequence run = CgClipModifiers.rootMotionFromFeet(raw, s, true, "root", "foot_l", "foot_r");
     * run = CgClipModifiers.footSyncMarkers(run, s, "root", "foot_l", "foot_l", true);
     * run = CgClipModifiers.footSyncMarkers(run, s, "root", "foot_r", "foot_r", true);
     * }</pre>
     *
     * <ul>
     *   <li>The reference must travel: give an in-place clip root motion first ({@link #rootMotionFromFeet}).</li>
     *   <li>A loop ({@code looping}) is read across its seam, its end before its start; otherwise a crossing in the first
     *       two samples is not marked.</li>
     * </ul>
     */
    public static CgAnimSequence footSyncMarkers(CgAnimSequence clip, CgSkeleton skeleton, String referenceBone, String foot, String marker,
                                                 boolean looping) {
        int ref = bone(skeleton, referenceBone, clip), f = bone(skeleton, foot, clip);
        float length = clip.duration(), step = 1f / 60f;
        int samples = (int) (length / step);
        CgAnimPose now = new CgAnimPose(skeleton), next = new CgAnimPose(skeleton);
        Vector3f r0 = new Vector3f(), r1 = new Vector3f(), p = new Vector3f();
        Matrix4f m = new Matrix4f();
        List<CgSyncMarker> markers = new ArrayList<>(clip.settings().markers());
        float previous = looping ? side(clip, now, next, ref, f, length - step, length, r0, r1, p, m) : 0f;
        for (int i = 0; i < samples - 1; i++) {
            float t = Math.min(i * step, length);
            float dot = side(clip, now, next, ref, f, t, Math.min((i + 1) * step, length), r0, r1, p, m);
            if ((looping || i > 1) && dot > 0f && previous < 0f) markers.add(new CgSyncMarker(marker, t));
            previous = dot;
        }
        markers.sort(Comparator.comparingDouble(CgSyncMarker::time));
        return clip.with(clip.settings().withMarkers(markers));
    }

    /** Which side of the reference's plane of travel (between {@code t} and {@code next}) the foot stands at {@code t}. */
    private static float side(CgAnimSequence clip, CgAnimPose now, CgAnimPose then, int ref, int foot, float t, float next,
                              Vector3f r0, Vector3f r1, Vector3f p, Matrix4f m) {
        sampleUnlocked(clip, now, t);
        sampleUnlocked(clip, then, next);
        now.component(ref, m).getTranslation(r0);
        then.component(ref, m).getTranslation(r1);
        now.component(foot, m).getTranslation(p);
        return r1.sub(r0).dot(p.sub(r0));
    }

    private static void sampleUnlocked(CgAnimSequence clip, CgAnimPose pose, float time) {
        pose.rest();
        clip.sample(pose, time, false);
        pose.resolve();
    }

    /** The horizontal distance its root motion covers over the whole clip, over its length: metres a second at rate 1. */
    public static float speed(CgAnimSequence clip, CgSkeleton skeleton) {
        float length = clip.duration();
        if (!(length > 0f)) return 0f;
        Vector3f t = clip.extractRootMotion(skeleton, 0f, length, false, new Matrix4f()).getTranslation(new Vector3f());
        return (float) Math.sqrt(t.x * t.x + t.z * t.z) / length;
    }

    private static int bone(CgSkeleton skeleton, String name, CgAnimSequence clip) {
        int b = skeleton.indexOfIgnoringCase(name);
        if (b < 0) throw new IllegalArgumentException(clip.name() + ": " + name + " is not a bone of " + skeleton);
        return b;
    }
}
