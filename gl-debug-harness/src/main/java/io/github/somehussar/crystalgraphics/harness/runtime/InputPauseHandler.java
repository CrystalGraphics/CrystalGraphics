package io.github.somehussar.crystalgraphics.harness.runtime;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.util.logging.Logger;

/**
 * Input and pause-state service for the interactive render loop.
 *
 * <p>Manages keyboard polling for the pause toggle (ESCAPE/T keys) and
 * the associated mouse grab/ungrab behavior. Extracted from
 * {@code InteractiveSceneRunner} to isolate input handling from
 * rendering, timing, and overlay concerns.</p>
 *
 * <p><b>Pause toggle semantics</b>: Uses the LWJGL keyboard event queue
 * (not {@code Keyboard.isKeyDown()}) to detect key-down events, ensuring
 * a single press produces exactly one toggle regardless of how many frames
 * the key is held.</p>
 *
 * <p><b>Mouse cursor contract</b>:</p>
 * <ul>
 *   <li>When toggling to paused: releases the mouse cursor</li>
 *   <li>When toggling to unpaused: grabs the mouse cursor and drains
 *       accumulated mouse delta to prevent a camera jump on resume</li>
 * </ul>
 *
 * <p><b>Thread safety</b>: Only used on the LWJGL render thread.</p>
 *
 * <p><b>Frame ordering contract</b>: {@link #pollPauseToggle()} must be
 * called once per frame BEFORE camera input processing, so the camera
 * skips updates while paused.</p>
 */
public final class InputPauseHandler {

    private static final Logger LOGGER = Logger.getLogger(InputPauseHandler.class.getName());

    private boolean paused = false;

    /**
     * Creates a new InputPauseHandler in the unpaused state.
     */
    public InputPauseHandler() {
    }

    /**
     * Polls the LWJGL keyboard event queue for ESCAPE and T key-down events
     * to toggle pause state.
     *
     * <p>Uses the event queue rather than {@code Keyboard.isKeyDown()} to
     * ensure a single press produces exactly one toggle, regardless of how
     * many frames the key is held.</p>
     *
     * <p>When toggling to paused: releases the mouse cursor.
     * When toggling to unpaused: grabs the mouse cursor and drains any
     * accumulated mouse delta to prevent a camera jump.</p>
     */
    public void pollPauseToggle() {
        while (Keyboard.next()) {
            if (!Keyboard.getEventKeyState()) {
                // Only act on key-down events, ignore key-up
                continue;
            }
            int key = Keyboard.getEventKey();
            if (key == Keyboard.KEY_ESCAPE || key == Keyboard.KEY_T) {
                paused = !paused;
                if (paused) {
                    Mouse.setGrabbed(false);
                    LOGGER.info("[InputPauseHandler] PAUSED \u2014 cursor released");
                } else {
                    Mouse.setGrabbed(true);
                    // Drain accumulated mouse delta to prevent a camera jump on resume
                    Mouse.getDX();
                    Mouse.getDY();
                    LOGGER.info("[InputPauseHandler] RESUMED \u2014 cursor locked");
                }
            }
        }
    }

    /**
     * Returns whether the runner is currently in paused state.
     *
     * @return true if paused
     */
    public boolean isPaused() {
        return paused;
    }

    /**
     * Programmatically sets the paused state. Used by test scenes to
     * trigger pause without keyboard input.
     *
     * <p>Handles mouse grab/ungrab and drains accumulated mouse delta
     * to prevent camera jumps, mirroring the behavior of keyboard-driven
     * pause toggling.</p>
     *
     * @param paused true to pause, false to resume
     */
    public void setPaused(boolean paused) {
        if (this.paused == paused) {
            return;
        }
        this.paused = paused;
        if (paused) {
            Mouse.setGrabbed(false);
        } else {
            Mouse.setGrabbed(true);
            Mouse.getDX();
            Mouse.getDY();
        }
    }

    /**
     * Releases the mouse cursor. Should be called during runner shutdown
     * to ensure the cursor is not trapped after the window closes.
     */
    public void releaseCursor() {
        Mouse.setGrabbed(false);
    }

    @Override
    public String toString() {
        return "InputPauseHandler[paused=" + paused + "]";
    }
}
