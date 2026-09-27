package com.crystalgraphics.mc.shared;

/**
 * Whether an FML-family loader is running a client, across both shapes the answer has had.
 *
 * <pre>
 * if (FmlSide.isClient(FMLLoader.class)) VariantBootstrap.startClient(...);
 * </pre>
 *
 * <p>For a bootstrapper, which is one copy for every node of its loader: {@code FMLEnvironment.dist}
 * through FML 9, {@code FMLLoader.getCurrent().getDist()} from FML 10 (NeoForge 21.9). A direct call
 * compiles against one and fails on the other.</p>
 */
public final class FmlSide {

    private FmlSide() {
    }

    /** @param fmlLoader the loader's {@code FMLLoader}; {@code FMLEnvironment} is its package sibling */
    public static boolean isClient(Class<?> fmlLoader) {
        try {
            Object dist;
            try {
                Object loader = fmlLoader.getMethod("getCurrent").invoke(null);
                dist = loader.getClass().getMethod("getDist").invoke(loader);
            } catch (NoSuchMethodException beforeFml10) {
                String environment = fmlLoader.getPackage().getName() + ".FMLEnvironment";
                dist = Class.forName(environment, true, fmlLoader.getClassLoader()).getField("dist").get(null);
            }
            return "CLIENT".equals(String.valueOf(dist));
        } catch (Exception e) {
            throw new UnsupportedVariant("could not read the side from " + fmlLoader.getName() + ": " + e);
        }
    }
}
