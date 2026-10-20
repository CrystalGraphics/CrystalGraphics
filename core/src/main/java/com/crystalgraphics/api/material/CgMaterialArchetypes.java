package com.crystalgraphics.api.material;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.crystalgraphics.gl.material.parse.CgParsedShader;
import com.crystalgraphics.gl.material.parse.CgShaderParser;
import com.crystalgraphics.util.io.CgIO;

/**
 * Where a material definition finds its archetype: the {@code .shader} its root names, parsed, and the domain its tags
 * name. GL-free, so a worker compiling definitions may call it.
 *
 * <pre>{@code
 * CgMaterialArchetypes.FILES.parse("mymod:shaders/fx.shader");             // read through CgIO, parsed once
 * CgMaterialDefinitions.archetypes(path -> CgShaderParser.parse(SOURCES.get(path), path));   // a test's own
 * }</pre>
 */
@FunctionalInterface
public interface CgMaterialArchetypes {

    /** The engine's: each path read through {@link CgIO} and parsed once. */
    CgMaterialArchetypes FILES = new CgMaterialArchetypes() {
        private final Map<String, CgParsedShader> parsed = new ConcurrentHashMap<>();
        private final Map<String, CgMaterialDomain> domains = new ConcurrentHashMap<>();

        @Override
        public CgParsedShader parse(String shader) {
            return parsed.computeIfAbsent(shader, p -> CgShaderParser.parse(source(p), p));
        }

        @Override
        public CgMaterialDomain domain(String shader) {
            return domains.computeIfAbsent(shader, p -> CgMaterialDomain.ofSource(source(p)));
        }

        private final Map<String, String> resolved = new ConcurrentHashMap<>();

        @Override
        public String resolve(String shader) {
            return resolved.computeIfAbsent(shader, p -> CgIO.loadSource(p) != null ? p : engineArchetype(p));
        }

        private String source(String path) {
            String s = CgIO.loadSource(resolve(path));
            if (s == null) throw new IllegalArgumentException(path + " is not found, nor " + engineArchetype(path));
            return s;
        }
    };

    /**
     * The path {@code shader} is drawn from: itself where it exists, else the engine's archetype of its name
     * ({@link #engineArchetype}). What a material's programs are keyed and loaded by.
     */
    default String resolve(String shader) {
        return shader;
    }

    /** Where the engine keeps the archetypes kits name. */
    String ENGINE_ARCHETYPES = "crystalgraphics:shaders/vfx/archetype/";

    /**
     * The engine's archetype a kit's shader path stands for: {@code nsuns4_kit:shaders/cc2_effect.shader} is
     * {@code crystalgraphics:shaders/vfx/archetype/cc2_effect.shader}. A kit ships no shaders of its own; the engine
     * ports each game's archetype once.
     */
    static String engineArchetype(String path) {
        int slash = path.lastIndexOf('/');
        return ENGINE_ARCHETYPES + (slash >= 0 ? path.substring(slash + 1) : path.substring(path.indexOf(':') + 1));
    }

    /**
     * {@code shader}, parsed.
     *
     * @throws RuntimeException naming it when it is missing or does not parse
     */
    CgParsedShader parse(String shader);

    /** The domain {@code shader}'s tags name ({@link CgMaterialDomain#ofSource}); {@code SURFACE} unless overridden. */
    default CgMaterialDomain domain(String shader) {
        return CgMaterialDomain.SURFACE;
    }
}
