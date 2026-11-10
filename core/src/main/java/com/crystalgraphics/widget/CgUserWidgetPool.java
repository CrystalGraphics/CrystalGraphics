package com.crystalgraphics.widget;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Instances of widget trees kept for reuse: UMG's {@code FUserWidgetPool}. {@link #obtain} hands back an inactive
 * instance of the tree, else makes one; {@link #release} returns it. A {@link CgWorldWidgetLayer} keeps one for its
 * components; a HUD of its own keeps one for what it shows and hides often.
 *
 * <pre>{@code
 * CgUserWidgetPool pool = new CgUserWidgetPool();
 * CgUserWidget toast = pool.obtain(TOAST.loaded());   // the designer's values: never the last use's text
 * toast.text("Txt_Message", message);
 * // ... shown, then done with:
 * pool.release(toast);
 * }</pre>
 *
 * <ul>
 *   <li>An instance comes back {@link CgUserWidget#reset reset}, unlike Unreal's, so no use sees another's state.</li>
 *   <li>Instances match by tree identity: a tree reloaded since is a new tree, and its old instances are not reused.
 *       {@link #reset()} drops them.</li>
 *   <li>One thread at a time; releasing an instance not obtained here does nothing.</li>
 * </ul>
 */
public final class CgUserWidgetPool {

    private final List<CgUserWidget> active = new ArrayList<>();
    private final List<CgUserWidget> inactive = new ArrayList<>();
    private final List<CgUserWidget> activeView = Collections.unmodifiableList(active);

    /** An instance of {@code tree} at its designer's values: an inactive one, else a new one. */
    public CgUserWidget obtain(CgWidgetTree tree) {
        CgUserWidget widget = null;
        for (int i = inactive.size() - 1; i >= 0; i--) {
            if (inactive.get(i).tree() == tree) {
                widget = inactive.get(i);
                // Swap-remove: the order of the inactive is no one's.
                inactive.set(i, inactive.get(inactive.size() - 1));
                inactive.remove(inactive.size() - 1);
                break;
            }
        }
        if (widget == null) {
            widget = tree.newInstance();
        } else {
            widget.reset();
        }
        active.add(widget);
        return widget;
    }

    /** Returns {@code widget} for reuse. */
    public void release(CgUserWidget widget) {
        for (int i = active.size() - 1; i >= 0; i--) {
            if (active.get(i) == widget) {
                active.remove(i);
                inactive.add(widget);
                return;
            }
        }
    }

    /** Returns every active instance for reuse. */
    public void releaseAll() {
        inactive.addAll(active);
        active.clear();
    }

    /** Forgets every instance, active or not: at a reload, or when the pool's owner goes. */
    public void reset() {
        active.clear();
        inactive.clear();
    }

    /** The instances in use. */
    public List<CgUserWidget> active() {
        return activeView;
    }

    /** How many wait to be reused. */
    public int inactive() {
        return inactive.size();
    }
}
