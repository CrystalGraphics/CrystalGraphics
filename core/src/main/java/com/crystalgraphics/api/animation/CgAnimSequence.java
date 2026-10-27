package com.crystalgraphics.api.animation;

import org.joml.Quaternionf;
import org.joml.Vector3f;

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
    private final String[] curveNames;
    final float[][] curves;
    private final Map<String, Integer> trackIndex = new HashMap<>(), curveIndex = new HashMap<>();

    CgAnimSequence(String name, double frameRate, int frameCount, String[] trackBones, float[][] translations,
                   float[][] rotations, float[][] scales, String[] curveNames, float[][] curves) {
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

    /** Seconds from the first key to the last. */
    public float duration() {
        return (float) ((frameCount - 1) / frameRate);
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
        float[] k = translations[track];
        int at = k.length == 3 ? 0 : frame * 3;
        return out.set(k[at], k[at + 1], k[at + 2]);
    }

    /** Frame {@code frame}'s rotation of the track's bone, local to its parent, unit length. */
    public Quaternionf rotation(int track, int frame, Quaternionf out) {
        float[] k = rotations[track];
        int at = k.length == 4 ? 0 : frame * 4;
        return out.set(k[at], k[at + 1], k[at + 2], k[at + 3]);
    }

    /** Frame {@code frame}'s scale of the track's bone. */
    public Vector3f scale(int track, int frame, Vector3f out) {
        float[] k = scales[track];
        int at = k.length == 3 ? 0 : frame * 3;
        return out.set(k[at], k[at + 1], k[at + 2]);
    }

    /** Whether the track's translation, rotation and scale each hold one key for the whole sequence. */
    public boolean held(int track) {
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
                && Arrays.equals(curveNames, s.curveNames) && Arrays.deepEquals(curves, s.curves);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * name.hashCode() + frameCount) + Arrays.hashCode(trackBones);
    }

    @Override
    public String toString() {
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
            if (curveNames.contains(curve)) throw new IllegalArgumentException(name + " has curve " + curve + " twice");
            curveNames.add(curve);
            curves.add(trivial(keys(curve, keys, 1).clone(), 1, 0f));
            return this;
        }

        public CgAnimSequence build() {
            return new CgAnimSequence(name, frameRate, frameCount, bones.toArray(new String[0]),
                    translations.toArray(new float[0][]), rotations.toArray(new float[0][]), scales.toArray(new float[0][]),
                    curveNames.toArray(new String[0]), curves.toArray(new float[0][]));
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
}
