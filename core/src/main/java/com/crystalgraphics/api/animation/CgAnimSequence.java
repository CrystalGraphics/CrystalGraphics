package com.crystalgraphics.api.animation;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.crystalgraphics.asset.curve.CgRichCurve;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An animation clip: each animated bone's translation, rotation and scale at evenly spaced frames, and any float
 * curves (a morph target's weight) beside them. Mirrors Unreal's {@code UAnimSequence}: a frame rate, a number of
 * keys, a track per animated bone holding all three channels, a bone without a track holding its rest pose.
 *
 * <pre>{@code
 * CgSkeletalMesh kurama = CgMeshLoader.skeletal("mymod:models/kurama.glb");
 * CgAnimSequence attack = kurama.sequence("atk010");
 * attack.duration();                                    // seconds: (frameCount() - 1) / frameRate()
 * int pelvis = attack.track("pelvis");                  // -1: the pelvis is not animated, it rests
 * attack.rotation(pelvis, 12, new Quaternionf());       // frame 12's rotation, local to its parent
 * }</pre>
 *
 * <p>Built in Java, the same as one loaded; tracks name their bones, so a sequence plays on any skeleton that has
 * them:</p>
 *
 * <pre>{@code
 * CgAnimSequence wave = CgAnimSequence.builder("wave", 30, 31)          // 31 keys at 30 a second: one second
 *         .track("elbow", new float[]{0, -0.3f, 0},                      // one key: held for the whole sequence
 *                 rotations,                                             // 31 x (x, y, z, w)
 *                 new float[]{1, 1, 1})
 *         .build();
 * }</pre>
 *
 * <ul>
 *   <li>Immutable and shared; any thread.</li>
 *   <li>A channel holds one key or {@code frameCount()} keys. {@link Builder#build()} keeps one where every key lies
 *       within Unreal's threshold of the first ({@code UAnimCompress::FilterTrivialKeys}), so frame lookups never need
 *       to know: a held channel answers the same for every frame.</li>
 *   <li>Frames, not seconds: sampling between frames is the player's.</li>
 * </ul>
 *
 * <p><b>Keyed</b>, a sequence holds each channel as a curve with keys at their own times instead, as a GeckoLib or
 * Blockbench animation keeps them: a bone's translation, its rotation as Euler angles composed {@code Rz·Ry·Rx}, and
 * its scale, nine {@link CgRichCurve}s, each eased as its keys say. It samples exactly at any time, with no frames:</p>
 *
 * <pre>{@code
 * CgRichCurve still = CgRichCurve.decode(Map.of("keys", List.of(List.of(0, 0))));
 * CgRichCurve one = CgRichCurve.decode(Map.of("keys", List.of(List.of(0, 1))));
 * CgRichCurve swing = CgRichCurve.decode(Map.of("keys", List.of(List.of(0, 0, "sin_in_out"), List.of(0.5, 1.2))));
 * CgAnimSequence wave = CgAnimSequence.keyed("wave", 0.5f)
 *         .track("elbow", still, still, still,   swing, still, still,   one, one, one)
 *         .build();
 * wave.sample(pose, 0.25f);                                              // the elbow halfway through its ease
 * }</pre>
 *
 * <ul>
 *   <li>A keyed sequence has no frames: {@link #frameRate()} and {@link #frameCount()} are 0, the per-frame reads and
 *       {@link #withCurve} throw, and it is never cooked: its file is read as it is.</li>
 *   <li>Every channel of a keyed track is given; a still one is a single key.</li>
 * </ul>
 */
public final class CgAnimSequence {

    /** Unreal's trivial-key thresholds: translation 0.0001 cm in blocks, rotation in {@code FQuat::Error}, scale. */
    static final float TRANSLATION_THRESHOLD = 0.0001f / 100f, ROTATION_THRESHOLD = 0.0003f, SCALE_THRESHOLD = 0.000001f;

    private final String name;
    private final double frameRate;
    private final int frameCount;
    private final String[] trackBones;
    /** Per track: translations (3 a key), rotations (4: x, y, z, w), scales (3); each one key or every frame. */
    final float[][] translations, rotations, scales;
    /** What a curve drives: a morph target's weight, the default. */
    public static final int DRIVES_MORPH = 0;
    /**
     * What a curve drives: the material scalar of its name on every material of the mesh that declares it, as Unreal's
     * material curves do ({@code USkeletalMeshComponent::ApplyAnimationCurvesToComponent}).
     */
    public static final int DRIVES_MATERIAL = 1;
    /** What a curve drives: nothing the engine applies, a value the role's own logic may read. */
    public static final int DRIVES_NONE = 2;

    private final String[] curveNames;
    final float[][] curves;
    final int[] curveDrives;
    private final Map<String, Integer> trackIndex = new HashMap<>(), curveIndex = new HashMap<>();
    /** A keyed sequence's channels, nine a track (translation, Euler rotation, scale, each x y z); null when framed. */
    final CgRichCurve[][] keys;
    private final float keyedDuration;

    CgAnimSequence(String name, double frameRate, int frameCount, String[] trackBones, float[][] translations,
                   float[][] rotations, float[][] scales, String[] curveNames, float[][] curves, int[] curveDrives) {
        this(name, frameRate, frameCount, trackBones, translations, rotations, scales, curveNames, curves, curveDrives, null, 0f);
    }

    private CgAnimSequence(String name, double frameRate, int frameCount, String[] trackBones, float[][] translations,
                           float[][] rotations, float[][] scales, String[] curveNames, float[][] curves, int[] curveDrives,
                           CgRichCurve[][] keys, float keyedDuration) {
        this.keys = keys;
        this.keyedDuration = keyedDuration;
        this.curveDrives = curveDrives;
        this.name = name;
        this.frameRate = frameRate;
        this.frameCount = frameCount;
        this.trackBones = trackBones;
        this.translations = translations;
        this.rotations = rotations;
        this.scales = scales;
        this.curveNames = curveNames;
        this.curves = curves;
        for (int t = 0; t < trackBones.length; t++) trackIndex.put(trackBones[t], t);
        for (int c = 0; c < curveNames.length; c++) curveIndex.put(curveNames[c], c);
    }

    /**
     * A sequence of {@code frameCount} keys at {@code frameRate} a second, built track by track.
     *
     * @param frameCount at least 1; a sequence of one key is a pose
     */
    public static Builder builder(String name, double frameRate, int frameCount) {
        return new Builder(name, frameRate, frameCount);
    }

    /**
     * A keyed sequence {@code duration} seconds long, built track by track: each bone's nine channels as curves.
     *
     * @param duration its length, at least 0: how long a section playing it runs, not where its keys stop
     */
    public static KeyedBuilder keyed(String name, float duration) {
        return new KeyedBuilder(name, duration);
    }

    /** Whether its channels are curves with keys at their own times ({@link #keyed}), rather than frames. */
    public boolean isKeyed() {
        return keys != null;
    }

    /**
     * A keyed track's channel: {@code channel} 0-2 its translation's x, y, z, 3-5 its Euler rotation's in radians, 6-8
     * its scale's.
     *
     * @throws IllegalStateException on a framed sequence
     */
    public CgRichCurve channel(int track, int channel) {
        if (keys == null) throw new IllegalStateException(name + " is framed, not keyed");
        return keys[track][channel];
    }

    public String name() {
        return name;
    }

    /** Keys a second. */
    public double frameRate() {
        return frameRate;
    }

    /** Keys from the first to the last, both included. */
    public int frameCount() {
        return frameCount;
    }

    /** Seconds from the first key to the last; a keyed sequence's, the length it was given. */
    public float duration() {
        return keys != null ? keyedDuration : (float) ((frameCount - 1) / frameRate);
    }

    public int trackCount() {
        return trackBones.length;
    }

    /** The bone track {@code track} animates, by name. */
    public String trackBone(int track) {
        return trackBones[track];
    }

    /** The track animating the bone named {@code bone}, or -1 where it rests. */
    public int track(String bone) {
        Integer t = trackIndex.get(bone);
        return t == null ? -1 : t;
    }

    /** Frame {@code frame}'s translation of the track's bone, local to its parent. */
    public Vector3f translation(int track, int frame, Vector3f out) {
        framed();
        float[] k = translations[track];
        int at = k.length == 3 ? 0 : frame * 3;
        return out.set(k[at], k[at + 1], k[at + 2]);
    }

    /** Frame {@code frame}'s rotation of the track's bone, local to its parent, unit length. */
    public Quaternionf rotation(int track, int frame, Quaternionf out) {
        framed();
        float[] k = rotations[track];
        int at = k.length == 4 ? 0 : frame * 4;
        return out.set(k[at], k[at + 1], k[at + 2], k[at + 3]);
    }

    /** Frame {@code frame}'s scale of the track's bone. */
    public Vector3f scale(int track, int frame, Vector3f out) {
        framed();
        float[] k = scales[track];
        int at = k.length == 3 ? 0 : frame * 3;
        return out.set(k[at], k[at + 1], k[at + 2]);
    }

    /** Whether the track's translation, rotation and scale each hold one key for the whole sequence. */
    public boolean held(int track) {
        framed();
        return translations[track].length == 3 && rotations[track].length == 4 && scales[track].length == 3;
    }

    public int curveCount() {
        return curveNames.length;
    }

    public String curveName(int curve) {
        return curveNames[curve];
    }

    /** The curve named {@code name} (a morph target's), or -1. */
    public int curve(String name) {
        Integer c = curveIndex.get(name);
        return c == null ? -1 : c;
    }

    /** Frame {@code frame}'s value of the curve. */
    public float curveValue(int curve, int frame) {
        float[] k = curves[curve];
        return k[k.length == 1 ? 0 : frame];
    }

    /** What the curve drives: {@link #DRIVES_MORPH} or {@link #DRIVES_MATERIAL}. */
    public int curveDrives(int curve) {
        return curveDrives[curve];
    }

    /**
     * This sequence with one more curve, {@code keys} one or {@link #frameCount()} values: what a loader adds from a
     * clip's sidecar ({@code curves:} beside its glb).
     *
     * @throws IllegalArgumentException as {@link Builder#curve(String, float[], int)} does
     */
    public CgAnimSequence withCurve(String curve, float[] keys, int drives) {
        framed();
        if (curveIndex.containsKey(curve)) throw new IllegalArgumentException(name + " has curve " + curve + " twice");
        Builder b = new Builder(name, frameRate, frameCount);
        for (int c = 0; c < curveNames.length; c++) b.curve(curveNames[c], curves[c], curveDrives[c]);
        b.curve(curve, keys, drives);
        float[][] k = b.curves.toArray(new float[0][]);
        int[] d = new int[b.drives.size()];
        for (int i = 0; i < d.length; i++) d[i] = b.drives.get(i);
        return new CgAnimSequence(name, frameRate, frameCount, trackBones, translations, rotations, scales,
                b.curveNames.toArray(new String[0]), k, d);
    }

    /** The curve's value at {@code time} seconds: between its keys as {@link #sample} takes a bone's, clamped to its ends. */
    public float curveAt(int curve, float time) {
        float[] k = curves[curve];
        if (k.length == 1 || frameCount < 2 || time <= 0f) return k[0];
        if (time >= duration()) return k[frameCount - 1];
        float key = time / duration() * (frameCount - 1);
        int a = Math.min((int) Math.floor(key), frameCount - 1), b = Math.min(a + 1, frameCount - 1);
        return k[a] + (k[b] - k[a]) * (key - (float) Math.floor(key));
    }

    /**
     * Writes the sequence's bones at {@code time} seconds into {@code pose}; a bone it does not animate keeps what the
     * pose held. Unreal's sampling: the key pair and alpha of {@code AnimEncoding::TimeToIndex} (AnimEncoding.h:399-446),
     * clamped to the first and last key, then {@code FTransform::Blend} (TransformVectorized.h:427-460): translation and
     * scale lerped, rotation along the shortest path and renormalised.
     *
     * <pre>{@code
     * pose.rest();
     * attack.sample(pose, 0.4f);
     * }</pre>
     *
     * <ul>
     *   <li>Tracks bind to the pose's skeleton by bone name ignoring case, once a skeleton; a track naming a bone the
     *       skeleton lacks is skipped.</li>
     *   <li>Allocates nothing once bound; any thread, one pose per thread.</li>
     * </ul>
     */
    public void sample(CgPose pose, float time) {
        int[] bones = bind(pose.skeleton());
        if (keys != null) {
            sampleKeyed(bones, pose.local, time);
            return;
        }
        int a, b;
        float alpha;
        if (frameCount < 2 || time <= 0f) {
            a = b = 0;
            alpha = 0f;
        } else if (time >= duration()) {
            a = b = frameCount - 1;
            alpha = 0f;
        } else {
            float key = time / duration() * (frameCount - 1);
            a = Math.min((int) Math.floor(key), frameCount - 1);
            b = Math.min(a + 1, frameCount - 1);
            alpha = key - (float) Math.floor(key);
        }
        float[] out = pose.local;
        for (int t = 0; t < bones.length; t++) {
            int bone = bones[t];
            if (bone < 0) continue;
            int at = bone * CgSkeleton.REST;
            lerp(translations[t], 3, a, b, alpha, out, at);
            nlerp(rotations[t], a, b, alpha, out, at + 3);
            lerp(scales[t], 3, a, b, alpha, out, at + 7);
        }
    }

    /** Each keyed track's nine curves at {@code time}, its Euler angles composed {@code Rz·Ry·Rx} into a unit quaternion. */
    private void sampleKeyed(int[] bones, float[] out, float time) {
        for (int t = 0; t < bones.length; t++) {
            int bone = bones[t];
            if (bone < 0) continue;
            CgRichCurve[] c = keys[t];
            int at = bone * CgSkeleton.REST;
            out[at] = c[0].eval(time);
            out[at + 1] = c[1].eval(time);
            out[at + 2] = c[2].eval(time);
            double hx = c[3].eval(time) * 0.5, hy = c[4].eval(time) * 0.5, hz = c[5].eval(time) * 0.5;
            double sx = Math.sin(hx), cx = Math.cos(hx), sy = Math.sin(hy), cy = Math.cos(hy), sz = Math.sin(hz), cz = Math.cos(hz);
            out[at + 3] = (float) (cz * cy * sx - sz * sy * cx);
            out[at + 4] = (float) (cz * sy * cx + sz * cy * sx);
            out[at + 5] = (float) (sz * cy * cx - cz * sy * sx);
            out[at + 6] = (float) (cz * cy * cx + sz * sy * sx);
            out[at + 7] = c[6].eval(time);
            out[at + 8] = c[7].eval(time);
            out[at + 9] = c[8].eval(time);
        }
    }

    private void framed() {
        if (keys != null) throw new IllegalStateException(name + " is keyed: it has no frames, sample it by time");
    }

    /** A channel's value between keys {@code a} and {@code b}: a held channel's one key, else the lerp. */
    private static void lerp(float[] keys, int n, int a, int b, float alpha, float[] out, int at) {
        if (keys.length == n) {
            System.arraycopy(keys, 0, out, at, n);
            return;
        }
        if (alpha <= CgPose.ZERO_WEIGHT || a == b) {
            System.arraycopy(keys, a * n, out, at, n);
        } else if (alpha >= 1f - CgPose.ZERO_WEIGHT) {
            System.arraycopy(keys, b * n, out, at, n);
        } else {
            for (int c = 0; c < n; c++) out[at + c] = keys[a * n + c] + (keys[b * n + c] - keys[a * n + c]) * alpha;
        }
    }

    /** {@code FQuat::FastLerp} (Quat.h:1371-1377), then normalised. */
    private static void nlerp(float[] keys, int a, int b, float alpha, float[] out, int at) {
        if (keys.length == 4 || alpha <= CgPose.ZERO_WEIGHT || a == b) {
            System.arraycopy(keys, keys.length == 4 ? 0 : a * 4, out, at, 4);
            return;
        }
        if (alpha >= 1f - CgPose.ZERO_WEIGHT) {
            System.arraycopy(keys, b * 4, out, at, 4);
            return;
        }
        int pa = a * 4, pb = b * 4;
        float dot = keys[pa] * keys[pb] + keys[pa + 1] * keys[pb + 1] + keys[pa + 2] * keys[pb + 2] + keys[pa + 3] * keys[pb + 3];
        float bias = dot >= 0f ? 1f - alpha : alpha - 1f;
        for (int c = 0; c < 4; c++) out[at + c] = keys[pb + c] * alpha + keys[pa + c] * bias;
        CgPose.normalizeRotation(out, at);
    }

    /** Track to bone for {@code skeleton}, -1 where it lacks the bone; made once a skeleton. */
    private int[] bind(CgSkeleton skeleton) {
        Binding bound = binding;
        if (bound != null && bound.skeleton == skeleton) return bound.bones;
        int[] bones = new int[trackBones.length];
        for (int t = 0; t < bones.length; t++) bones[t] = skeleton.indexOfIgnoringCase(trackBones[t]);
        binding = new Binding(skeleton, bones);
        return bones;
    }

    /** The last skeleton bound and its tracks' bones: one skeleton per sequence is the common case. */
    private record Binding(CgSkeleton skeleton, int[] bones) {
    }

    private volatile Binding binding;

    /**
     * What to divide a rotation by to make it unit length: its length, or exactly 1 where it is unit already to float
     * precision. Dividing a normalised quaternion by its length again moves its last bit, so a loaded value would
     * otherwise not read back equal from its own cook.
     */
    static float unitLength(float x, float y, float z, float w) {
        float length = (float) Math.sqrt(x * x + y * y + z * z + w * w);
        return Math.abs(length - 1f) <= 2e-7f ? 1f : length;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CgAnimSequence s)) return false;
        return name.equals(s.name) && frameRate == s.frameRate && frameCount == s.frameCount
                && Arrays.equals(trackBones, s.trackBones) && Arrays.deepEquals(translations, s.translations)
                && Arrays.deepEquals(rotations, s.rotations) && Arrays.deepEquals(scales, s.scales)
                && Arrays.equals(curveNames, s.curveNames) && Arrays.deepEquals(curves, s.curves)
                && Arrays.equals(curveDrives, s.curveDrives) && Arrays.deepEquals(keys, s.keys)
                && keyedDuration == s.keyedDuration;
    }

    @Override
    public int hashCode() {
        return 31 * (31 * name.hashCode() + frameCount) + Arrays.hashCode(trackBones);
    }

    @Override
    public String toString() {
        if (keys != null) return "CgAnimSequence{" + name + ", keyed over " + keyedDuration + " s, " + trackBones.length + " tracks}";
        return "CgAnimSequence{" + name + ", " + frameCount + " keys at " + frameRate + "/s, " + trackBones.length
                + " tracks, " + curveNames.length + " curves}";
    }

    /** Builds a {@link CgAnimSequence}. Arrays handed in are copied. */
    public static final class Builder {

        private final String name;
        private final double frameRate;
        private final int frameCount;
        private final List<String> bones = new ArrayList<>(), curveNames = new ArrayList<>();
        private final List<float[]> translations = new ArrayList<>(), rotations = new ArrayList<>(), scales = new ArrayList<>(),
                curves = new ArrayList<>();
        private final List<Integer> drives = new ArrayList<>();

        private Builder(String name, double frameRate, int frameCount) {
            if (name == null) throw new IllegalArgumentException("a sequence has a name");
            if (!(frameRate > 0) || Double.isInfinite(frameRate)) throw new IllegalArgumentException(name + ": frame rate " + frameRate);
            if (frameCount < 1) throw new IllegalArgumentException(name + ": " + frameCount + " keys");
            this.name = name;
            this.frameRate = frameRate;
            this.frameCount = frameCount;
        }

        /**
         * The bone named {@code bone}'s channels, local to its parent: translations and scales 3 floats a key, rotations
         * 4 ({@code x, y, z, w}), each one key or {@code frameCount} keys. Rotations are normalised.
         */
        public Builder track(String bone, float[] translations, float[] rotations, float[] scales) {
            if (bones.contains(bone)) throw new IllegalArgumentException(name + " animates " + bone + " twice");
            float[] r = keys(bone + "'s rotations", rotations, 4).clone();
            for (int i = 0; i < r.length; i += 4) {
                float length = unitLength(r[i], r[i + 1], r[i + 2], r[i + 3]);
                if (!(length > 0f)) throw new IllegalArgumentException(name + ": " + bone + "'s rotation " + i / 4 + " is zero");
                for (int c = 0; c < 4; c++) r[i + c] /= length;
            }
            bones.add(bone);
            this.translations.add(trivial(keys(bone + "'s translations", translations, 3).clone(), 3, TRANSLATION_THRESHOLD));
            this.rotations.add(trivialRotation(r));
            this.scales.add(trivial(keys(bone + "'s scales", scales, 3).clone(), 3, SCALE_THRESHOLD));
            return this;
        }

        /** A float curve over the frames, one key or {@code frameCount}: a morph target's weight, by its name. */
        public Builder curve(String curve, float[] keys) {
            return curve(curve, keys, DRIVES_MORPH);
        }

        /**
         * A float curve over the frames driving {@code drives}: {@link #DRIVES_MORPH} a morph target's weight,
         * {@link #DRIVES_MATERIAL} the material scalar of its name.
         *
         * <pre>{@code
         * builder.curve("eye_l_v_scale", blinkKeys, CgAnimSequence.DRIVES_MATERIAL);   // the eye texture squashed shut
         * }</pre>
         */
        public Builder curve(String curve, float[] keys, int drives) {
            if (curveNames.contains(curve)) throw new IllegalArgumentException(name + " has curve " + curve + " twice");
            if (drives < DRIVES_MORPH || drives > DRIVES_NONE) throw new IllegalArgumentException(name + ": curve " + curve + " drives " + drives);
            curveNames.add(curve);
            curves.add(trivial(keys(curve, keys, 1).clone(), 1, 0f));
            this.drives.add(drives);
            return this;
        }

        public CgAnimSequence build() {
            int[] d = new int[drives.size()];
            for (int i = 0; i < d.length; i++) d[i] = drives.get(i);
            return new CgAnimSequence(name, frameRate, frameCount, bones.toArray(new String[0]),
                    translations.toArray(new float[0][]), rotations.toArray(new float[0][]), scales.toArray(new float[0][]),
                    curveNames.toArray(new String[0]), curves.toArray(new float[0][]), d);
        }

        private float[] keys(String what, float[] keys, int width) {
            if (keys == null || keys.length != width && keys.length != width * frameCount) {
                throw new IllegalArgumentException(name + ": " + what + " hold " + (keys == null ? "nothing" : keys.length + " floats")
                        + ", not " + width + " or " + width * frameCount);
            }
            for (float k : keys) if (!Float.isFinite(k)) throw new IllegalArgumentException(name + ": " + what + " hold " + k);
            return keys;
        }

        /** The first key alone where every component of every key lies within {@code threshold} of it. */
        private static float[] trivial(float[] keys, int width, float threshold) {
            for (int i = width; i < keys.length; i++) {
                if (Math.abs(keys[i] - keys[i % width]) > threshold) return keys;
            }
            return Arrays.copyOf(keys, width);
        }

        /** The first key alone where every key's {@code FQuat::Error} from it, {@code acos(|dot|) / π}, is within threshold. */
        private static float[] trivialRotation(float[] keys) {
            for (int i = 4; i < keys.length; i += 4) {
                double cos = Math.abs(keys[0] * keys[i] + keys[1] * keys[i + 1] + keys[2] * keys[i + 2] + keys[3] * keys[i + 3]);
                double error = cos < 0.9999999f ? Math.acos(Math.min(cos, 1.0)) / Math.PI : 0.0;
                if (error > ROTATION_THRESHOLD) return keys;
            }
            return Arrays.copyOf(keys, 4);
        }
    }

    /** Builds a keyed {@link CgAnimSequence}: curves are shared, not copied, being immutable once decoded. */
    public static final class KeyedBuilder {

        private final String name;
        private final float duration;
        private final List<String> bones = new ArrayList<>();
        private final List<CgRichCurve[]> tracks = new ArrayList<>();

        private KeyedBuilder(String name, float duration) {
            if (name == null) throw new IllegalArgumentException("a sequence has a name");
            if (!(duration >= 0f) || Float.isInfinite(duration)) throw new IllegalArgumentException(name + ": duration " + duration);
            this.name = name;
            this.duration = duration;
        }

        /**
         * The bone named {@code bone}'s nine channels, local to its parent: translation x, y, z; rotation x, y, z in
         * radians, composed {@code Rz·Ry·Rx}; scale x, y, z.
         */
        public KeyedBuilder track(String bone, CgRichCurve tx, CgRichCurve ty, CgRichCurve tz, CgRichCurve rx,
                                  CgRichCurve ry, CgRichCurve rz, CgRichCurve sx, CgRichCurve sy, CgRichCurve sz) {
            if (bones.contains(bone)) throw new IllegalArgumentException(name + " animates " + bone + " twice");
            CgRichCurve[] c = {tx, ty, tz, rx, ry, rz, sx, sy, sz};
            for (int i = 0; i < c.length; i++) {
                if (c[i] == null) throw new IllegalArgumentException(name + ": " + bone + "'s channel " + i + " is missing");
            }
            bones.add(bone);
            tracks.add(c);
            return this;
        }

        public CgAnimSequence build() {
            int n = bones.size();
            float[][] none = new float[n][];
            return new CgAnimSequence(name, 0, 0, bones.toArray(new String[0]), none, none, none, new String[0],
                    new float[0][], new int[0], tracks.toArray(new CgRichCurve[0][]), duration);
        }
    }
}
