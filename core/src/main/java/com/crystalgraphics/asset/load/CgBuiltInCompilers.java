package com.crystalgraphics.asset.load;

import com.crystalgraphics.api.material.CgMaterialDefinitions;
import com.crystalgraphics.asset.camera.CgCameraRigDefinition;
import com.crystalgraphics.asset.curve.CgCurveDefinition;
import com.crystalgraphics.sequence.CgSequenceDefinition;

/**
 * Registers the engine's own kind compilers: materials, curves, camera rigs and sequences. Runs on both sides,
 * when the process's assets are made ({@link CgVfxAssets}), so a dedicated server compiles what a client does.
 *
 * <pre>{@code
 * CgBuiltInCompilers.register();                         // idempotent; CgVfxAssets calls it
 * CgAssetCompiler.of(CgLookKinds.CURVE_KIND);            // CgCurveDefinition.COMPILER
 * }</pre>
 *
 * <ul>
 *   <li>Systems register in {@code CgVfx.install}, since they compile to client playables.</li>
 *   <li>Calling it again re-registers the same compilers: harmless.</li>
 * </ul>
 */
public final class CgBuiltInCompilers {

    private CgBuiltInCompilers() {
    }

    /** Each built-in kind's compiler, replacing {@link CgAssetCompiler#RESOLVED}. */
    public static void register() {
        CgMaterialDefinitions.register();
        CgCurveDefinition.register();
        CgCameraRigDefinition.register();
        CgSequenceDefinition.register();
    }
}
