// The depth of field demo's pillars and board (CgDepthOfFieldDemo): an unlit checker in the record's custom0 colour,
// fine enough that a blur shows.
#domain surface
Material { ShadingModel Unlit  Fog Off }

struct v2f {
    vec3 worldPos;
};

void vertex(inout CgVertex v, out v2f o) {
    o.worldPos = (CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0)).xyz;
}

void surface(in v2f i, inout CgSurface s) {
    vec3 cell = floor(i.worldPos * 4.0);
    float check = mod(cell.x + cell.y + cell.z, 2.0);
    s.emissive = CG_OBJECT_CUSTOM0.rgb * mix(0.35, 1.0, check);
}
