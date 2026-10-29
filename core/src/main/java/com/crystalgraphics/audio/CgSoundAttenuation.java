package com.crystalgraphics.audio;

/**
 * How a placed sound fades with distance, and what else the distance does to it: Unreal's
 * {@code FSoundAttenuationSettings} for its sphere shape. Distances are in blocks; Unreal's defaults are converted
 * from centimetres.
 *
 * <pre>{@code
 * CgSoundAttenuation near = new CgSoundAttenuation().inner(2).falloff(30);            // full within 2, gone by 32
 * long id = audio.play(boom, CgSoundClass.SFX, 1f);
 * audio.activeSounds().at(id, x, y, z).attenuation(id, near);
 * }</pre>
 *
 * Muffled behind walls, duller far off, with a doppler shift:
 *
 * <pre>{@code
 * CgSoundAttenuation engine = new CgSoundAttenuation()
 *         .algorithm(CgSoundAttenuation.Algorithm.NATURAL_SOUND)
 *         .occlusion(true).occlusionLowPass(800).occlusionVolume(0.6f)
 *         .lowPass(true).lowPassRange(10, 40).lowPassHz(20000, 3000)
 *         .doppler(1f);
 * }</pre>
 *
 * <ul>
 *   <li>Shared by every sound it is given to: set it up before, never while they play.</li>
 *   <li>A sound with none is heard everywhere at full volume, unpanned, as an unplaced one is.</li>
 *   <li>Doppler is off unless asked ({@link #doppler(float)}), as Unreal's doppler node is.</li>
 * </ul>
 */
public final class CgSoundAttenuation {

    /** Unreal's {@code EAttenuationDistanceModel}, without the custom curve. */
    public enum Algorithm { LINEAR, LOGARITHMIC, INVERSE, LOG_REVERSE, NATURAL_SOUND }

    /** Unreal's defaults: linear, full within 4 blocks, gone 36 blocks beyond. */
    public static final CgSoundAttenuation DEFAULT = new CgSoundAttenuation();

    /** Unreal's floor in decibels, and a natural sound's loudness at its falloff distance by default. */
    private static final float MIN_DB = -60f;
    public static final float MAX_FILTER_HZ = 20000f;
    /** Unreal's speed of sound in air, 330 m/s, in blocks a second. */
    static final double SPEED_OF_SOUND = 330;

    private Algorithm algorithm = Algorithm.LINEAR;
    private double inner = 4, falloff = 36;
    private float dbAtMax = MIN_DB;
    private boolean spatialize = true;
    private boolean lowPass;
    private double lowPassMin = 30, lowPassMax = 60;
    private float lowPassHzAtMin = MAX_FILTER_HZ, lowPassHzAtMax = MAX_FILTER_HZ;
    private boolean occlusion;
    private float occlusionLowPass = MAX_FILTER_HZ, occlusionVolume = 1f;
    private float occlusionInterpolation = 0.1f, occlusionInterval = 0.1f;
    private boolean reverbSend = true;
    private float reverbWetMin = 0.3f, reverbWetMax = 0.95f;
    private double reverbDistanceMin = Double.NaN, reverbDistanceMax = Double.NaN;
    private float doppler;

    public CgSoundAttenuation algorithm(Algorithm a) {
        algorithm = a;
        return this;
    }

    /** The sphere's radius: full volume within it. */
    public CgSoundAttenuation inner(double blocks) {
        inner = blocks;
        return this;
    }

    /** How far beyond {@link #inner(double)} the curve runs. */
    public CgSoundAttenuation falloff(double blocks) {
        falloff = blocks;
        return this;
    }

    /** For {@link Algorithm#NATURAL_SOUND}: decibels at the falloff distance, at most 0. */
    public CgSoundAttenuation dbAtMax(float db) {
        dbAtMax = Math.min(0f, db);
        return this;
    }

    /** Panned to where it is; false plays it as if at the listener, still fading with distance. */
    public CgSoundAttenuation spatialize(boolean on) {
        spatialize = on;
        return this;
    }

    /** Duller with distance ({@code bAttenuateWithLPF}). */
    public CgSoundAttenuation lowPass(boolean on) {
        lowPass = on;
        return this;
    }

    public CgSoundAttenuation lowPassRange(double minBlocks, double maxBlocks) {
        lowPassMin = minBlocks;
        lowPassMax = maxBlocks;
        return this;
    }

    public CgSoundAttenuation lowPassHz(float atMin, float atMax) {
        lowPassHzAtMin = atMin;
        lowPassHzAtMax = atMax;
        return this;
    }

