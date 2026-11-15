package com.crystalgraphics.sequence.binding;

import javax.annotation.Nullable;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.crystalgraphics.world.CgAttachment;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;

/**
 * Where a spawnable or a locator stands each frame: its parent (a role's socket, a spawned object's node, a locator)
 * composed with its own offset and turn. A locator's own is what its transform tracks keyed this frame, else its rest;
 * a spawnable's is its {@code at}'s offset and rotation. Attachments: what a spawned effect follows.
 */
public final class CgSequencePlacement implements CgAttachment {

    /** Resolves the parent each frame: a spawnable placed under another appears only once that one is made. */
    public interface Parent {
        @Nullable
        CgAttachment get();
    }

    private final Parent parent;
    /** A locator's live transform, or null for a spawnable's fixed offset. */
    @Nullable
    private final CgSequenceLocator locator;
    private final CgSequenceDefinition definition;
    /** A locator's role's figure transform, composed after its own; null for none. */
    @Nullable
    private final CgFigureDefinition.Transform figure;
    private final Vector3f offset = new Vector3f(), scratch = new Vector3f();
    private final Quaternionf turn = new Quaternionf(), parentTurn = new Quaternionf(), local = new Quaternionf();

    /** A spawnable's place: its parent and its fixed offset and turn, in host units. */
    public CgSequencePlacement(Parent parent, CgSequenceDefinition definition, float[] offset, @Nullable Quaternionf turn) {
        this.parent = parent;
        this.locator = null;
        this.figure = null;
        this.definition = definition;
        this.offset.set(offset[0], offset[1], offset[2]);
        if (turn != null) this.turn.set(turn);
    }

    /**
     * A locator's place: its parent, its live transform, then its role's figure transform, the cue's own placement
     * scaled and moved by the body it lands on ({@code null} for none).
     */
    public CgSequencePlacement(Parent parent, CgSequenceDefinition definition, CgSequenceLocator locator,
                               @Nullable CgFigureDefinition.Transform figure) {
        this.parent = parent;
        this.locator = locator;
        this.figure = figure;
        this.definition = definition;
    }

    @Override
    public boolean place(double[] point, @Nullable Quaternionf out) {
        CgAttachment p = parent.get();
        if (p == null) {
            point[0] = point[1] = point[2] = 0;
            parentTurn.identity();
        } else if (!p.place(point, parentTurn)) {
            return false;
        }
        if (locator != null) {
            float[] t = locator.translation(), r = locator.rotation();
            offset.set(t[0], t[1], t[2]);
            if (locator.fixedTurn() != null) local.set(locator.fixedTurn());
            else definition.units().rotationToHost(r[0], r[1], r[2], local);
        } else {
            local.set(turn);
        }
        parentTurn.transform(offset, scratch);
        point[0] += scratch.x;
        point[1] += scratch.y;
        point[2] += scratch.z;
        if (figure != null) {
            // the figure under the locator's own: its offset in the locator's turned frame, its turn after
            parentTurn.mul(local).transform(scratch.set(figure.location()));
            point[0] += scratch.x;
            point[1] += scratch.y;
            point[2] += scratch.z;
            parentTurn.mul(figure.rotation());
        }
        if (out != null) {
            if (figure != null) out.set(parentTurn);
            else parentTurn.mul(local, out);
        }
        return true;
    }

    /** Its parent's scale times what its transform track keys: a spawnable's component scale. */
    @Override
    public Vector3f scale(@Nullable String name, Vector3f out) {
        CgAttachment p = parent.get();
        if (p == null) out.set(1f);
        else p.scale(null, out);
        if (locator != null) out.mul(locator.scale()[0], locator.scale()[1], locator.scale()[2]);
        if (figure != null) out.mul(figure.scale());
        return out;
    }

    /** Whether its transform is keyed and can move each frame: what is spawned at it must follow it. */
    public boolean live() {
        return locator != null;
    }
}
