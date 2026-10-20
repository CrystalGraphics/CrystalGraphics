package com.crystalgraphics.vfx.particle.gpu;

import java.util.Arrays;

import com.crystalgraphics.object.layout.CgFormClass;
import com.crystalgraphics.object.layout.CgLayout;
import com.crystalgraphics.object.layout.CgLayoutValues;

/**
 * A module's GPU words, laid out from its declaration: the {@link CgFormClass#ROW} slots of its {@link CgLayout}, in
 * declared order, packed into {@code vec4 m0..mN}. What a kind's {@code fx_<kind>} function reads as its numbers.
 *
 * <pre>{@code
 * CgVfxModuleWords words = CgVfxModuleWords.of(layout);    // once a declaration
 * words.vectors();                                          // the vec4s the kernel takes
 * words.write(values, out);                                 // each definition's row
 * }</pre>
 *
 * <ul>
 *   <li>Packing: a scalar takes the next lane; a {@code vec2} starts on an even lane; a {@code vec3} or {@code vec4}
 *       on a new vec4. Lanes left between are 0.</li>
 *   <li>An int, a {@code bool} (0 or 1) and an enum's index are written as floats: the kernels read {@code vec4}s.</li>
 *   <li>{@code PER_INSTANCE} slots go to the instance lanes and objects (textures, fields) outside the words:
 *       neither takes a lane here.</li>
 *   <li>A kind with no row slot still writes one vec4 of zeros.</li>
 * </ul>
 */
public final class CgVfxModuleWords {

    private static final int PAD = -1;

    private final CgLayout layout;
    /** Per lane: the float offset ({@code >= 0}), {@code -(intOffset + 2)} for an int, or {@link #PAD}. */
    private final int[] lanes;

    private CgVfxModuleWords(CgLayout layout, int[] lanes) {
        this.layout = layout;
        this.lanes = lanes;
    }

    /** The words of {@code layout}'s row slots. */
    public static CgVfxModuleWords of(CgLayout layout) {
        int[] scratch = new int[4 + 4 * (layout.floats() + layout.ints())];
        Arrays.fill(scratch, PAD);
        int lane = 0;
        for (CgLayout.Slot s : layout.slots()) {
            if (s.formClass() != CgFormClass.ROW) continue;
            int w = s.width();
            int align = w == 1 ? 1 : w == 2 ? 2 : 4;
            lane = (lane + align - 1) / align * align;
            for (int i = 0; i < w; i++) {
                scratch[lane + i] = s.store() == CgLayout.Store.FLOAT ? s.offset() + i : -(s.offset() + 2);
            }
            lane += w;
        }
        int vectors = Math.max(1, (lane + 3) / 4);
        int[] lanes = new int[vectors * 4];
        System.arraycopy(scratch, 0, lanes, 0, lanes.length);
        return new CgVfxModuleWords(layout, lanes);
    }

    public CgLayout layout() {
        return layout;
    }

    /** The vec4s it writes. */
    public int vectors() {
        return lanes.length / 4;
    }

    /** Writes {@code values}' row slots as {@link #vectors()} vec4s. */
    public void write(CgLayoutValues values, CgVfxWords out) {
        for (int v = 0; v < lanes.length; v += 4) {
            out.vec4(lane(values, lanes[v]), lane(values, lanes[v + 1]), lane(values, lanes[v + 2]), lane(values, lanes[v + 3]));
        }
    }

    private float lane(CgLayoutValues values, int code) {
        if (code == PAD) return 0f;
        if (code >= 0) return values.floatAt(code);
        return values.intAt(-code - 2);
    }
}
