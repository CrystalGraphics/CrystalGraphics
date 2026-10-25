package com.crystalgraphics.vulkan;

import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.api.material.CgMaterialDefinitions;
import com.crystalgraphics.api.material.CgParameterCollections;
import com.crystalgraphics.api.material.CgShadingModel;
import com.crystalgraphics.api.material.CgShadingModels;
import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.asset.kind.CgLookKinds;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.gl.material.CgMaterialShader;
import com.crystalgraphics.gl.material.CgMaterialShaderRegistry;
import com.crystalgraphics.gl.material.parse.CgMaterialShaderCompiler;
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
import java.util.HashMap;
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

    /**
     * A sprite port declaring the contract's outputs: emissive and opacity composed by the engine, fog off, a clipped
     * depth, a Distortion pass beside its Forward pass; and the particle fields the sprite port leaves out.
     */
    private static final String DECLARING_PORT = """
            #type none
            #pragma cg_use particle

            Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" "Fog" = "Off" "Depth" = "Clip" "Output" = "EmissiveOpacity" }
            Queue = "Transparent"

            Properties {
                _Mask ("Mask", sampler2D) = "white"
            }

            struct v2f { vec2 uv; vec4 colour; vec3 heading; float speed; vec2 size; };

            Pass {
                Tags { "LightMode" = "Forward" }
                RenderState {
                    Blend ONE ONE
                    DepthWrite OFF
                    Cull OFF
                }
                void vertex(out v2f o) {
                    CgMaterialVertex v = cg_material_vertex();
                    CgMaterialParticle p = cg_material_particle();
                    o.uv = mix(p.subUVCoords0, p.subUVCoords1, p.subUVLerp);
                    o.colour = p.color;
                    o.heading = p.velocity.xyz;
                    o.speed = p.velocity.w;
                    o.size = p.size;
                    gl_Position = cg_material_project(v);
                }
                void fragment(in v2f i, out vec4 fragColor) {
                    float mask = texture(_Mask, i.uv).r;
                    cg_Clip(mask);
                    float behind = CG_SCENE_EYE_DEPTH(gl_FragCoord.xy / CG_RESOLUTION) - cg_LinearEyeDepth(gl_FragCoord.z);
                    fragColor = vec4(i.colour.rgb * (1.0 + i.speed * length(i.heading)) * min(i.size.x, 1.0), mask * clamp(behind, 0.0, 1.0));
                }
            }

            Pass {
                Tags { "LightMode" = "Distortion" }
                void fragment(in v2f i, out vec4 distortion) {
                    distortion = CG_DISTORTION(i.heading.xy * 0.01, 0.0, cg_LinearEyeDepth(gl_FragCoord.z));
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

    /** What a read of an output no pass writes yet fails naming. */
    private static final String NOT_WRITTEN = "_has_no_pass_writing_it_yet";

    /** A screen material reading the stencil, as a skill grade does. */
    private static final String STENCIL_PORT = """
            #type none

            Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" }
            Queue = "Transparent"

            struct v2f { vec2 uv; };

            Pass {
                Tags { "LightMode" = "Forward" }
                void vertex(out v2f o) {
                    o.uv = vec2(CG_VERTEX_ID & 1, CG_VERTEX_ID >> 1);
                    gl_Position = vec4(o.uv * 2.0 - 1.0, 0.0, 1.0);
                }
                void fragment(in v2f i, out vec4 fragColor) { fragColor = vec4(float(CG_CUSTOM_STENCIL(i.uv))); }
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
    public void aPortDeclaringTheContractsOutputsLinksInEveryPass() {
        CgMaterialShader shader = CgMaterialShaderRegistry.get().getOrCreateGenerated(DECLARING_PORT);
        List<String> failures = new ArrayList<>();
        int programs = link("the declaring port", shader, failures);
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
    public void aPortReadingTheStencilFailsNamingIt() {
        CgMaterialShader shader = CgMaterialShaderRegistry.get().getOrCreateGenerated(STENCIL_PORT);
        shader.recompile();
        assertTrue("a port read a stencil no pass writes", shader.hasCompileFailed());
        assertTrue(shader.lastCompileError(), shader.lastCompileError().contains(NOT_WRITTEN));
    }

    /**
     * Every port of each kit linked in every pass. One reading an output no pass writes yet (the stencil, the
     * shading-model id) fails naming it and is listed apart: that is the failure it is owed, not a broken port.
     */
    @Test
    public void everyKitPortLinksInEveryPass() throws IOException {
        String namespaces = System.getProperty("crystalgraphics.kits.ports");
        Assume.assumeTrue("no -PkitPorts given", namespaces != null && !namespaces.isBlank());
        Path root = Paths.get(System.getProperty("crystalgraphics.kits.portsRoot"), "assets");
        List<String> failures = new ArrayList<>(), waiting = new ArrayList<>();
        int programs = 0, ports = 0;
        for (String namespace : namespaces.split(";")) {
            CgParameterCollections.declareAll(namespace, root.resolve(namespace).resolve("parameters"));
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
                List<String> own = new ArrayList<>();
                try {
                    programs += link(path, CgMaterialShaderRegistry.get().getOrCreate(path), own);
                } catch (RuntimeException e) {
                    own.add(path + ": " + e.getMessage());
                }
                (!own.isEmpty() && own.stream().allMatch(f -> f.contains(NOT_WRITTEN)) ? waiting : failures).addAll(own);
            }
        }
        if (!waiting.isEmpty()) System.out.println("[kit ports] waiting on an output no pass writes:\n" + String.join("\n", waiting));
        assertTrue(String.join("\n", failures), failures.isEmpty());
        assertTrue("no port was found under " + namespaces, ports > 0);
        assertTrue("no program was linked", programs > 0);
    }

    /**
     * Each kit's {@code shading.yaml} registered under its namespace, and every material of the kit naming one of those
     * models compiled through its parent chain, its model's keyword on, and its forward program linked. A material
     * whose archetype the kit has not ported is listed, not linked.
     */
    @Test
    public void aKitsShadingModelsRegisterAndItsMaterialsLinkUnderThem() throws IOException {
        String kits = System.getProperty("crystalgraphics.kits.shading");
        Assume.assumeTrue("no -PkitShading given", kits != null && !kits.isBlank());
        CgAssetKinds.register();
        String portsRoot = System.getProperty("crystalgraphics.kits.portsRoot");
        List<String> failures = new ArrayList<>(), unported = new ArrayList<>();
        int linked = 0;
        for (String pair : kits.split(";")) {
            String namespace = pair.substring(0, pair.indexOf('='));
            Path dir = Paths.get(pair.substring(pair.indexOf('=') + 1));
            String kit = dir.getFileName().toString();
            List<CgShadingModel> models = CgShadingModels.registerKit(namespace, dir);
            assertTrue(dir + " registers no shading model", !models.isEmpty());
            Map<String, CgAssetData> assets = new HashMap<>();
            Path materials = dir.resolve("materials");
            List<Path> files;
            try (Stream<Path> walk = Files.walk(materials)) {
                files = walk.filter(f -> f.toString().endsWith(".yaml")).sorted().toList();
            }
            for (Path file : files) {
                String id = kit + ":" + materials.relativize(file).toString().replace(File.separatorChar, '/')
                        .replaceFirst("[.]yaml$", "");
                CgAssetData asset = material(id, materials, kit, assets);
                Object state = asset.resolved().get("state");
                Object shading = state instanceof Map<?, ?> s ? s.get("shading") : null;
                if (!(shading instanceof String model) || !model.startsWith(namespace + ":")) continue;
                String shader = String.valueOf(asset.resolved().get("shader"));
                Path port = Paths.get(portsRoot, "assets", shader.substring(0, shader.indexOf(':')),
                        shader.substring(shader.indexOf(':') + 1));
                if (!Files.isRegularFile(port)) {
                    unported.add(id + " (" + shader + ")");
                    continue;
                }
                try {
                    CgMaterialDefinition d = CgMaterialDefinitions.compile(asset);
                    String keyword = CgShadingModels.get(model).keyword();
                    if (keyword != null && !d.permutation().keywords().contains(keyword)) {
                        failures.add(id + ": " + model + "'s keyword " + keyword + " is not on");
                        continue;
                    }
                    CgMaterialShader program = CgMaterialShaderRegistry.get().getOrCreate(d.permutation().shader(), d.state());
                    if (program.getLastParsed() == null || program.hasCompileFailed()) program.recompile();
                    if (program.hasCompileFailed()) {
                        failures.add(id + ": " + program.lastCompileError());
                        continue;
                    }
                    if (program.getOrCompileForwardPass(new HashSet<>(d.permutation().keywords())) == null) {
                        failures.add(id + " " + d.permutation().keywords() + ": "
                                + program.variantError(program.getLastParsed().passes().get(0).name(), new HashSet<>(d.permutation().keywords())));
                        continue;
                    }
                    linked++;
                } catch (RuntimeException e) {
                    failures.add(id + ": " + e.getMessage());
                }
            }
        }
        System.out.println("[kit-shading] " + linked + " linked; not ported: " + unported.size() + " " + unported);
        assertTrue(String.join("\n", failures), failures.isEmpty());
        assertTrue("no material naming a kit shading model was linked", linked > 0);
    }

    /** The kit material {@code id}, resolved over its parent chain from {@code materials}. */
    private static CgAssetData material(String id, Path materials, String kit, Map<String, CgAssetData> assets)
            throws IOException {
        CgAssetData had = assets.get(id);
        if (had != null) return had;
        Path file = materials.resolve(id.substring(kit.length() + 1) + ".yaml");
        Map<?, ?> tree = (Map<?, ?>) CgAssetYaml.read(Files.readString(file));
        CgAssetData parent = tree.get("parent") instanceof String p ? material(p, materials, kit, assets) : null;
        CgAssetData asset = CgAssetData.of(id, CgLookKinds.MATERIAL, tree, parent);
        assets.put(id, asset);
        return asset;
    }

    /**
     * Every pass with no keyword, each keyword alone and all of them, and a particle program on the strip factory a
     * ribbon or beam draws it with: the programs counted, the failures listed.
     */
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
        if (parsed.engineBuffers().contains("particle")) variants.add(Set.of(CgMaterialShaderCompiler.STRIP));
        int programs = 0;
        for (CgParsedPass pass : parsed.passes()) {
            for (Set<String> keywords : variants) {
                programs++;
                if (shader.getOrCompile(pass.name(), keywords) == null) {
                    failures.add(name + " pass " + pass.name() + " " + keywords + ": " + shader.variantError(pass.name(), keywords));
                }
            }
        }
        return programs;
    }
}
