// A toon energy body in one draw: up to three flat bands of colour and a soft halo past them, on a tube or a sphere,
// by how near the view ray passes the tube's axis or the sphere's centre (lib/vfx/bands.glsl). Reads as flat bands from
// any side and from inside, each edge antialiased over a pixel. _Bands is the palette, innermost first and the halo's
// colour last, each alpha a strength; the layer's size is the halo's edge, _Edges each band's as a share of it.
//
// On a tube slot (FX_ON_TUBE) traced through its chunk's own rings on the chunk's closed hull, as body_core is; on a
// mesh slot's sphere from its centre, cg_VfxMesh.values.z an intensity which below 1 shrinks it. It tests the scene's
// depth where the ray passes nearest and writes none. Only its glow blooms. CgEnergyWave.TOON.
#domain surface
#include "crystalgraphics:shaders/lib/vfx/common.glsl"
#include "crystalgraphics:shaders/lib/vfx/volume.glsl"
#include "crystalgraphics:shaders/lib/vfx/bands.glsl"
Material { BlendMode AlphaComposite  ShadingModel Unlit  Faces Back  VertexFactory VfxMesh, TubeClosed  PerDrawValues On }

Properties {
    _Bands  ("Its bands' colours, innermost first, then the halo's; each alpha a strength", color[4]) = (1, 1, 1, 1), (0.4, 0.85, 1, 1), (0.1, 0.45, 1, 1), (0.2, 0.55, 1, 0.5)
    _Edges  ("Each band's outer edge, a share of the size, ascending: the halo runs from the last to 1", vec4) = (0.41, 0.58, 0.74, 0)
    _Count  ("How many bands, 1 to 3", float) = 3.0
    _Glows  ("Light past white each band glows with, which is what blooms; w the halo's", vec4) = (0.6, 0.35, 0, 0.35)
    _Soft   ("How the halo falls away: 1 evenly, more keeps it close", float) = 2.0
}

struct v2f { vec3 world; };

void surface(in v2f i, inout CgSurface s) {
    vec3 eye = CG_CAMERA_POSITION;
    vec3 ray = cg_PixelRay();
#ifdef FX_ON_TUBE
    int row = cg_Tube.row, first = cg_Tube.first;
    vec4 header = fx_path_header(_FxPath, row);
    int count = max(int(header.x + 0.5), 2);
    int end = min(first + cg_Tube.owned, count - 1);
    // Traced past its own segments and drawn only where the ray passes nearest one of its own, as body_core.
    int lo = max(first - FX_CORE_MARGIN, 0), hi = min(end + FX_CORE_MARGIN, count - 1);
    vec4 near;
    int segment;
    fx_core_nearest(_FxPath, row, lo, hi, cg_Tube.origin, eye, ray, cg_Tube.size, near, segment);
    if (segment < first || segment >= end || near.x <= 0.0 || near.y >= 1.0) discard;
    float along = near.x, b = near.y;
#else
    vec3 centre = CG_OBJECT_TO_WORLD[3].xyz;
    // Into the hull's own space, where it is the unit sphere.
    mat3 toLocal = inverse(mat3(CG_OBJECT_TO_WORLD));
    vec3 o = toLocal * (eye - centre), dl = toLocal * ray;
    float along = -dot(o, dl) / dot(dl, dl);
    float b = length(o + dl * along) / max(clamp(cg_VfxMesh.values.z, 0.0, 1.0), 1.0e-3);
    if (b >= 1.0 || along <= 0.0) discard;
#endif
    s.depth = cg_EyeDepth(eye + ray * max(along, 0.05));
    int bands = int(clamp(_Count, 1.0, 3.0) + 0.5);
    float glow;
    vec4 band = fx_bands(b, max(fwidth(b), 1.0e-4), _Bands(0), _Bands(1), _Bands(2), _Edges.xyz, bands,
                         _Glows.xyz, glow);
    float outer = bands > 2 ? _Edges.z : bands > 1 ? _Edges.y : _Edges.x;
    vec4 halo = _Bands(3);
    float haloA = fx_band_halo(b, outer, _Soft) * halo.a;
    // The bands over their halo, premultiplied.
    s.baseColor = band.rgb * band.a + halo.rgb * haloA * (1.0 - band.a);
    s.opacity = band.a + haloA * (1.0 - band.a);
    s.emissive = band.rgb * band.a * glow + halo.rgb * haloA * (1.0 - band.a) * _Glows.w;
}
