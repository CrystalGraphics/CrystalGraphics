package com.crystalgraphics.mc.legacy.mixin;

import com.crystalgraphics.mc.legacy.platform.LangFilesLegacy;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.Locale;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Map;

/**
 * Mods' {@code lang/<code>.json} into the locale's table once it has loaded the {@code .lang} files, at every resource
 * reload and language change: {@code loadLocaleDataFiles} and its {@code properties}, at their SRG names.
 */
@Mixin(value = Locale.class, remap = false)
public abstract class LocaleHook {

    @Shadow(remap = false)
    private Map<String, String> field_135032_a;

    @Inject(method = "func_135022_a", remap = false, require = 1, at = @At("TAIL"))
    private void crystalgraphics$langFiles(IResourceManager manager, List<String> languages, CallbackInfo ci) {
        LangFilesLegacy.merge(field_135032_a, manager, languages);
    }
}
