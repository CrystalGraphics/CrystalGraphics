package com.crystalgraphics.material.shader;

import com.crystalgraphics.api.material.CgVertexFactory;
import com.crystalgraphics.material.shader.syntax.CgKeywordGrammar;
import com.crystalgraphics.material.shader.syntax.CgShaderTokens;
import com.github.bsideup.jabel.Desugar;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The vertex factory a surface material is compiled for: the one it names, or the several it lists
 * ({@code VertexFactory Mesh, MeshParticles}), the first the base and each other's fill, defaults and fade under its
 * usage keywords, as Unreal compiles a material once per vertex factory it is used with.
 */
@Desugar
record FactoryUse(String name, List<CgVertexFactory.Factory> factories, String vertexType, boolean placesNothing,
                  List<String> engineBuffers, String declarations, String properties, Map<String, String> tags,
                  @Nullable String parameters, Map<String, String> parameterFields, String fill, String defaults,
                  @Nullable String fade) {

    /** The fade of a listed set: each factory's expression, chosen by its usage keywords. */
    private static final String FADE = "_CG_FACTORY_FADE";

    /** {@code listed}, combined; errors placed on token {@code at}, the {@code VertexFactory} setting. */
    static FactoryUse of(List<CgVertexFactory.Factory> listed, CgShaderTokens t, int at) {
        CgVertexFactory.Factory base = listed.get(0);
        if (listed.size() == 1) {
            return new FactoryUse(base.name(), listed, base.vertexType(), base.placesNothing(), base.engineBuffers(),
                    base.declarations(), base.properties(), base.tags(), base.parameters(), base.parameterFields(),
                    base.fill(), base.defaults(), base.fade());
        }
        List<String> names = new ArrayList<>();
        StringBuilder declarations = new StringBuilder(), properties = new StringBuilder();
        CgVertexFactory.Factory parameters = null;
        boolean fades = false;
        Set<String> buffers = new LinkedHashSet<>();
        Map<String, String> tags = new LinkedHashMap<>();
        for (CgVertexFactory.Factory f : listed) {
            names.add(f.name());
            if (!f.vertexType().equals(base.vertexType()) || f.placesNothing() != base.placesNothing()) {
                throw t.error(at, "'VertexFactory " + base.name() + ", " + f.name() + "': factories listed together share"
                        + " a vertex type and whether they place anything");
            }
            if (f != base && f.usages().isEmpty()) {
                throw t.error(at, "'VertexFactory " + f.name() + "' has no 'Usage' keyword to compile under beside " + base.name());
            }
            if (f.parameters() != null) {
                if (parameters != null) {
                    throw t.error(at, "'VertexFactory " + parameters.name() + ", " + f.name() + "': at most one factory"
                            + " listed together has parameters");
                }
                parameters = f;
            }
            if (!f.declarations().isBlank()) declarations.append(f.declarations().strip()).append('\n');
            properties.append(f.properties()).append('\n');
            fades |= f.fade() != null;
            buffers.addAll(f.engineBuffers());
            tags.putAll(f.tags());
        }
        if (fades) {
            declarations.append(branches(listed, f -> "#define " + FADE + " (" + (f.fade() == null ? "1.0" : f.fade()) + ")"));
        }
        return new FactoryUse(String.join(", ", names), listed, base.vertexType(), base.placesNothing(),
                new ArrayList<>(buffers), declarations.toString(), properties.toString(), tags,
                parameters == null ? null : parameters.parameters(),
                parameters == null ? Map.of() : parameters.parameterFields(),
                branches(listed, CgVertexFactory.Factory::fill), branches(listed, CgVertexFactory.Factory::defaults),
                fades ? FADE : null);
    }

    /** Its keywords into {@code spec}: a listed set's usages first, each factory's own after, each declared once. */
    void declareKeywords(SpecBuilder spec, String path) {
        boolean listed = factories.size() > 1;
        for (CgVertexFactory.Factory f : factories) {
            if (listed) for (String u : f.usages()) spec.usage(u);
            if (!f.keywords().isBlank()) spec.keywords(CgKeywordGrammar.read(f.keywords(), path), listed);
        }
    }

    /** {@code part} of each factory, the base's under none of the others' usage keywords. */
    private static String branches(List<CgVertexFactory.Factory> factories, Function<CgVertexFactory.Factory, String> part) {
        StringBuilder out = new StringBuilder();
        for (int i = 1; i < factories.size(); i++) {
            List<String> defined = new ArrayList<>();
            for (String u : factories.get(i).usages()) defined.add("defined(" + u + ")");
            out.append(i == 1 ? "#if " : "#elif ").append(String.join(" || ", defined)).append('\n')
               .append(part.apply(factories.get(i)).strip()).append('\n');
        }
        return out.append("#else\n").append(part.apply(factories.get(0)).strip()).append("\n#endif\n").toString();
    }
}
