package com.crystalgraphics.platform.service;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgService;

/**
 * Ends the process at once, the way its host allows. A diagnostic run that should close itself (the network stress
 * run) halts through this rather than {@code Runtime.halt}, which FML before 1.13 traps when a mod calls it.
 *
 * <pre>{@code
 * CgProcessExit.halt(0);
 *
 * // a host whose loader traps a mod's exit, once
 * CgPlatform.provide(CgProcessExit.HOST, status -> FMLCommonHandler.instance().exitJava(status, true));
 * }</pre>
 *
 * <p>Nothing is saved: a world, options or a log still buffered are lost. Never for an ordinary shutdown.</p>
 */
public final class CgProcessExit {

    /** How a host ends the process. Unprovided: {@code Runtime.halt}, which every host but FML before 1.13 allows. */
    @FunctionalInterface
    public interface Host {

        void halt(int status);

        Host NONE = status -> Runtime.getRuntime().halt(status);
    }

    /** The slot a host fills. */
    public static final CgService<Host> HOST = CgService.of("crystalgraphics:process_exit", Host.NONE);

    private CgProcessExit() {
    }

    /** Ends the process now with {@code status}. */
    public static void halt(int status) {
        CgPlatform.get(HOST).halt(status);
    }
}
