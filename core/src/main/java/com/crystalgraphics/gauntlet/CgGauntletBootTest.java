package com.crystalgraphics.gauntlet;

import com.crystalgraphics.lifecycle.CgGraphicsLifecycle;

/**
 * {@code crystalgraphics:boot}: the game started, the engine with it, and its first screen drew. Unreal's
 * {@code UGauntletTestControllerBootTest}, which ends once the boot is complete. It photographs the title screen as
 * {@code crystalgraphics-boot-title.png}.
 *
 * <pre>{@code
 * -Dcrystalgraphics.gauntlet=crystalgraphics:boot
 * }</pre>
 *
 * - On a dedicated server the boot is the server ticking, and there is nothing to photograph.
 */
final class CgGauntletBootTest extends CgGauntletController {

    /** Seconds on the first screen before its photograph: Minecraft's title fades its buttons in over its second second. */
    private static final double SETTLE = 3;

    private CgGauntletCapture.Shot shot;

    @Override
    protected void onStateChange(CgGauntletState from, CgGauntletState to) {
        if (to.equals(CgGauntletState.SERVER)) {
            check("boot.server", true, "the dedicated server ticks");
            endTest();
        }
    }

    @Override
    protected void onTick(float seconds) {
        if (shot == null) {
            boolean up = state().equals(CgGauntletState.TITLE) || state().equals(CgGauntletState.IN_WORLD);
            if (!up || timeInState() < SETTLE) return;
            check("boot.engine", CgGraphicsLifecycle.isInitialized(), "the graphics started");
            shot = capture("title");
            return;
        }
        if (!shot.done()) return;
        check("boot.capture", shot.written(), shot.written() ? shot.file().toString() : shot.failure());
        endTest();
    }
}
