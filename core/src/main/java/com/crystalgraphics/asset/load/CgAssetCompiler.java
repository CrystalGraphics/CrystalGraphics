package com.crystalgraphics.asset.load;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.object.value.CgUnits;

/**
 * Turns a kind's resolved, validated asset into the definition what plays it reads: one per kind, run on a worker
 * after the asset's hard dependencies have compiled. A kind with no compiler of its own compiles to its resolved asset.
 *
 * <pre>{@code
 * CgAssetCompiler.register(CgLookKinds.CURVE_KIND, (asset, deps) -> CgCurveDefinition.of(asset.resolved()));
 *
 * // A system reads the emitters it inlines through deps, each already compiled:
 * CgAssetCompiler.register(CgParticleKinds.SYSTEM, (asset, deps) -> {
 *     Object emitter = deps.definition(CgParticleKinds.EMITTER, "mymod:kamehameha/core");
 *     ...
 * });
 * }</pre>
 *
 * <ul>
 *   <li>Runs on a worker: read nothing but {@code asset} and {@code deps}, touch no GPU and no host.</li>
 *   <li>Throwing fails the asset with the exception's message; its referrers fail naming it.</li>
 *   <li>Register before the first load of its kind, at init.</li>
 * </ul>
 *
 * @param <D> the definition
 */
@FunctionalInterface
public interface CgAssetCompiler<D> {

    /** The compiler of a kind with none of its own: the resolved, validated asset is its definition. */
    CgAssetCompiler<CgAssetData> RESOLVED = (asset, deps) -> asset;

    /** {@code asset}'s definition. */
    D compile(CgAssetData asset, Dependencies deps);

    /** The compiled definitions of an asset's hard dependencies, and the units its values are written in. */
    @FunctionalInterface
    interface Dependencies {
        /** {@code kind}'s {@code id}, compiled; null if the asset holds no hard reference to it. */
        @Nullable
        Object definition(CgType kind, String id);

        /**
         * The units the asset's values are written in ({@code core.md} § Units): its {@code units:} over its namespace's
         * {@code units.yaml}. A compiler converts each value by its property's quantity: {@code units().toHost(v, p)}.
         */
        default CgUnits units() {
            return CgUnits.HOST;
        }
    }

    /** Sets {@code kind}'s compiler, replacing {@link #RESOLVED}. */
    static void register(CgType kind, CgAssetCompiler<?> compiler) {
        Compilers.BY_KIND.put(kind, compiler);
    }

    /** {@code kind}'s compiler: its own, else {@link #RESOLVED}. */
    static CgAssetCompiler<?> of(CgType kind) {
        CgAssetCompiler<?> c = Compilers.BY_KIND.get(kind);
        return c != null ? c : RESOLVED;
    }

    /** Holds the compilers by kind. */
    final class Compilers {
        static final Map<CgType, CgAssetCompiler<?>> BY_KIND = new ConcurrentHashMap<>();

        private Compilers() {
        }
    }
}
