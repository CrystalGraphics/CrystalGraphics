package com.crystalgraphics.mc.shared;

import java.lang.reflect.Method;

/**
 * The Minecraft version, from an FML-family loader, across both shapes the lookup has had.
 *
 * <pre>
 * String minecraft = FmlVersion.of(FMLLoader.class);   // Forge or NeoForge alike
 * </pre>
 *
 * <p><b>Reflection is the point, not a shortcut.</b> One compiled bootstrapper serves a whole loader
 * family, and inside that family the call moved: {@code FMLLoader.versionInfo().mcVersion()} from
 * 1.17, {@code FMLLoader.mcVersion()} on 1.13–1.16. A direct call picks one and fails on the other
 * half of the range — and the newer one returns a <i>record</i>, which a class compiled to Java 8
 * cannot even name, since {@code java.lang.Record} is not in that API.</p>
 *
 * <p>Both shapes return a plain {@link String}, so nothing beyond the call site is reflective.</p>
 */
public final class FmlVersion {

    private FmlVersion() {
    }

    public static String of(Class<?> fmlLoader) {
        try {
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
        } catch (Exception e) {
            throw new UnsupportedVariant(
                    "could not read the Minecraft version from " + fmlLoader.getName() + ": " + e);
        }
        throw new UnsupportedVariant(fmlLoader.getName()
                + " has neither versionInfo() nor mcVersion(); this loader is newer than this jar's"
                + " bootstrapper knows how to ask");
    }

    private static Method method(Class<?> owner, String name) {
        try {
            return owner.getMethod(name);
        } catch (NoSuchMethodException absent) {
            return null;
        }
    }
}
