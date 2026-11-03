package com.crystalgraphics.cue;

import javax.annotation.Nullable;

import com.crystalgraphics.net.object.CgAnchor;
import com.crystalgraphics.vfx.look.CgVfxParam;
import com.crystalgraphics.world.CgAttachment;

/**
 * What a cue is played with: where, toward what, by whom, on whom, how hard. Mirrors GAS's
 * {@code FGameplayCueParameters}.
 *
 * <pre>{@code
 * CgCueParameters hit = new CgCueParameters().at(x, y, z).normal(dx, 0, dz).instigator(caster).target(victim).magnitude(12);
 * cues.execute(MYMOD_CUE_HIT, hit);              // copied into the queue: reuse hit at once
 * }</pre>
 *
 * <p>Per-hit values reach the systems the cue plays as its {@code writes} names, which they bind as {@code user.<name>}:
 *
 * <pre>{@code
 * # cues/hit_spark.yaml:   writes: { size: the spark's size in blocks, tint: the attacker's ki colour }
 * hit.write("size", 1.5f).write("tint", 0xFF66CCFF, 2f);
 * cues.execute(MYMOD_CUE_HIT, hit);              // every viewer's spark plays at that size and colour
 * }</pre>
 *
 * <ul>
 *   <li>A name the cue does not write is ignored, with a warning; a system the cue plays that binds no write keeps its
 *       own default.</li>
 *   <li>The manager copies it when queued, so one instance can be refilled for every call.</li>
 *   <li>{@link #seed} rolls each entry's {@code chance}: the same seed plays the same entries on every viewer.</li>
 *   <li>{@link #surface} is the physical surface hit, matched against an entry's {@code surfaces}; null for none.</li>
 *   <li>A play stands at {@link #at} facing {@link #normal} where {@code at} is not the origin, else at its target's
 *       socket: Unreal's {@code FindSpawnTransform}. A zero normal faces the world's axes.</li>
 * </ul>
 *
 * <p>Sized by what it strikes, a continuous scale over every system and sequence it plays:
 *
 * <pre>{@code
 * hit.scale(Math.max(0.75f, Math.min(4f, struckHeight / 1.8f)));   // the same spark, as big as the body it hits
 * }</pre>
 */
public final class CgCueParameters {
    private double x, y, z;
    private float nx, ny, nz, scale = 1;
    @Nullable
    private CgAttachment instigator, target;
    private float magnitude, normalizedMagnitude;
    private long seed;
    @Nullable
    private String surface;
    @Nullable
    private CgAnchor anchor;
    private static final int WRITES = CgCueNet.WRITES;
    private final String[] writeNames = new String[WRITES];
    private final float[] writeValues = new float[WRITES * 4];
    private int writes;

    public CgCueParameters at(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
        return this;
    }

    /**
     * The way its plays face: each turns its x axis along it, yaw and pitch, no roll, as Unreal's
     * {@code Normal.Rotation()}. Zero by default, which turns nothing. Read only where {@link #at} is not the origin.
     */
    public CgCueParameters normal(float nx, float ny, float nz) {
        this.nx = nx;
        this.ny = ny;
        this.nz = nz;
        return this;
    }

    /**
     * What every system and sequence it plays is scaled by, times its placement's own scale: Unreal's spawn transform
     * scale. 1 by default.
     */
    public CgCueParameters scale(float scale) {
        this.scale = scale;
        return this;
    }

    /** What its plays are scaled by ({@link #scale(float)}): 1 unless set. */
    public float scale() {
        return scale;
    }

    /** Who caused it: whose local control a {@code source: instigator} spawn condition tests. */
    public CgCueParameters instigator(@Nullable CgAttachment instigator) {
        this.instigator = instigator;
        return this;
    }

    /** What it plays on: what an effect attaches to and places by, and a {@code source: target} condition tests. */
    public CgCueParameters target(@Nullable CgAttachment target) {
        this.target = target;
        return this;
    }

    /** {@code RawMagnitude}. */
    public CgCueParameters magnitude(float magnitude) {
        this.magnitude = magnitude;
        return this;
    }

    /** {@code NormalizedMagnitude}: 0 to 1. */
    public CgCueParameters normalizedMagnitude(float normalizedMagnitude) {
        this.normalizedMagnitude = normalizedMagnitude;
        return this;
    }

    public CgCueParameters seed(long seed) {
        this.seed = seed;
        return this;
    }

