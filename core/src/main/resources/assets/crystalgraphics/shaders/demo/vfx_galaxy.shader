// A galaxy in a glass marble: black space and sharp stars inside the glass, and in it a grand-design spiral galaxy as
// Hubble shows one -- a compact golden bulge, two arms of cyan and violet star clusters strung with magenta
// star-forming knots, thin dark dust lanes along their inner edges with feathery spurs between, and a fine grain of
// stars over the whole disk, the arms turning slowly. The glass magnifies it as it refracts and mirrors the studio
// over it.
//
// The disk is laid out in the unwound angle, where every logarithmic arm is a straight line, so noise sampled there
// streams along the arms as real ones do. It is drawn where the ray crosses it, so it stays sharp. CgVfxShowcase.
#type spatial
#include "crystalgraphics:shaders/demo/vfx_common.glsl"

Tags { "RenderType" = "Opaque" }
Queue = "Geometry"

struct v2f { vec3 worldPos; vec3 normalWs; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        DepthTest LEQUAL
        DepthWrite ON
        Cull BACK
    }

    // erf, to 0.0004 (Vedder's tanh form).
    float galaxy_erf(float x) {
        x = clamp(x, -4.0, 4.0);
        return tanh(x * (1.1283792 + 0.1009 * x * x));
    }

    // Points scattered over the plane, {@code density} cells per marble radius, the share {@code 1 - keep} of them
    // holding one, {@code size} cells across: their light at {@code x}. A point is never drawn smaller than a pixel
    // ({@code pixel}, in marble radii) and dims as it spreads, so it never shimmers.
    float galaxy_points(vec2 x, float density, float keep, float size, float seed, float pixel) {
        vec2 g = x * density;
        vec2 cell = floor(g);
        float radius = max(size, pixel * density * 0.8);
        float energy = (size / radius) * (size / radius);
        float light = 0.0;
        for (int j = -1; j <= 1; j++) {
            for (int i = -1; i <= 1; i++) {
                vec2 c = cell + vec2(float(i), float(j));
                vec3 h = vfx_hash33(vec3(c, seed));
                if (h.z < keep) continue;
                float d = length(g - c - h.xy);
                light += (1.0 - smoothstep(0.0, radius, d)) * (0.35 + 0.65 * fract(h.z * 17.0));
            }
        }
        return light * energy;
    }

    // The disk at {@code x} in its own plane (marble radii): its light in rgb, its dust's opacity in a.
    vec4 galaxy_disk(vec2 x, float t, float pixel) {
        float r = length(x);
        float lr = log(max(r, 0.015));
        // The unwound angle: constant along each arm. The pattern turns toward lower angles, so the arms trail.
        float s = atan(x.y, x.x) - 2.4 * lr + t * 0.08;
        vec2 ring = vec2(cos(s), sin(s));
        float wobble = (vfx_fbm(vec3(ring * 1.4, lr * 0.9 + 5.0), 3) - 0.5) * 1.3;
        float phase = 2.0 * s + wobble;
        float arm = pow(0.5 + 0.5 * cos(phase), 3.0);
        float crest = pow(0.5 + 0.5 * cos(phase), 10.0);
        // Texture streaming along the arms: fast round the unwound circle, slow along log r.
        float streaks = vfx_fbm(vec3(ring * 7.0, lr * 1.6), 5);
        float edge = 1.0 - smoothstep(0.5, 0.74, r);
        float inner = exp(-r / 0.11);
        float young = smoothstep(0.06, 0.22, r) * edge;
        vec3 light = vec3(1.65, 1.05, 0.5) * inner * (0.7 + 0.5 * arm) * 1.6;
        // The arms' young stars, cyan-blue in places and violet in others.
        float hue = smoothstep(0.35, 0.7, vfx_fbm(vec3(ring * 2.5, lr * 1.2 + 9.0), 3));
        vec3 armColor = mix(vec3(0.3, 0.72, 1.8), vec3(0.95, 0.42, 1.8), hue);
        light += armColor * young * arm * (0.2 + 1.2 * streaks * streaks) * exp(-r / 0.55) * 3.2;
        // A soft pink glow of ionised gas along the arm crests.
        light += vec3(1.6, 0.35, 0.8) * young * crest * streaks * 0.7;
        // The diffuse glow of the disk between the arms, so they stand on a disk rather than in empty space.
        light += vec3(0.55, 0.6, 0.95) * exp(-r / 0.2) * edge * (0.25 + 0.2 * streaks);
        // Star clusters strung along the arms, pink knots where stars are forming, and a grain of old stars everywhere.
        light += vec3(0.75, 0.88, 1.7) * galaxy_points(x, 70.0, 0.5, 0.1, 1.0, pixel) * arm * young * 5.0;
        light += vec3(2.0, 0.3, 0.9) * galaxy_points(x, 26.0, 0.6, 0.22, 2.0, pixel) * crest * young * 3.5;
        light += vec3(1.3, 1.1, 0.85) * galaxy_points(x, 170.0, 0.45, 0.09, 3.0, pixel) * (inner * 2.0 + 0.25 * edge) * 1.2;
        // Dust: thin filaments along the inner edge of each arm, and feathery spurs crossing between them.
        float lane = pow(0.5 + 0.5 * cos(phase - 0.8), 7.0);
        float filaments = vfx_ridged(vec3(ring * 10.0, lr * 2.2), 4);
        float spurs = vfx_ridged(vec3(x * 16.0, 7.0), 3);
        float dust = lane * smoothstep(0.45, 0.8, filaments) + (1.0 - arm) * smoothstep(0.8, 0.95, spurs) * 0.5;
        dust *= smoothstep(0.035, 0.12, r) * edge;
        // Dust dims blue more than red, so its edges redden what shows through.
        light *= 1.0 - clamp(dust, 0.0, 1.0) * vec3(0.7, 0.85, 0.95);
        return vec4(light, clamp(dust * 0.95, 0.0, 0.95));
    }

    // Deep space behind the galaxy, in direction {@code d} of the marble's frame: sharp stars of every colour, a few
    // distant galaxies, and a nebula only just there.
    vec3 galaxy_space(vec3 d) {
        vec3 s = d * 90.0;
        vec3 cell = floor(s);
        vec3 at = fract(s) - 0.5 - (vfx_hash33(cell) - 0.5) * 0.6;
        float star = step(0.965, vfx_hash31(cell)) * exp(-dot(at, at) * 90.0) * (0.4 + vfx_hash31(cell + 2.0));
        vec3 tint = mix(vec3(1.0, 0.8, 0.6), vec3(0.65, 0.8, 1.35), vfx_hash31(cell + 5.0));
        vec3 far = d * 22.0;
        vec3 farCell = floor(far);
        vec3 h = vfx_hash33(farCell + 11.0);
        vec3 off = fract(far) - 0.5 - (h - 0.5) * 0.5;
        vec3 axis = normalize(h - 0.5);
        float squash = dot(off, axis);
        float smudge = step(0.975, vfx_hash31(farCell + 13.0)) * exp(-(dot(off, off) - squash * squash * 0.85) * 220.0);
        vec3 nebula = mix(vec3(0.3, 0.06, 0.45), vec3(0.06, 0.2, 0.45), vfx_fbm(d * 2.2, 3)) * pow(vfx_fbm(d * 3.0 + 7.0, 4), 3.0);
        return tint * star * 2.2 + vec3(1.2, 0.95, 0.8) * smudge * 0.5 + nebula * 0.45 + vec3(0.002, 0.002, 0.006);
    }

    void vertex(out v2f o) {
        vec4 world = CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0);
        o.worldPos = world.xyz;
        o.normalWs = CG_NORMAL_MATRIX * cg_Normal;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * world;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        float t = CG_TIME;
        mat3 model = mat3(CG_OBJECT_TO_WORLD);
        mat3 toObject = inverse(model);
        vec3 centre = CG_OBJECT_TO_WORLD[3].xyz;
        float floorY = centre.y - CG_OBJECT_CUSTOM3.x;
        // A pixel's size in marble radii, taken before any branch.
        float pixel = length(fwidth(i.worldPos)) / length(CG_OBJECT_TO_WORLD[0].xyz);
        vec3 n = normalize(i.normalWs);
        vec3 v = normalize(VFX_CAMERA - i.worldPos);
        float nv = max(dot(n, v), 0.0);
        // Into the glass: refracted, so the galaxy is magnified and bends at the rim.
        vec3 refracted = refract(-v, n, 1.0 / 1.45);
        vec3 entry = toObject * (i.worldPos - centre);
        vec3 dir = normalize(toObject * refracted);
        float span = max(-2.0 * dot(entry, dir), 0.0);
        // Deep space, and the bulge as a Gaussian integrated along the ray.
        vec3 behind = galaxy_space(dir);
        float along = -dot(entry, dir);
        vec3 nearest = entry + dir * along;
        float near2 = dot(nearest, nearest);
        float bulgeSize = 0.075;
        float bulge = exp(-near2 / (bulgeSize * bulgeSize)) * 1.77 * bulgeSize * 26.0 * (1.0 + 0.06 * sin(t * 1.3))
                + exp(-near2 / 0.03) * 0.35;
        vec3 bulgeColor = vec3(1.8, 1.15, 0.5);
        vec3 color;
        float plane = -entry.y / dir.y;
        vec2 hit = (entry + dir * plane).xz;
        if (abs(dir.y) > 1.0e-4 && plane > 0.0 && plane < span && length(hit) < 0.8) {
            // The disk lit thicker the more steeply it is seen past; its dust hides the bulge and the space behind it.
            float slant = min(1.0 / abs(dir.y), 5.0);
            vec4 disk = galaxy_disk(hit, t, pixel * slant);
            float dust = 1.0 - pow(1.0 - disk.a, slant);
            float before = 0.5 * (1.0 + galaxy_erf((plane - along) / bulgeSize));
            color = (behind + bulgeColor * bulge * (1.0 - before)) * (1.0 - dust) + disk.rgb * min(slant, 2.5) * 0.6
                    + bulgeColor * bulge * before;
        } else {
            color = behind + bulgeColor * bulge;
        }
        // The glass: the studio mirrored over it, strongest at the rim, and the key light's highlight.
        float fresnel = 0.04 + 0.96 * pow(1.0 - nv, 5.0);
        vec3 glass = vfx_studio(i.worldPos, reflect(-v, n), 0.0, floorY)
                + vfx_direct(n, v, VFX_KEY_DIR, VFX_KEY_COLOR, vec3(1.0), 0.0, 0.03) * 2.0;
        color = color * (1.0 - fresnel) + glass * fresnel;
        fragColor = vec4(vfx_aces(color), 1.0);
    }
}
