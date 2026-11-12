package com.crystalgraphics.sequence;

import com.crystalgraphics.platform.world.CgCameraManager;

/**
 * How a play takes over the viewer while its camera holds the view: Unreal's {@code FMovieSceneSequencePlaybackSettings}
 * cinematic switches, given by the caller with the play, never by the sequence's file. Both off by default: a move
 * played in battle keeps the HUD and the player in view, a story cut asks for them hidden.
 *
 * <pre>{@code
 * CgSequencePlayer play = sequencer.play(ultimate, roles);                              // HUD and player stay
 * CgSequencePlayer cut = sequencer.play(intro, roles)
 *         .playbackSettings(new CgSequencePlaybackSettings(true, false));             // the HUD hidden while it holds
 * }</pre>
 *
 * <ul>
 *   <li>The look, movement and keys are locked by any play holding the camera, whatever these say.</li>
 *   <li>{@code hideHud} hides the host's own HUD alone; a mod's HUD reads {@link #hideHud()} itself.</li>
 * </ul>
 *
 * @param hideHud    the host's HUD hidden while its camera holds the view ({@code bHideHud})
 * @param hidePlayer the viewer's own player not drawn while it holds ({@code bHidePlayer})
 */
public record CgSequencePlaybackSettings(boolean hideHud, boolean hidePlayer) {

    /** Both off: Unreal's defaults. */
    public static final CgSequencePlaybackSettings DEFAULT = new CgSequencePlaybackSettings(false, false);

    /** As {@link CgCameraManager#HIDE_HUD} and {@link CgCameraManager#HIDE_PLAYER} bits. */
    public int flags() {
        return (hideHud ? CgCameraManager.HIDE_HUD : 0) | (hidePlayer ? CgCameraManager.HIDE_PLAYER : 0);
    }
}
