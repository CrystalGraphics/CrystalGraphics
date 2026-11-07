package com.crystalgraphics.platform.service;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgService;

import java.lang.reflect.Method;

/**
 * The shader-pack mod the game runs, if any: which one, whether a pack is on, and whether its shadow pass is drawing
 * now. Ask it anywhere on a client; a host answers it through {@link Host}. The engine feeds a pack its scene objects
 * (the opaque and transparent passes' meshes and roles), never its VFX, so this is what decides how they are drawn.
 *
 * <pre>{@code
 * if (CgShaderPacks.packInUse()) {
 *     body.cpuSkin(slot, ownerView, sink);                  // through the host's render type: the pack lights it
 * } else {
 *     world.draw(body);                                     // the engine's own draw
 * }
 * if (CgShaderPacks.renderingShadowPass()) return;          // inside a renderer the pack calls again for its shadows
 * LOGGER.info("shader mod: {}", CgShaderPacks.mod());       // "iris", "oculus", "angelica" or "none"
 * }</pre>
 *
 * A host, once, as it registers the platform, where its era has a shader mod:
 *
 * <pre>{@code
 * CgPlatform.provide(CgShaderPacks.HOST, CgShaderPacks.irisApi());   // Iris and Oculus (modern), Angelica (1.7.10)
 * }</pre>
 *
 * <ul>
 *   <li>Iris, Oculus and Angelica all ship Iris's public API ({@code net.irisshaders.iris.api.v0.IrisApi}), which
 *       {@link #irisApi()} reads; it answers {@link Host#NONE}'s values where none of them is installed, as on a
 *       dedicated server.</li>
 *   <li>Without a host (legacy Forge, the harness, a test) no pack is ever in use.</li>
 *   <li>Both answers change at runtime (a player turns a pack on): ask each frame, never cache them.</li>
 * </ul>
 */
public final class CgShaderPacks {

    /** What a host answers. */
    public interface Host {
        /** The shader mod: {@code "iris"}, {@code "oculus"}, {@code "angelica"}; {@code "none"} without one. */
        String mod();

        /** Whether a shader pack is in use now. */
        boolean packInUse();

        /** Whether the pack's shadow pass is drawing now: a renderer called inside it draws into the shadow map. */
        boolean renderingShadowPass();

        Host NONE = new Host() {
            @Override public String mod() { return "none"; }
            @Override public boolean packInUse() { return false; }
            @Override public boolean renderingShadowPass() { return false; }
        };
    }

    /** The slot a host fills. */
    public static final CgService<Host> HOST = CgService.of("crystalgraphics:shader_packs", Host.NONE);

    private CgShaderPacks() {
    }

    private static Host host() {
        return CgPlatform.get(HOST);
    }

    /** The shader mod the game runs: {@code "iris"}, {@code "oculus"}, {@code "angelica"}, or {@code "none"}. */
    public static String mod() {
        return host().mod();
    }

    /** Whether a shader pack is in use now. */
    public static boolean packInUse() {
        return host().packInUse();
    }

    /** Whether the pack's shadow pass is drawing now. */
    public static boolean renderingShadowPass() {
        return host().renderingShadowPass();
    }

    /**
     * The host over Iris's public API, which Iris, Oculus and Angelica ship under one name: found at its first answer,
     * so a host may provide it before the shader mod has loaded. Names no game type.
     */
    public static Host irisApi() {
        return new IrisApiHost();
    }

    /** Iris's {@code IrisApi} v0, read by reflection: the engine never compiles against a shader mod. */
    private static final class IrisApiHost implements Host {
        private static final String API = "net.irisshaders.iris.api.v0.IrisApi";

        // Written before resolved, which publishes them; a race resolves twice, to the same answer
        private volatile boolean resolved;
        private Object api;
        private Method packInUse, shadowPass;
        private String mod = "none";

        private void resolve() {
            if (resolved) return;
            try {
                Class<?> type = Class.forName(API);
                // From the interface: the instance's own class need not be public
                packInUse = type.getMethod("isShaderPackInUse");
                shadowPass = type.getMethod("isRenderingShadowPass");
                api = type.getMethod("getInstance").invoke(null);
                mod = CgModLoader.isLoaded("oculus") ? "oculus" : CgModLoader.isLoaded("angelica") ? "angelica" : "iris";
            } catch (Throwable absent) {
                api = null;
            }
            resolved = true;
        }

        @Override
        public String mod() {
            resolve();
            return mod;
        }

        @Override
        public boolean packInUse() {
            resolve();
            return ask(packInUse);
        }

        @Override
        public boolean renderingShadowPass() {
            resolve();
            return ask(shadowPass);
        }

        private boolean ask(Method method) {
            if (api == null || method == null) return false;
            try {
                return (boolean) method.invoke(api);
            } catch (Throwable e) {
                return false;
            }
        }
    }
}
