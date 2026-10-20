package com.crystalgraphics.asset.load;

import java.util.Collections;

import javax.annotation.Nullable;

import com.crystalgraphics.object.CgType;

/**
 * Names an asset by kind and id and holds its published definition. Declared at init on both sides, loading nothing;
 * Unreal's {@code TSoftObjectPtr} (SoftObjectPtr.h:173), {@link #get()} its {@code LoadSynchronous} (:547).
 *
 * <pre>{@code
 * static final CgAssetRef<CgAssetData> NOVA = CgVfxAssets.ref(CgParticleKinds.SYSTEM, "novamod:nova_cannon");
 *
 * NOVA.request(CgLoadPriority.MOVE);          // at join: loads it to READY over the next frames
 * if (NOVA.ready()) play(NOVA.get());         // a play: never loads, never uploads
 * }</pre>
 *
 * <ul>
 *   <li>{@link #get()} on an asset not yet compiled loads it synchronously, counted ({@code asset.sync-loads}) and
 *       logged once per id. Inside a stage firing it refuses and answers null: the play draws what is ready.</li>
 *   <li>The definition changes only by a swap at the frame boundary, which bumps {@link #generation()}; a playing
 *       instance follows it through {@link CgAssetFollower}.</li>
 *   <li>The same kind and id always answer the same ref.</li>
 * </ul>
 *
 * @param <D> the definition its kind's compiler builds
 */
public final class CgAssetRef<D> {

    final CgVfxAssets assets;
    private final CgType kind;
    private final String id;
    @Nullable volatile Object definition;
    volatile int generation;
    /** The generation of the latest structural swap. */
    volatile int structural;
    volatile boolean ready;
    @Nullable CgAssetLoad load;
    @Nullable CgLoadPriority priority;
    boolean loggedSync, loggedRefused;
    /** Its prepare's upload bytes, in all and gone up; both 0 until it compiles. */
    volatile long totalBytes, uploadedBytes;

    CgAssetRef(CgVfxAssets assets, CgType kind, String id) {
        this.assets = assets;
        this.kind = kind;
        this.id = id;
    }

    /**
     * The definition, loaded synchronously if it has not compiled: allowed at the frame boundary and outside frames,
     * counted. Null if it failed, or if asked inside a stage firing before it compiled.
     */
    @Nullable
    @SuppressWarnings("unchecked")
    public D get() {
        Object d = definition;
        return d != null ? (D) d : (D) assets.loadNow(this);
    }

    /** The published definition, or null; never loads. */
    @Nullable
    @SuppressWarnings("unchecked")
    public D loaded() {
        return (D) definition;
    }

    /** Whether it is {@link CgAssetState#READY}: free. */
    public boolean ready() {
        return ready;
    }

    /** Starts loading it to READY at {@code priority}, or raises a load already running. Its loop's thread. */
    public CgLoadHandle request(CgLoadPriority priority) {
        return assets.load(Collections.<CgAssetRef<?>>singletonList(this), priority);
    }

    /** Bumped at every swap of its definition; 0 before the first. */
    public int generation() {
        return generation;
    }

    /** How far it has come; {@link CgAssetState#REGISTERED} before it was requested. */
    public CgAssetState state() {
        if (ready) return CgAssetState.READY;
        CgAssetLoad l = load;
        return l == null ? CgAssetState.REGISTERED : l.state();
    }

    /** Why it failed, or null. */
    @Nullable
    public String failure() {
        CgAssetLoad l = load;
        return l == null ? null : l.failure();
    }

    long weight() {
        return totalBytes;
    }

    long doneBytes() {
        return uploadedBytes;
    }

    public CgType kind() {
        return kind;
    }

    public String id() {
        return id;
    }

    @Override
    public String toString() {
        return kind.id() + " " + id + " " + state() + " g" + generation;
    }
}
