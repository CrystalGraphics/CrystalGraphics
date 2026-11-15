package com.crystalgraphics.animation.retarget;

import com.crystalgraphics.api.animation.CgAnimPose;
import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.api.animation.CgSkeleton;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A source clip baked into a clip of the target's own bones, once: what plays then costs what any clip costs, and
 * nothing is retargeted per frame. Bakes are cached by the clip, the retargeter and the bake's own version.
 *
 * <pre>{@code
 * CgRetargetProcessor p = new CgRetargetProcessor(retargeter, gokuSkeleton, playerSkeleton);
 * CgAnimSequence punch = CgRetargetBake.cached(p, gokuPunch);   // baked the first time, shared after
 * playerInstance.play(punch, false);
 * }</pre>
 *
 * <ul>
 *   <li>Bake at load, on a worker ({@link CgClipResolver#bakeAll}): a bake runs the processor once a frame of the clip.</li>
 *   <li>A bone the retarget leaves still gets one key, not one a frame; virtual bones are left to the pose.</li>
 *   <li>The clip keeps its name, curves and settings (markers, notifies, root motion).</li>
 * </ul>
 */
public final class CgRetargetBake {

    /** Raised whenever what a bake makes changes, so a cache never serves an old one. */
    public static final int VERSION = 1;
    /** The rate a keyed clip (GeckoLib's) is baked at. */
    static final double KEYED_RATE = 30.0;

    private record Key(String clip, int clipHash, long retargeter, CgSkeleton source, CgSkeleton target, int version) {
    }

    private static final Map<Key, CgAnimSequence> CACHE = new ConcurrentHashMap<>();

    private CgRetargetBake() {
    }

    private static Key key(CgRetargetProcessor processor, CgAnimSequence clip) {
        return new Key(clip.name(), clip.hashCode(), processor.retargeter().contentHash(), processor.sourceSkeleton(),
                processor.targetSkeleton(), VERSION);
    }

    /** Whether {@code clip} through {@code processor} is baked already. */
    public static boolean isCached(CgRetargetProcessor processor, CgAnimSequence clip) {
        return CACHE.containsKey(key(processor, clip));
    }

    /** {@code clip} baked through {@code processor}, from the cache when it has been before. Any thread. */
    public static CgAnimSequence cached(CgRetargetProcessor processor, CgAnimSequence clip) {
        Key key = key(processor, clip);
        CgAnimSequence hit = CACHE.get(key);
        if (hit != null) return hit;
        // a processor is one thread's at a time; a bake on another thread takes a processor of its own
        CgAnimSequence baked;
        synchronized (processor) {
            baked = bake(processor, clip);
        }
        CgAnimSequence raced = CACHE.putIfAbsent(key, baked);
        return raced != null ? raced : baked;
    }

    /** {@code clip} baked through {@code processor}, uncached. The processor is busy until it returns. */
    public static CgAnimSequence bake(CgRetargetProcessor processor, CgAnimSequence clip) {
        CgSkeleton source = processor.sourceSkeleton(), target = processor.targetSkeleton();
        boolean keyed = clip.isKeyed();
        double rate = keyed ? KEYED_RATE : clip.frameRate();
        int frames = keyed ? Math.max(1, (int) Math.round(clip.duration() * rate) + 1) : clip.frameCount();
        int bones = target.boneCount();
        float[][] t = new float[bones][frames * 3], r = new float[bones][frames * 4], s = new float[bones][frames * 3];
        float[][] curves = new float[clip.curveCount()][frames];
        CgAnimPose in = new CgAnimPose(source), out = new CgAnimPose(target);
        Vector3f v = new Vector3f();
        Quaternionf q = new Quaternionf();
        for (int f = 0; f < frames; f++) {
            float time = (float) Math.min(clip.duration(), f / rate);
            in.rest();
            clip.sample(in, time);
            processor.run(in, out);
            for (int b = 0; b < bones; b++) {
                put(out.translation(b, v), t[b], f * 3);
                out.rotation(b, q);
                r[b][f * 4] = q.x;
                r[b][f * 4 + 1] = q.y;
                r[b][f * 4 + 2] = q.z;
                r[b][f * 4 + 3] = q.w;
                put(out.scale(b, v), s[b], f * 3);
            }
            for (int c = 0; c < curves.length; c++) curves[c][f] = clip.curveAt(c, time);
        }
        CgAnimSequence.Builder b = CgAnimSequence.builder(clip.name(), rate, frames);
        for (int bone = 0; bone < bones; bone++) {
            if (target.virtualTarget(bone) >= 0) continue;
            b.track(target.name(bone), t[bone], r[bone], s[bone]);
        }
        for (int c = 0; c < curves.length; c++) b.curve(clip.curveName(c), curves[c], clip.curveDrives(c));
        return b.build().with(clip.settings());
    }

    private static void put(Vector3f v, float[] into, int at) {
        into[at] = v.x;
        into[at + 1] = v.y;
        into[at + 2] = v.z;
    }

    /** Drops every cached bake: after a retargeter or a clip is reloaded. */
    public static void clear() {
        CACHE.clear();
    }
}
