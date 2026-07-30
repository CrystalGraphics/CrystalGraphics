package com.crystalgraphics.platform;

import com.crystalgraphics.platform.gl.*;
import com.crystalgraphics.platform.service.*;

/**
 * API contract for a complete CrystalGraphics platform bundle.
 *
 * <p>Implement this interface to provide the platform services as a single cohesive unit.
 * Register the bundle via {@link CgPlatform#register(CgPlatformService)}.</p>
 *
 * <p>Each getter is called once during registration and the result is cached
 * inside {@link CgPlatform} — implementations may return new instances
 * per call or cached singletons; either is correct.</p>
 *
 * <h3>Every method is abstract, deliberately — there are no defaults here</h3>
 * <p>The last three services ({@link #input()}, {@link #sound()}, {@link #cursor()}) serve the UI layer
 * built on top of CrystalGraphics rather than CrystalGraphics itself, and it is tempting to give them
 * inert defaults so a bundle that does not care can stay silent. <b>They do not get one.</b></p>
 *
 * <p>A default is an answer chosen on behalf of someone who never saw the question. The failure mode is
 * that a new platform compiles cleanly while silently inheriting "no sound, no cursor" — and nothing ever
 * reports it, because inheriting a no-op is indistinguishable from deciding on one. Adding a service to
 * this interface later has the same shape: with defaults, every existing bundle keeps compiling and
 * quietly does without the new capability.</p>
 *
 * <p>Abstract methods make the compiler the reminder. A platform that genuinely has nothing to offer is
 * still free to say so — an empty method body is a perfectly good answer, and it is a <em>recorded</em>
 * one, sitting in that platform's source where a reader can see the decision was made.</p>
 */
public interface CgPlatformService {
    /** @return the GL dispatch implementation; must not be {@code null} */
    CgGLBackend gl();
    /** @return the GL context capability implementation; must not be {@code null} */
    CgGLContext capabilities();
    /** @return the resource loading service; must not be {@code null} */
    CgResourceService resources();
    /** @return the rendering service; must not be {@code null} */
    CgRenderingService rendering();
    /** @return the lifecycle service; must not be {@code null} */
    CgLifecycleService lifecycle();
    /** @return the reload service; must not be {@code null} */
    CgReloadService reload();

    /** @return the input and clipboard service; must not be {@code null} */
    CgInputService input();
    /** @return the UI sound service; must not be {@code null} — a platform with no audio returns one whose {@code play} is empty */
    CgSoundService sound();
    /** @return the cursor presentation service; must not be {@code null} — a platform that cannot show cursors returns one whose {@code setCursor} is empty */
    CgCursorService cursor();
}