    public CgCueParameters surface(@Nullable String surface) {
        this.surface = surface;
        return this;
    }

    /**
     * Where a server's play lives on the network, which decides who sees it: {@code CgAnchor.at(level, x, y, z)},
     * or {@code CgAnchor.on(entity)} to follow one. A server's play without one is not sent.
     */
    public CgCueParameters anchor(@Nullable CgAnchor anchor) {
        this.anchor = anchor;
        return this;
    }

    @Nullable
    public CgAnchor anchor() {
        return anchor;
    }

    /**
     * This play's value of the cue's {@code writes} name {@code name}: a float, set on every system the cue plays that
     * binds it. A burst's per-hit values go here; a looping play starts with them.
     */
    public CgCueParameters write(String name, float value) {
        return write(name, value, 0f, 0f, 0f);
    }

    /** A vec3 or position {@code writes} value. */
    public CgCueParameters write(String name, float x, float y, float z) {
        return write(name, x, y, z, 0f);
    }

    /** A colour {@code writes} value, {@code 0xAARRGGBB}, its rgb times {@code intensity}: past white where above 1. */
    public CgCueParameters write(String name, int argb, float intensity) {
        return write(name, CgVfxParam.Color.red(argb) * intensity, CgVfxParam.Color.green(argb) * intensity,
                CgVfxParam.Color.blue(argb) * intensity, CgVfxParam.Color.alpha(argb));
    }

    /**
     * A {@code writes} value as its four floats, replacing one written before under {@code name}.
     *
     * @throws IllegalStateException past {@link CgCueNet#WRITES} names, the most a cue declares
     */
    public CgCueParameters write(String name, float x, float y, float z, float w) {
        int i = writeIndex(name);
        if (i < 0) {
            if (writes == WRITES) throw new IllegalStateException("a play writes at most " + WRITES + " names: " + name);
            i = writes++;
            writeNames[i] = name;
        }
        writeValues[i * 4] = x;
        writeValues[i * 4 + 1] = y;
        writeValues[i * 4 + 2] = z;
        writeValues[i * 4 + 3] = w;
        return this;
    }

    /** How many {@code writes} values it carries. */
    public int writes() {
        return writes;
    }

    /** The name of its {@code i}th {@code writes} value. */
    public String writeName(int i) {
        return writeNames[i];
    }

    /** Float {@code k} of its {@code i}th {@code writes} value. */
    public float writeValue(int i, int k) {
        return writeValues[i * 4 + k];
    }

    /** The place of its value for {@code name}; -1 for none. */
    public int writeIndex(String name) {
        for (int i = 0; i < writes; i++) if (writeNames[i].equals(name)) return i;
        return -1;
    }

    /** Becomes a copy of {@code other}. */
    public CgCueParameters set(CgCueParameters other) {
        x = other.x;
        y = other.y;
        z = other.z;
        nx = other.nx;
        ny = other.ny;
        nz = other.nz;
        scale = other.scale;
        instigator = other.instigator;
        target = other.target;
        magnitude = other.magnitude;
        normalizedMagnitude = other.normalizedMagnitude;
        seed = other.seed;
        surface = other.surface;
        anchor = other.anchor;
        System.arraycopy(other.writeNames, 0, writeNames, 0, other.writes);
        System.arraycopy(other.writeValues, 0, writeValues, 0, other.writes * 4);
        for (int i = other.writes; i < writes; i++) writeNames[i] = null;
        writes = other.writes;
        return this;
    }

    /** Back to a fresh instance's values, dropping its references. */
    public CgCueParameters reset() {
        x = y = z = 0;
        nx = ny = nz = 0;
        scale = 1;
        instigator = target = null;
        magnitude = normalizedMagnitude = 0;
        seed = 0;
        surface = null;
        anchor = null;
        for (int i = 0; i < writes; i++) writeNames[i] = null;
        writes = 0;
        return this;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    public float nx() {
        return nx;
    }

    public float ny() {
        return ny;
    }

    public float nz() {
        return nz;
    }

    @Nullable
    public CgAttachment instigator() {
        return instigator;
    }

    @Nullable
    public CgAttachment target() {
        return target;
    }

    public float magnitude() {
        return magnitude;
    }

    public float normalizedMagnitude() {
        return normalizedMagnitude;
    }

    public long seed() {
        return seed;
    }

    @Nullable
    public String surface() {
        return surface;
    }
}
