package com.crystalgraphics.api.mesh.geckolib;

import com.crystalgraphics.api.animation.CgAnimSequence;

/**
 * One animation of a GeckoLib {@code .animation.json}, converted for a {@link CgGeckoModel}: its keyed sequence, under
 * the animation's key, and how it loops.
 *
 * <pre>{@code
 * for (CgGeckoAnimation a : model.animations("rpgcore:animations/core/styles/default/idle.animation.json")) {
 *     if (a.loop() == CgGeckoAnimation.Loop.LOOP) idle = a.sequence();
 * }
 * }</pre>
 *
 * @param name     the animation's key in the file
 * @param sequence keyed, its duration the file's {@code animation_length}, else its last key's time
 * @param loop     the file's {@code loop}
 */
public record CgGeckoAnimation(String name, CgAnimSequence sequence, Loop loop) {

    /** GeckoLib's {@code Animation.LoopType}: what the controller does at the end. */
    public enum Loop {
        /** Stops: {@code false}, {@code "play_once"}, or no {@code loop}. */
        PLAY_ONCE,
        /** Plays again from the start: {@code true}, {@code "loop"}. */
        LOOP,
        /** Holds its last frame: {@code "hold_on_last_frame"}. */
        HOLD_ON_LAST_FRAME
    }
}
