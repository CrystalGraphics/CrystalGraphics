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
    vec4 dynamicParameters[4];   // Niagara's DynamicMaterialParameter to _3, where dynamics has their bits
    int dynamics;
};
#define CG_MATERIAL_PARTICLE 1

// Its dynamic parameter i (0 to 3), or d where its emitter sets none: Unreal's DynamicParameter node and its defaults.
#define cg_dynamic_parameter(p, i, d) ((((p).dynamics >> (i)) & 1) != 0 ? (p).dynamicParameters[i] : (d))

// The renderer's _cg_Drawn: bits 0 to 3 the dynamic parameters its particles carry, then the sprite's sub-image index
// and camera offset.
#define _CG_DRAWN_SUB_IMAGE 32
#define _CG_DRAWN_CAMERA_OFFSET 64

#define cg_material_project(v) (cg_ProjMatrix * cg_ViewMatrix * vec4((v).worldPosition, 1.0))

// The eye's place in the space worldPosition is in.
#define _CG_EYE (-(transpose(mat3(cg_ViewMatrix)) * cg_ViewMatrix[3].xyz))

// Cascade's camera offset (ParticleSystemRender.cpp's GetCameraOffset): offset blocks toward the eye, and a particle the
// offset would carry past the eye pushed far behind it, out of view.
vec3 _cg_toward_eye(vec3 centre, vec3 eye, float offset) {
    vec3 toEye = eye - centre;
    float d = max(length(toEye), 1.0e-4);
    return toEye / d * offset * (d > offset ? 1.0 : 1.0e6);
}

#ifdef CG_VERTEX_STAGE
// Particle n's dynamic parameters into p, those the renderer's drawn bits name.
void _cg_dynamics(int n, int drawn, inout CgMaterialParticle p) {
    p.dynamics = drawn & 15;
    p.dynamicParameters[0] = p.dynamicParameters[1] = p.dynamicParameters[2] = p.dynamicParameters[3] = vec4(0.0);
    if (p.dynamics != 0) {
        p.dynamicParameters[0] = CG_PARTICLE_DRAWN(n).place;
        p.dynamicParameters[1] = CG_PARTICLE_DRAWN(n).motion;
        p.dynamicParameters[2] = CG_PARTICLE_DRAWN(n).state;
        p.dynamicParameters[3] = CG_PARTICLE_DRAWN(n).light;
    }
}
#endif

// The factory follows the usage a program is compiled for, as Unreal compiles a material once per vertex factory
// its usages enable: a strip (FX_ON_RIBBONS, which every particle program takes) gets the strip factory, a sprite
// program (no vertex data, or FX_ON_SPRITES) the sprite factory, any other mesh's.
// The strip factory: Unreal's beam-and-trail vertex factory (ParticleBeamTrailVertexFactory), compiled where a draw
// enables FX_ON_RIBBONS, which every particle program takes. Each particle is a strip, placed from CG_VERTEX_ID, of the
// draw's kind; a beam (CgVfxBeamRendererProperties) is a run of its pool's PARTICLE_BEAMS (fx_beam.glsl's FX_BEAM_*)
// drawn as ParticleSystemRender.cpp's beam fills draw it. What CgVfxFrame passes:
//   CUSTOM0  the draw's first particle, its count, the renderer's size, sheets + 256 * segments a sheet
//   CUSTOM1  the slot's first run, its runs (its capacity), vec4s a run, noise points a run
//   CUSTOM2  interpolation points, flags (1 noise on the target, 2 one up for the whole beam, 4 tiles by distance), its
//            tiles or the blocks a tile, the noise's frequency
// An include is resolved once whatever #if it sits in, so what both factories read is included ahead of either.
#if defined(CG_VERTEX_STAGE) && (defined(FX_ON_RIBBONS) || defined(CG_TYPE_NONE) || defined(FX_ON_SPRITES) || defined(CG_TYPE_SPATIAL))
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_particle.glsl"
#endif
#if defined(CG_VERTEX_STAGE) && defined(FX_ON_RIBBONS)
#define FX_BEAM_RUN(at) CG_PARTICLE_BEAM(at)
#include "crystalgraphics:shaders/lib/vfx/fx_beam.glsl"

