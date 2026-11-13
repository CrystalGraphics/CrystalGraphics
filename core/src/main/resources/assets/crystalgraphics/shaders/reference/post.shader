// The reference post material (docs/SHADERS.md, docs/MATERIAL_FORMAT.md § 7): a soft glow over the finished world. A
// half-size step keeps the bright part of the scene, blurred; the last step adds it back over the scene.
// CgPostMaterial.of("crystalgraphics:shaders/reference/post.shader") draws it at its Location.
#domain post
Material { Location AFTER_WORLD  SceneColorMargin 0.01 }

Properties {
    _Threshold ("What counts as bright, linear", float, range(0, 4)) = 1
    _Strength  ("How much glow is added", float, range(0, 2)) = 0.5
}

// A step writes a texture of its own: its format and its size as a share of the screen's
Step bright {
    Target RGBA16_FLOAT  Scale 0.5
    void surface(in CgScreen i, inout CgPostSurface s) {
        vec2 texel = 1.0 / CG_RESOLUTION;
        vec3 sum = vec3(0.0);
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) sum += CG_SCENE_COLOR(i.uv + vec2(x, y) * texel * 2.0).rgb;
        }
        vec3 c = sum / 9.0;
        s.color = max(c - vec3(_Threshold), vec3(0.0));
    }
}

// The last step draws onto the scene: s.color starts as the scene, in linear light
Step over {
    void surface(in CgScreen i, inout CgPostSurface s) {
        s.color += CG_STEP(bright, i.uv).rgb * _Strength * POST_WEIGHT;   // POST_WEIGHT: CgPostMaterial.weight
    }
}
