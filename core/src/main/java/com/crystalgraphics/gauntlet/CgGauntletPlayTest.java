package com.crystalgraphics.gauntlet;

import com.crystalgraphics.variable.CgVar;

/**
 * {@code crystalgraphics:play}: a world entered and played unattended for a while, then photographed. The run behind
 * a profile, a pipeline-list refresh or a look at a frame, with nothing of a test's own in the way.
 *
 * <pre>{@code
 * -Dcrystalgraphics.gauntlet=crystalgraphics:play -Dcrystalgraphics.gauntlet.play.seconds=90
 * -Dcrystalgraphics.gauntlet=crystalgraphics:play -Dcrystalgraphics.gauntlet.play.seconds=0   // until the window closes
 * }</pre>
 *
 * - Its seconds count from the world's loading, so a cold start's load does not eat them.
 * - With no end the run's timeout still applies: raise {@code crystalgraphics.gauntlet.timeout} to play longer.
 */
final class CgGauntletPlayTest extends CgGauntletController {

    private static final CgVar.Int SECONDS = CgVar.integer("crystalgraphics.gauntlet.play.seconds", 60, 0, 86400,
            CgVar.DEV | CgVar.RESTART, "Seconds crystalgraphics:play plays in its world before it ends; 0 never ends.");

    private CgGauntletCapture.Shot shot;

    @Override
    protected void onStateChange(CgGauntletState from, CgGauntletState to) {
        if (to.equals(CgGauntletState.TITLE)) enterWorld();
    }

    @Override
    protected void onTick(float seconds) {
        if (!state().equals(CgGauntletState.IN_WORLD)) return;
        int play = SECONDS.get();
        if (shot == null) {
            if (play == 0 || timeInState() < play) return;
            shot = capture("world");
            return;
        }
        if (!shot.done()) return;
        check("play.capture", shot.written(), shot.written() ? shot.file().toString() : shot.failure());
        endTest();
    }
}