// One vertex of the draw's beam strips: the particle CG_VERTEX_ID falls in, its sheet, its point along and its side.
void _cg_strip(float cameraOffset, float drawnBits, out CgMaterialVertex v, out CgMaterialParticle p) {
    int drawn = int(drawnBits + 0.5);
    int sheets = int(mod(CG_OBJECT_CUSTOM0.w, 256.0) + 0.5), segments = int(CG_OBJECT_CUSTOM0.w / 256.0);
    int per = sheets * segments * 6, rank = CG_VERTEX_ID / per, local = CG_VERTEX_ID - rank * per;
    int sheet = local / (segments * 6), q = local - sheet * segments * 6, corner = q % 6;
    int k = q / 6 + ((0x34 >> corner) & 1);
    float side = ((0x1A >> corner) & 1) == 0 ? 1.0 : -1.0;
    int n = fx_particle_index(float(rank), CG_OBJECT_CUSTOM0.x, CG_OBJECT_CUSTOM0.y), i = max(n, 0);
    int id = int(CG_PARTICLE_ID(i) + 0.5);
    int at = int(CG_OBJECT_CUSTOM1.x + 0.5) + (id % max(int(CG_OBJECT_CUSTOM1.y + 0.5), 1)) * int(CG_OBJECT_CUSTOM1.z + 0.5);
    vec4 c2 = CG_OBJECT_CUSTOM2;
    int flags = int(c2.y + 0.5);
    vec3 origin = CG_OBJECT_TO_WORLD[3].xyz, eye = _CG_EYE;
    vec3 end = CG_PARTICLE_POSITION(i);
    // How many of its segments this beam fills: the rest fold onto its last point.
    vec4 tag = FX_BEAM_RUN(at + FX_BEAM_TAG);
    int noisePoints = int(CG_OBJECT_CUSTOM1.w + 0.5), filled = fx_beam_filled(at, noisePoints, segments);
    vec3 here = origin + fx_beam_point(at, min(k, filled), filled, noisePoints, end, c2);
    int from = (flags & 2) != 0 ? 0 : max(min(k, filled) - 1, 0), to = (flags & 2) != 0 ? 1 : max(min(k, filled), 1);
    vec3 location = origin + fx_beam_point(at, from, filled, noisePoints, end, c2);
    vec3 right = fx_beam_unit(location - (origin + fx_beam_point(at, to, filled, noisePoints, end, c2)), vec3(1.0, 0.0, 0.0));
    vec3 up = fx_beam_unit(cross(right, location - eye), vec3(cg_ViewMatrix[0][1], cg_ViewMatrix[1][1], cg_ViewMatrix[2][1]));
    if (sheet > 0) {
        float angle = 3.14159265 / float(sheets) * float(sheet);
        up = fx_beam_turn(vec4(right * sin(angle * 0.5), cos(angle * 0.5)), up);
    }
    float size = n < 0 ? 0.0 : CG_PARTICLE_SIZE(i) * CG_PARTICLE_SCALE(i).x * CG_OBJECT_CUSTOM0.z;
    float u;
    if (int(CG_OBJECT_CUSTOM1.w + 0.5) > 0) {
        u = float(min(k, filled)) / (max(c2.w, 1.0) * max(tag.z, 1.0));
    } else {
        float tiles = (flags & 4) != 0 ? length(FX_BEAM_RUN(at + FX_BEAM_TARGET).xyz - FX_BEAM_RUN(at + FX_BEAM_SOURCE).xyz) / max(c2.z, 1e-4)
                : max(c2.z, 1.0);
        u = tiles * float(min(k, filled)) / float(max(filled, 1));
    }
    vec2 uv = vec2(u, side > 0.0 ? 0.0 : 1.0);
    vec4 color = CG_PARTICLE_COLOR(i);
    here += _cg_toward_eye(here, eye, cameraOffset);

    v.worldPosition = here + up * size * side;
    v.texCoords[0] = uv;
    v.texCoords[1] = uv;
    v.texCoords[2] = uv;
    v.texCoords[3] = uv;
    v.tangentToWorld = mat3(-right, up, normalize(cross(-right, up)));
    v.vertexColor = vec4(color.rgb, color.a * CG_PARTICLE_OPACITY(i));

    vec3 velocity = CG_PARTICLE_VELOCITY(i);
    p.color = v.vertexColor;
    p.relativeTime = CG_PARTICLE_PROGRESS(i);
    p.random = CG_PARTICLE_SEED(i);
    p.velocity = vec4(length(velocity) > 0.0 ? normalize(velocity) : vec3(0.0), length(velocity));
    p.positionAndSize = vec4(origin + end, size);
    p.size = vec2(size);
    p.spriteRotation = 0.0;
    p.subUVCoords0 = uv;
    p.subUVCoords1 = uv;
    p.subUVLerp = 0.0;
    p.record = i;
    _cg_dynamics(i, drawn, p);
}

