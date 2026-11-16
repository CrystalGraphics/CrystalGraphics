package com.crystalgraphics.render.meshpass;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where a primitive is drawn into custom depth, and the stencil value it writes there: Unreal's
 * {@code bRenderCustomDepth} with {@code CustomDepthStencilValue}. A post material reads the mark (an outline, an x-ray,
 * a highlight) from custom depth and its stencil. A mark is 0 to 31, the stencil's bits 0-4: bits 5-7 hold the look a
 * graded primitive's pixels are in ({@link #LOOK_BITS}, written by the engine), and a post material never sees them.
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
 *   <li>A value over 31, or a mask with bits over 31, is refused: those bits are the look's.</li>
 * </ul>
 */
public final class CgCustomDepth {

    /** The stencil's bits a mark may use, and the bits above them that hold a graded primitive's look id (1 to 7). */
    public static final int MARK_BITS = 0x1F, LOOK_BITS = 0xE0, LOOK_SHIFT = 5;

    private static final Map<String, CgCustomDepth> NAMED = new LinkedHashMap<>();
    private static final CgCustomDepth[] RAW = new CgCustomDepth[MARK_BITS + 1];
    private static final boolean[] TAKEN = new boolean[MARK_BITS + 1];

    /** Custom depth alone: no stencil written. */
    public static final CgCustomDepth DEPTH = new CgCustomDepth("depth", 0, 0);

    private final String id;
    private final int value, writeMask;
    /** This mark with each look id in bits 5-7, made at the first ask. */
    private final CgCustomDepth[] looked = new CgCustomDepth[(LOOK_BITS >> LOOK_SHIFT) + 1];

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
        while (value <= MARK_BITS && TAKEN[value]) value++;
        if (value > MARK_BITS) throw new IllegalStateException("every stencil value 1-31 is taken; " + id + " is one too many");
        TAKEN[value] = true;
        named = new CgCustomDepth(id, value, MARK_BITS);
        NAMED.put(id, named);
        return named;
    }

    /** Stencil value {@code value} (0 to 31) written under the mark's full mask: a kit's raw value. */
    public static CgCustomDepth stencil(int value) {
        return stencil(value, MARK_BITS);
    }

    /** Stencil value {@code value} written under {@code writeMask}: Unreal's {@code CustomDepthStencilWriteMask}. */
    public static synchronized CgCustomDepth stencil(int value, int writeMask) {
        if (value < 0 || value > MARK_BITS) {
            throw new IllegalArgumentException("a stencil value is 0 to 31 (bits 5-7 are a graded primitive's look), not " + value);
        }
        if ((writeMask & ~MARK_BITS & 0xFF) != 0) {
            throw new IllegalArgumentException("a stencil write mask is within 0x1F (bits 5-7 are a graded primitive's look), not 0x"
                    + Integer.toHexString(writeMask));
        }
        if (writeMask == MARK_BITS && RAW[value] != null) return RAW[value];
        CgCustomDepth raw = new CgCustomDepth("stencil " + value, value, writeMask);
        if (writeMask == MARK_BITS) {
            RAW[value] = raw;
            TAKEN[value] = true;
        }
        return raw;
    }

    /**
     * Engine side: this mark with look id {@code look} (1 to 7, {@code CgLookIds}) in bits 5-7, written only where the
     * depth test passes, so a graded primitive behind another never claims its pixels; {@code this} for 0.
     */
    public synchronized CgCustomDepth withLook(int look) {
        if (look == 0) return this;
        if (look < 0 || look > LOOK_BITS >> LOOK_SHIFT) throw new IllegalArgumentException("a look id is 1 to 7, not " + look);
        CgCustomDepth with = looked[look];
        if (with == null) {
            looked[look] = with = new CgCustomDepth(id + ", look " + look, value | look << LOOK_SHIFT, writeMask | LOOK_BITS);
        }
        return with;
    }

    /** The look id in its bits 5-7: 0 but for a mark {@link #withLook} made. */
    public int look() {
        return (value & LOOK_BITS) >> LOOK_SHIFT;
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
