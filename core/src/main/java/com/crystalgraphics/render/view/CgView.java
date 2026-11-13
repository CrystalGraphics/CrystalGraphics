package com.crystalgraphics.render.view;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * One camera's picture of the world, frame after frame: the game's ({@link #main()}), an anaglyph's second eye, a
 * portal's. A host names the view each stage firing draws ({@code CgHostFrame.view}); what the view keeps across its
 * firings and frames is its {@link #state()} (Unreal's {@code FSceneView} and its {@code FSceneViewState}).
 *
 * <pre>{@code
 * // A host drawing the world twice a frame, once per eye: a view for the second, made once
 * static final CgView RIGHT_EYE = CgView.create("anaglyph.right");
 *
 * CgRenderStage.WORLD_OPAQUE.host().view(eye == 0 ? CgView.main() : RIGHT_EYE);
 * CgRenderStage.WORLD_OPAQUE.fire();
 * }</pre>
 *
 * <ul>
 *   <li>A view draws each stage once a frame. A second firing of a stage in one host frame for the same view draws as
 *       {@link #unkept()}: a host drawing the world twice names a view for each.</li>
 *   <li>Make a view once and keep it: each holds its own histories, and a view made per frame keeps none.</li>
 * </ul>
 */
public final class CgView {

    /** Every view made, held weakly: what {@link #forgetAll} clears. */
    private static final Set<CgView> ALL = Collections.newSetFromMap(new WeakHashMap<>());
    private static final CgView MAIN = new CgView("main", true);
    private static final CgView UNKEPT = new CgView("unkept", false);

    private final String name;
    private final CgViewState state;

    private CgView(String name, boolean keepsHistory) {
        this.name = name;
        this.state = new CgViewState(keepsHistory);
        synchronized (ALL) {
            ALL.add(this);
        }
    }

    /** Engine side, at context teardown: every view's state forgets what it kept, whose storage goes with the context. */
    public static void forgetAll() {
        synchronized (ALL) {
            for (CgView view : ALL) view.state.forget();
        }
    }

    /** The game's view: what every stage draws unless its host names another. */
    public static CgView main() {
        return MAIN;
    }

    /** A view of the host's own, which keeps its own histories: an anaglyph eye, a portal. Make it once. */
    public static CgView create(String name) {
        return new CgView(name, true);
    }

    /**
     * The view a firing draws as when it is nobody's: a rehearsal's, or a second firing of a stage in one host frame
     * for a view that already drew it. It hands one firing's work to the next of its frame and keeps no history.
     */
    public static CgView unkept() {
        return UNKEPT;
    }

    public String name() {
        return name;
    }

    /** What it keeps across its firings and frames. */
    public CgViewState state() {
        return state;
    }

    @Override
    public String toString() {
        return name;
    }
}
