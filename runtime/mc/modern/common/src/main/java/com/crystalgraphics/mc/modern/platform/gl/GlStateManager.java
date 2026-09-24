package com.crystalgraphics.mc.modern.platform.gl;

//? if <1.15 {
/*// 1.14's GlStateManager under the `_` names 1.15 gave it, for Blaze3dGLBackend, which imports none below 1.15.
final class GlStateManager {

    private GlStateManager() {}

    static void _activeTexture(int a0) {
        com.mojang.blaze3d.platform.GlStateManager.activeTexture(a0);
    }

    static void _alphaFunc(int a0, float a1) {
        com.mojang.blaze3d.platform.GlStateManager.alphaFunc(a0, a1);
    }

    static void _bindTexture(int a0) {
        com.mojang.blaze3d.platform.GlStateManager.bindTexture(a0);
    }

    static void _blendFuncSeparate(int a0, int a1, int a2, int a3) {
        com.mojang.blaze3d.platform.GlStateManager.blendFuncSeparate(a0, a1, a2, a3);
    }

    static void _clearColor(float a0, float a1, float a2, float a3) {
        com.mojang.blaze3d.platform.GlStateManager.clearColor(a0, a1, a2, a3);
    }

    static void _colorMask(boolean a0, boolean a1, boolean a2, boolean a3) {
        com.mojang.blaze3d.platform.GlStateManager.colorMask(a0, a1, a2, a3);
    }

    static void _deleteTexture(int a0) {
        com.mojang.blaze3d.platform.GlStateManager.deleteTexture(a0);
    }

    static void _depthFunc(int a0) {
        com.mojang.blaze3d.platform.GlStateManager.depthFunc(a0);
    }

    static void _depthMask(boolean a0) {
        com.mojang.blaze3d.platform.GlStateManager.depthMask(a0);
    }

    static void _disableAlphaTest() {
        com.mojang.blaze3d.platform.GlStateManager.disableAlphaTest();
    }

    static void _disableBlend() {
        com.mojang.blaze3d.platform.GlStateManager.disableBlend();
    }

    static void _disableCull() {
        com.mojang.blaze3d.platform.GlStateManager.disableCull();
    }

    static void _disableDepthTest() {
        com.mojang.blaze3d.platform.GlStateManager.disableDepthTest();
    }

    static void _disablePolygonOffset() {
        com.mojang.blaze3d.platform.GlStateManager.disablePolygonOffset();
    }

    static void _enableAlphaTest() {
        com.mojang.blaze3d.platform.GlStateManager.enableAlphaTest();
    }

    static void _enableBlend() {
        com.mojang.blaze3d.platform.GlStateManager.enableBlend();
    }

    static void _enableCull() {
        com.mojang.blaze3d.platform.GlStateManager.enableCull();
    }

    static void _enableDepthTest() {
        com.mojang.blaze3d.platform.GlStateManager.enableDepthTest();
    }

    static void _enablePolygonOffset() {
        com.mojang.blaze3d.platform.GlStateManager.enablePolygonOffset();
    }

    static void _pixelStore(int a0, int a1) {
        com.mojang.blaze3d.platform.GlStateManager.pixelStore(a0, a1);
    }

    static void _polygonMode(int a0, int a1) {
        com.mojang.blaze3d.platform.GlStateManager.polygonMode(a0, a1);
    }

    static void _polygonOffset(float a0, float a1) {
        com.mojang.blaze3d.platform.GlStateManager.polygonOffset(a0, a1);
    }

    static void _stencilFunc(int a0, int a1, int a2) {
        com.mojang.blaze3d.platform.GlStateManager.stencilFunc(a0, a1, a2);
    }

    static void _stencilMask(int a0) {
        com.mojang.blaze3d.platform.GlStateManager.stencilMask(a0);
    }

    static void _stencilOp(int a0, int a1, int a2) {
        com.mojang.blaze3d.platform.GlStateManager.stencilOp(a0, a1, a2);
    }

    static void _texParameter(int a0, int a1, float a2) {
        com.mojang.blaze3d.platform.GlStateManager.texParameter(a0, a1, a2);
    }

    static void _viewport(int a0, int a1, int a2, int a3) {
        com.mojang.blaze3d.platform.GlStateManager.viewport(a0, a1, a2, a3);
    }
}
*///?}
