package com.crystalgraphics.vfx.render;

import com.crystalgraphics.api.material.CgVertexFactory;
import com.crystalgraphics.api.material.CgVertexFactory.Factory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The VFX engine's vertex factories, for a {@code #domain surface} material drawn by a particle renderer or a tube
 * slot: Unreal's sprite, mesh particle and beam/trail factories, and the tube's own.
 *
 * <pre>{@code
 * // smoke.shader: a lit sprite, its opacity from the particle's
 * #domain surface
 * Material { BlendMode Translucent  VertexFactory Sprites }
 * Properties { _Puff ("Puff", sampler2D) = "white" }
 * struct v2f { vec2 uv0; };
 * void surface(in v2f i, inout CgSurface s) {
 *     s.baseColor = cg_Particle.color.rgb * texture(_Puff, i.uv0).rgb;
 *     s.opacity = cg_Particle.color.a * (1.0 - cg_Particle.relativeTime);
 * }
 *
 * // shell.shader: a beam's skin, pushed out from its axis
 * #domain surface
 * Material { BlendMode Additive  ShadingModel Unlit  Faces Both  VertexFactory Tube }
 * struct v2f { float along; };
 * void vertex(inout CgVertex v, out v2f o) {
 *     v.world += v.normal * 0.1 * cg_Tube.ring.intensity;
 *     o.along = cg_Tube.ring.arc;
 * }
 * void surface(in v2f i, inout CgSurface s) { s.baseColor = vec3(fract(i.along)); }
 * }</pre>
 *
 * <table>
 *   <tr><th>Factory</th><th>Drawn by</th><th>{@code CgVertex}</th></tr>
 *   <tr><td>{@code Sprites}</td><td>a particle renderer's quads, and a beam's or a ribbon's strips</td>
 *       <td>the corner placed, faced and flipbooked by the renderer; {@code position} its offset from the particle's
 *       centre; {@code uv0}/{@code uv1} frame a's and b's, {@code uv2}/{@code uv3} unflipped</td></tr>
 *   <tr><td>{@code MeshParticles}</td><td>a mesh renderer, a mesh an instance a particle</td>
 *       <td>the mesh's vertex placed by its particle</td></tr>
 *   <tr><td>{@code Tube}</td><td>a tube slot, each chunk an open run of rings</td>
 *       <td>skinned onto its ring; {@code uv0} (blocks along the path, 0..1 around)</td></tr>
 *   <tr><td>{@code TubeClosed}</td><td>a tube slot, its chunks capped and its end rings pushed out a radius</td>
 *       <td>as {@code Tube}; an end centre's angle -1</td></tr>
 *   <tr><td>{@code TubeVolume}</td><td>a tube slot, each chunk a sphere round its rings</td>
 *       <td>the sphere's vertex</td></tr>
 * </table>
 *
 * <ul>
 *   <li>{@code cg_Particle} ({@code CgMaterialParticle}, {@code particle_factory.glsl}) under the particle factories;
 *       {@code cg_Particle.record} reads the rest through {@code CG_PARTICLE_*}.</li>
 *   <li>{@code cg_Tube} ({@code CgTube}, {@code fx_tube.glsl}) under the tube factories; a tube's draw binds
 *       {@code _FxPath}, so a material traces its path with {@code cg_Tube.row} and {@code fx_ring}.</li>
 * </ul>
 */
public final class CgVfxVertexFactories {

    public static final String SPRITES = "Sprites", MESH_PARTICLES = "MeshParticles";
    public static final String TUBE = "Tube", TUBE_CLOSED = "TubeClosed", TUBE_VOLUME = "TubeVolume";

    private static final String PARTICLE_FILL = """
            CgMaterialVertex _cg_placed = cg_material_vertex();
            cg_Particle = cg_material_particle();
            v.position = _cg_placed.worldPosition - cg_Particle.positionAndSize.xyz;
            v.normal = _cg_placed.tangentToWorld[2];
            v.color = _cg_placed.vertexColor;
            v.uv0 = _cg_placed.texCoords[0];
            v.uv1 = _cg_placed.texCoords[1];
            v.uv2 = _cg_placed.texCoords[2];
            v.uv3 = _cg_placed.texCoords[3];
            v.world = _cg_placed.worldPosition;
            """;

    /** What CgVfxTube writes a chunk's draw: CG_OBJECT_CUSTOM0 row, first ring, size; CUSTOM1 centre less origin, owned. */
    private static final String TUBE_CHUNK = """
            cg_Tube.row = int(CG_OBJECT_CUSTOM0.x + 0.5);
            cg_Tube.first = int(CG_OBJECT_CUSTOM0.y + 0.5);
            cg_Tube.owned = int(CG_OBJECT_CUSTOM1.w + 0.5);
            cg_Tube.size = CG_OBJECT_CUSTOM0.z;
            cg_Tube.origin = CG_OBJECT_TO_WORLD[3].xyz - CG_OBJECT_CUSTOM1.xyz;
            """;

