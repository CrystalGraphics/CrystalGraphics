// The final blast's dome: a shell of plasma bursting outward, nearly clear face-on so the core burns through it, its body
// and a crisp bright rim in cel bands whose width the flow breaks up, threads of white heat wandering up it from the
// ground, and faint wisps of plasma inside. Where it meets the ground it burns a band of light and fades into it rather than being cut. As it cools to
// blue it erodes into dimming shreds until nothing is left. Its front wall veils what is behind it, a little face-on and
// more toward the rim, so smoke and ink on its far side read as behind it; what is in front hides it by depth. Drawn on
// CgVfxFrame.mesh's sphere, both faces, premultiplied.
// CG_OBJECT_CUSTOM1.z is an intensity, .w the blast's progress 0..1. Colour A is the hot burst, colour B the cool shell,
// A's alpha a strength. CgEnergyWave.
#type spatial
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" }
Queue = "Transparent"

Properties {
    _Erode   ("Progress the dome starts breaking up at", float) = 0.3
    _Scale   ("Break-up frequency", float) = 3.0
    _Billow  ("Billows out of the sphere, share of its radius", float) = 0.16
    _Contact ("Width of the band of light where it meets the ground, share of its radius", float) = 0.12
    _Veil    ("How much its front wall dims what is behind it", float) = 1.0
    _Noise ("Noise", sampler3D) = "cg_noise"
}

