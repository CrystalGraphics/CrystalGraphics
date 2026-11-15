package com.crystalgraphics.render.post;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialInstance;
import com.crystalgraphics.material.CgMaterialProxy;
import com.crystalgraphics.render.post.bloom.CgBloom;
import com.crystalgraphics.render.post.composite.CgPostComposite;
import com.crystalgraphics.render.post.debug.CgPostDebug;
import com.crystalgraphics.render.post.distortion.CgPostDistortion;
import com.crystalgraphics.render.post.fog.CgHeightFogPass;
import com.crystalgraphics.render.post.look.CgPostLooks;
import com.crystalgraphics.render.view.CgView;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * A view's post chain (Unreal's post-processing passes): effects at {@link CgPostPoint}s over the picture, the engine's
 * bloom, tonemapper and looks between them, its last step writing the host's target. The client's views share
 * {@link #client()}; a view of a scene of its own has its own ({@link CgView#post()}), so a lab's glow leaves the game's
 * alone.
 *
 * <pre>{@code
 * CgPost post = CgPost.client();
 *
 * // A material over the screen: the common case, no code
 * CgPostMaterial thermal = post.material("mymod:shaders/post/thermal.shader");   // at the Location its file names
 * thermal.weight(0.8f);
 * thermal.remove();
 *
 * // An effect of your own: given its input, it answers its output
 * CgPost.Registration outline = post.add(CgPostPoint.HDR, (ctx, input) -> {
 *     CgScreenTexture out = ctx.texture("mymod.outline", input);
 *     return ctx.screen(outlineMaterial).reads(ctx.scene().customDepth()).into(out);
 * });
 * outline.close();
 *
 * post.bloom().intensity(1.5f);
 * post.crossfade(0.5f);                       // this frame is held; the cut comes next frame
 * }</pre>
 *
 * <ul>
 *   <li>A look for a moment is a post volume in the scene ({@code scene.postVolume(settings)}), never a write to the
 *       chain's settings.</li>
 *   <li>Any thread may add or remove an effect: it takes effect at the next firing. Effects run on the render thread.</li>
 * </ul>
 */
public final class CgPost {

    /** Every chain made, held weakly: what the renderer releases at context teardown. */
    private static final Set<CgPost> ALL = Collections.newSetFromMap(new WeakHashMap<>());
    private static final CgPost CLIENT = new CgPost();

    // The chain's own steps, which CgPostRenderer runs in order
    final CgPostDistortion distortion = new CgPostDistortion();
    final CgPostContext context = new CgPostContext(distortion);
    final CgPostComposite composite = new CgPostComposite();
    final CgBloom bloom = new CgBloom();
    final CgPostLooks looks = new CgPostLooks();
    final CgPostCrossfade crossfade = new CgPostCrossfade();
    final CgTranslucencyComposition translucency = new CgTranslucencyComposition();
    /** {@code -Dcrystalgraphics.post.debug}'s view; null when unset. */
    @Nullable
    final CgPostDebug debug = CgPostDebug.fromProperty();

    /** Every effect by point, then order, then when it was added: replaced whole, so a firing reads a snapshot. */
    volatile Entry[] entries;
    private long added;
    /** Whether this firing's {@code SURFACES} effects ran at the renderer's point. */
    boolean ranSurfaces;

    /** One effect at its place. */
    record Entry(CgPostPoint point, int order, long added, CgPostEffect effect) {
    }

    /** An effect added to a chain; closing it removes it. */
    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * Engine side: a chain for a view of a scene of its own, which {@link CgView#post()} makes. Its height fog is an
     * effect at {@code SURFACES}, order 0, ahead of what is added there at 0.
     */
    public CgPost() {
        entries = new Entry[]{new Entry(CgPostPoint.SURFACES, 0, 0, new CgHeightFogPass())};
        added = 1;
        synchronized (ALL) {
            ALL.add(this);
        }
    }

    /** The chain the client's views share: the game's. */
    public static CgPost client() {
        return CLIENT;
    }

    /** Adds {@code effect} at {@code point}, after those there; closing the registration removes it. */
    public Registration add(CgPostPoint point, CgPostEffect effect) {
        return add(point, 0, effect);
    }

    /** Adds {@code effect} at {@code point}, {@code order} among the effects there, ascending, ties in the order added. */
    public Registration add(CgPostPoint point, int order, CgPostEffect effect) {
        Objects.requireNonNull(point, "point");
        Objects.requireNonNull(effect, "effect");
        synchronized (this) {
            Entry[] next = Arrays.copyOf(entries, entries.length + 1);
            next[next.length - 1] = new Entry(point, order, added++, effect);
            Arrays.sort(next, (a, b) -> a.point != b.point ? a.point.compareTo(b.point)
                    : a.order != b.order ? Integer.compare(a.order, b.order) : Long.compare(a.added, b.added));
            entries = next;
        }
        return () -> remove(effect);
    }

    private synchronized void remove(CgPostEffect effect) {
        Entry[] current = entries;
        for (int i = 0; i < current.length; i++) {
            if (current[i].effect != effect) continue;
            Entry[] next = new Entry[current.length - 1];
            System.arraycopy(current, 0, next, 0, i);
            System.arraycopy(current, i + 1, next, i, current.length - i - 1);
            entries = next;
            return;
        }
    }

    /**
     * A {@code #domain post} file over the picture at the {@code Location} it names.
     *
     * @throws IllegalArgumentException where the file names no {@code Location}, or does not parse
     */
    public CgPostMaterial material(String shader) {
        CgMaterialInstance material = CgMaterial.load(shader).instance();
        String location = CgMaterialProxy.of(material).postLocation();
        if (location == null) {
            throw new IllegalArgumentException(shader + " names no Location (Material { Location SCENE }): pass a"
                    + " CgPostPoint to material(shader, point)");
        }
        return material(material, CgPostPoint.valueOf(location), 0);
    }

    /** A {@code #domain post} file over the picture at {@code point}, whatever its {@code Location}. */
    public CgPostMaterial material(String shader, CgPostPoint point) {
        return material(CgMaterial.load(shader).instance(), point, 0);
    }

    /** {@code material} over the picture at {@code point}, {@code order} among the effects there. */
    public CgPostMaterial material(CgMaterialInstance material, CgPostPoint point, int order) {
        return CgPostMaterial.added(this, material, point, order);
    }

    /** The chain's bloom, and its settings. */
    public CgBloom bloom() {
        return bloom;
    }

    /**
     * A cut's crossfade: the next firing's final picture is held, and the frames after it draw it over theirs, fading
     * out linearly over {@code seconds} of the frame clock. Call it on the old shot's last frame. Any thread.
     */
    public void crossfade(float seconds) {
        crossfade.crossfade(seconds);
    }

    /** Engine side: every chain forgets what it made on the GPU, at context teardown. */
    static void releaseAll() {
        synchronized (ALL) {
            for (CgPost post : ALL) post.release();
        }
    }

    private void release() {
        composite.release();
        distortion.release();
        bloom.release();
        crossfade.release();
        if (debug != null) debug.release();
    }
}