CgMaterialVertex _cg_strip_vertex(float cameraOffset, float drawnBits) {
    CgMaterialVertex v;
    CgMaterialParticle p;
    _cg_strip(cameraOffset, drawnBits, v, p);
    return v;
}

CgMaterialParticle _cg_strip_particle(float cameraOffset, float drawnBits) {
    CgMaterialVertex v;
    CgMaterialParticle p;
    _cg_strip(cameraOffset, drawnBits, v, p);
    return p;
}

#define cg_material_vertex() _cg_strip_vertex(_cg_CameraOffset, _cg_Drawn)
#define cg_material_particle() _cg_strip_particle(_cg_CameraOffset, _cg_Drawn)
#elif defined(CG_VERTEX_STAGE) && (defined(CG_TYPE_NONE) || defined(FX_ON_SPRITES))

// One quad corner of the draw's particle: the placement every sprite of the vfx engine shares.
void _cg_sprite(float facing, vec4 facingVector, vec4 alignmentVector, float stretch, float maxStretch,
                float cameraOffset, float rotationBias, vec4 subImage, vec4 subUv, sampler2D subImages, float drawnBits,
                out CgMaterialVertex v, out CgMaterialParticle p) {
    int drawn = int(drawnBits + 0.5);
    int n = fx_particle_index(FX_QUAD_INDEX, CG_OBJECT_CUSTOM0.x, CG_OBJECT_CUSTOM0.y);
    vec2 corner = FX_QUAD_CORNER;
    vec3 origin = CG_OBJECT_TO_WORLD[3].xyz - CG_OBJECT_CUSTOM1.xyz;
    vec3 eyeRight = vec3(cg_ViewMatrix[0][0], cg_ViewMatrix[1][0], cg_ViewMatrix[2][0]);
    vec3 eyeUp = vec3(cg_ViewMatrix[0][1], cg_ViewMatrix[1][1], cg_ViewMatrix[2][1]);
    vec3 eye = _CG_EYE;
    int i = max(n, 0);
    vec3 velocity = CG_PARTICLE_VELOCITY(i);
    float size = n < 0 ? 0.0 : CG_PARTICLE_SIZE(i) * CG_OBJECT_CUSTOM0.z;
    vec3 centre = origin + CG_PARTICLE_POSITION(i);
    vec2 spriteDrawn = (drawn & (_CG_DRAWN_SUB_IMAGE | _CG_DRAWN_CAMERA_OFFSET)) != 0 ? CG_PARTICLE_DRAWN(i).color.xy : vec2(0.0);
    if ((drawn & _CG_DRAWN_CAMERA_OFFSET) != 0) cameraOffset = spriteDrawn.y;
    centre += _cg_toward_eye(centre, eye, cameraOffset);
    int mode = int(facing + 0.5), alignment = mode / 8;
    vec3 right, up;
    fx_particle_facing(mode - alignment * 8, alignment, centre, velocity, facingVector.xyz, alignmentVector.xyz, eye,
                       eyeRight, eyeUp, right, up);
    bool aligned = alignment == FX_ALIGN_VELOCITY;
    float length_ = aligned ? clamp(1.0 + length(velocity) * stretch, 1.0, maxStretch) : 1.0;
    vec2 extent = size * CG_PARTICLE_SCALE(i).xy * vec2(1.0, length_);
    float spin = aligned ? 0.0 : CG_PARTICLE_SPIN(i) + rotationBias;
    float a, b, blend;
    fx_subuv_frames(subUv, subImage, CG_PARTICLE_AGE(i), CG_PARTICLE_PROGRESS(i), CG_PARTICLE_SEED(i), spriteDrawn.x, a, b,
                    blend);
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
    _cg_dynamics(i, drawn, p);
}

CgMaterialVertex _cg_sprite_vertex(float facing, vec4 facingVector, vec4 alignmentVector, float stretch,
                                   float maxStretch, float cameraOffset, float rotationBias, vec4 subImage, vec4 subUv,
                                   sampler2D subImages, float drawnBits) {
    CgMaterialVertex v;
    CgMaterialParticle p;
    _cg_sprite(facing, facingVector, alignmentVector, stretch, maxStretch, cameraOffset, rotationBias, subImage, subUv,
               subImages, drawnBits, v, p);
    return v;
}

CgMaterialParticle _cg_sprite_particle(float facing, vec4 facingVector, vec4 alignmentVector, float stretch,
                                       float maxStretch, float cameraOffset, float rotationBias, vec4 subImage,
                                       vec4 subUv, sampler2D subImages, float drawnBits) {
    CgMaterialVertex v;
    CgMaterialParticle p;
    _cg_sprite(facing, facingVector, alignmentVector, stretch, maxStretch, cameraOffset, rotationBias, subImage, subUv,
               subImages, drawnBits, v, p);
    return p;
}

