package com.crystalgraphics.mc.modern.platform.gl;

import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;

/**
 * Pins {@link Blaze3dGLBackend} to bookkeeping: on 1.20.1 it overrides only what a host section's close cannot set back.
 *
 * <p>Every domain Blaze3D's cache keeps that the state shadow models is set back as the section closes
 * ({@code HostStateModern.sectionSlots()}), so routing one of those calls through {@code GlStateManager} again would
 * be a second mechanism for the same job. The runtime check that the slots are complete is
 * {@code -Dcrystalgraphics.host.verify=true}.</p>
 */
public class Blaze3dMirrorTest {

    /** The section's edges, and the clear colour: cached by Blaze3D below 1.21.5 and in no domain of the shadow's. */
    private static final Set<String> BOOKKEEPING = new TreeSet<>(Arrays.asList("fromHost", "toHost", "glClearColor"));

    @Test
    public void overridesOnlyTheBookkeeping() {
        Set<String> declared = Arrays.stream(Blaze3dGLBackend.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .map(Method::getName)
                .collect(Collectors.toCollection(TreeSet::new));

        assertEquals("Blaze3dGLBackend routes a call the host section's close already sets back, or has lost one "
                + "it cannot: a domain the shadow models belongs in HostStateModern's section slots instead.",
                BOOKKEEPING, declared);
    }

    @Test
    public void extendsTierOne() {
        assertEquals("Blaze3dGLBackend must extend the tier-1 backend, so every other call reaches the driver",
                "Lwjgl3GLBackend", Blaze3dGLBackend.class.getSuperclass().getSimpleName());
    }
}
