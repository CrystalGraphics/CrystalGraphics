package com.crystalgraphics.mc.v1710.platform;

import com.crystalgraphics.i18n.CgLangFiles;

import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.util.ResourceLocation;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Every mod's {@code lang/<code>.json} on 1.7.10, which reads only {@code .lang}: merged into the locale's table each
 * time it loads ({@code LocaleHook}), through the resource manager so resource packs count.
 */
public final class LangFiles1710 {

    private LangFiles1710() {
    }

    /** After {@code Locale} loaded {@code languages}' {@code .lang} files from {@code manager} into {@code table}. */
    @SuppressWarnings("unchecked")   // 1.7.10's resource manager is raw
    public static void merge(Map<String, String> table, IResourceManager manager, List<String> languages) {
        CgLangFiles.mergeInto(table, languages, (Iterable<String>) manager.getResourceDomains(), (namespace, path) -> {
            List<InputStream> files = new ArrayList<>();
            for (Object resource : manager.getAllResources(new ResourceLocation(namespace, path))) {
                files.add(((IResource) resource).getInputStream());
            }
            return files;
        });
    }
}
