package com.crystalgraphics.platform.service;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgService;
import com.crystalgraphics.platform.input.CgSystemInput;

/**
 * A screen of the engine's in front of the host's game: what a UI drawn at the {@code SCREEN} render stage puts up to
 * take the player's attention. The host treats it as a screen of its own -- the cursor free, the player still, input
 * through {@code CgInputStage.SCREEN}, the {@code SCREEN} stage drawn over it each frame, Escape its owner's rather
 * than the pause menu's -- and it draws nothing itself and pauses nothing.
 *
 * <pre>{@code
 * final class Menu implements CgScreens.Owner {
 *     public void shown()  { layout(); }                  // up, and again on every resize
 *     public void hidden() { save(); }                    // down: closed, or another screen took its place
 * }
 * Menu menu = new Menu();
 * CgScreens.open(menu);
 * CgRenderStage.SCREEN.register(CgRenderStage.VIEWPORT_WIDGETS, frame -> {
 *     if (CgScreens.isOpen(menu)) menu.draw(frame.canvas());
 * });
 * }</pre>
 *
 * <p>An owner that plays itself off first takes Escape and closes once it has:</p>
 *
 * <pre>{@code
 * public void escape() { animateOut(() -> CgScreens.close(this)); }
 * }</pre>
 *
 * <ul>
 *   <li>Client and render thread. Without a host (a server, a test) nothing opens and nothing is open.</li>
 *   <li>{@link #close} lands at the host's next tick, never inside the frame being drawn: {@link Owner#hidden} follows
 *       it there.</li>
 *   <li>A key the {@code SCREEN} input stage took and its listener left goes back through {@link #giveBack}.</li>
 * </ul>
 */
public final class CgScreens {

    /** What has a screen up, and hears it come and go. */
    public interface Owner {

        /** Its screen is up: once opened, and again whenever the host lays its screens out (a resize). */
        default void shown() {
        }

        /** Its screen is down: closed, or replaced by another screen. */
        default void hidden() {
        }

        /** Escape, which no listener of the {@code SCREEN} input stage took: closes it unless overridden. */
        default void escape() {
            close(this);
        }

        /** Whether the game's HUD is hidden while it is up, where the host draws its HUD over screens (1.21.6 to 26.0). */
        default boolean hidesHud() {
            return false;
        }

        /**
         * Where text is being typed, in device pixels from the top left, into {@code out} as x, y, width, height: where an
         * input method opens its candidate list, on a host that asks (26.3's SDL). False while nothing is.
         */
        default boolean textInputArea(float[] out) {
            return false;
        }
    }

    /** What a host answers: its screen of the engine's, one class per era, {@code Screens<Era>}. */
    public interface Host {

        /** Puts {@code owner}'s screen up in front, replacing any other; nothing if it is up already. */
        void open(Owner owner);

        /** Takes {@code owner}'s screen down at the next tick, if it is in front. */
        void close(Owner owner);

        /** The owner of the screen in front when it is the engine's; null for the host's own or none. */
        Owner front();

        /** Whether any screen is in front, the host's or the engine's. */
        boolean anyFront();

        /** Gives the screen in front {@code key} through its own handling, as though no listener had seen it. */
        void giveBack(CgSystemInput.Keyboard.Event key);

        Host NONE = new Host() {
            @Override public void open(Owner owner) { }
            @Override public void close(Owner owner) { }
            @Override public Owner front() { return null; }
            @Override public boolean anyFront() { return false; }
            @Override public void giveBack(CgSystemInput.Keyboard.Event key) { }
        };
    }

    /** The slot a host fills, on a client. */
    public static final CgService<Host> HOST = CgService.of("crystalgraphics:screens", Host.NONE);

    private CgScreens() {
    }

    private static Host host() {
        return CgPlatform.get(HOST);
    }

    /** Puts {@code owner}'s screen up in front of whatever is there; nothing if it is up already. */
    public static void open(Owner owner) {
        host().open(owner);
    }

    /** Takes {@code owner}'s screen down at the host's next tick; nothing if it is not in front. */
    public static void close(Owner owner) {
        host().close(owner);
    }

    /** Whether {@code owner}'s screen is the one in front. */
    public static boolean isOpen(Owner owner) {
        return host().front() == owner;
    }

    /** Whether any screen is in front, the host's own or the engine's. */
    public static boolean anyOpen() {
        return host().anyFront();
    }

    /**
     * Gives the screen in front a key a {@code SCREEN} input listener took and then left: a listener answering a frame
     * late (a UI on its own thread) takes every key and returns those it did not want. A screen of the host's handles it
     * as though no listener had seen it; the engine's gives an Escape press to its owner ({@link Owner#escape}).
     *
     * <pre>{@code
     * for (CgSystemInput.Keyboard.Event key; (key = ui.pollUnhandledKey()) != null; ) CgScreens.giveBack(key);
     * }</pre>
     *
     * <ul>
     *   <li>A key carries a key code or a character, never both; each reaches the matching handler.</li>
     *   <li>Releases reach only a host whose screens take them (from 1.8): 1.7.10's screens hear presses alone.</li>
     * </ul>
     */
    public static void giveBack(CgSystemInput.Keyboard.Event key) {
        host().giveBack(key);
    }
}
