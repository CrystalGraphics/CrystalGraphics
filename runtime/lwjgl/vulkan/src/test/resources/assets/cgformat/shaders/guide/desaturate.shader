// docs/MATERIAL_FORMAT.md § 7
#domain post
Material { Location AFTER_WORLD }
Properties { _Amount ("How grey", float, range(0, 1)) = 1 }
void surface(in CgScreen i, inout CgPostSurface s) {
    vec3 c = s.color;
    s.color = mix(c, vec3(dot(c, vec3(0.2126, 0.7152, 0.0722))), _Amount * POST_WEIGHT);
}
