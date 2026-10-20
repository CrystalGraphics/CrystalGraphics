package com.crystalgraphics.asset.tree;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.crystalgraphics.object.CgPropertyPath;
import com.crystalgraphics.object.validate.CgValidation;

/**
 * A check registered from outside an asset's types, run on every asset it accepts: Unreal's
 * {@code UEditorValidatorBase}. Where a type's own {@code validator(...)} checks its values, this checks whole assets
 * of any kind, so a mod can hold another mod's assets to its own rules.
 *
 * <pre>{@code
 * CgAssetValidator.register(new CgAssetValidator() {
 *     public boolean canValidate(CgValidation.Usecase usecase) {
 *         return usecase == CgValidation.Usecase.COOK;             // only a cook pays for it
 *     }
 *
 *     public boolean canValidate(CgAssetData asset) {
 *         return asset.kind() == CgParticleKinds.SYSTEM;
 *     }
 *
 *     public void validate(CgAssetData asset, CgValidation into) {
 *         Object emitters = asset.resolved().get("emitters");
 *         if (emitters instanceof List && ((List<?>) emitters).size() > 32) {
 *             into.warning(CgPropertyPath.of(asset.id()).key("emitters"), "many_emitters", "more than 32 emitters");
 *         }
 *     }
 * });
 * }</pre>
 *
 * <ul>
 *   <li>It runs only on an asset its own checks found valid: an asset already failing is not checked further.</li>
 *   <li>Register at init, before the first load; it then runs on every validation, from any thread.</li>
 *   <li>Report under the asset's paths ({@link CgPropertyPath#of}), so an editor can select what an issue names.</li>
 * </ul>
 */
public interface CgAssetValidator {

    /** Whether it runs for {@code usecase}: a costly check may skip loads and run at cook. */
    default boolean canValidate(CgValidation.Usecase usecase) {
        return true;
    }

    /** Whether {@code asset} is one it checks. */
    boolean canValidate(CgAssetData asset);

    /** Checks {@code asset}, resolved, reporting into {@code into}. */
    void validate(CgAssetData asset, CgValidation into);

    /** Adds {@code validator}, run after every asset's own checks; run the answer to remove it. */
    static Runnable register(CgAssetValidator validator) {
        Registered.ALL.add(validator);
        return () -> Registered.ALL.remove(validator);
    }

    /** Every validator registered, in registration order. */
    static List<CgAssetValidator> all() {
        return Collections.unmodifiableList(Registered.ALL);
    }

    /** Holds the registered validators. */
    final class Registered {
        static final List<CgAssetValidator> ALL = new CopyOnWriteArrayList<>();

        private Registered() {
        }
    }
}
