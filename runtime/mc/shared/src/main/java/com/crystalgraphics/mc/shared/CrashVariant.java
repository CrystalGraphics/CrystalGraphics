package com.crystalgraphics.mc.shared;

import java.security.CodeSource;

/**
 * What a crash report has to say about WHICH variant of the merged jar was running.
 *
 * <p>One artifact carries a copy of the host for every loader, each relocated under its own prefix, so
 * a stack trace reads {@code com.crystalgraphics.mc.forge.common.PlatformServiceModern} — and "which variant is
 * that, on which loader, out of which jar" becomes the first question asked of every bug report. This
 * answers it in the crash report itself, where the reporter has already pasted it.</p>
 *
 * <p>Each loader registers it through its own mechanism, since there is no shared one:</p>
 *
 * <pre>{@code
 * // Forge 1.20.1 / NeoForge
 * CrashReportCallables.registerCrashCallable(CrashVariant.label("MyMod"),
 *         () -> CrashVariant.report(MyModClass.class));
 *
 * // 1.7.10
 * FMLCommonHandler.instance().registerCrashCallable(new ICrashCallable() {
 *     public String getLabel() { return CrashVariant.label("MyMod"); }
 *     public String call() { return CrashVariant.report(MyModClass.class); }
 * });
 * }</pre>
 *
 * <p>Pass a class from the LOADER module rather than a shared one: its package is the relocation
 * prefix, which is the whole point — a shared class has the same name in every variant and identifies
 * none of them.</p>
 */
public final class CrashVariant {

    /** CrystalGraphics' own heading. Any other mod passes its own name to {@link #label}. */
    public static final String LABEL = label("CrystalGraphics");

    private CrashVariant() {
    }

    /**
     * The heading a crash report shows one mod's variant under.
     *
     * <pre>{@code
     * CrashReportCallables.registerCrashCallable(CrashVariant.label("MyMod"),
     *         () -> CrashVariant.report(MyModClass.class));
     * }</pre>
     *
     * <p>One heading per mod: two mods registering under the same label print two sections a reader
     * cannot tell apart.</p>
     */
    public static String label(String modName) {
        return modName + " variant";
    }

    /**
     * A one-line description: the loader, how it was detected, the anchor's relocated name, its jar.
     *
     * <p>Never throws. A crash callable that dies while a crash report is being written replaces the
     * report's useful part with its own stack trace, so every step here degrades to saying less.</p>
     */
    public static String report(Class<?> anchor) {
        StringBuilder text = new StringBuilder();
        try {
            text.append(LoaderProbe.describe());
        } catch (Throwable unavailable) {
            text.append("loader unknown (").append(unavailable).append(')');
        }
        if (anchor != null) {
            text.append("; ").append(anchor.getName());
            String jar = jarOf(anchor);
            if (jar != null) text.append(" from ").append(jar);
        }
        return text.toString();
    }

    /**
     * The file name the class was loaded from, or null when the source cannot be read.
     *
     * <p>Trimmed to the file name because a modern loader hands back a whole URL: NeoForge's union
     * filesystem reports
     * {@code union:/C:/.../mods/crystalgraphics-1.0.0.jar%23180!/}, where the interesting part is four
     * characters of a hundred and eighty.</p>
     */
    private static String jarOf(Class<?> anchor) {
        try {
            CodeSource source = anchor.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) return null;
            String path = source.getLocation().toString();
            // A jar URL ends `!/`, and a union one appends `%23<index>` to the name.
            while (path.endsWith("/") || path.endsWith("!")) path = path.substring(0, path.length() - 1);
            int lastSlash = path.lastIndexOf('/');
            String name = lastSlash < 0 ? path : path.substring(lastSlash + 1);
            int member = name.indexOf("%23");
            return member < 0 ? name : name.substring(0, member);
        } catch (Throwable notPermitted) {
            // A security manager, or a classloader that reports no source at all -- neither is worth
            // failing a crash report over.
            return null;
        }
    }
}
