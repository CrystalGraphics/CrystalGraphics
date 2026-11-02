package com.crystalgraphics.mc.compat;

import javax.annotation.Nullable;

import java.lang.reflect.Method;

/**
 * What the shader mod says, when there is one: whether a pack is in use, and whether its shadow pass is drawing now.
 * Asked through Iris's public API ({@code net.irisshaders.iris.api.v0.IrisApi}), which Iris on Fabric and NeoForge and
 * Oculus on Forge both ship under that name; mods older than it fall back to their {@code Iris.isPackInUseQuick}.
 * With no shader mod, both answer false.
 *
 * <pre>{@code
 * if (CgIrisCompat.isShaderPackActive()) {
 *     body.cpuSkin(slot, ownerView, sink);          // through the host's entity render type: the pack draws it
 * } else {
 *     world.draw(body);                             // our own skinned draw
 * }
 * boolean shadow = CgIrisCompat.isRenderingShadowPass();   // inside an entity renderer the pack calls again for shadows
 * }</pre>
 *
 * <ul>
 *   <li>Lookups are made once, at class load; a call is one reflective invoke.</li>
 *   <li>A mod whose API throws answers false, never fails the frame.</li>
 * </ul>
 */
public final class CgIrisCompat {
    private CgIrisCompat() {}

    private static final String API_TYPE = "net.irisshaders.iris.api.v0.IrisApi";
    @Nullable
    private static final Object API = api();
    // From the interface: the instance's own class need not be public.
    @Nullable
    private static final Method API_PACK_IN_USE = API == null ? null : publicMethod(API_TYPE, "isShaderPackInUse");
    @Nullable
    private static final Method API_SHADOW_PASS = API == null ? null : publicMethod(API_TYPE, "isRenderingShadowPass");
    // Before the public API: Iris and Angelica's own class, then Oculus's older package.
    @Nullable
    private static final Method IRIS_IS_PACK_IN_USE = publicMethod("net.irisshaders.iris.Iris", "isPackInUseQuick");
    @Nullable
    private static final Method OCULUS_IS_PACK_IN_USE = publicMethod("net.coderbot.iris.Iris", "isPackInUseQuick");

    /** Whether a shader pack is in use now. */
    public static boolean isShaderPackActive() {
        if (API_PACK_IN_USE != null) return invoke(API_PACK_IN_USE, API);
        return invoke(IRIS_IS_PACK_IN_USE, null) || invoke(OCULUS_IS_PACK_IN_USE, null);
    }

    /** Whether the pack's shadow pass is drawing now: an entity renderer called inside it is drawing into the shadow map. */
    public static boolean isRenderingShadowPass() {
        return API_SHADOW_PASS != null && invoke(API_SHADOW_PASS, API);
    }

    @Nullable
    private static Object api() {
        try {
            return Class.forName(API_TYPE).getMethod("getInstance").invoke(null);
        } catch (Throwable e) {
            return null;
        }
    }

    /** {@code className}'s public method {@code name} with no parameters, static or not; null if absent. */
    @Nullable
    private static Method publicMethod(String className, String name) {
        try {
            return Class.forName(className).getMethod(name);
        } catch (Throwable e) {
            return null;
        }
    }

    private static boolean invoke(@Nullable Method method, @Nullable Object target) {
        if (method == null) return false;
        try {
            return (boolean) method.invoke(target);
        } catch (Throwable e) {
            return false;
        }
    }
}
