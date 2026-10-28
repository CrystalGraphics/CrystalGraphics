package com.crystalgraphics.cue;

import javax.annotation.Nullable;

import com.crystalgraphics.net.object.CgAnchor;
import com.crystalgraphics.world.CgAttachment;

/**
 * What a cue is played with: where, toward what, by whom, on whom, how hard. Mirrors GAS's
 * {@code FGameplayCueParameters}.
 *
 * <pre>{@code
 * CgCueParameters hit = new CgCueParameters().at(x, y, z).normal(0, 1, 0).instigator(caster).target(victim).magnitude(12);
 * cues.execute(MYMOD_CUE_HIT, hit);              // copied into the queue: reuse hit at once
 * }</pre>
 *
 * <ul>
 *   <li>The manager copies it when queued, so one instance can be refilled for every call.</li>
 *   <li>{@link #seed} rolls each entry's {@code chance}: the same seed plays the same entries on every viewer.</li>
 *   <li>{@link #surface} is the physical surface hit, matched against an entry's {@code surfaces}; null for none.</li>
 * </ul>
 */
public final class CgCueParameters {
    private double x, y, z;
    private float nx, ny = 1, nz;
    @Nullable
    private CgAttachment instigator, target;
    private float magnitude, normalizedMagnitude;
    private long seed;
    @Nullable
    private String surface;
    @Nullable
    private CgAnchor anchor;

    public CgCueParameters at(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
        return this;
    }

    /** The surface's normal at {@link #x}; up by default. */
    public CgCueParameters normal(float nx, float ny, float nz) {
        this.nx = nx;
        this.ny = ny;
        this.nz = nz;
        return this;
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

    /** Becomes a copy of {@code other}. */
    public CgCueParameters set(CgCueParameters other) {
        x = other.x;
        y = other.y;
        z = other.z;
        nx = other.nx;
        ny = other.ny;
        nz = other.nz;
        instigator = other.instigator;
        target = other.target;
        magnitude = other.magnitude;
        normalizedMagnitude = other.normalizedMagnitude;
        seed = other.seed;
        surface = other.surface;
        anchor = other.anchor;
        return this;
    }

    /** Back to a fresh instance's values, dropping its references. */
    public CgCueParameters reset() {
        x = y = z = 0;
        nx = nz = 0;
        ny = 1;
        instigator = target = null;
        magnitude = normalizedMagnitude = 0;
        seed = 0;
        surface = null;
        anchor = null;
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
