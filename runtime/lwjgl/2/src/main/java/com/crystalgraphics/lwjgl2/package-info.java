/**
 * Tier 1 for LWJGL2 — the GL backend, the context and the input service, written against LWJGL 2.9.4
 * and the {@code platform} SPI and nothing else.
 *
 * <p>One compiled copy serves every LWJGL2 target and the debug harness. The rule for anything added
 * here: <b>a class lives in the lowest tier whose dependencies it needs, and a class that needs one
 * value from a higher tier takes it as a constructor argument.</b> A class that needs a Minecraft
 * <i>call</i> is not a value case — it is a subclass in the target's own module.
 *
 * <p><b>This tier needs no such subclass.</b> The plan expected one, {@code OpenGlHelperGLBackend},
 * for the single call {@code Lwjgl2GLBackend} made into {@code OpenGlHelper} — and that call is gone:
 * nothing invoked it, the backend already carries the Core &gt; ARB &gt; EXT waterfall it existed for,
 * and on 1.20.x its twin had converged on the same {@code GlStateManager} call as the ordinary bind.
 * So the LWJGL2 backend is tier 1 as it stands, with no host half at all.
 *
 * <p>The build enforces the boundary: importing {@code net.minecraft}, {@code com.mojang} or any
 * loader package fails {@code compileJava} with the offending files named.
 */
package com.crystalgraphics.lwjgl2;
