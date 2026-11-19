package com.crystalgraphics.api.material.engine;

/** Where a {@link CgEngineMaterial} draws: over what is there, or among the world's depth. */
public enum CgPlacement {
    /** Over whatever is drawn: a HUD, a screen, a texture of your own. No depth test. */
    SCREEN,
    /** In the world: tested against the scene's depth, writing none. */
    WORLD
}
