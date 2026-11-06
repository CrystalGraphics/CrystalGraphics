package com.crystalgraphics.platform.input;

/**
 * What a mod registers on a {@link CgInputStage} to take input. Each method answers whether it <b>consumed</b> the
 * event, and each defaults to false, so a listener writes only the kinds it takes.
 *
 * <pre>{@code
 * CgInputStage.SCREEN.register(0, new CgInputListener() {
 *     @Override public boolean mouseButton(CgSystemInput.Mouse.Event e) {
 *         return panel.contains(e.x(), e.y()) && panel.click(e.button(), e.state());
 *     }
 *     @Override public boolean key(CgSystemInput.Keyboard.Event e) { return panel.focused() && panel.type(e); }
 * });
 * }</pre>
 *
 * <p>Every event is in the engine's vocabulary whatever the host: positions are raw surface pixels from the top left,
 * keys are {@link CgKeyCodes}, buttons are {@link CgMouseCodes}, a wheel notch is 1 and positive rolled down. A
 * listener runs on the host's input thread, the render thread, and holds no host type.</p>
 *
 * <ul>
 *   <li>A move is delivered to every listener and its answer is ignored: the game keeps working over what is not yours.</li>
 *   <li>Consuming a key means the game acts on none of it, so take it only while you own the keyboard.</li>
 * </ul>
 */
public interface CgInputListener {

    /**
     * Whether this listener wants input at the moment. A host that must do work to produce input (legacy Forge drains
     * a foreign screen's queue each frame) does it only while some listener answers true. Defaults to true.
     */
    default boolean wants() {
        return true;
    }

    /** The pointer moved. Delivered to every listener; the return is ignored. */
    default boolean mouseMoved(CgSystemInput.Mouse.Event event) {
        return false;
    }

    /** A button went down or up. {@code event.button()} and {@code event.state()} say which. */
    default boolean mouseButton(CgSystemInput.Mouse.Event event) {
        return false;
    }

    /** The wheel turned by {@code event.wheelDelta()} notches. */
    default boolean scrolled(CgSystemInput.Mouse.Event event) {
        return false;
    }

    /** A key went down or up, with no character; the character arrives as its own {@link #character}. */
    default boolean key(CgSystemInput.Keyboard.Event event) {
        return false;
    }

    /** A character was typed, with no key. */
    default boolean character(CgSystemInput.Keyboard.Event event) {
        return false;
    }

    /** An input method's run in progress, with the caret in it; an empty {@code text} ends the run. */
    default boolean composition(String text, int caret) {
        return false;
    }
}
