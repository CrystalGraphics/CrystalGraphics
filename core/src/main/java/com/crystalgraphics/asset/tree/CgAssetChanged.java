package com.crystalgraphics.asset.tree;

import java.util.Collections;
import java.util.List;

/**
 * An asset's change, fired once an edit or a transaction applied: Unreal's
 * {@code FPropertyChangedChainEvent}. An undo fires one too, its edits the inverses.
 *
 * <pre>{@code
 * Runnable stop = data.onChanged(e -> {
 *     for (CgEdit edit : e.edits()) log(edit.path() + ": " + edit.before() + " -> " + edit.after());
 *     if (e.change() == CgChange.STRUCTURAL) rebuild();
 * });
 * }</pre>
 */
public final class CgAssetChanged {

    private final CgAssetData asset;
    private final List<CgEdit> edits;
    private final CgChange change;

    CgAssetChanged(CgAssetData asset, List<CgEdit> edits, CgChange change) {
        this.asset = asset;
        this.edits = Collections.unmodifiableList(edits);
        this.change = change;
    }

    /** The asset, as it is now. */
    public CgAssetData asset() {
        return asset;
    }

    /** The edits, applied, in order: each with its {@link CgEdit#before()}. */
    public List<CgEdit> edits() {
        return edits;
    }

    /** The costliest change among them. */
    public CgChange change() {
        return change;
    }
}
