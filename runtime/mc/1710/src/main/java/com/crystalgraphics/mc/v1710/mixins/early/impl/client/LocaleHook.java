package com.crystalgraphics.mc.v1710.mixins.early.impl.client;

import com.crystalgraphics.mc.v1710.platform.LangFiles1710;
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
 * reload and language change. FML merges its own injected entries after this, without overwriting.
 */
@Mixin(Locale.class)
public abstract class LocaleHook {

    @Shadow
    Map<String, String> field_135032_a;

    @Inject(method = "loadLocaleDataFiles", at = @At("TAIL"))
    private void crystalgraphics$langFiles(IResourceManager manager, List<String> languages, CallbackInfo ci) {
        LangFiles1710.merge(field_135032_a, manager, languages);
    }
}
