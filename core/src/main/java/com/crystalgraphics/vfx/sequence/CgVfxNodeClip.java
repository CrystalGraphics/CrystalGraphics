package com.crystalgraphics.vfx.sequence;

import com.crystalgraphics.vfx.particle.gpu.CgVfxGpuEmitter;
import com.crystalgraphics.vfx.prefab.CgVfxPrefab;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * What a particle plays: a prefab whose nodes move, fade and change their material scalars over a clip of frames,
 * starting child emitters at clip times. CC2's particle-played clip, as a Java builder: a {@code SEQUENCE} emitter
 * ({@code CgVfxEmitter.Builder.sequence}) draws it on each particle at the particle's age, and its bursts and streams
 * become that emitter's events, spawning on the GPU at the node's place.
 *
 * <pre>{@code
 * static final CgVfxNodeClip SPINNER = CgVfxNodeClip.of(CORE, 60)                       // 60 frames, 30 a second
 *         .channel("blade", Channel.ROTATION_Y, Interp.LINEAR, 0, 0f, 60, 720f)          // frame, value pairs
 *         .channel("blade", Channel.OPACITY, Interp.LINEAR, 0, 1f, 45, 1f, 60, 0f)
 *         .material("blade", "_U0_LocX", Interp.CONSTANT, 0, 0f, 15, 0.25f, 30, 0.5f)   // a stepped UV window
 *         .burst("tip", SPARKS, 20, 8)                                                  // 8 sparks at the tip on frame 20
 *         .stream("tip", SMOKE, 10, 50, 30f)                                            // 30 a clip second, frames 10 to 50
 *         .build();
 * }</pre>
 *
 * <ul>
 *   <li>Rotations are Euler degrees in CC2's order, Rx·Ry·Rz intrinsic; a channel no track keys is the node's rest.</li>
 *   <li>Before its first key a channel holds that key's value, after its last the last's. {@code CONSTANT} keys step
 *       at each key's frame; {@code LINEAR} keys interpolate.</li>
 *   <li>A material scalar the clip neither keys nor sets at rest ({@link Builder#rest}) is the layer's own: its
 *       material's value, which the bake leaves alone ({@link #keys(int)}).</li>
 *   <li>A child is an emitter of its own: its shape, launch and modules decide its particles, the clip only where and
 *       when they are born. At most {@code CgVfxEvent.MAX_EVENTS} bursts and streams together.</li>
 * </ul>
 */
public final class CgVfxNodeClip {

    /** Clip frames a second: CC2's tick resolution. */
    public static final int FRAMES_PER_SECOND = 30;
    /** Material scalar slots a node has; CC2 names {@link #MATERIAL_SCALARS}. */
    public static final int MATERIAL_SLOTS = 16;
    /** CC2's material scalars, by slot. */
    public static final List<String> MATERIAL_SCALARS = Collections.unmodifiableList(Arrays.asList(
            "_U0_LocX", "_V0_LocY", "_U1_LocX", "_V1_LocY", "_U0_ScaleX", "_V0_ScaleY", "_U1_ScaleX", "_V1_ScaleY",
            "_BlendRate1", "_BlendRate2", "_Glare", "_Clip", "_OutlineID", "_Falloff"));
    /** What a layer falls back to where neither the clip nor its material says: a window of (0, 0, 1, 1), OutlineID 1. */
    private static final float[] MATERIAL_DEFAULTS = {0f, 0f, 0f, 0f, 1f, 1f, 1f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f};

    /** A node's channels: its transform and opacity; its material scalars are {@link #material(int)}. */
    public enum Channel {
        LOCATION_X, LOCATION_Y, LOCATION_Z, ROTATION_X, ROTATION_Y, ROTATION_Z, SCALE_X, SCALE_Y, SCALE_Z, OPACITY;

        /** Channel ids past the transform's: material slot {@code slot}'s. */
        public static int material(int slot) {
            return values().length + slot;
        }
    }

    public enum Interp { LINEAR, CONSTANT }

    /** Channel ids a node has: {@link Channel}'s, then {@link #MATERIAL_SLOTS} scalars. */
    static final int CHANNELS = Channel.values().length + MATERIAL_SLOTS;

    /** A child emitter the clip starts at a node: a burst at a frame, or a stream between two frames. */
    public static final class Spawn {
        private final int node;
        private final CgVfxGpuEmitter child;
        private final float from, until, perSecond;
        private final int count;

        Spawn(int node, CgVfxGpuEmitter child, float from, float until, float perSecond, int count) {
            this.node = node;
            this.child = child;
            this.from = from;
            this.until = until;
            this.perSecond = perSecond;
            this.count = count;
        }

        /** The prefab node it spawns at. */
        public int node() {
            return node;
        }

        public CgVfxGpuEmitter child() {
            return child;
        }

        /** Its clip frame: a burst's, or where a stream starts. */
        public float from() {
            return from;
        }

        /** Where a stream ends; a burst's own frame. */
        public float until() {
            return until;
        }

        /** A stream's children a clip second; 0 for a burst. */
        public float perSecond() {
            return perSecond;
        }

        /** A burst's children; 1 for a stream, each of its firings. */
        public int count() {
            return count;
        }

        public boolean isStream() {
            return perSecond > 0f;
        }
    }

    private final CgVfxPrefab prefab;
    private final int frames;
    /** By node, then channel id: its interpolation and keys, or null where nothing keys it. */
    private final Interp[][] interp;
    private final float[][][] keyFrames, keyValues;
    private final List<Spawn> spawns;

    private CgVfxNodeClip(Builder b) {
        prefab = b.prefab;
        frames = b.frames;
        interp = b.interp;
        keyFrames = b.keyFrames;
        keyValues = b.keyValues;
        spawns = Collections.unmodifiableList(new ArrayList<>(b.spawns));
    }

    /** A clip of {@code prefab} {@code frames} frames long, every channel at rest until keyed. */
    public static Builder of(CgVfxPrefab prefab, int frames) {
        return new Builder(prefab, frames);
    }

    public CgVfxPrefab prefab() {
        return prefab;
    }

    /** Its length in frames: it plays frames 0 to this, then holds the last. */
    public int frames() {
        return frames;
    }

    /** Its bursts and streams, in the order given: the order of the events they become. */
    public List<Spawn> spawns() {
        return spawns;
    }

    /** Channel id {@code channel} of {@code node} at {@code frame}, a fraction allowed; NaN where nothing keys it. */
    public float keyed(int node, int channel, float frame) {
        float[] f = keyFrames[node][channel];
        if (f == null) return Float.NaN;
        float[] v = keyValues[node][channel];
        if (frame <= f[0]) return v[0];
        int last = f.length - 1;
        if (frame >= f[last]) return v[last];
        int i = 0;
        while (frame >= f[i + 1]) i++;
        if (interp[node][channel] == Interp.CONSTANT) return v[i];
        float t = (frame - f[i]) / (f[i + 1] - f[i]);
        return v[i] + (v[i + 1] - v[i]) * t;
    }

    /** Whether something keys channel id {@code channel} of {@code node} with {@link Interp#CONSTANT} keys. */
    public boolean stepped(int node, int channel) {
        return keyFrames[node][channel] != null && interp[node][channel] == Interp.CONSTANT;
    }

    /** Material scalar {@code slot} of {@code node} at {@code frame}: its keys', else the fallback a layer would take. */
    public float material(int node, int slot, float frame) {
        float v = keyed(node, Channel.material(slot), frame);
        return v == v ? v : MATERIAL_DEFAULTS[slot];
    }

    /** The material slots the clip gives {@code node}, a bit each: the rest are its layer's own values. */
    public int keys(int node) {
        int mask = 0;
        for (int slot = 0; slot < MATERIAL_SLOTS; slot++) if (keyFrames[node][Channel.material(slot)] != null) mask |= 1 << slot;
        return mask;
    }

    /**
     * {@code node}'s local matrix at {@code frame} into {@code out}: its rest with what its channels key, location times
     * Rx·Ry·Rz times scale. Answers {@code out}.
     */
    public Matrix4f local(int node, float frame, Matrix4f out, float[] rest, Vector3f euler, Quaternionf turn) {
        prefab.rest(node, rest);
        float lx = or(node, Channel.LOCATION_X, frame, rest[0]), ly = or(node, Channel.LOCATION_Y, frame, rest[1]),
                lz = or(node, Channel.LOCATION_Z, frame, rest[2]);
        float sx = or(node, Channel.SCALE_X, frame, rest[7]), sy = or(node, Channel.SCALE_Y, frame, rest[8]),
                sz = or(node, Channel.SCALE_Z, frame, rest[9]);
        boolean turns = keyFrames[node][Channel.ROTATION_X.ordinal()] != null
                || keyFrames[node][Channel.ROTATION_Y.ordinal()] != null || keyFrames[node][Channel.ROTATION_Z.ordinal()] != null;
        out.translation(lx, ly, lz);
        if (turns) {
            turn.set(rest[3], rest[4], rest[5], rest[6]).getEulerAnglesXYZ(euler);
            float ax = degrees(node, Channel.ROTATION_X, frame, euler.x), ay = degrees(node, Channel.ROTATION_Y, frame, euler.y),
                    az = degrees(node, Channel.ROTATION_Z, frame, euler.z);
            out.rotateXYZ(ax, ay, az);
        } else {
            out.rotate(turn.set(rest[3], rest[4], rest[5], rest[6]));
        }
        return out.scale(sx, sy, sz);
    }

    /** {@code node}'s own opacity at {@code frame}, before its ancestors'. */
    public float opacity(int node, float frame) {
        return or(node, Channel.OPACITY, frame, prefab.opacity(node));
    }

    private float or(int node, Channel c, float frame, float rest) {
        float v = keyed(node, c.ordinal(), frame);
        return v == v ? v : rest;
    }

    /** A rotation channel in radians: its keys' degrees, else the rest's angle in radians. */
    private float degrees(int node, Channel c, float frame, float restRadians) {
        float v = keyed(node, c.ordinal(), frame);
        return v == v ? (float) Math.toRadians(v) : restRadians;
    }

    /** Builds a clip; channels and spawns in any order. */
    public static final class Builder {
        private final CgVfxPrefab prefab;
        private final int frames;
        private final Interp[][] interp;
        private final float[][][] keyFrames, keyValues;
        private final List<Spawn> spawns = new ArrayList<>();

        private Builder(CgVfxPrefab prefab, int frames) {
            this.prefab = Objects.requireNonNull(prefab, "prefab");
            if (frames < 1) throw new IllegalArgumentException("a clip of " + frames + " frames");
            this.frames = frames;
            int n = prefab.nodes();
            interp = new Interp[n][CHANNELS];
            keyFrames = new float[n][CHANNELS][];
            keyValues = new float[n][CHANNELS][];
        }

        /** {@code node}'s {@code channel} keyed by (frame, value) pairs, frames ascending. */
        public Builder channel(String node, Channel channel, Interp interp, float... keys) {
            return keys(node(node), channel.ordinal(), interp, keys, channel.name());
        }

        /** {@code node}'s material scalar {@code name}, one of {@link #MATERIAL_SCALARS}, keyed as {@link #channel}. */
        public Builder material(String node, String name, Interp interp, float... keys) {
            int slot = MATERIAL_SCALARS.indexOf(name);
            if (slot < 0) throw new IllegalArgumentException(name + " is no material scalar: " + MATERIAL_SCALARS);
            return keys(node(node), Channel.material(slot), interp, keys, name);
        }

        /** {@code node}'s material scalar {@code name} held at {@code value}: the node's rest {@code values:}, over its material's. */
        public Builder rest(String node, String name, float value) {
            return material(node, name, Interp.CONSTANT, 0f, value);
        }

        /** {@code count} children of {@code child} born at {@code node} on {@code frame}. */
        public Builder burst(String node, CgVfxGpuEmitter child, float frame, int count) {
            if (frame < 0f || frame > frames) throw new IllegalArgumentException("a burst on frame " + frame + " of " + frames);
            if (count < 1) throw new IllegalArgumentException("a burst of " + count);
            spawns.add(new Spawn(node(node), Objects.requireNonNull(child, "child"), frame, frame, 0f, count));
            return this;
        }

        /** Children of {@code child} born at {@code node}, {@code perSecond} a clip second from frame {@code from} to {@code until}. */
        public Builder stream(String node, CgVfxGpuEmitter child, float from, float until, float perSecond) {
            if (from < 0f || until <= from || until > frames) {
                throw new IllegalArgumentException("a stream from frame " + from + " to " + until + " of " + frames);
            }
            if (!(perSecond > 0f)) throw new IllegalArgumentException("a stream of " + perSecond + " a second");
            spawns.add(new Spawn(node(node), Objects.requireNonNull(child, "child"), from, until, perSecond, 1));
            return this;
        }

        public CgVfxNodeClip build() {
            return new CgVfxNodeClip(this);
        }

        private int node(String name) {
            int n = prefab.node(name);
            if (n < 0) throw new IllegalArgumentException(prefab.id() + " has no node " + name);
            return n;
        }

        private Builder keys(int node, int channel, Interp interp, float[] keys, String what) {
            Objects.requireNonNull(interp, "interp");
            if (keys.length < 2 || keys.length % 2 != 0) {
                throw new IllegalArgumentException(what + ": keys are (frame, value) pairs, at least one");
            }
            int n = keys.length / 2;
            float[] f = new float[n], v = new float[n];
            for (int i = 0; i < n; i++) {
                f[i] = keys[i * 2];
                v[i] = keys[i * 2 + 1];
                if (i > 0 && f[i] <= f[i - 1]) throw new IllegalArgumentException(what + ": frames must rise, " + f[i] + " after " + f[i - 1]);
            }
            this.interp[node][channel] = interp;
            keyFrames[node][channel] = f;
            keyValues[node][channel] = v;
            return this;
        }
    }
}
