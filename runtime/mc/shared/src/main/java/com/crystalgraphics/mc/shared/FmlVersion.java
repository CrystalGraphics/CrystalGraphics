package com.crystalgraphics.mc.shared;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * The Minecraft version, from an FML-family loader, across every shape the lookup has had.
 *
 * <pre>
 * String minecraft = FmlVersion.of(FMLLoader.class);   // Forge or NeoForge alike
 * </pre>
 *
 * <p><b>Reflection is the point, not a shortcut.</b> One compiled bootstrapper serves a whole loader
 * family, and inside that family the call moved: {@code FMLLoader.getCurrent().getVersionInfo()} from
 * FML 10 (NeoForge 21.9), {@code FMLLoader.versionInfo().mcVersion()} from 1.17,
 * {@code FMLLoader.mcVersion()} before that, and on Forge 31 (1.15.2) only a package-private static
 * field of the same name. A direct call picks one and fails on the rest of the range — and the newer one returns a <i>record</i>, which a class compiled to Java 8
 * cannot even name, since {@code java.lang.Record} is not in that API.</p>
 *
 * <p>Both shapes return a plain {@link String}, so nothing beyond the call site is reflective.</p>
 */
public final class FmlVersion {

    private FmlVersion() {
    }

    public static String of(Class<?> fmlLoader) {
        try {
            // FML 10 (NeoForge 21.9) moved it onto the loader instance.
            Method current = method(fmlLoader, "getCurrent");
            if (current != null) {
                Object loader = current.invoke(null);
                Object info = loader.getClass().getMethod("getVersionInfo").invoke(loader);
                return String.valueOf(info.getClass().getMethod("mcVersion").invoke(info));
            }
            Method versionInfo = method(fmlLoader, "versionInfo");
            if (versionInfo != null) {
                Object info = versionInfo.invoke(null);
                if (info != null) {
                    // The accessor is read off the returned object, so the record type is never named.
                    return String.valueOf(info.getClass().getMethod("mcVersion").invoke(info));
                }
            }
            Method direct = method(fmlLoader, "mcVersion");
            if (direct != null) {
                return String.valueOf(direct.invoke(null));
            }
            Field field = field(fmlLoader, "mcVersion");
            if (field != null) {
                return String.valueOf(field.get(null));
            }
        } catch (Exception e) {
            throw new UnsupportedVariant(
                    "could not read the Minecraft version from " + fmlLoader.getName() + ": " + e);
        }
        throw new UnsupportedVariant(fmlLoader.getName()
                + " has neither versionInfo() nor mcVersion; this loader is newer than this jar's"
                + " bootstrapper knows how to ask");
    }

    private static Field field(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException absent) {
            return null;
        }
    }

    private static Method method(Class<?> owner, String name) {
        try {
            return owner.getMethod(name);
        } catch (NoSuchMethodException absent) {
            return null;
        }
    }
}
