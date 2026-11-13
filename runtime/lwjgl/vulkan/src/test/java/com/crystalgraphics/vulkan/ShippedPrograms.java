package com.crystalgraphics.vulkan;

import com.crystalgraphics.material.CgMaterialProxy;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgRenderPassVariant;
import com.crystalgraphics.material.CgMaterialShader;
import com.crystalgraphics.material.CgMaterialShaderRegistry;
import com.crystalgraphics.material.parse.CgMaterialShaderCompiler;
import com.crystalgraphics.material.parse.CgParsedPass;
import com.crystalgraphics.material.parse.CgParsedShader;

import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Every program the shipped materials make, built on whatever device is installed: each pass, its
 * keyword variants, the generated depth pass and the world renderer's volume forms. For the tests that prove every one
 * compiles on a device.
 */
public final class ShippedPrograms {

    private ShippedPrograms() {}

    /** One program of a shipped material: its pass by name and the keywords it is compiled with. */
    public interface Variant {
        void accept(String path, CgMaterialShader shader, String pass, Set<String> keywords);
    }

    /** Links every program; adds what failed to {@code failures} and answers how many it tried. */
    public static int linkEveryVariant(List<String> failures) throws Exception {
        return forEachVariant(failures, true, (path, shader, pass, keywords) -> {
            if (shader.getOrCompile(pass, keywords) == null)
                failures.add(path + " pass " + pass + " " + keywords + ": did not link (see log)");
        });
    }

    /**
     * Visits every program, as {@link #linkEveryVariant} links them; with {@code everySubset} false, a Forward pass's
     * keywords one at a time rather than every subset of them. Answers how many it visited.
     */
    public static int forEachVariant(List<String> failures, boolean everySubset, Variant each) throws Exception {
        List<String> materials = materials();
        int programs = 0;
        for (String path : materials) {
            CgMaterialShader shader = CgMaterialShaderRegistry.get().getOrCreate(path);
            if (shader.getLastParsed() == null || shader.hasCompileFailed()) shader.recompile();
            if (shader.hasCompileFailed()) {
                failures.add(shader.lastCompileError());
                continue;
            }
            CgParsedShader parsed = shader.getLastParsed();
            CgParsedPass forward = parsed.getPassByLightMode(CgRenderPassVariant.FORWARD.lightModeName());
            for (CgParsedPass pass : parsed.passes()) {
                List<Set<String>> variants = pass != forward ? List.of(Set.of())
                        : everySubset ? variants(parsed.featureNames()) : singles(parsed.featureNames());
                for (Set<String> keywords : variants) {
                    programs++;
                    each.accept(path, shader, pass.name(), keywords);
                }
            }
            String depth = CgRenderPassVariant.DEPTH.lightModeName();
            if (parsed.getPassByName(depth) == null && shader.hasCompiledPass(depth)) {   // the generated depth pass
                programs++;
                each.accept(path, shader, depth, Set.of());
            }
            if (forward != null && !forward.fragOutput().isMrt()) {   // the world renderer's volume pass, folded or not
                List<Set<String>> volumes = new ArrayList<>(List.of(Set.of(CgMaterialShaderCompiler.VOLUME_TARGET)));
                if (CgMaterialShaderCompiler.sceneFolds(parsed)) {
                    volumes.add(Set.of(CgMaterialShaderCompiler.VOLUME_TARGET, CgMaterialShaderCompiler.SCENE_FOLD));
                }
                for (Set<String> keywords : volumes) {
                    programs++;
                    each.accept(path, shader, forward.name(), keywords);
                }
            }
            CgMaterial material = CgMaterial.load(path);
            if (parsed.renderQueue() < 3000 && forward != null && !CgMaterialProxy.of(material).hasDepthPass()
                    && !shader.hasCompiledPass(CgRenderPassVariant.DEPTH.lightModeName()))
                failures.add(path + ": the generated depth pass did not link (see log)");
        }
        return programs;
    }

    public static List<String> materials() throws Exception {
        URI dir = CgMaterial.class.getResource("/assets/crystalgraphics/shaders/").toURI();
        try (FileSystem jar = "jar".equals(dir.getScheme()) ? FileSystems.newFileSystem(dir, Map.of()) : null;
             Stream<Path> files = Files.walk(Path.of(dir))) {
            Path root = Path.of(dir);
            return files.map(f -> root.relativize(f).toString().replace('\\', '/'))
                    .filter(n -> n.endsWith(".shader"))
                    .sorted().map(n -> "crystalgraphics:shaders/" + n).toList();
        }
    }

    /**
     * Every subset of a pass's keywords but the strip kinds; then each strip kind with the strip alone and with each
     * other keyword. A kind swaps only the vertex factory and excludes the other, so its every subset would quadruple
     * a particle shader's programs past the test's heap.
     */
    private static List<Set<String>> variants(List<String> names) {
        List<String> kinds = List.of(CgMaterialShaderCompiler.STRIP_BEAM, CgMaterialShaderCompiler.STRIP_ANIM);
        List<String> plain = new ArrayList<>(names);
        plain.removeAll(kinds);
        List<Set<String>> out = subsets(plain);
        if (!plain.contains(CgMaterialShaderCompiler.STRIP)) return out;
        for (String kind : kinds) {
            if (!names.contains(kind)) continue;
            out.add(new LinkedHashSet<>(List.of(CgMaterialShaderCompiler.STRIP, kind)));
            for (String other : plain) {
                if (!other.equals(CgMaterialShaderCompiler.STRIP)) {
                    out.add(new LinkedHashSet<>(List.of(CgMaterialShaderCompiler.STRIP, kind, other)));
                }
            }
        }
        return out;
    }

    /** None, then each keyword alone; a strip kind with the strip. */
    private static List<Set<String>> singles(List<String> names) {
        List<Set<String>> out = new ArrayList<>(List.of(Set.of()));
        for (String name : names) {
            boolean kind = name.equals(CgMaterialShaderCompiler.STRIP_BEAM) || name.equals(CgMaterialShaderCompiler.STRIP_ANIM);
            out.add(kind ? Set.of(CgMaterialShaderCompiler.STRIP, name) : Set.of(name));
        }
        return out;
    }

    private static List<Set<String>> subsets(List<String> names) {
        List<Set<String>> out = new ArrayList<>();
        for (int mask = 0; mask < 1 << names.size(); mask++) {
            Set<String> s = new LinkedHashSet<>();
            for (int i = 0; i < names.size(); i++) if ((mask & 1 << i) != 0) s.add(names.get(i));
            out.add(s);
        }
        return out;
    }
}
