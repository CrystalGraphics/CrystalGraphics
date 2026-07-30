package com.crystalgraphics.platform.input;

/**
 * What the pointer should look like — the vocabulary shared by whoever <em>decides</em> on a cursor and
 * whoever <em>presents</em> one.
 *
 * <p>The keyword set is <a href="https://www.w3.org/TR/css-ui-4/#cursor">CSS Basic User Interface L4</a>'s,
 * because CrystalGUI's {@code cursor:} property is the first consumer and CSS has already done the work of
 * enumerating every cursor a UI wants. Nothing here is CSS-specific though: the enum is a list of pointer
 * shapes, and a caller that has never parsed a stylesheet can use it exactly as well.</p>
 *
 * <p>The full set is present even though no platform can present all of it. A curated subset makes the
 * omissions invisible until someone hits one; the whole list makes a gap explicit at the mapping table,
 * where it belongs.</p>
 *
 * <h3>Presentation is a platform concern — which is why this type lives here</h3>
 * <p>Nothing in this enum draws anything. Deciding on a cursor and <em>showing</em> one are different jobs,
 * and the second is loader-specific to an awkward degree: LWJGL3/GLFW ships standard system cursors
 * including the resize set, while LWJGL2 — which both the harness and MC 1.7.10 use — has no standard
 * cursors at all and can only build custom ones from pixel data ({@link CgCursorBitmaps} supplies those).
 * So a caller resolves a value and hands it to {@link com.crystalgraphics.platform.service.CgCursorService},
 * whose default is a no-op.</p>
 *
 * <h3>{@link #AUTO} is the caller's problem, never the service's</h3>
 * <p>{@code AUTO} means "decide from context" and cannot be presented. CrystalGUI resolves it — the CSS rule
 * is "behaves as {@code text} over editable elements and {@code default} otherwise" — before dispatching, so
 * a {@code CgCursorService} implementation only ever sees a concrete value. {@link #needsResolution()} is
 * the predicate for anyone else doing the same.</p>
 */
public enum CgCursor {

    // ── General purpose ─────────────────────────────────────────────────────
    /** The initial value. Context-dependent: {@code text} over editable content, {@code default}
     * elsewhere — resolved by the input handler, never presented directly. */
    AUTO("auto"),
    DEFAULT("default"),
    NONE("none"),

    // ── Links and status ────────────────────────────────────────────────────
    CONTEXT_MENU("context-menu"),
    HELP("help"),
    POINTER("pointer"),
    PROGRESS("progress"),
    WAIT("wait"),

    // ── Selection ───────────────────────────────────────────────────────────
    CELL("cell"),
    CROSSHAIR("crosshair"),
    TEXT("text"),
    VERTICAL_TEXT("vertical-text"),

    // ── Drag and drop ───────────────────────────────────────────────────────
    ALIAS("alias"),
    COPY("copy"),
    MOVE("move"),
    NO_DROP("no-drop"),
    NOT_ALLOWED("not-allowed"),
    GRAB("grab"),
    GRABBING("grabbing"),

    // ── Resizing and scrolling ──────────────────────────────────────────────
    E_RESIZE("e-resize"),
    N_RESIZE("n-resize"),
    NE_RESIZE("ne-resize"),
    NW_RESIZE("nw-resize"),
    S_RESIZE("s-resize"),
    SE_RESIZE("se-resize"),
    SW_RESIZE("sw-resize"),
    W_RESIZE("w-resize"),
    /** Bidirectional — the ones a resize handle actually wants, since a handle resizes both ways. */
    EW_RESIZE("ew-resize"),
    NS_RESIZE("ns-resize"),
    NESW_RESIZE("nesw-resize"),
    NWSE_RESIZE("nwse-resize"),
    COL_RESIZE("col-resize"),
    ROW_RESIZE("row-resize"),
    ALL_SCROLL("all-scroll"),

    // ── Zooming ─────────────────────────────────────────────────────────────
    ZOOM_IN("zoom-in"),
    ZOOM_OUT("zoom-out");

    /** The CSS keyword. Kept because the enum name cannot spell it — {@code EW_RESIZE} is
     * {@code ew-resize}, and a platform mapping table is far easier to read against the real names. */
    public final String cssName;

    CgCursor(String cssName) {
        this.cssName = cssName;
    }

    /** True for the values that only make sense after resolution — see {@link #AUTO}. */
    public boolean needsResolution() {
        return this == AUTO;
    }
}