struct v2f { vec3 world; vec3 local; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend ONE ONE_MINUS_SRC_ALPHA
        DepthTest LEQUAL
        DepthWrite OFF
        Cull OFF
    }

    void vertex(out v2f o) {
        vec3 p = cg_Position;
        float age = CG_OBJECT_CUSTOM0.z, seed = CG_OBJECT_CUSTOM0.w;
        float billow = fx_fbm(p * 1.8 + vec3(0.0, -age * 0.8, age * 0.5) + seed * 5.0, 3);
        p *= 1.0 + _Billow * billow;
        vec4 world = CG_OBJECT_TO_WORLD * vec4(p, 1.0);
        o.world = world.xyz;
        o.local = cg_Position;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * world;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        float progress = CG_OBJECT_CUSTOM1.w, seed = CG_OBJECT_CUSTOM0.w, age = CG_OBJECT_CUSTOM0.z;
        float heat = 1.0 - progress;
        vec3 n = normalize(i.world - CG_OBJECT_TO_WORLD[3].xyz);
        float face = abs(dot(n, normalize(i.world - FX_CAMERA))), rim = 1.0 - face;
        // Breaking up: sooner at the silhouette, so the outline tears first.
        float e = 0.5 + 0.5 * fx_fbm(i.local * _Scale + vec3(0.0, 0.0, -progress * 2.0) + seed * 19.0, 4);
        float threshold = mix(-0.1, 1.05, smoothstep(_Erode, 1.0, progress)) + 0.35 * pow(rim, 3.0);
        float aa = fwidth(e) + 0.002;
        float left = smoothstep(threshold - aa, threshold + aa, e);
        float torn = (1.0 - smoothstep(threshold, threshold + 0.05, e)) * step(0.0, threshold);
        float flow = 0.5 + 0.5 * fx_warped(i.local * 1.8 + vec3(0.0, -age * 1.2, 0.0) + seed * 11.0, 1.4);
        float detail = fx_warped(i.local * 5.5 + vec3(age * 0.6, -age * 1.6, 0.0) + seed * 5.0, 1.2);
        // Filaments stretched along the meridians, racing up from the ground, and finer, faster ones between them, both
        // bent by the flow so they wander like arcs rather than run straight.
        vec3 bend = vec3(detail, 0.0, flow - 0.5) * 0.35;
        float filament = fx_ridged(vec3(i.local.x * 3.0, i.local.y * 0.9 - age * 1.8, i.local.z * 3.0) + bend + seed * 7.0, 3);
        float fine = fx_ridged(vec3(i.local.x * 7.0, i.local.y * 2.2 - age * 3.0, i.local.z * 7.0) + bend * 0.6 + seed * 3.0, 3);
        // Cel bands: nearly clear face-on, a body toward the rim, the bright rim, and a hot line at its very edge; the
        // fine detail makes every band's edge intricate.
        float energy = rim * rim * 1.3 + flow * 0.4 + detail * 0.08;
        float ea = fwidth(energy) + 0.004;
        float body = smoothstep(0.42 - ea, 0.42 + ea, energy);
        float bright = smoothstep(0.9 - ea, 0.9 + ea, energy);
        float outline = smoothstep(1.38 - ea, 1.38 + ea, energy);
        float fa = fwidth(filament) + 0.004, ff = fwidth(fine) + 0.004, fl = fwidth(flow) + 0.004;
        // Thin bright cores in a soft halo: threads of light, not shards.
        float vein = (smoothstep(0.9 - fa, 0.9 + fa, filament) + 0.3 * smoothstep(0.72, 0.95, filament)) * (0.35 + 0.65 * heat);
        float thread = (smoothstep(0.93 - ff, 0.93 + ff, fine) + 0.2 * smoothstep(0.78, 0.97, fine)) * (0.25 + 0.75 * heat);
        float glint = smoothstep(0.8 - fl, 0.8 + fl, flow) * body;
        vec3 cool = CG_OBJECT_CUSTOM3.rgb, hot = CG_OBJECT_CUSTOM2.rgb;
        vec3 shell = mix(cool, hot, heat * 0.6);
        vec3 col = shell * (0.06 + 0.3 * body) + mix(shell, hot, 0.5 + 0.5 * heat) * bright * 0.9
                + hot * (vein * 1.4 + thread * 0.6 + glint * 0.3 + outline * 0.8);
        if (gl_FrontFacing) {
            // Faint wisps inside, by how much of it the eye looks through: rising plasma sampled at the chord's middle, so
            // it shifts with the view, denser low down, its densest a step paler.
            vec3 centre = CG_OBJECT_TO_WORLD[3].xyz, ray = normalize(i.world - FX_CAMERA), toCentre = centre - FX_CAMERA;
            float size = length(CG_OBJECT_TO_WORLD[0].xyz), along = dot(ray, toCentre);
            float thick = sqrt(max(1.0 - (dot(toCentre, toCentre) - along * along) / (size * size), 0.0));
            vec3 inner = (FX_CAMERA + ray * along - centre) / size;
            float plasma = 0.5 + 0.5 * fx_warped(inner * 2.2 + vec3(0.0, -age * 0.9, 0.0) + seed * 13.0, 1.5);
            float fill = thick * thick * plasma * (1.0 - 0.6 * smoothstep(0.0, 0.8, inner.y));
            float pa = fwidth(fill) + 0.004;
            col += mix(shell, hot, 0.3) * (0.08 * fill + 0.07 * smoothstep(0.4 - pa, 0.4 + pa, fill));
        }
        col = (col + hot * torn * 0.8 * heat) * (0.35 + 0.9 * heat);
        // Where it meets the ground: a band of light just over the contact, fading into it with no line.
        float gap = CG_SCENE_EYE_DEPTH(gl_FragCoord.xy / CG_RESOLUTION) - cg_LinearEyeDepth(gl_FragCoord.z);
        float meet = clamp(gap / max(_Contact * length(CG_OBJECT_TO_WORLD[0].xyz), 1.0e-3), 0.0, 1.0);
        float fade = smoothstep(0.0, 0.3, meet), band = fade * (1.0 - smoothstep(0.3, 1.0, meet));
        col = col * fade + mix(shell, hot, 0.5) * band * (0.8 + filament) * (0.3 + 0.7 * heat);
        col *= fx_flicker(age, seed);
        float veil = gl_FrontFacing ? min(_Veil * (0.2 + 0.35 * body + 0.25 * bright), 0.85) * left * fade : 0.0;
        fragColor = vec4(col * left * CG_OBJECT_CUSTOM2.a * CG_OBJECT_CUSTOM1.z * (gl_FrontFacing ? 1.0 : 0.35),
                veil * CG_OBJECT_CUSTOM1.z);
    }
}

// Its light again, into the world's bloom: the Forward pass's code, added, so its veil never darkens the glows behind
// it there; on one draw with the Forward pass all the same.
Pass {
    Tags { "LightMode" = "Emissive" }
    RenderState {
        Blend ONE ONE
        DepthTest LEQUAL
        DepthWrite OFF
        Cull OFF
    }
}
