package com.crystalgraphics.mc.compat;

import java.lang.reflect.Method;

/**
 * Fail-open Iris/Oculus shader pack detection.
 *
 * <p>When an Iris or Oculus shader pack is active, CG renders outside Iris's deferred
 * GBuffer chain. CG geometry will appear unlit under shader packs with deferred pipelines.
 * The depth texture IS shared, so {@code cg_DepthBuffer} works correctly regardless.</p>
 *
 * <p>Covers all supported loaders: Iris on Fabric (1.20.x), Oculus on Forge/NeoForge (1.20.x),
 * and Angelica on Forge (1.7.10, which ships Iris as its shader pipeline).</p>
 *
 * <p>Detection is fail-open: if no compatible shader mod is on the classpath,
 * returns {@code false} without throwing. Class and method lookups are cached at
 * class-init time so per-frame calls incur no repeated reflection overhead.</p>
 */
public final class CgIrisCompat {
    private CgIrisCompat() {}

    private static final Method IRIS_IS_PACK_IN_USE = resolveMethod("net.irisshaders.iris.Iris", "isPackInUseQuick");
    private static final Method OCULUS_IS_PACK_IN_USE = resolveMethod("net.coderbot.iris.Iris", "isPackInUseQuick");

    /** Returns true if Iris (Fabric / Angelica 1.7.10) has an active shader pack. */
    public static boolean isIrisPackActive() {
        return invoke(IRIS_IS_PACK_IN_USE);
    }

    /** Returns true if Oculus (Forge/NeoForge port of Iris) has an active shader pack. */
    public static boolean isOculusPackActive() {
        return invoke(OCULUS_IS_PACK_IN_USE);
    }

    /** Returns true if any compatible shader mod has an active pack. */
    public static boolean isShaderPackActive() {
        return isIrisPackActive() || isOculusPackActive();
    }

    private static Method resolveMethod(String className, String methodName) {
        try {
            return Class.forName(className).getMethod(methodName);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean invoke(Method method) {
        if (method == null) return false;
        try {
            return (boolean) method.invoke(null);
        } catch (Exception e) {
            return false;
        }
    }
}
