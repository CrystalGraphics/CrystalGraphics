package com.crystalgraphics.collision.mirror;

import javax.annotation.Nullable;
import java.util.IdentityHashMap;

/**
 * A level mirror's shapes, and which shape each host state is: the worker's alone. States are described once, on
 * their first appearance; {@link #view} hands a published version the entries as they stand.
 */
final class MirrorPalette extends ShapeStore {

    private final CgMirrorHost host;
    private final IdentityHashMap<Object, Integer> states = new IdentityHashMap<>();
    private final CgMirrorShape scratch = new CgMirrorShape();
    @Nullable private Object lastState;
    private int lastEntry;
    private View view;

    MirrorPalette(CgMirrorHost host) {
        this.host = host;
    }

    /** The entry {@code state} stands for, or -1 when its shape depends on where it stands (and for a null state). */
    int entryOf(@Nullable Object state) {
        if (state == null) return -1;
        if (state == lastState) return lastEntry;
        Integer known = states.get(state);
        int entry;
        if (known != null) {
            entry = known;
        } else {
            host.describe(state, scratch.clear());
            entry = scratch.positional ? -1 : intern(scratch);
            states.put(state, entry);
        }
        lastState = state;
        lastEntry = entry;
        return entry;
    }

    /** The entries as they stand, for a version: the same view while nothing was added. */
    View view() {
        if (view == null || view.count != count) view = new View(this);
        return view;
    }

    /** A published version's palette: entries below {@link #count}, in arrays that never change there. */
    static final class View {
        final int[] start;
        final float[] boxes, top, bottom, fluidHeight;
        final byte[] fluidKind;
        final boolean[] followsAbove;
        final int count;

        View(ShapeStore s) {
            start = s.start;
            boxes = s.boxes;
            top = s.top;
            bottom = s.bottom;
            fluidHeight = s.fluidHeight;
            fluidKind = s.fluidKind;
            followsAbove = s.followsAbove;
            count = s.count;
        }
    }
}
