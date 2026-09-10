package com.crystalgraphics.mc.modern.platform.gl;

import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Pins {@link Blaze3dGLBackend}'s override list — contracts C5, requirement F5.
 *
 * <p><b>Why a test and not a review.</b> Every method here exists because Minecraft caches that GL
 * domain and elides calls it thinks redundant. Delete one and nothing fails: our call still reaches
 * the driver, the host's shadow simply goes stale, and the damage lands on <i>Minecraft's</i> next
 * draw as wrong rendering with nothing in any log. There is no exception to catch and no assertion in
 * the engine that could notice, so the list is asserted here instead.
 *
 * <p><b>What it cannot tell you</b> is whether the list is COMPLETE — that is a fact about Minecraft,
 * not about us, and it was established by reading {@code GlStateManager}'s fields on 1.20.1. This only
 * proves the list has not shrunk since. The runtime half is
 * {@code -Dcrystalgraphics.host.verify=true}, which compares the driver against the host's shadow
 * after every pass and names the domain that disagrees.
 *
 * <p>A deliberate change to the list is a deliberate change here, in the same commit.
 */
public class Blaze3dMirrorTest {

    /** The domains {@code GlStateManager} keeps a shadow of, measured on 1.20.1. @see Blaze3dGLBackend */
    private static final Set<String> MIRRORED = new TreeSet<>(Arrays.asList(
            // enable/disable caps: BLEND, DEPTH_TEST, CULL_FACE, SCISSOR_TEST, POLYGON_OFFSET_FILL
            "glEnable", "glDisable",
            // blend, depth, colour mask, clear
            "glBlendFunc", "glBlendFuncSeparate", "glDepthMask", "glDepthFunc",
            "glColorMask", "glClearColor",
            // stencil
            "glStencilFunc", "glStencilOp", "glStencilMask",
            // scissor, viewport, polygon
            "glScissor", "glViewport", "glPolygonMode", "glPolygonOffset",
            // textures: the active unit and the per-unit bindings
            "glActiveTexture", "glBindTexture", "glDeleteTextures", "glTexParameteri", "glPixelStorei",
            // framebuffers, so its own FBO bookkeeping stays honest
            "bindFramebuffer", "blitFramebuffer", "genFramebuffers", "deleteFramebuffers",
            "framebufferTexture2D", "checkFramebufferStatus",
            // renderbuffers, same reason
            "glGenRenderbuffers", "glDeleteRenderbuffers", "glBindRenderbuffer",
            "glRenderbufferStorage", "glFramebufferRenderbuffer"));

    @Test
    public void overridesExactlyTheMirroredDomains() {
        Set<String> declared = Arrays.stream(Blaze3dGLBackend.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .map(Method::getName)
                .collect(Collectors.toCollection(TreeSet::new));

        assertEquals("Blaze3dGLBackend's override list has drifted from the measured mirror. Adding a "
                + "method here means Minecraft caches that domain too; removing one means it does not, "
                + "and either claim belongs in C5 before it belongs in code.",
                MIRRORED, declared);
    }

    @Test
    public void everythingElseIsInheritedFromTierOne() {
        // The subclass carries the mirror and nothing else: the other ~110 entry points must reach the
        // driver from tier 1. If this number climbs, someone has copied a method down rather than
        // overriding a domain -- which is how a tier stops being one.
        assertTrue("tier 2 should be small; it is the exception list, not a backend",
                Blaze3dGLBackend.class.getDeclaredMethods().length < 40);

        assertEquals("Blaze3dGLBackend must extend the tier-1 backend, or none of this holds",
                "Lwjgl3GLBackend", Blaze3dGLBackend.class.getSuperclass().getSimpleName());
    }
}
