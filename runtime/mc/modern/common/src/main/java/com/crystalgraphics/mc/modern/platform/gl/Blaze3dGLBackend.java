package com.crystalgraphics.mc.modern.platform.gl;

// 1.13 ships LWJGL 3.1, which has no core-profile GLxxC classes.
//? if >=1.14 {
import com.crystalgraphics.lwjgl3.Lwjgl3GLBackend;
//?} else {
/*import com.crystalgraphics.lwjgl3.Lwjgl31GLBackend;
*///?}

//? if >=1.15 <1.21.5 {
import com.mojang.blaze3d.platform.GlStateManager;
//?}
//? if >=1.21.11 {
/*import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL33C;
*///?}
//? if >=26.3 {
/*import com.crystalgraphics.platform.gl.CgGLWorkerContext;
import com.crystalgraphics.sdl.SdlWorkerContext;
*///?}

/**
 * {@link Lwjgl3GLBackend} on Minecraft's context: the clear colour, which Minecraft caches below 1.21.5, through
 * {@code GlStateManager}; 1.21.11's sampler objects unbound from a unit we bind; 26.3's worker context over SDL. The
 * GL device's host sections read Minecraft's state themselves ({@code HostStateModern}).
 */
//? if >=1.14 {
public final class Blaze3dGLBackend extends Lwjgl3GLBackend {
//?} else {
/*public final class Blaze3dGLBackend extends Lwjgl31GLBackend {
*///?}

    // 26.3 ships SDL3 and no GLFW: its GL context (as Iris forces) shares with an SDL window's.
    //? if >=26.3 {
    /*@Override
    public CgGLWorkerContext createWorkerContext() {
        return SdlWorkerContext.create();
    }
    *///?}

    // 1.21.11 samples through sampler objects it leaves bound, always with a mipmapped min filter; over our mip-less
    // textures that is incomplete and reads black. It rebinds its own per draw.
    //? if >=1.21.11 {
    /*private int activeTextureUnit;

    @Override
    public void glActiveTexture(int texture) {
        super.glActiveTexture(texture);
        activeTextureUnit = texture - GL13.GL_TEXTURE0;
    }

    @Override
    public void glBindTexture(int target, int texture) {
        super.glBindTexture(target, texture);
        if (target == GL11.GL_TEXTURE_2D) GL33C.glBindSampler(activeTextureUnit, 0);
    }
    *///?}

    // Cached below 1.21.5 and in no domain of the shadow's, so no section can set it back. Below 1.15 the
    // same-package GlStateManager shim spells it.
    //? if <1.21.5 {
    @Override
    public void glClearColor(float r, float g, float b, float a) {
        GlStateManager._clearColor(r, g, b, a);
    }
    //?}
}
