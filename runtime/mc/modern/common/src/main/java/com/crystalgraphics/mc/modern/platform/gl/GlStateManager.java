package com.crystalgraphics.mc.modern.platform.gl;

//? if <1.15 {
/*// 1.14's GlStateManager under the `_` names 1.15 gave it, for Blaze3dGLBackend and HostStateModern, which import none
// below 1.15.
final class GlStateManager {

    private GlStateManager() {}

    /^* The class this stands in for, for HostStateModern's reflection. ^/
    static final Class<?> HOST = com.mojang.blaze3d.platform.GlStateManager.class;

    static void _activeTexture(int a0) {
        com.mojang.blaze3d.platform.GlStateManager.activeTexture(a0);
    }

    static void _clearColor(float a0, float a1, float a2, float a3) {
        com.mojang.blaze3d.platform.GlStateManager.clearColor(a0, a1, a2, a3);
    }
}
*///?}
