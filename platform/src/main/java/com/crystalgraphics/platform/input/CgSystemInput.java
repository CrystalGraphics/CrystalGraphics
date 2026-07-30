package com.crystalgraphics.platform.input;

import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;

/**
 * The sink for <b>raw</b> system input events — the seam a loader pushes mouse and keyboard events
 * across, into whatever is consuming them.
 *
 * <p>This interface says nothing about when the platform's event pool is drained. It is purely a
 * listener for already-drained events, dispatched by whoever owns the pump.</p>
 *
 * <h3>Events arrive already translated</h3>
 * <p>Codes reaching {@link Mouse#consumeMouseEvent} and {@link Keyboard#consumeKeyboardEvent} must
 * already be {@link CgKeyCodes} / {@link CgMouseCodes} values. Translating from native codes is
 * {@link com.crystalgraphics.platform.service.CgInputService}'s job, and doing it at the boundary means
 * nothing downstream ever has to ask which platform an event came from.</p>
 *
 */
public interface CgSystemInput {

    long DEFAULT_MULTI_CLICK_INTERVAL_MS = 300L;

    /**
     * The OS double-click threshold in milliseconds, resolved once on first use.
     *
     * <p>Read from AWT's {@code awt.multiClickInterval} desktop property, which is the only portable way
     * to ask. Every failure mode — headless JVM, no AWT, a platform that does not publish the property —
     * falls back to {@link #DEFAULT_MULTI_CLICK_INTERVAL_MS} rather than propagating, because a
     * multi-click threshold is never worth failing a frame over.</p>
     */
    static long multiClickIntervalMs() {
        return MultiClickInterval.VALUE;
    }

    /** Holder idiom: computes {@link #multiClickIntervalMs()} once, on first access, without locking. */
    final class MultiClickInterval {
        private MultiClickInterval() {}

        static final long VALUE = resolve();

        private static long resolve() {
            if (GraphicsEnvironment.isHeadless()) {
                return DEFAULT_MULTI_CLICK_INTERVAL_MS;
            }
            try {
                Object value = Toolkit.getDefaultToolkit().getDesktopProperty("awt.multiClickInterval");
                if (value instanceof Number n) {
                    return n.longValue();
                }
            } catch (Throwable t) {
                // AWT unavailable, headless, or a platform-specific failure — fall back safely.
            }
            return DEFAULT_MULTI_CLICK_INTERVAL_MS;
        }
    }

    @FunctionalInterface
    interface Mouse {

        /**
         * A low-level mouse event.
         *
         * <p>Unlike raw Windows or LWJGL2, {@code wheelDelta} is continuous and normalised: one notch of a
         * mouse wheel is {@code 1.0}.</p>
         *
         * @param x          window X of the event, top-left origin
         * @param y          window Y of the event, top-left origin
         * @param dx         delta X
         * @param dy         delta Y
         * @param button     {@link CgMouseCodes} button id, or {@link CgMouseCodes#NONE} for a
         *                   non-click event
         * @param state      true when the button is pressed
         * @param wheelDelta wheel movement in <b>notches</b>
         * @param millis     timestamp for click/release events; -1 for move events
         */
        record Event(int x, int y, int dx, int dy, int button, boolean state, float wheelDelta, long millis) {}

        /**
         * Processes one event.
         *
         * @return whether the event should keep propagating
         */
        boolean consumeMouseEvent(Event event);
    }

    @FunctionalInterface
    interface Keyboard {

        /**
         * A low-level keyboard event.
         *
         * @param character the character produced, if any
         * @param key       {@link CgKeyCodes} keycode
         * @param pressed   true for a press, false for a release
         * @param repeat    true when this is an auto-repeat rather than a fresh press
         * @param millis    event timestamp
         */
        record Event(char character, int key, boolean pressed, boolean repeat, long millis) {}

        /**
         * Processes one event.
         *
         * @return whether the event should keep propagating
         */
        boolean consumeKeyboardEvent(Event event);
    }
}
