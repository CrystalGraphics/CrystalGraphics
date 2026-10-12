package com.crystalgraphics.platform.service;

import com.crystalgraphics.platform.CgService;

/**
 * Ends the process at once, the way its host allows. A diagnostic run that should close itself (the network stress
 * run) halts through this rather than {@code Runtime.halt}, which FML before 1.13 traps when a mod calls it.
 *
 * <pre>{@code
 * CgPlatform.get(CgProcessExit.SERVICE).halt(0);
 *
 * // A host whose loader traps a mod's exit, once:
 * CgPlatform.provide(CgProcessExit.SERVICE, status -> FMLCommonHandler.instance().exitJava(status, true));
 * }</pre>
 *
 * <p>Nothing is saved: a world, options or a log still buffered are lost. Never for an ordinary shutdown.</p>
 */
@FunctionalInterface
public interface CgProcessExit {

    /** {@code Runtime.halt}, which every host but FML before 1.13 lets a mod call. */
    CgProcessExit DIRECT = status -> Runtime.getRuntime().halt(status);

    CgService<CgProcessExit> SERVICE = CgService.of("crystalgraphics:process_exit", DIRECT);

    void halt(int status);
}
