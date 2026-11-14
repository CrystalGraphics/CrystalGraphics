package com.crystalgraphics.render.meshpass;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where a primitive is drawn into custom depth, and the stencil value it writes there: Unreal's
 * {@code bRenderCustomDepth} with {@code CustomDepthStencilValue}. A post material reads the mark (an outline, an x-ray,
 * a highlight) from custom depth and its stencil.
 *
 * <pre>{@code
 * static final CgCustomDepth DRAGON_OUTLINE = CgCustomDepth.stencil("mymod:dragon_outline");   // a value, allocated
 * body.customDepth(DRAGON_OUTLINE);                    // drawn into custom depth writing it
 * body.customDepth(CgCustomDepth.DEPTH);               // into custom depth alone, its stencil untouched
 * int value = DRAGON_OUTLINE.value();                  // what the post material compares
 * }</pre>
 *
 * <ul>
 *   <li>Named values are allocated 1 up, in the order first asked, past any raw value already taken: two mods naming
 *       their own never share one.</li>
 *   <li>{@link #stencil(int)} names a value directly, as a kit's raw stencil does: it may meet a named one.</li>
 * </ul>
 */
public final class CgCustomDepth {

    private static final Map<String, CgCustomDepth> NAMED = new LinkedHashMap<>();
    private static final CgCustomDepth[] RAW = new CgCustomDepth[256];
    private static final boolean[] TAKEN = new boolean[256];

    /** Custom depth alone: no stencil written. */
    public static final CgCustomDepth DEPTH = new CgCustomDepth("depth", 0, 0);

    private final String id;
    private final int value, writeMask;

    private CgCustomDepth(String id, int value, int writeMask) {
        this.id = id;
        this.value = value;
        this.writeMask = writeMask;
    }

    /** The stencil value named {@code id}: allocated at its first ask, the same after. */
    public static synchronized CgCustomDepth stencil(String id) {
        CgCustomDepth named = NAMED.get(id);
        if (named != null) return named;
        int value = 1;
        while (value < 256 && TAKEN[value]) value++;
        if (value == 256) throw new IllegalStateException("every stencil value is taken; " + id + " is one too many");
        TAKEN[value] = true;
        named = new CgCustomDepth(id, value, 0xFF);
        NAMED.put(id, named);
        return named;
    }

    /** Stencil value {@code value} (0 to 255) written under the full mask: a kit's raw value. */
    public static CgCustomDepth stencil(int value) {
        return stencil(value, 0xFF);
    }

    /** Stencil value {@code value} written under {@code writeMask}: Unreal's {@code CustomDepthStencilWriteMask}. */
    public static synchronized CgCustomDepth stencil(int value, int writeMask) {
        if (value < 0 || value > 255) throw new IllegalArgumentException("a stencil value is 0 to 255, not " + value);
        writeMask &= 0xFF;
        if (writeMask == 0xFF && RAW[value] != null) return RAW[value];
        CgCustomDepth raw = new CgCustomDepth("stencil " + value, value, writeMask);
        if (writeMask == 0xFF) {
            RAW[value] = raw;
            TAKEN[value] = true;
        }
        return raw;
    }

    /** Its stencil value; 0 for {@link #DEPTH}. */
    public int value() {
        return value;
    }

    /** The stencil bits it writes; 0 for {@link #DEPTH}. */
    public int writeMask() {
        return writeMask;
    }

    @Override
    public String toString() {
        return "CgCustomDepth(" + id + (writeMask != 0 ? ", " + value : "") + ")";
    }
}
