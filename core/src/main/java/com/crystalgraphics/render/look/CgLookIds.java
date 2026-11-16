package com.crystalgraphics.render.look;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.crystalgraphics.api.material.CgMaterialInstance;
import com.crystalgraphics.render.meshpass.CgCustomDepth;
import com.crystalgraphics.render.post.CgPostPoint;
import com.crystalgraphics.render.post.settings.CgPostBlock;
import com.crystalgraphics.render.post.settings.CgPostSettings;

/**
 * The looks graded primitives are seen through, each by an id 1 to 7: what the custom depth pass writes into a graded
 * solid's stencil bits 5-7 ({@link CgCustomDepth#withLook}), and what the look weights read back. Looks equal in value
 * (their post block and their blendables) share an id, so a kit's look on every character of the kit is one look.
 *
 * <pre>{@code
 * int id = CgLookIds.of(primitive.grade());     // 0 for the frame's look (null)
 * CgPostSettings look = CgLookIds.look(id);      // the look id names, or null
 * }</pre>
 *
 * <ul>
 *   <li>An id is held while its look is seen; once seven are, an eighth look takes the id seen longest ago, which
 *       bumps {@link #epoch()}, and every graded primitive's kept commands are made again.</li>
 *   <li>Eight looks seen in one frame: the eighth is drawn in the frame's look, warned once naming all eight.</li>
 *   <li>A look is compared by value the first time it is seen, then by identity: change a look's fields after it grades
 *       and it keeps the id of its old value. Make a new {@link CgPostSettings} for a new look.</li>
 *   <li>Render thread.</li>
 * </ul>
 */
public final class CgLookIds {

    /** The most looks at once: the stencil's bits 5-7. */
    public static final int MOST = CgCustomDepth.LOOK_BITS >> CgCustomDepth.LOOK_SHIFT;

    private static final Logger LOGGER = LogManager.getLogger("CgLookIds");
    private static final CgPostSettings[] LOOKS = new CgPostSettings[MOST + 1];
    private static final Key[] KEYS = new Key[MOST + 1];
    private static final long[] SEEN = new long[MOST + 1];
    /** Every look object seen, to its id: a lookup that allocates nothing. */
    private static final Map<CgPostSettings, Integer> BY_LOOK = new IdentityHashMap<>();
    private static long frame;
    private static int epoch;
    private static boolean warned;

    private CgLookIds() {}

    /** A look's value: its post block and its blendables. */
    private record Key(Map<String, Object> block, List<Blended> blendables) {
        static Key of(CgPostSettings look) {
            List<Blended> blended = new ArrayList<>();
            for (CgPostSettings.Blendable b : look.blendables()) {
                blended.add(new Blended(b.material(), b.point(), b.weight(), b.priority()));
            }
            return new Key(CgPostBlock.block(look), blended);
        }
    }

    private record Blended(CgMaterialInstance material, CgPostPoint point, float weight, int priority) {}

    /** Engine side, once a host frame before anything draws: what counts as seen this frame. */
    public static synchronized void frame(long hostFrame) {
        frame = hostFrame;
    }

    /** The id of {@code look}, seen this frame: 0 for null, or where all seven ids are seen this frame already. */
    public static synchronized int of(@Nullable CgPostSettings look) {
        if (look == null) return 0;
        Integer known = BY_LOOK.get(look);
        if (known != null) {
            SEEN[known] = frame;
            return known;
        }
        Key key = Key.of(look);
        for (int id = 1; id <= MOST; id++) {
            if (KEYS[id] != null && KEYS[id].equals(key)) return take(look, id);
        }
        for (int id = 1; id <= MOST; id++) {
            if (KEYS[id] == null) return assign(look, key, id);
        }
        int oldest = 0;
        for (int id = 1; id <= MOST; id++) {
            if (SEEN[id] < frame && (oldest == 0 || SEEN[id] < SEEN[oldest])) oldest = id;
        }
        if (oldest == 0) {
            if (!warned) {
                warned = true;
                LOGGER.warn("Eight looks graded in one frame; the eighth draws in the frame's look: {} and {}",
                        Arrays.toString(LOOKS), look);
            }
            return 0;
        }
        forget(oldest);
        epoch++;
        return assign(look, key, oldest);
    }

    /** The look {@code id} names, or null. */
    @Nullable
    public static synchronized CgPostSettings look(int id) {
        return id <= 0 || id > MOST ? null : LOOKS[id];
    }

    /** Moves when an id is given to another look: what a graded primitive's kept commands were built under. */
    public static synchronized int epoch() {
        return epoch;
    }

    private static int assign(CgPostSettings look, Key key, int id) {
        LOOKS[id] = look;
        KEYS[id] = key;
        return take(look, id);
    }

    private static int take(CgPostSettings look, int id) {
        BY_LOOK.put(look, id);
        SEEN[id] = frame;
        return id;
    }

    private static void forget(int id) {
        for (Iterator<Integer> it = BY_LOOK.values().iterator(); it.hasNext(); ) {
            if (Objects.equals(it.next(), id)) it.remove();
        }
        LOOKS[id] = null;
        KEYS[id] = null;
    }
}
