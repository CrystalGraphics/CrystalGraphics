package com.crystalgraphics.gauntlet;

/**
 * Where an unattended run is, by name: what {@link CgGauntletController#onStateChange} reports. Unreal's Gauntlet
 * states ({@code FGauntletStates}), which a game names for itself. The engine broadcasts the ones below as the game
 * moves between them; a mod may broadcast its own with {@link CgGauntlet#broadcastStateChange}.
 *
 * <pre>{@code
 * @Override protected void onStateChange(CgGauntletState from, CgGauntletState to) {
 *     if (to.equals(CgGauntletState.TITLE)) enterWorld();
 *     if (to.equals(CgGauntletState.IN_WORLD)) startChecks();
 * }
 *
 * public static final CgGauntletState ARENA = new CgGauntletState("mymod:arena");   // one of a mod's own
 * }</pre>
 *
 * - Compare with {@code equals}: two states of one name are the same state.
 */
public record CgGauntletState(String name) {

    /** The run has started; nothing about the game is known yet. */
    public static final CgGauntletState INITIALIZED = new CgGauntletState("initialized");
    /** The game's loading overlay or CrystalGraphics' loading screen is up: nothing can be driven. */
    public static final CgGauntletState LOADING = new CgGauntletState("loading");
    /** Ready, and no world loaded: the title screen or a menu over it. */
    public static final CgGauntletState TITLE = new CgGauntletState("title");
    /** A world is loaded on this client. */
    public static final CgGauntletState IN_WORLD = new CgGauntletState("in_world");
    /** A dedicated server, up and ticking. */
    public static final CgGauntletState SERVER = new CgGauntletState("server");

    @Override
    public String toString() {
        return name;
    }
}
