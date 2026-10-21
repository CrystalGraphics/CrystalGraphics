package com.crystalgraphics.vulkan;

import com.crystalgraphics.api.material.CgParameterCollections;
import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgLookKinds;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.gl.material.CgMaterialShader;
import com.crystalgraphics.gl.material.CgMaterialShaderRegistry;
import com.crystalgraphics.gl.material.parse.CgParsedPass;
import com.crystalgraphics.gl.material.parse.CgParsedShader;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.Assert.*;

/**
 * The kit program contract under shaderc, as a Vulkan device links it: a port written against the vertex factory and
 * the material library links in every pass, and a kit's ported programs link too.
 *
 * <pre>{@code
 * ./gradlew :CrystalGraphics:runtime:lwjgl:vulkan:test --tests "*KitProgramsLinkTest"
 * ./gradlew :CrystalGraphics:runtime:lwjgl:vulkan:test --tests "*KitProgramsLinkTest" -PkitPorts=mykit=path/to/ports/shaders
 * }</pre>
 *
 * <p>{@code -PkitPorts} takes {@code <namespace>=<dir>} pairs separated by {@code ;}: each dir is loaded as
 * {@code <namespace>:shaders/}, so a port's includes of its own namespace resolve, and a {@code parameters/} beside it
 * declares the collections the ports read. Without it the kits' ports are skipped.</p>
 */
public class KitProgramsLinkTest {

    /** A sprite port reading the whole contract: the factory's vertex and particle, the library, a dynamic parameter. */
    private static final String SPRITE_PORT = """
            #type none
            #pragma cg_use particle
            #pragma cg_use rows
            #include "crystalgraphics:shaders/lib/material/depth_fade.glsl"
            #include "crystalgraphics:shaders/lib/material/panner.glsl"
            #include "crystalgraphics:shaders/lib/material/rotator.glsl"
            #include "crystalgraphics:shaders/lib/material/rotate_about_axis.glsl"
            #include "crystalgraphics:shaders/lib/material/particle_sub_uv.glsl"

            Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" }
            Queue = "Transparent"

            Properties {
                _Mask ("Mask", sampler2D) = "white"
                _Fade ("Fade distance", float) = 1.0
            }

            struct v2f { vec2 uv0; vec2 uv1; float frames; vec4 colour; vec4 dynamic; };

            Pass {
                Tags { "LightMode" = "Forward" }
                RenderState {
                    Blend ONE ONE_MINUS_SRC_ALPHA
                    DepthTest LEQUAL
                    DepthWrite OFF
                    Cull OFF
                }

                void vertex(out v2f o) {
                    CgMaterialVertex v = cg_material_vertex();
                    CgMaterialParticle p = cg_material_particle();
                    v.worldPosition += cg_rotate_about_axis(vec4(v.tangentToWorld[2], p.spriteRotation),
                            p.positionAndSize.xyz, v.worldPosition) * 0.0;
                    o.uv0 = cg_panner(v.texCoords[0], CG_TIME, vec2(0.0, 0.1), true);
                    o.uv1 = cg_rotator(v.texCoords[1], vec2(0.5), CG_TIME, 1.0);
                    o.frames = p.subUVLerp;
                    o.colour = CG_PARTICLE_SUB_UV(_Mask, p, true);
                    o.colour *= p.color * v.vertexColor;
                    o.dynamic = cg_dynamic_parameter(p, 0, vec4(1.0));
                    gl_Position = cg_material_project(v);
                }

                void fragment(in v2f i, out vec4 fragColor) {
                    float a = cg_particle_sub_uv(_Mask, i.uv0, i.uv1, i.frames, true).r * i.colour.a * i.dynamic.x;
                    a = CG_DEPTH_FADE(a, _Fade, gl_FragCoord.xy / CG_RESOLUTION);
                    fragColor = vec4(i.colour.rgb * a, a);
                }
            }
            """;

    /** A mesh port on the mesh factory: the mesh's own colour and UVs, the particle's colour, time and record. */
    private static final String MESH_PORT = """
            #type spatial
            #pragma cg_use particle
            #include "crystalgraphics:shaders/lib/material/panner.glsl"
            #include "crystalgraphics:shaders/lib/material/rotate_about_axis.glsl"

            Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" }
            Queue = "Transparent"

            Properties {
                _Mask ("Mask", sampler2D) = "white"
            }

            struct v2f { vec2 uv; vec4 colour; float heat; };

            Pass {
                Tags { "LightMode" = "Forward" }
                RenderState {
                    Blend ONE ONE
                    DepthWrite OFF
                }
                void vertex(out v2f o) {
                    CgMaterialVertex v = cg_material_vertex();
                    CgMaterialParticle p = cg_material_particle();
                    v.worldPosition += v.tangentToWorld[2] * 0.1 * p.relativeTime
                            + cg_rotate_about_axis(vec4(0.0, 1.0, 0.0, p.random), p.positionAndSize.xyz, v.worldPosition);
                    o.uv = cg_panner(v.texCoords[0], CG_TIME, vec2(0.5, 0.0), true);
                    o.colour = p.color * v.vertexColor * cg_dynamic_parameter(p, 0, vec4(1.0));
                    o.heat = CG_PARTICLE_HEAT(p.record);
                    gl_Position = cg_material_project(v);
                }
                void fragment(in v2f i, out vec4 fragColor) {
                    fragColor = vec4(i.colour.rgb * texture(_Mask, i.uv).r * (1.0 + i.heat), 0.0);
                }
            }
            """;

