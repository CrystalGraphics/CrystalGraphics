package com.crystalgraphics.platform;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * <b>A service slot a platform may fill — the open half of {@link CgPlatform}.</b>
 *
 * <p>{@link CgPlatformService} is the <em>closed</em> half: nine methods, no defaults, so the compiler
 * forces a new loader to answer every one of them. That enforcement is the whole reason it is a bundle,
 * and it only works for services this project owns. A consumer of CrystalGraphics — CrystalGUI, or
 * anything else — has services of its own that the framework must not name, and until this class existed
 * the only way to have one was a second static registry beside `CgPlatform`.</p>
 *
 * <h3>Two registries is the failure this exists to remove</h3>
 *
 * <p>It has been paid for once already. `CrystalGuiCore` used to hold four static fields with setters,
 * and a loader had to find both registries — which meant it could wire up one and leave a UI with a
 * working GL backend and a dead keyboard, with nothing to report it. That is why every platform seam
 * moved here. A downstream mod declaring its own registry rebuilds the same hazard one layer out.</p>
 *
 * <h3>Why a slot object rather than a {@code Map<Class<?>, Object>}</h3>
 *
 * <p>The obvious shape is {@code provide(Key.class, impl)} / {@code find(Key.class)} returning an
 * {@code Optional}. It works, and it puts the <b>absent-value at every call site</b> — so N consumers
 * each write their own {@code .orElse(...)} and are free to disagree about what absence means. The
 * fallback is part of the contract, so it belongs with the contract, stated once:</p>
 *
 * <pre>{@code
 * // declared once, by the module that owns the contract
 * public static final CgService<ScriptService> SERVICE =
 *         CgService.of("crystalgui:script-platform", ScriptService.NONE);
 *
 * // filled by a loader, and read by anyone -- both through the one registry
 * CgPlatform.provide(ScriptServices.SERVICE, new Mc1710ScriptService());
 * CgPlatform.get(ScriptServices.SERVICE).liveBytes();
 * }</pre>
 *
 * <p>Three things then collapse into one object. <b>Declaring a slot is expecting it</b>, so there is no
 * separate registration of "somebody wants this" that can drift from the code that reads it. The value
 * arrives typed, so no cast and no {@code Optional} reaches a consumer. And {@link #declared()} can print
 * the whole platform stack, which nothing could answer before.</p>
 *
 * <h3>Absence announces itself, once, at first use</h3>
 *
 * <p>Not at a lifecycle checkpoint, which was the first design and is worse in a way that matters: a slot
 * only exists once its declaring class has loaded, so a checkpoint would silently skip exactly the
 * service nobody had touched — the case worth reporting. Announcing from {@link #get()} instead cannot
 * fire for a service nobody uses, lands at the moment the absence has a consequence, and costs a field
 * and a branch rather than an API.</p>
 *
 * <p>This is the same rule the rendering side already follows: <em>a capability that can be silently
 * skipped must say it is on</em>. "Live" and "inert" are indistinguishable from behaviour alone for every
 * consumer whose work happens to not need the missing piece — which is most of them, most of the
 * time.</p>
 *
 * <h3>What this is not</h3>
 *
 * <p><b>Not a replacement for the nine.</b> Migrating them here would trade compiler enforcement for
 * uniformity, and the enforcement is worth more: a graceful absent-value is precisely what
 * {@code CgPlatformService} must not have. Closed bundle for what the framework requires; slots for what
 * its consumers require.</p>
 *
 * <p><b>Not {@code ServiceLoader}.</b> Discovery through the thread context loader is hazardous in this
 * process — an engine classloader on the thread makes every {@code ServiceLoader} in the application
 * resolve against the wrong classpath — and it would lose the explicit statement of who provided what,
 * which is the thing that makes the stack printable.</p>
 *
 * @param <T> the service contract, declared by whoever owns it and never named here
 */
public final class CgService<T> {

    /** Every slot declared so far. Append-only; read for {@link #declared()}. */
    private static final List<CgService<?>> DECLARED = new CopyOnWriteArrayList<CgService<?>>();

    private final String name;
    private final T whenAbsent;

    private volatile T provided;
    /** Whether the absence has already been said out loud. @see #get() */
    private volatile boolean announced;

    private CgService(String name, T whenAbsent) {
        this.name = name;
        this.whenAbsent = whenAbsent;
    }

    /**
     * Declares a slot.
     *
     * @param name       a stable identifier for logs — {@code "namespace:service"} by convention
     * @param whenAbsent what {@link #get()} answers until something provides one. <b>Never null</b>: a
     *                   slot whose absent-value is null is a slot every consumer must null-check, which
     *                   is the call-site branching this class exists to remove. A contract with no
     *                   sensible do-nothing value is one that belongs in {@link CgPlatformService}, where
     *                   the compiler will insist on it
     */
    public static <T> CgService<T> of(String name, T whenAbsent) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("a service slot needs a name; it is what a log line says");
        }
        if (whenAbsent == null) {
            throw new IllegalArgumentException(
                    "a service slot needs an absent-value, and null is not one: " + name);
        }
        CgService<T> slot = new CgService<T>(name, whenAbsent);
        DECLARED.add(slot);
        return slot;
    }

    /**
     * The provided implementation, or the absent-value. Never null.
     *
     * <p><b>Package-private, and that is the design.</b> A slot is <em>declared</em> by whoever owns the
     * contract and <em>filled and read</em> through {@link CgPlatform} — so there is exactly one public
     * way to reach a platform service, and registering one looks like what it is: going into the
     * platform stack rather than into a static somewhere downstream. Same package, so the façade can
     * reach these; nothing else can.</p>
     */
    T get() {
        T current = provided;
        if (current != null) return current;
        if (!announced) {
            // ONCE, and only because somebody actually asked. A slot nobody reads says nothing.
            announced = true;
            System.err.println("[crystalgraphics] platform service '" + name
                    + "' was not provided; using its absent-value ("
                    + whenAbsent.getClass().getName() + ")");
        }
        return whenAbsent;
    }

    /** Whether a real implementation is installed. @see CgPlatform#isProvided */
    boolean isProvided() {
        return provided != null;
    }

    /**
     * Installs an implementation, or clears it when given null.
     *
     * <p>Last write wins, deliberately. Ordering between a loader and the framework is not guaranteed —
     * two mods initialise in whatever order the loader chooses — so a slot that refused a second write
     * would make correctness depend on that order. A replacement is announced, because silently swapping
     * a platform service under a running application is the kind of thing worth seeing in a log.</p>
     */
    void provide(T implementation) {
        T previous = provided;
        provided = implementation;
        if (implementation == null) {
            announced = false;
            return;
        }
        // Said on the way in, which is the other half of the rule: a capability that IS on should be
        // legible in a log without anybody having to reproduce a symptom to find out.
        System.err.println("[crystalgraphics] platform service '" + name + "' provided by "
                + implementation.getClass().getName()
                + (previous == null ? "" : " (replacing " + previous.getClass().getName() + ")"));
        announced = false;
    }

    /** Back to absent — {@code CgPlatform.provide(slot, null)}. Tests must not leak a platform. */
    void reset() {
        provided = null;
        announced = false;
    }

    /** This slot's identifier — what a log line and {@link #declared()} call it. */
    public String name() {
        return name;
    }

    /**
     * Every slot declared so far, in declaration order.
     *
     * <p>The answer to "what is this platform actually carrying", which nothing could give before. Note
     * what it cannot tell you: a slot whose declaring class has not loaded is not in this list, so an
     * empty answer means "nothing has been asked for yet" rather than "nothing is installed". That is
     * also why absence is reported from {@link #get()} rather than from a sweep over this list.</p>
     */
    public static List<CgService<?>> declared() {
        return Collections.unmodifiableList(new ArrayList<CgService<?>>(DECLARED));
    }

    @Override
    public String toString() {
        T current = provided;
        return name + (current == null ? " (absent)" : " -> " + current.getClass().getName());
    }
}
