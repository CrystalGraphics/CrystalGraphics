package com.crystalgraphics.asset.tree;

import java.util.Map;

import javax.annotation.Nullable;

import com.crystalgraphics.object.CgPropertyPath;

/**
 * One change to an asset: what it touches, how, and the values before and after. Applying one
 * answers its inverse, which undoes it to the byte.
 *
 * <pre>{@code
 * CgPropertyPath h1 = CgPropertyPath.of(id).key("emitters").item("h1");
 * CgEdit undo = data.apply(CgEdit.set(h1.key("name"), "core"));            // an override, even of the parent's value
 * data.apply(CgEdit.reset(h1.key("name")));                                // back to the parent's
 * data.apply(CgEdit.insertAfter(h1.parent(), ring, "h1"));                 // a new element, placed by id
 * data.apply(CgEdit.disable(h1));                                          // kept, so the parent still reaches it
 * data.apply(undo);                                                        // the bytes before the first edit
 * }</pre>
 *
 * <ul>
 *   <li>Values are plain trees, as a file holds them; an inserted element carries its {@code id}.</li>
 *   <li>{@link #interactive()} marks one frame of a drag: a listener may take the fast path and rebuild once at the
 *       end.</li>
 *   <li>{@link #before()} is filled in when the edit is applied, for its inverse and for the change event.</li>
 * </ul>
 */
public final class CgEdit {

    /** What an edit does ({@code EPropertyChangeType}'s kinds, by name). */
    public enum Op {
        /** A value set here: an override. */
        SET,
        /** An override removed: the value is the parent's, or the type's default. */
        RESET,
        /** An element added to an id list, at an id or at the end. */
        INSERT,
        /** An element dropped: one this asset added, or an inherited one through {@code remove:}. */
        REMOVE,
        /** An element moved to another place in its list. */
        MOVE,
        /** An element turned back on. */
        ENABLE,
        /** An element turned off and kept. */
        DISABLE
    }

    private final CgPropertyPath path;
    private final Op op;
    @Nullable private final Object before;
    @Nullable private final Object after;
    @Nullable private final String anchor;
    private final boolean anchorAfter;
    private final boolean interactive;
    /** What undoes it exactly: the file's top-level keys it touched, as they were; null on an edit not yet applied. */
    @Nullable final Map<Object, Object> restore;

    CgEdit(CgPropertyPath path, Op op, @Nullable Object before, @Nullable Object after, @Nullable String anchor,
           boolean anchorAfter, boolean interactive, @Nullable Map<Object, Object> restore) {
        this.path = path;
        this.op = op;
        this.before = before;
        this.after = after;
        this.anchor = anchor;
        this.anchorAfter = anchorAfter;
        this.interactive = interactive;
        this.restore = restore;
    }

    /** Sets the value at {@code path}: an override. */
    public static CgEdit set(CgPropertyPath path, Object value) {
        return new CgEdit(path, Op.SET, null, value, null, true, false, null);
    }

    /** Removes the override at {@code path}. */
    public static CgEdit reset(CgPropertyPath path) {
        return new CgEdit(path, Op.RESET, null, null, null, true, false, null);
    }

    /** Adds {@code element} at the end of the id list at {@code list}. */
    public static CgEdit insert(CgPropertyPath list, Map<?, ?> element) {
        return new CgEdit(list, Op.INSERT, null, element, null, true, false, null);
    }

    /** Adds {@code element} after element {@code anchor} of the id list at {@code list}. */
    public static CgEdit insertAfter(CgPropertyPath list, Map<?, ?> element, String anchor) {
        return new CgEdit(list, Op.INSERT, null, element, anchor, true, false, null);
    }

    /** Adds {@code element} before element {@code anchor} of the id list at {@code list}. */
    public static CgEdit insertBefore(CgPropertyPath list, Map<?, ?> element, String anchor) {
        return new CgEdit(list, Op.INSERT, null, element, anchor, false, false, null);
    }

    /** Drops the element at {@code element}. */
    public static CgEdit remove(CgPropertyPath element) {
        return new CgEdit(element, Op.REMOVE, null, null, null, true, false, null);
    }

    /** Moves the element at {@code element} after element {@code anchor}. */
    public static CgEdit moveAfter(CgPropertyPath element, String anchor) {
        return new CgEdit(element, Op.MOVE, null, null, anchor, true, false, null);
    }

    /** Moves the element at {@code element} before element {@code anchor}. */
    public static CgEdit moveBefore(CgPropertyPath element, String anchor) {
        return new CgEdit(element, Op.MOVE, null, null, anchor, false, false, null);
    }

    /** Turns the element at {@code element} back on. */
    public static CgEdit enable(CgPropertyPath element) {
        return new CgEdit(element, Op.ENABLE, null, null, null, true, false, null);
    }

    /** Turns the element at {@code element} off, keeping it. */
    public static CgEdit disable(CgPropertyPath element) {
        return new CgEdit(element, Op.DISABLE, null, null, null, true, false, null);
    }

    /** This edit as one frame of a drag. */
    public CgEdit interactive() {
        return new CgEdit(path, op, before, after, anchor, anchorAfter, true, restore);
    }

    /** What it touches: a value, an element, or a list. */
    public CgPropertyPath path() {
        return path;
    }

    /** What it does. */
    public Op op() {
        return op;
    }

    /** The resolved value before it applied; null until then, or where there was none. */
    @Nullable
    public Object before() {
        return before;
    }

    /** The value it sets, or the element it inserts; null otherwise. */
    @Nullable
    public Object after() {
        return after;
    }

    /** The element an insert or a move is placed by; null for the end. */
    @Nullable
    public String anchor() {
        return anchor;
    }

    /** Whether it goes after its {@link #anchor()} rather than before. */
    public boolean anchorAfter() {
        return anchorAfter;
    }

    /** Whether it is one frame of a drag. */
    public boolean isInteractive() {
        return interactive;
    }

    @Override
    public String toString() {
        return op.name().toLowerCase() + " " + path + (after != null ? " = " + after : "")
                + (anchor != null ? (anchorAfter ? " after " : " before ") + anchor : "");
    }
}
