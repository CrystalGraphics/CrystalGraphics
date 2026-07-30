package com.crystalgraphics.platform.service;

/**
 * Platform abstraction for playing UI sounds (button clicks and the like).
 *
 * <p>Callers play sounds unconditionally — a widget asks for {@code "button_click"} and does not check
 * whether anything is listening. Sound is cosmetic, so a platform with no audio is a normal case: implement
 * {@link #play} with an empty body and everything above keeps working.</p>
 *
 * <p><b>There is deliberately no {@code NOOP} constant.</b> A shared do-nothing instance is the thing a
 * bundle reaches for to avoid deciding, and "this platform has no sound" is worth one empty method in that
 * platform's own source rather than a reference to somebody else's silence — see
 * {@link com.crystalgraphics.platform.CgPlatformService} for why none of its methods have defaults either.</p>
 */
@FunctionalInterface
public interface CgSoundService {

    /** Plays a named UI sound (e.g. {@code "button_click"}). Unrecognised ids are a no-op, not an error. */
    void play(String soundId);
}
