package com.crystalgraphics.util.io;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The source resource folders of a dev run, found from what its launcher passed: what {@link CgIO} reads ahead of the
 * classpath when {@code crystalgraphics.resourceOverrideDirs} is unset, so an edited asset is seen (and hot reloaded)
 * with no setup in the consuming project. A production client loads every mod from a jar, so it finds none.
 *
 * <pre>{@code
 * List<File> roots = CgDevResourceRoots.discover(System::getProperty, System::getenv);
 * // ModDevGradle's run of a mod built in X:/mods/dragon:  [X:/mods/dragon/src/main/resources]
 * }</pre>
 *
 * <p>Read, in order: {@code fml.modFolders} (ModDevGradle), {@code MOD_CLASSES} (ForgeGradle and ModDevGradle's
 * legacy Forge), {@code fabric.classPathGroups} (Loom), {@code java.class.path} and {@code jdk.module.path}
 * (RetroFuturaGradle, the harness, tests). Each folder there is mapped to the source folder it was copied from:</p>
 *
 * <ul>
 *   <li>{@code <project>/build/resources/<set>} to {@code <project>/src/<set>/resources} (Gradle)</li>
 *   <li>{@code <project>/bin/<set>} to {@code <project>/src/<set>/resources} (Eclipse, VS Code)</li>
 *   <li>{@code <project>/out/production/resources} to {@code <project>/src/main/resources} (IntelliJ's own build)</li>
 *   <li>{@code <project>/build/devlibs/*.jar} and {@code build/libs/*.jar} to {@code <project>/src/main/resources}:
 *       an included build's mod, run from its jar</li>
 * </ul>
 *
 * <p>A source folder counts only when it holds {@code assets/}. Class folders and other jars map to nothing.</p>
 */
public final class CgDevResourceRoots {

    private static final String[] PATH_PROPERTIES = {"fml.modFolders", "fabric.classPathGroups", "java.class.path",
            "jdk.module.path"};

    private CgDevResourceRoots() {}

    /** The source resource folders holding {@code assets/}, in the order found, each once. */
    public static List<File> discover(Function<String, String> properties, Function<String, String> environment) {
        List<String> entries = new ArrayList<>();
        split(properties.apply(PATH_PROPERTIES[0]), entries);
        split(environment.apply("MOD_CLASSES"), entries);
        for (int i = 1; i < PATH_PROPERTIES.length; i++) split(properties.apply(PATH_PROPERTIES[i]), entries);

        Set<File> roots = new LinkedHashSet<>();
        for (String entry : entries) {
            File source = sourceOf(new File(entry));
            if (source != null && new File(source, "assets").isDirectory()) roots.add(canonical(source));
        }
        return new ArrayList<>(roots);
    }

    /** The folder {@code output} was copied from, by its build tool's layout; null when it follows none. */
    static File sourceOf(File output) {
        File dir = output.getAbsoluteFile();
        File parent = dir.getParentFile();
        File grand = parent != null ? parent.getParentFile() : null;
        if (grand == null) return null;
        String name = dir.getName(), parentName = parent.getName(), grandName = grand.getName();
        if (name.endsWith(".jar")) {
            // An included build's jar: ModDevGradle's devlibs, or a plain jar task's libs.
            boolean built = grandName.equals("build") && (parentName.equals("devlibs") || parentName.equals("libs"));
            return built ? source(grand.getParentFile(), "main") : null;
        }
        if (parentName.equals("resources") && grandName.equals("build")) return source(grand.getParentFile(), name);
        if (parentName.equals("bin")) return source(grand, name);
        if (name.equals("resources") && parentName.equals("production") && grandName.equals("out")) {
            return source(grand.getParentFile(), "main");
        }
        return null;
    }

    private static File source(File project, String set) {
        return project == null ? null : new File(new File(new File(project, "src"), set), "resources");
    }

    /** Entries of a path list; ModDevGradle and ForgeGradle prefix each with {@code modid%%}. */
    private static void split(String list, List<String> into) {
        if (list == null || list.isEmpty()) return;
        for (String part : list.split(Pattern.quote(File.pathSeparator))) {
            String entry = part.trim();
            int mod = entry.indexOf("%%");
            if (mod >= 0) entry = entry.substring(mod + 2);
            if (!entry.isEmpty()) into.add(entry);
        }
    }

    private static File canonical(File file) {
        try {
            return file.getCanonicalFile();
        } catch (Exception e) {
            return file.getAbsoluteFile();
        }
    }
}
