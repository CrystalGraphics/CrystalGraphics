// The vertex factory a kit's material program reads (Unreal's FMaterialVertexParameters and
// FMaterialParticleParameters, MaterialTemplate.ush): what cg_use particle gives a port beside the records.
//
//     void vertex(out v2f o) {
//         CgMaterialVertex v = cg_material_vertex();        // placed by the factory, under the renderer's settings
//         CgMaterialParticle p = cg_material_particle();
//         v.worldPosition += wpo(v);                         // the material's World Position Offset, if any
//         o.uv = v.texCoords[0];
//         o.color = p.color;
//         gl_Position = cg_material_project(v);
//     }
//
// The factory is the program's #type: none is the sprite factory (Unreal's sprite vertex factory), spatial the mesh
// factory (its mesh particle vertex factory). A port never places a vertex: facing, stretch, camera offset and the
// flipbook are the renderer's settings, the _cg_ row properties this token declares. The accessors are macros, so
// they expand where a port calls them, after its properties exist.
#pragma once

struct CgMaterialVertex {
    vec3 worldPosition;      // camera-relative, blocks: Unreal's translated world position
    vec2 texCoords[4];       // sprite: frame a's, frame b's, then both unflipped (the same until a renderer flips)
    mat3 tangentToWorld;     // tangent, bitangent, normal; a sprite's normal faces the eye
    vec4 vertexColor;        // on a sprite, the particle's colour, as Unreal's
};

struct CgMaterialParticle {
    vec4 color;              // linear; alpha its opacity
    float relativeTime;      // age over lifetime, 0..1
    float random;            // 0..1, fixed for its life
    vec4 velocity;           // xyz its direction, w its speed in blocks a second
    vec4 positionAndSize;    // camera-relative centre, radius
    vec2 size;               // the sprite's width and height
    float spriteRotation;    // radians
    vec2 subUVCoords0, subUVCoords1;
    float subUVLerp;
    int record;              // its record: CG_PARTICLE_*(p.record) reads what Unreal's parameters do not name
};
#define CG_MATERIAL_PARTICLE 1

// The renderer's dynamic parameter i, or d where it binds none: no renderer binds one yet.
#define cg_dynamic_parameter(p, i, d) (d)

#define cg_material_project(v) (cg_ProjMatrix * cg_ViewMatrix * vec4((v).worldPosition, 1.0))

#if defined(CG_VERTEX_STAGE) && defined(CG_TYPE_NONE)
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_particle.glsl"

// One quad corner of the draw's particle: the placement every sprite of the vfx engine shares.
void _cg_sprite(float facing, vec4 axis, float stretch, float maxStretch, float cameraOffset, vec4 subImage,
                vec4 subUv, sampler2D subImages, out CgMaterialVertex v, out CgMaterialParticle p) {
    int n = fx_particle_index(FX_QUAD_INDEX, CG_OBJECT_CUSTOM0.x, CG_OBJECT_CUSTOM0.y);
    vec2 corner = FX_QUAD_CORNER;
    vec3 origin = CG_OBJECT_TO_WORLD[3].xyz - CG_OBJECT_CUSTOM1.xyz;
    vec3 eyeRight = vec3(cg_ViewMatrix[0][0], cg_ViewMatrix[1][0], cg_ViewMatrix[2][0]);
    vec3 eyeUp = vec3(cg_ViewMatrix[0][1], cg_ViewMatrix[1][1], cg_ViewMatrix[2][1]);
    vec3 eye = -(transpose(mat3(cg_ViewMatrix)) * cg_ViewMatrix[3].xyz);
    int i = max(n, 0);
    vec3 velocity = CG_PARTICLE_VELOCITY(i);
    float size = n < 0 ? 0.0 : CG_PARTICLE_SIZE(i) * CG_OBJECT_CUSTOM0.z, keep;
    vec3 centre = fx_particle_toward_eye(origin + CG_PARTICLE_POSITION(i), eye, cameraOffset * size, keep);
    size *= keep;
    int mode = int(facing + 0.5);
    vec3 right, up;
    fx_particle_facing(mode, centre, velocity, axis.xyz, eye, eyeRight, eyeUp, right, up);
    bool streak = mode == FX_FACE_VELOCITY;
    float length_ = streak ? min(1.0 + length(velocity) * stretch, maxStretch) : 1.0;
    vec2 extent = size * CG_PARTICLE_SCALE(i).xy * vec2(length_, 1.0);
    float spin = streak ? 0.0 : CG_PARTICLE_SPIN(i);
    float a, b, blend;
    fx_subuv_frames(subUv, subImage, CG_PARTICLE_AGE(i), CG_PARTICLE_PROGRESS(i), CG_PARTICLE_SEED(i), a, b, blend);
    vec2 uvA = fx_subimage_uv(subImages, subImage, a, corner), uvB = fx_subimage_uv(subImages, subImage, b, corner);
    vec4 color = CG_PARTICLE_COLOR(i);

    v.worldPosition = fx_particle_corner(centre, corner, extent, spin, right, up);
    v.texCoords[0] = uvA;
    v.texCoords[1] = uvB;
    v.texCoords[2] = uvA;
    v.texCoords[3] = uvB;
    v.tangentToWorld = mat3(normalize(right), normalize(up), normalize(cross(right, up)));
    v.vertexColor = vec4(color.rgb, color.a * CG_PARTICLE_OPACITY(i));

    p.color = v.vertexColor;
    p.relativeTime = CG_PARTICLE_PROGRESS(i);
    p.random = CG_PARTICLE_SEED(i);
    p.velocity = vec4(length(velocity) > 0.0 ? normalize(velocity) : vec3(0.0), length(velocity));
    p.positionAndSize = vec4(centre, max(extent.x, extent.y));
    p.size = 2.0 * extent;
    p.spriteRotation = spin;
    p.subUVCoords0 = uvA;
    p.subUVCoords1 = uvB;
    p.subUVLerp = blend;
    p.record = i;
}

