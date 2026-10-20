package com.crystalgraphics.asset.load;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.object.validate.CgIssue;

/**
 * One asset's load, as far as it has come: its state, then what that state holds. Read it on the pipeline's loop
 * thread, where it changes.
 *
 * <pre>{@code
 * CgAssetLoad load = pipeline.request(CgParticleKinds.SYSTEM, "mymod:kamehameha/charge");
 * pipeline.drain();                                   // each frame, at the boundary
 * if (load.state() == CgAssetState.COMPILED) play(load.definition());
 * if (load.state() == CgAssetState.FAILED) log.error("{}: {}", load.id(), load.failure());
 * }</pre>
 */
public final class CgAssetLoad {

    private final CgType kind;
    private final String id;
    CgAssetState state = CgAssetState.REGISTERED;
    @Nullable Map<?, ?> tree;
    @Nullable CgAssetData asset;
    @Nullable Object definition;
    @Nullable String failure;
    List<CgIssue> issues = Collections.emptyList();
    final List<Runnable> waiters = new ArrayList<>();

    CgAssetLoad(CgType kind, String id) {
        this.kind = kind;
        this.id = id;
    }

    /** Its kind. */
    public CgType kind() {
        return kind;
    }

    /** Its id. */
    public String id() {
        return id;
    }

    /** How far it has come. */
    public CgAssetState state() {
        return state;
    }

    /** The asset resolved over its parents, from {@link CgAssetState#RESOLVED}; null before, or failed earlier. */
    @Nullable
    public CgAssetData asset() {
        return asset;
    }

    /** Its definition, from {@link CgAssetState#COMPILED}; null before. */
    @Nullable
    public Object definition() {
        return definition;
    }

    /** Why it failed, in a line; null unless {@link CgAssetState#FAILED}. */
    @Nullable
    public String failure() {
        return failure;
    }

    /** The validation issues that failed it, by path; empty for any other failure. */
    public List<CgIssue> issues() {
        return issues;
    }

    @Override
    public String toString() {
        return kind.id() + " " + id + " " + state + (failure == null ? "" : ": " + failure);
    }
}
