package com.crystalgraphics.mc.legacy.platform;

import com.crystalgraphics.i18n.CgLangFiles;

import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.util.ResourceLocation;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Every mod's {@code lang/<code>.json} on Forge 1.8-1.12.2, which reads only {@code .lang}: merged into the locale's
 * table each time it loads ({@code LocaleHook}), through the resource manager so resource packs count.
 */
public final class LangFilesLegacy {

    private LangFilesLegacy() {
    }

    /** After {@code Locale} loaded {@code languages}' {@code .lang} files from {@code manager} into {@code table}. */
    public static void merge(Map<String, String> table, IResourceManager manager, List<String> languages) {
        CgLangFiles.mergeInto(table, languages, manager.getResourceDomains(), (namespace, path) -> {
            List<InputStream> files = new ArrayList<>();
            for (IResource resource : manager.getAllResources(new ResourceLocation(namespace, path))) {
                files.add(resource.getInputStream());
            }
            return files;
        });
    }
}