    /** A type with no factory: it must fail naming that, not draw something else. */
    private static final String UNFACTORED_PORT = """
            #type pos3_uv2_col4ub
            #pragma cg_use particle

            Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" }
            Queue = "Transparent"

            struct v2f { vec4 colour; };

            Pass {
                Tags { "LightMode" = "Forward" }
                void vertex(out v2f o) {
                    CgMaterialVertex v = cg_material_vertex();
                    o.colour = v.vertexColor;
                    gl_Position = cg_material_project(v);
                }
                void fragment(in v2f i, out vec4 fragColor) { fragColor = i.colour; }
            }
            """;

    @BeforeClass
    public static void install() throws ClassNotFoundException {
        EngineOnTrackedBackendTest.install();
    }

    @AfterClass
    public static void uninstall() {
        EngineOnTrackedBackendTest.uninstall();
    }

    @Test
    public void aSpritePortOnTheContractLinksInEveryPass() {
        CgMaterialShader shader = CgMaterialShaderRegistry.get().getOrCreateGenerated(SPRITE_PORT);
        List<String> failures = new ArrayList<>();
        int programs = link("the sprite port", shader, failures);
        assertTrue(String.join("\n", failures), failures.isEmpty());
        assertTrue("no program was linked", programs > 0);
    }

    @Test
    public void aMeshPortOnTheContractLinksInEveryPass() {
        CgMaterialShader shader = CgMaterialShaderRegistry.get().getOrCreateGenerated(MESH_PORT);
        List<String> failures = new ArrayList<>();
        int programs = link("the mesh port", shader, failures);
        assertTrue(String.join(System.lineSeparator(), failures), failures.isEmpty());
        assertTrue("no program was linked", programs > 0);
    }

    @Test
    public void aPortOnATypeWithNoFactoryFailsNamingIt() {
        CgMaterialShader shader = CgMaterialShaderRegistry.get().getOrCreateGenerated(UNFACTORED_PORT);
        shader.recompile();
        assertTrue("a port compiled against a factory that does not exist", shader.hasCompileFailed());
        assertTrue(shader.lastCompileError(), shader.lastCompileError().contains("has_no_factory_for_this_type"));
    }

    @Test
    public void everyKitPortLinksInEveryPass() throws IOException {
        String namespaces = System.getProperty("crystalgraphics.kits.ports");
        Assume.assumeTrue("no -PkitPorts given", namespaces != null && !namespaces.isBlank());
        Path root = Paths.get(System.getProperty("crystalgraphics.kits.portsRoot"), "assets");
        List<String> failures = new ArrayList<>();
        int programs = 0, ports = 0;
        for (String namespace : namespaces.split(";")) {
            declareCollections(namespace, root.resolve(namespace).resolve("parameters"));
        }
        for (String namespace : namespaces.split(";")) {
            Path shaders = root.resolve(namespace).resolve("shaders");
            List<Path> files;
            try (Stream<Path> walk = Files.walk(shaders)) {
                files = walk.filter(f -> f.toString().endsWith(".shader")).sorted().toList();
            }
            for (Path file : files) {
                ports++;
                String path = namespace + ":shaders/" + shaders.relativize(file).toString().replace(File.separatorChar, '/');
                try {
                    programs += link(path, CgMaterialShaderRegistry.get().getOrCreate(path), failures);
                } catch (RuntimeException e) {
                    failures.add(path + ": " + e.getMessage());
                }
            }
        }
        assertTrue(String.join("\n", failures), failures.isEmpty());
        assertTrue("no port was found under " + namespaces, ports > 0);
        assertTrue("no program was linked", programs > 0);
    }

    /** Each collection asset under {@code dir}, declared as its kit's load would. */
    private static void declareCollections(String namespace, Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path file : walk.filter(f -> f.toString().endsWith(".yaml")).sorted().toList()) {
                String name = dir.relativize(file).toString().replace(File.separatorChar, '/').replaceFirst("[.]yaml$", "");
                Map<?, ?> tree = (Map<?, ?>) CgAssetYaml.read(Files.readString(file));
                CgParameterCollections.compile(CgAssetData.of(namespace + ":parameters/" + name, CgLookKinds.PARAMETERS, tree, null),
                        (kind, id) -> null);
            }
        }
    }

    /** Every pass with no keyword, each keyword alone and all of them: the programs counted, the failures listed. */
    private static int link(String name, CgMaterialShader shader, List<String> failures) {
        if (shader.getLastParsed() == null || shader.hasCompileFailed()) shader.recompile();
        if (shader.hasCompileFailed()) {
            failures.add(name + ": " + shader.lastCompileError());
            return 0;
        }
        CgParsedShader parsed = shader.getLastParsed();
        List<Set<String>> variants = new ArrayList<>();
        variants.add(Set.of());
        for (String keyword : parsed.featureNames()) variants.add(Set.of(keyword));
        if (parsed.featureNames().size() > 1) variants.add(new HashSet<>(parsed.featureNames()));
        int programs = 0;
        for (CgParsedPass pass : parsed.passes()) {
            for (Set<String> keywords : variants) {
                programs++;
                if (shader.getOrCompile(pass.name(), keywords) == null) {
                    failures.add(name + " pass " + pass.name() + " " + keywords + ": did not link (see log)");
                }
            }
        }
        return programs;
    }
}