    private static final String TUBE_DECLARATIONS = """
            #include "crystalgraphics:shaders/lib/vfx/fx_tube.glsl"
            CgTube cg_Tube;
            """;

    private static final String TUBE_PROPERTIES = "_FxPath (\"Path rings\", sampler2D) = \"black\"";

    private CgVfxVertexFactories() {}

    /** The factories, for {@link CgVertexFactory}'s registration. */
    public static List<Factory> all() {
        return List.of(
                particles(SPRITES, "none"),
                particles(MESH_PARTICLES, "spatial"),
                tube(TUBE, 0f, null),
                tube(TUBE_CLOSED, 1f, "Closed"),
                tubeVolume());
    }

    private static Factory particles(String name, String vertexType) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("color", "vec4");
        fields.put("relativeTime", "float");
        fields.put("random", "float");
        fields.put("velocity", "vec4");
        fields.put("positionAndSize", "vec4");
        fields.put("size", "vec2");
        fields.put("spriteRotation", "float");
        fields.put("subUVCoords0", "vec2");
        fields.put("subUVCoords1", "vec2");
        fields.put("subUVLerp", "float");
        fields.put("record", "flat int");
        fields.put("dynamicParameters", "vec4[4]");
        fields.put("dynamics", "flat int");
        return CgVertexFactory.builder(name).vertexType(vertexType).buffers("particle")
                .declarations("CgMaterialParticle cg_Particle;\n").fill(PARTICLE_FILL)
                .parameters("cg_Particle", fields).build();
    }

    /** A chunk skinned onto its rings; {@code capPush} moves the end rings out by that many radii. */
    private static Factory tube(String name, float capPush, String tag) {
        CgVertexFactory.Builder b = CgVertexFactory.builder(name).vertexType("spatial")
                .declarations(TUBE_DECLARATIONS).properties(TUBE_PROPERTIES)
                .fill(TUBE_CHUNK + """
                        FxTubeVertex _cg_skinned = fx_tube_vertex(_FxPath, cg_Tube.row, cg_Tube.first, cg_TexCoord0,
                                cg_Tube.size, %s);
                        cg_Tube.ring = _cg_skinned.ring;
                        cg_Tube.angle = _cg_skinned.angle;
                        cg_Tube.cap = _cg_skinned.cap;
                        cg_Tube.header = _cg_skinned.header;
                        vec3 _cg_out = _cg_skinned.position - _cg_skinned.ring.position;
                        v.position = _cg_skinned.position;
                        v.normal = dot(_cg_out, _cg_out) > 1.0e-12 ? normalize(_cg_out) : _cg_skinned.ring.tangent * _cg_skinned.cap;
                        v.color = vec4(1.0);
                        v.uv0 = vec2(_cg_skinned.ring.arc, _cg_skinned.angle);
                        v.uv1 = v.uv0;
                        v.uv2 = v.uv0;
                        v.uv3 = v.uv0;
                        v.world = cg_Tube.origin + v.position;
                        """.formatted(capPush == 0f ? "0.0" : "1.0"))
                .parameters("cg_Tube", tubeFields());
        if (tag != null) b.tag("Tube", tag);
        return b.build();
    }

    private static Factory tubeVolume() {
        return CgVertexFactory.builder(TUBE_VOLUME).vertexType("spatial")
                .declarations(TUBE_DECLARATIONS).properties(TUBE_PROPERTIES).tag("Tube", "Volume")
                .fill(TUBE_CHUNK + """
                        cg_Tube.header = fx_path_header(_FxPath, cg_Tube.row);
                        cg_Tube.ring = fx_ring(_FxPath, cg_Tube.row, cg_Tube.first);
                        cg_Tube.angle = 0.0;
                        cg_Tube.cap = 0.0;
                        """ + CgVertexFactory.MESH_FILL)
                .parameters("cg_Tube", tubeFields()).build();
    }

    private static Map<String, String> tubeFields() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("ring.position", "vec3");
        fields.put("ring.radius", "float");
        fields.put("ring.tangent", "vec3");
        fields.put("ring.arc", "float");
        fields.put("ring.normal", "vec3");
        fields.put("ring.intensity", "float");
        fields.put("angle", "float");
        fields.put("cap", "float");
        fields.put("header", "vec4");
        fields.put("row", "flat int");
        fields.put("first", "flat int");
        fields.put("owned", "flat int");
        fields.put("size", "float");
        fields.put("origin", "vec3");
        return fields;
    }
}
