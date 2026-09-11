/**
 * The vanilla-facing platform services for MC 1.13+ — tier 2: it may name {@code net.minecraft} and
 * {@code com.mojang}, and it may not name a loader.
 *
 * <p><b>"modern" is the era, not a version</b>, which is the whole point of the name: one source tree
 * compiled once per target, so a new Minecraft version is a row in the target list rather than a
 * directory. A class in here carries no version in its name for the same reason.
 *
 * <p>The one thing this tier owns that {@code runtime/lwjgl/3} cannot: <b>Minecraft caches GL state
 * in {@code GlStateManager} and elides calls it believes redundant</b>, so a raw {@code glEnable}
 * from tier 1 leaves that cache stale and the next vanilla draw skips the enable.
 * {@code Blaze3dGLBackend extends Lwjgl3GLBackend} overrides exactly the methods that touch a cached
 * domain and routes them through {@code RenderSystem}/{@code GlStateManager}; the override list is
 * contracts C5, a test pins it, and {@code -Dcrystalgraphics.host.verify=true} names the domain that
 * disagrees at runtime.
 *
 * <p>Anything here that needs no Minecraft type belongs one tier down, in {@code runtime/lwjgl/3}.
 */
package com.crystalgraphics.mc.modern;
