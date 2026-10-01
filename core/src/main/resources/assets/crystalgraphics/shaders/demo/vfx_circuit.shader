// A neon circuit globe: a dark glossy board wrapped round the sphere as a cube-sphere (so no poles), routed with traces
// that bend at 45 degrees, vias and chips with glowing cores; packets of light stream along the traces in cyan and
// orange, and every few seconds a power surge ripples out across the whole board. CgVfxShowcase.
//
// The traces are Truchet tiles: each cell links its four edge midpoints in two chamfered pairs, so every trace runs on
// into the next cell. Each pair turns about a cell corner, and turning one way about even corners and the other about
// odd ones keeps the flow continuous from cell to cell.
#type spatial
#include "crystalgraphics:shaders/demo/vfx_common.glsl"

Tags { "RenderType" = "Opaque" }
Queue = "Geometry"

struct v2f { vec3 worldPos; vec3 normalWs; vec3 objPos; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        DepthTest LEQUAL
        DepthWrite ON
        Cull BACK
    }

    // The cube face {@code p} is on, and where on it: xy in [-1, 1], equi-angular so cells keep their size; z the
    // face. Neighbouring faces share each edge's coordinate, so the cells line up across it.
    vec3 circuit_face(vec3 p) {
        vec3 a = abs(p);
        vec2 uv;
        float face;
        if (a.x >= a.y && a.x >= a.z) {
            uv = vec2(p.z, p.y) / a.x;
            face = p.x > 0.0 ? 0.0 : 1.0;
        } else if (a.y >= a.z) {
            uv = vec2(p.x, p.z) / a.y;
            face = p.y > 0.0 ? 2.0 : 3.0;
        } else {
            uv = vec2(p.x, p.y) / a.z;
            face = p.z > 0.0 ? 4.0 : 5.0;
        }
        return vec3(atan(uv) * 4.0 / 3.14159265, face);
    }

    // The distance from {@code x} to the segment from {@code a} to {@code b}, and how far along it the nearest point is.
    vec2 circuit_segment(vec2 x, vec2 a, vec2 b) {
        vec2 ab = b - a;
        float h = clamp(dot(x - a, ab) / dot(ab, ab), 0.0, 1.0);
        return vec2(length(x - a - ab * h), h * length(ab));
    }

    // The trace from the left midpoint to the top one, chamfered at 45 degrees: the distance to it, and how far along
    // it in [0, 1].
    vec2 circuit_trace(vec2 x) {
        vec2 s1 = circuit_segment(x, vec2(-0.5, 0.0), vec2(-0.2, 0.0));
        vec2 s2 = circuit_segment(x, vec2(-0.2, 0.0), vec2(0.0, 0.2));
        vec2 s3 = circuit_segment(x, vec2(0.0, 0.2), vec2(0.0, 0.5));
        vec2 best = s1;
        if (s2.x < best.x) best = vec2(s2.x, 0.3 + s2.y);
        if (s3.x < best.x) best = vec2(s3.x, 0.58284 + s3.y);
        return vec2(best.x, best.y / 0.88284);
    }

    // Which way along a trace the packets run: {@code along} measured from its start, turning about corner
    // {@code pivot} (whole-cell coordinates), counter-clockwise from its start or not.
    float circuit_flow(float along, vec2 pivot, bool counterClockwise) {
        float ccw = counterClockwise ? along : 1.0 - along;
        return mod(pivot.x + pivot.y, 2.0) < 0.5 ? ccw : 1.0 - ccw;
    }

    void vertex(out v2f o) {
        vec4 world = CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0);
        o.worldPos = world.xyz;
        o.normalWs = CG_NORMAL_MATRIX * cg_Normal;
        o.objPos = cg_Position;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * world;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        float t = CG_TIME;
        float floorY = CG_OBJECT_TO_WORLD[3].y - CG_OBJECT_CUSTOM3.x;
        vec3 n = normalize(i.normalWs);
        vec3 v = normalize(VFX_CAMERA - i.worldPos);
        vec3 p = normalize(i.objPos);
        vec3 face = circuit_face(p);
        const float CELLS = 7.0;
        vec2 g = (face.xy * 0.5 + 0.5) * CELLS;
        vec2 cell = floor(g);
        vec2 local = fract(g) - 0.5;
        // A pixel's size in cells, from the sphere rather than the face coordinates, which jump at the face edges.
        float aa = length(fwidth(p)) * CELLS / 1.5708 * 0.75;
        float pick = vfx_hash31(vec3(cell, face.z * 13.0 + 1.0));
        float chipPick = vfx_hash31(vec3(cell, face.z * 13.0 + 2.0));
        bool chip = chipPick < 0.09;
        bool mirrored = pick > 0.5;

        float trace = 0.0, packet = 0.0, glowNear = 8.0, via = 0.0, viaHole = 0.0, chipBody = 0.0, chipCore = 0.0, pins = 0.0;
        vec3 chipTint = vec3(0.0);
        if (chip) {
            // A chip: a dark package with pins on every side, the middle ones meeting the neighbours' traces.
            vec2 box = abs(local);
            chipBody = 1.0 - smoothstep(0.33 - aa, 0.33 + aa, max(box.x, box.y));
            float bevel = smoothstep(0.26, 0.33, max(box.x, box.y)) * chipBody;
            chipBody -= bevel * 0.5;
            float coreSize = 0.11 + 0.02 * sin(t * 3.0 + chipPick * 60.0);
            chipCore = 1.0 - smoothstep(coreSize - aa, coreSize + aa, max(box.x, box.y));
            chipTint = mix(vec3(0.3, 1.0, 1.8), vec3(1.9, 0.6, 0.15), step(0.045, chipPick));
            for (int side = 0; side < 4; side++) {
                vec2 q = side == 0 ? local : side == 1 ? vec2(-local.x, local.y) : side == 2 ? local.yx : vec2(-local.y, local.x);
                float lead = (1.0 - smoothstep(0.03 - aa, 0.03 + aa, abs(q.y))) * step(0.33, q.x);
                float stubs = (1.0 - smoothstep(0.022 - aa, 0.022 + aa, abs(abs(q.y) - 0.17))) * step(0.33, q.x) * step(q.x, 0.42);
                pins = max(pins, max(lead, stubs));
            }
        } else {
            vec2 x = mirrored ? vec2(local.x, -local.y) : local;
            vec2 one = circuit_trace(x);
            vec2 two = circuit_trace(-x);
            float flowOne = circuit_flow(one.y, cell + vec2(0.0, mirrored ? 0.0 : 1.0), !mirrored);
            float flowTwo = circuit_flow(two.y, cell + vec2(1.0, mirrored ? 1.0 : 0.0), !mirrored);
            float d = min(one.x, two.x);
            float flow = one.x < two.x ? flowOne : flowTwo;
            trace = 1.0 - smoothstep(0.045 - aa, 0.045 + aa, d);
            glowNear = d;
            // Packets: a bright head and a fading tail running along the flow, one a cell. One speed everywhere: a
            // speed varying across the board, multiplied by the time, combs the traces into stripes.
            float behind = fract(t * 1.1 - flow);
            packet = exp(-behind * 9.0);
            // Vias: copper rings, some cells carrying one where the trace bends.
            if (vfx_hash31(vec3(cell, face.z * 13.0 + 3.0)) < 0.3) {
                float r = length(x - vec2(-0.1, 0.1));
                via = 1.0 - smoothstep(0.085 - aa, 0.085 + aa, r);
                viaHole = 1.0 - smoothstep(0.04 - aa, 0.04 + aa, r);
            }
        }
        // Regions of traffic, and which colour each runs in.
        float activity = smoothstep(0.35, 0.65, vfx_fbm(p * 2.2 + vec3(0.0, t * 0.15, 0.0), 3));
        // Packets come and go in bursts: every cell runs one at the same point along its trace, so a finer drifting
        // field, read where the packet is, keeps most of them dark and the rest irregular.
        float burst = smoothstep(0.5, 0.72, vfx_noise(p * 7.0 + vec3(t * 0.6, -t * 0.4, t * 0.3)));
        vec3 cyan = vec3(0.25, 0.95, 1.8);
        vec3 orange = vec3(2.0, 0.65, 0.12);
        vec3 energy = mix(cyan, orange, smoothstep(0.55, 0.62, vfx_noise(p * 1.6 + vec3(9.0))));
        // A power surge: every five seconds a front rolls out from somewhere across the board.
        float wave = 5.0;
        float strike = floor(t / wave);
        float age = t - strike * wave;
        vec3 from = normalize(vfx_hash33(vec3(strike, 7.0, 7.0)) - 0.5);
        float surge = exp(-pow((acos(clamp(dot(p, from), -1.0, 1.0)) - age * 1.6) * 7.0, 2.0)) * exp(-age * 0.5);

        // The board: a dark navy mask under a clear coat mirroring the studio.
        vec3 board = vfx_pbr_studio(i.worldPos, floorY, n, v, vec3(0.008, 0.012, 0.03), 0.0, 0.3);
        vec3 copper = vfx_pbr_studio(i.worldPos, floorY, n, v, vec3(0.75, 0.42, 0.22), 1.0, 0.35);
        float lit = packet * burst * (0.4 + 1.6 * activity) + surge * 2.5;
        vec3 color = board;
        color = mix(color, copper * 0.35 + energy * (0.12 + lit * 2.5) + vec3(1.0) * pow(packet, 6.0) * burst * activity * 1.5, trace);
        color += energy * exp(-glowNear / 0.035) * lit * 0.5 * (1.0 - trace);
        color = mix(color, copper * 0.6 + energy * lit * 0.6, via * (1.0 - viaHole));
        color = mix(color, vec3(0.0), viaHole * 0.9);
        // Chips: a dark glossy package, its core glowing and its pins copper, lit by the surge too.
        vec3 housing = vfx_pbr_studio(i.worldPos, floorY, n, v, vec3(0.015, 0.015, 0.02), 0.0, 0.2);
        color = mix(color, housing, chipBody);
        color = mix(color, chipTint * (1.2 + 0.4 * sin(t * 3.0 + chipPick * 60.0) + surge * 2.0), chipCore);
        color = mix(color, copper * 0.9 + energy * surge * 2.0, pins * (1.0 - chipBody));
        // The surge washes faintly over the whole board as it passes.
        color += energy * surge * 0.15;
        fragColor = vec4(vfx_aces(color), 1.0);
    }
}