#define cg_material_vertex() _cg_sprite_vertex(_cg_Facing, _cg_FacingVector, _cg_AlignmentVector, _cg_Stretch,         _cg_MaxStretch, _cg_CameraOffset, _cg_RotationBias, _cg_SubImage, _cg_SubUV, _cg_SubImages, _cg_Drawn)
#define cg_material_particle() _cg_sprite_particle(_cg_Facing, _cg_FacingVector, _cg_AlignmentVector, _cg_Stretch,         _cg_MaxStretch, _cg_CameraOffset, _cg_RotationBias, _cg_SubImage, _cg_SubUV, _cg_SubImages, _cg_Drawn)
#elif defined(CG_VERTEX_STAGE) && defined(CG_TYPE_SPATIAL)
// The mesh factory: a particle's mesh, drawn an instance a particle from Range's object records (CgVfxRange.objects):
// the model its place, turn and size; custom0 its velocity and age; custom1 its progress, seed, opacity and heat;
// custom2 its colour and its place in the order. As Unreal's MeshParticleVertexFactory.ush, the vertex colour and UVs
// are the mesh's and the particle's colour is the particle's alone.
CgMaterialVertex _cg_mesh_vertex(float cameraOffset, float drawnBits) {
    CgMaterialVertex v;
    if ((int(drawnBits + 0.5) & _CG_DRAWN_CAMERA_OFFSET) != 0) cameraOffset = CG_PARTICLE_DRAWN(int(CG_OBJECT_CUSTOM2.w)).color.y;
    v.worldPosition = (CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0)).xyz
            + _cg_toward_eye(CG_OBJECT_TO_WORLD[3].xyz, _CG_EYE, cameraOffset);
    v.texCoords[0] = cg_TexCoord0;
    v.texCoords[1] = cg_TexCoord1;
    v.texCoords[2] = cg_TexCoord2;
    v.texCoords[3] = cg_TexCoord3;
    // The format carries no tangent: a frame about the normal (Duff et al., "Building an Orthonormal Basis, Revisited").
    vec3 n = normalize(CG_NORMAL_MATRIX * cg_Normal);
    float s = n.z >= 0.0 ? 1.0 : -1.0, a = -1.0 / (s + n.z), b = n.x * n.y * a;
    v.tangentToWorld = mat3(vec3(1.0 + s * n.x * n.x * a, s * b, -s * n.x), vec3(b, s + n.y * n.y * a, -n.y), n);
    v.vertexColor = cg_Color;
    return v;
}

CgMaterialParticle _cg_mesh_particle(vec4 subImage, vec4 subUv, sampler2D subImages, float drawnBits) {
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
    p.record = int(CG_OBJECT_CUSTOM2.w);
    // Niagara's mesh sub-UVs (NiagaraMeshVertexFactory.ush): the mesh's own UVs laid into each of its two frames,
    // (frame + uv) * SubImageSize; the whole image without a flipbook.
    int drawn = int(drawnBits + 0.5), i = p.record;
    float index = (drawn & _CG_DRAWN_SUB_IMAGE) != 0 ? CG_PARTICLE_DRAWN(i).color.x : 0.0, a, b, blend;
    fx_subuv_frames(subUv, subImage, CG_PARTICLE_AGE(i), CG_PARTICLE_PROGRESS(i), CG_PARTICLE_SEED(i), index, a, b, blend);
    vec4 rectA = fx_subimage_rect(subImages, subImage, a), rectB = fx_subimage_rect(subImages, subImage, b);
    p.subUVCoords0 = mix(rectA.xy, rectA.zw, cg_TexCoord0);
    p.subUVCoords1 = mix(rectB.xy, rectB.zw, cg_TexCoord0);
    p.subUVLerp = blend;
    _cg_dynamics(p.record, int(drawnBits + 0.5), p);
    return p;
}

#define cg_material_vertex() _cg_mesh_vertex(_cg_CameraOffset, _cg_Drawn)
#define cg_material_particle() _cg_mesh_particle(_cg_SubImage, _cg_SubUV, _cg_SubImages, _cg_Drawn)
#elif defined(CG_VERTEX_STAGE)
// No factory for this #type: a port calling one fails here, by name.
#define cg_material_vertex() cg_material_vertex_has_no_factory_for_this_type()
#define cg_material_particle() cg_material_particle_has_no_factory_for_this_type()
#endif
