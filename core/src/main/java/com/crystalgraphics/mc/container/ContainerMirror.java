package com.crystalgraphics.mc.container;

import com.crystalgraphics.mc.item.CgItemStack;
import com.crystalgraphics.serialization.nbt.CgNbtCompound;

import javax.annotation.Nullable;
import java.util.Arrays;

/**
 * A screen's copy of the open menu, kept current by asking the game what changed: each slot's hash (no copy), and a
 * stack copied only for the slot that differs. Client thread.
 *
 * <pre>{@code
 * ContainerMirror mirror = CgContainers.mirror();
 * // every frame:
 * ContainerMirror.Update update = mirror.poll();
 * if (update == null) return;                                      // nothing changed: no allocation was made
 * if (update.closed()) { screen.close(); return; }
 * Menu menu = update.menu();
 * for (int slot : update.slots()) show(slot, menu.slot(slot).stack());   // all of them on the first update, after that the changed
 * if (update.carried()) showCursor(menu.carried());
 * if (update.state()) showProgress(menu.state());
 * }</pre>
 *
 * <ul>
 *   <li>The first update of a menu has {@link Update#opened()} and every slot; a menu replaced by another (a new window id,
 *       kind or size) is a new opening.</li>
 *   <li>A host that can hook the game's {@code setItem} would publish at once; none is hooked yet, so the diff is the way
 *       on every version, and a change is seen on the poll after it.</li>
 *   <li>A slot's {@code canTake} is read when its stack changes.</li>
 * </ul>
 */
public final class ContainerMirror {

    private static final int[] NONE = new int[0];

    /**
     * What a poll found.
     *
     * @param menu    the menu as it now stands; null when it has closed
     * @param opened  the menu is new
     * @param closed  there is no menu any more
     * @param slots   the slots whose stack changed, ascending
     * @param carried the cursor's stack changed
     * @param state   the menu's state changed
     */
    public record Update(@Nullable CgContainers.Menu menu, boolean opened, boolean closed, int[] slots, boolean carried, boolean state) {
    }

    private final CgContainers.Host host;
    @Nullable
    private CgContainers.Menu menu;
    private CgContainers.Slot[] slots = new CgContainers.Slot[0];
    private int[] stamps = NONE;
    private int carriedStamp;
    private CgItemStack carried = CgItemStack.EMPTY;
    private CgNbtCompound state = new CgNbtCompound();
    private String title = "";

    public ContainerMirror(CgContainers.Host host) {
        this.host = host;
    }

    /** The menu as of the last poll; null before one or when there was none. */
    @Nullable
    public CgContainers.Menu menu() {
        return menu;
    }

    /** What changed since the last poll, or null if nothing did. */
    @Nullable
    public Update poll() {
        if (!host.present()) {
            if (menu == null) return null;
            menu = null;
            slots = new CgContainers.Slot[0];
            stamps = NONE;
            return new Update(null, false, true, NONE, false, false);
        }
        int size = host.size();
        int id = host.id();
        String type = host.type();
        boolean opened = menu == null || menu.id() != id || !menu.type().equals(type) || slots.length != size;
        int[] changed = NONE;
        int count = 0;
        if (opened) {
            slots = new CgContainers.Slot[size];
            stamps = new int[size];
            changed = new int[size];
            for (int i = 0; i < size; i++) {
                slots[i] = new CgContainers.Slot(i, host.stack(i), host.layout(i), host.canTake(i));
                stamps[i] = host.stamp(i);
                changed[count++] = i;
            }
        } else {
            for (int i = 0; i < size; i++) {
                int stamp = host.stamp(i);
                if (stamp == stamps[i]) continue;
                stamps[i] = stamp;
                slots[i] = slots[i].withStack(host.stack(i), host.canTake(i));
                if (count == changed.length) changed = Arrays.copyOf(changed, Math.max(4, count * 2));
                changed[count++] = i;
            }
        }
        if (count != changed.length) changed = Arrays.copyOf(changed, count);

        boolean carriedChanged = opened;
        int cs = host.carriedStamp();
        if (opened || cs != carriedStamp) {
            carriedStamp = cs;
            carriedChanged = true;
            carried = host.carried();
        }
        CgNbtCompound nowState = host.state();
        boolean stateChanged = opened || !nowState.equals(state);
        if (stateChanged) state = nowState;
        String nowTitle = host.title();
        boolean titleChanged = !nowTitle.equals(title);
        title = nowTitle;

        if (!opened && count == 0 && !carriedChanged && !stateChanged && !titleChanged) return null;
        menu = new CgContainers.Menu(id, type, nowTitle, Arrays.asList(slots), carried, state);
        return new Update(menu, opened, false, changed, carriedChanged, stateChanged);
    }
}
