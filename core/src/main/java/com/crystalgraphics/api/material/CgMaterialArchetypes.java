package com.crystalgraphics.api.material;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.crystalgraphics.material.parse.CgParsedShader;
import com.crystalgraphics.material.parse.CgShaderParser;
import com.crystalgraphics.util.io.CgIO;

/**
 * Where a material definition finds its archetype: the {@code .shader} its root names, parsed, and the domain its
 * {@code #domain} line names. A kit ships its archetypes in its own namespace ({@code nsuns4_kit:shaders/cc2_effect.shader}),
 * beside its materials. GL-free, so a worker compiling definitions may call it.
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

        private String source(String path) {
            String s = CgIO.loadSource(path);
            if (s == null) throw new IllegalArgumentException(path + " is not found: a kit ships its own archetypes");
            return s;
        }
    };

    /**
     * {@code shader}, parsed.
     *
     * @throws RuntimeException naming it when it is missing or does not parse
     */
    CgParsedShader parse(String shader);

    /** The domain {@code shader}'s {@code #domain} line names ({@link CgMaterialDomain#ofSource}); {@code SURFACE} unless overridden. */
    default CgMaterialDomain domain(String shader) {
        return CgMaterialDomain.SURFACE;
    }
}