CgMaterialVertex _cg_sprite_vertex(float facing, vec4 axis, float stretch, float maxStretch, float cameraOffset,
                                   vec4 subImage, vec4 subUv, sampler2D subImages) {
    CgMaterialVertex v;
    CgMaterialParticle p;
    _cg_sprite(facing, axis, stretch, maxStretch, cameraOffset, subImage, subUv, subImages, v, p);
    return v;
}

CgMaterialParticle _cg_sprite_particle(float facing, vec4 axis, float stretch, float maxStretch, float cameraOffset,
                                       vec4 subImage, vec4 subUv, sampler2D subImages) {
    CgMaterialVertex v;
    CgMaterialParticle p;
    _cg_sprite(facing, axis, stretch, maxStretch, cameraOffset, subImage, subUv, subImages, v, p);
    return p;
}

#define cg_material_vertex() _cg_sprite_vertex(_cg_Facing, _cg_Axis, _cg_Stretch, _cg_MaxStretch, _cg_CameraOffset, _cg_SubImage, _cg_SubUV, _cg_SubImages)
#define cg_material_particle() _cg_sprite_particle(_cg_Facing, _cg_Axis, _cg_Stretch, _cg_MaxStretch, _cg_CameraOffset, _cg_SubImage, _cg_SubUV, _cg_SubImages)
#elif defined(CG_VERTEX_STAGE) && defined(CG_TYPE_SPATIAL)
// The mesh factory: a particle's mesh, drawn an instance a particle from Range's object records (CgVfxRange.objects):
// the model its place, turn and size; custom0 its velocity and age; custom1 its progress, seed, opacity and heat;
// custom2 its colour and its place in the order. As Unreal's MeshParticleVertexFactory.ush, the vertex colour and UVs
// are the mesh's and the particle's colour is the particle's alone.
CgMaterialVertex _cg_mesh_vertex() {
    CgMaterialVertex v;
    v.worldPosition = (CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0)).xyz;
    v.texCoords[0] = cg_TexCoord0;
    v.texCoords[1] = cg_TexCoord1;
    v.texCoords[2] = vec2(0.0);   // the mesh has two sets; Unreal zeroes the rest
    v.texCoords[3] = vec2(0.0);
    // The format carries no tangent: a frame about the normal (Duff et al., "Building an Orthonormal Basis, Revisited").
    vec3 n = normalize(CG_NORMAL_MATRIX * cg_Normal);
    float s = n.z >= 0.0 ? 1.0 : -1.0, a = -1.0 / (s + n.z), b = n.x * n.y * a;
    v.tangentToWorld = mat3(vec3(1.0 + s * n.x * n.x * a, s * b, -s * n.x), vec3(b, s + n.y * n.y * a, -n.y), n);
    v.vertexColor = cg_Color;
    return v;
}

CgMaterialParticle _cg_mesh_particle() {
    CgMaterialParticle p;
    vec3 velocity = CG_OBJECT_CUSTOM0.xyz;
    float speed = length(velocity);
    p.color = vec4(CG_OBJECT_CUSTOM2.rgb, CG_OBJECT_CUSTOM1.z);
    p.relativeTime = CG_OBJECT_CUSTOM1.x;
    p.random = CG_OBJECT_CUSTOM1.y;
    p.velocity = vec4(speed > 0.0 ? velocity / speed : vec3(0.0), speed);
    p.positionAndSize = vec4(CG_OBJECT_TO_WORLD[3].xyz, 1.0);   // Unreal's mesh factory sizes it 1
    p.size = vec2(0.0);
    p.spriteRotation = 0.0;
    // One sub-image until a mesh renderer sets its grid: Unreal's (SubUVParams + uv) * SubImageSize at 1x1.
    p.subUVCoords0 = cg_TexCoord0;
    p.subUVCoords1 = cg_TexCoord0;
    p.subUVLerp = 0.0;
    p.record = int(CG_OBJECT_CUSTOM2.w);
    return p;
}

#define cg_material_vertex() _cg_mesh_vertex()
#define cg_material_particle() _cg_mesh_particle()
#elif defined(CG_VERTEX_STAGE)
// No factory for this #type: a port calling one fails here, by name.
#define cg_material_vertex() cg_material_vertex_has_no_factory_for_this_type()
#define cg_material_particle() cg_material_particle_has_no_factory_for_this_type()
#endif
