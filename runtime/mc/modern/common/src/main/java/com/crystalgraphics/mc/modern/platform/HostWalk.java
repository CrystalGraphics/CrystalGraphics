package com.crystalgraphics.mc.modern.platform;

/**
 * A walk cycle whose private state can be set: implemented on {@code WalkAnimationState} (1.19.4 on) by Forge's and
 * NeoForge's {@code WalkAnimationHook}, Forge's {@code WalkAnimationSrgHook} in an installed game, and Fabric's
 * {@code WalkAnimationAliasHook} (intermediary aliases, so never gated by field). Its public API cannot put a
 * live entity's cycle back exactly: from 1.21.3 {@code update} also writes {@code positionScale}, which has no getter.
 * Where neither hook applied, a pose's walk is the entity's own.
 */
public interface HostWalk {

    void crystalgraphics$walk(float position, float speed, float speedOld);
}
