package com.crystalgraphics.mc.legacy.platform.actor;

import net.minecraft.entity.Entity;

/**
 * What {@link ActorRenderLegacy} asks of the renderer it stands in for: its name tag and shadow, which are protected.
 * Every {@code Render} implements it through the {@code RenderHook} mixin; {@link #crystalgraphics$renderName} dispatches
 * virtually, so a living entity's renderer answers with its own (sneaking, teams, the crosshair).
 */
public interface HostRender {

    void crystalgraphics$renderName(Entity entity, double x, double y, double z);

    float crystalgraphics$shadowSize();

    float crystalgraphics$shadowOpaque();
}
