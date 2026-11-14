package com.crystalgraphics.mc.container;

/**
 * Something the player does to the open menu, handed to {@link CgContainers#act}. Each is what vanilla's own screen
 * sends, through the game's own call, so the server sees an ordinary player.
 *
 * <pre>{@code
 * CgContainers.act(menu.id(), new Action.Click(20, 0, ClickType.QUICK_MOVE));   // shift-click slot 20
 * CgContainers.act(menu.id(), new Action.Button(1));                           // the enchanting table's second option
 * CgContainers.act(menu.id(), new Action.Rename("Excalibur"));                 // the anvil's name field
 * CgContainers.act(menu.id(), new Action.SelectTrade(2));                      // a merchant's third offer
 * CgContainers.act(menu.id(), new Action.Close());
 * }</pre>
 *
 * Which of these a version can carry is {@link Capabilities}: {@code act} answers {@code false} for one it cannot.
 */
public interface Action {

    /** A click on {@code slot} ({@link CgContainers#OUTSIDE} for outside the window) with mouse {@code button}. */
    record Click(int slot, int button, ClickType type) implements Action {

        /** Starts a drag across slots with mouse {@code button} ({@code 0} split evenly, {@code 1} one each, {@code 2} creative's middle). */
        public static Click dragStart(int button) {
            return new Click(CgContainers.OUTSIDE, quickCraft(0, button), ClickType.QUICK_CRAFT);
        }

        /** Adds {@code slot} to the drag begun with {@code button}. */
        public static Click dragAdd(int slot, int button) {
            return new Click(slot, quickCraft(1, button), ClickType.QUICK_CRAFT);
        }

        /** Ends the drag begun with {@code button}: the stack is shared over the slots added. */
        public static Click dragEnd(int button) {
            return new Click(CgContainers.OUTSIDE, quickCraft(2, button), ClickType.QUICK_CRAFT);
        }

        /** Vanilla's packing of a drag's stage (0 start, 1 slot, 2 end) and mouse button into one number. */
        static int quickCraft(int stage, int button) {
            return (stage & 3) | ((button & 3) << 2);
        }
    }

    /** A menu button by id: the enchanting table's three options (0-2), a stonecutter's recipe, a lectern's page turn, a beacon's confirm. */
    record Button(int id) implements Action {
    }

    /** The anvil's name field. */
    record Rename(String name) implements Action {
    }

    /** The merchant's offer at {@code index}. */
    record SelectTrade(int index) implements Action {
    }

    /** Closes the menu as the player closing its screen does, telling the server. */
    record Close() implements Action {
    }
}