    /** Muffled while a block stands between it and the listener. */
    public CgSoundAttenuation occlusion(boolean on) {
        occlusion = on;
        return this;
    }

    public CgSoundAttenuation occlusionLowPass(float hz) {
        occlusionLowPass = hz;
        return this;
    }

    public CgSoundAttenuation occlusionVolume(float v) {
        occlusionVolume = v;
        return this;
    }

    /** Seconds to move into or out of occlusion. */
    public CgSoundAttenuation occlusionInterpolation(float seconds) {
        occlusionInterpolation = seconds;
        return this;
    }

    /** Seconds of play between two traces. */
    public CgSoundAttenuation occlusionInterval(float seconds) {
        occlusionInterval = seconds;
        return this;
    }

    public CgSoundAttenuation reverbSend(boolean on) {
        reverbSend = on;
        return this;
    }

    /** The send's level at the near and the far end of its distance range. */
    public CgSoundAttenuation reverbWet(float min, float max) {
        reverbWetMin = min;
        reverbWetMax = max;
        return this;
    }

    /** The send's distance range; by default {@link #inner(double)} to inner plus falloff. */
    public CgSoundAttenuation reverbDistance(double min, double max) {
        reverbDistanceMin = min;
        reverbDistanceMax = max;
        return this;
    }

    /** Doppler's strength: 0 none, 1 physical. */
    public CgSoundAttenuation doppler(float intensity) {
        doppler = intensity;
        return this;
    }

    public boolean spatialize() {
        return spatialize;
    }

    public boolean occlusion() {
        return occlusion;
    }

    public float occlusionLowPass() {
        return occlusionLowPass;
    }

    public float occlusionVolume() {
        return occlusionVolume;
    }

    public float occlusionInterpolation() {
        return occlusionInterpolation;
    }

    public float occlusionInterval() {
        return occlusionInterval;
    }

    public float doppler() {
        return doppler;
    }

    /** Its volume {@code distance} blocks from the listener, in [0, 1] ({@code Evaluate} on a sphere). */
    public float gain(double distance) {
        double d = Math.max(distance - inner, 0), f = Math.max(falloff, 0.01);
        double r;
        switch (algorithm) {
            case LOGARITHMIC:
                r = 0.5 * -Math.log(Math.max(d, 1e-8) / f);
                break;
            case INVERSE:
                r = 0.02 / (Math.max(d, 1e-8) / f);
                break;
            case LOG_REVERSE:
                r = d > f ? 0 : 1 + 0.5 * Math.log(Math.max(1 - d / f, 1e-8));
                break;
            case NATURAL_SOUND:
                r = Math.pow(10, d / f * dbAtMax / 20);
                break;
            case LINEAR:
            default:
                r = 1 - d / f;
        }
        return (float) Math.max(0, Math.min(1, r));
    }

    /** Past this it is out of range: virtual if it loops, ended if not. */
    public double maxDistance() {
        // GetMaxFalloffDistance: a natural sound runs on past its falloff until it reaches -60 dB.
        if (algorithm == Algorithm.NATURAL_SOUND) return inner + falloff * MIN_DB / Math.min(dbAtMax, -1e-4f);
        return inner + falloff;
    }

    /** Its distance low-pass in hertz ({@code GetAttenuationFrequency}, linear); 20 kHz where off. */
    public float lowPassHz(double distance) {
        if (!lowPass) return MAX_FILTER_HZ;
        if (lowPassHzAtMin == lowPassHzAtMax) return lowPassHzAtMin;
        if (lowPassMin == lowPassMax) return distance > lowPassMin ? lowPassHzAtMax : lowPassHzAtMin;
        double a = Math.max(0, Math.min(1, (distance - lowPassMin) / (lowPassMax - lowPassMin)));
        return (float) (lowPassHzAtMin + (lowPassHzAtMax - lowPassHzAtMin) * a);
    }

    /** Its reverb send at {@code distance}, lerped over the distance range; 0 where off. */
    public float reverbSend(double distance) {
        if (!reverbSend) return 0f;
        double lo = Double.isNaN(reverbDistanceMin) ? inner : reverbDistanceMin;
        double hi = Double.isNaN(reverbDistanceMax) ? inner + falloff : reverbDistanceMax;
        double a = Math.max(0, Math.min(1, (distance - lo) / Math.max(hi - lo, 0.01)));
        return (float) Math.max(0, Math.min(1, reverbWetMin + (reverbWetMax - reverbWetMin) * a));
    }
}
