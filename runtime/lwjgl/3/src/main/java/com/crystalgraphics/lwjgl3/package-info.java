/**
 * Tier 1 for LWJGL3 — the GL device's driver, the input service and the clipboard, written
 * against LWJGL 3.2.2 and the {@code platform} SPI and nothing else.
 *
 * <p>One compiled copy serves every 1.13+ target, so a new Minecraft version on this LWJGL family
 * adds nothing here. The rule for anything added: <b>a class lives in the lowest tier whose
 * dependencies it needs, and a class that needs one value from a higher tier takes it as a
 * constructor argument</b> — the GLFW window handle arrives as a {@code LongSupplier}, never by
 * reaching for {@code Minecraft.getInstance()}.
 *
 * <p><b>Minecraft caches GL state and elides calls it believes redundant</b>, so what the device changes is set back
 * to the cache's belief as each host section closes, by {@code HostStateModern} in {@code runtime/mc/modern/common}.
 * Nothing in this package may know about it.
 *
 * <p>Pinned to LWJGL <b>3.2.2</b>, the oldest in the supported range (MC 1.13–1.16). A 3.2.2 call
 * runs on the 3.3.x a modern client ships; the reverse throws {@code NoSuchMethodError} on a client
 * this tier is supposed to serve.
 *
 * <p>The build enforces the boundary: importing {@code net.minecraft}, {@code com.mojang} or any
 * loader package fails {@code compileJava} with the offending files named.
 */
package com.crystalgraphics.lwjgl3;
