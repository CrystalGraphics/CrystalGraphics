// A GPU particle's drawn part from its record, after its step: Look's (look.compute), and the Step's own where the
// two run as one dispatch (CgVfxEmitterCompiler's StepLook). Its look over life or speed from its row's curves, its
// starts rolled per channel from its seed (Niagara's non-uniform ranges), its light from the voxel window at its
// block, and the floor under it; then its drawn slots, each channel a value over life rolled between two as its row
// says; then its light, its colour scale and brightness (Cascade's Light module).
//
// The includer defines LOOK_IN(k), the particle's record vector k, and declares FxDrawn, the properties look.compute
// declares, and CURVES and INSTANCES:
//
//     #define LOOK_IN(k) IN(look_e + (k))       // Look: the record in the pool's records
//     #define LOOK_IN(k) look_rec[k]            // StepLook: the record it just packed
//     FxDrawn d = look_part(part);              // part 0 the drawn part, 1 its slots, 2 its light; zero past those

vec4 look_instance(int at) {
    return uintBitsToFloat(INSTANCES(at));
}

// The particle's record word w, a float.
float look_word(int w) {
    return uintBitsToFloat(LOOK_IN(w / 4)[w % 4]);
}

// A curve row's vec4s: _Texels shape samples, _Texels tint samples, two start colours, two start scales, then its
// domain's speeds (CgVfxGpuEmitter.writeCurves).
vec4 look_curve(int at, float t) {
    float x = clamp(t, 0.0, 1.0) * float(_Texels - 1);
    int i0 = int(floor(x));
    int i1 = min(i0 + 1, _Texels - 1);
    return mix(CURVES(at + i0), CURVES(at + i1), x - float(i0));
}

// A block of four channels at vec4 block, a drawn slot's or a light's: its low and high ends over life, mixed by each
// channel's roll (0 the low end alone; 1 by the seed, fixed for its life; 2 rolled again each step, keyed by its age;
// 3 its instance's bound user parameter, from instance vec4 bound), plus 10 on the viewer's clock: its life over the
// instance's time rate, as Cascade's SubUV bUseRealTime divides its relative time by the time dilation. b salts its rolls.
vec4 look_block(int block, int b, float t, uint bits, float age, int bound, int inst) {
    vec4 rolls = CURVES(block + 2 * _Texels), mixed, value;
    float rate = look_instance(inst + 2).z;
    for (int c = 0; c < 4; c++) {
        bool real = rolls[c] > 9.5;
        float roll = real ? rolls[c] - 10.0 : rolls[c];
        float u = real ? (rate > 0.0 ? min(t / rate, 1.0) : 1.0) : t;
        uint key = roll > 1.5 ? bits ^ floatBitsToUint(age) : bits;
        float m = roll > 0.5 && roll < 2.5 ? fx_rand(key, uint(b), 40u + uint(c)) : 0.0;
        value[c] = mix(look_curve(block, u)[c], look_curve(block + _Texels, u)[c], m);
        if (roll > 2.5 && bound >= 0) value[c] = look_instance(bound)[c];
    }
    return value;
}

// Drawn slot s's channels, its block b from the slots' first, its bound channels lane s from _DrawnBindAt.
vec4 look_drawn(int at, int b, int s, float t, uint bits, float age, int inst) {
    return look_block(at + _Texels * 2 + 6 + b * (2 * _Texels + 1), b, t, bits, age, _DrawnBindAt >= 0 ? inst + _DrawnBindAt + s : -1, inst);
}

// Unreal's rotator (roll, pitch, yaw) as the host's quaternion: Niagara's RotatorToQuat on radians, then through Unreal's
// axes, which swap y and z (CgUnits.rotatorToHost).
vec4 look_rotator(vec3 r) {
    vec3 h = r * 0.5, s = sin(h), c = cos(h);
    float x = c.x * s.y * s.z - s.x * c.y * c.z, y = -c.x * s.y * c.z - s.x * c.y * s.z;
    float z = c.x * c.y * s.z - s.x * s.y * c.z, w = c.x * c.y * c.z + s.x * s.y * s.z;
    return vec4(-x, -z, -y, w);
}

// The rotator (roll, pitch, yaw) turning a onto b, Unreal's axes: CgVfxMeshRotation.between, Unreal's FindBetweenVectors
// then Rotator. Opposite vectors turn about an axis square to a; a zero vector turns nothing.
vec3 look_between(vec3 a, vec3 b) {
    float norm = sqrt(dot(a, a) * dot(b, b)), w = norm + dot(a, b);
    vec3 v;
    if (w >= 1e-6 * norm) {
        v = cross(a, b);
    } else {
        w = 0.0;
        vec3 q = abs(a);
        v = cross(a, q.x > q.y && q.x > q.z ? vec3(0.0, 1.0, 0.0) : vec3(-1.0, 0.0, 0.0));
    }
    float len = dot(v, v) + w * w;
    if (len < 1e-8) return vec3(0.0);
    vec4 r = vec4(v, w) * inversesqrt(len);
    float singular = r.z * r.x - r.w * r.y;
    if (singular < -0.4999995) return vec3(0.0, -1.5707963, -2.0 * atan(r.x, r.w));
    if (singular > 0.4999995) return vec3(0.0, 1.5707963, 2.0 * atan(r.x, r.w));
    return vec3(atan(-2.0 * (r.w * r.x + r.y * r.z), 1.0 - 2.0 * (r.x * r.x + r.y * r.y)), asin(2.0 * singular),
                atan(2.0 * (r.w * r.z + r.x * r.y), 1.0 - 2.0 * (r.y * r.y + r.z * r.z)));
}

// Three rolls picked per axis by a vec4's w, 1 + x + 3y + 9z (CgVfxMeshRotation.rollCode).
vec3 look_rolls(vec3 roll, float w) {
    int code = int(w + 0.5) - 1;
    return vec3(roll[code % 3], roll[code / 3 % 3], roll[code / 9]);
}

// a b: b's turn, then a's.
vec4 look_mul(vec4 a, vec4 b) {
    return vec4(a.w * b.xyz + b.w * a.xyz + cross(a.xyz, b.xyz), a.w * b.w - dot(a.xyz, b.xyz));
}

// Part `part` of the particle's drawn element.
FxDrawn look_part(int part) {
    FxDrawn d;
    d.place = d.motion = d.state = d.light = d.color = d.scale = vec4(0.0);
    if (part == 1) {
        vec4 positionAge = uintBitsToFloat(LOOK_IN(FX_RECORD_POSITION_AGE));
        vec4 lifeSeed = uintBitsToFloat(LOOK_IN(FX_RECORD_LIFE_SEED));
        float t = min(positionAge.w / lifeSeed.x, 1.0);
        int at = int(LOOK_IN(FX_RECORD_HEAT_ID).w) * _RowVectors;
        uint bits = floatBitsToUint(lifeSeed.y);
        int slotInst = _InstanceAt + int(LOOK_IN(FX_RECORD_HEAT_ID).z) * _InstanceRow;
        vec4 slots[5];
        int b = 0;
        for (int s = 0; s < 5; s++) {
            slots[s] = vec4(0.0);
            if ((_Drawn & (1 << s)) != 0) slots[s] = look_drawn(at, b++, s, t, bits, positionAge.w, slotInst);
        }
        d.place = slots[0]; d.motion = slots[1]; d.state = slots[2]; d.light = slots[3]; d.color = slots[4];
        if ((_Drawn & 32) != 0) {
            // CgVfxMeshRotation, Cascade's: its start plus its rate times its age, each rolled from its seed, or where its
            // rate changes over life (the rate's end's w) its life times the rolled rate times one integral plus the
            // other; then its instance's turn where it inherits it.
            int r = at + _Texels * 2 + 6 + b * (2 * _Texels + 1);
            vec4 from = CURVES(r), to = CURVES(r + 1), rateFrom = CURVES(r + 2), rateTo = CURVES(r + 3);
            vec3 roll = vec3(fx_rand(bits, 0u, 30u), fx_rand(bits, 0u, 31u), fx_rand(bits, 0u, 32u));
            vec3 rateRoll = vec3(fx_rand(bits, 0u, 33u), fx_rand(bits, 0u, 34u), fx_rand(bits, 0u, 35u));
            vec3 rate = mix(rateFrom.xyz, rateTo.xyz, look_rolls(rateRoll, rateFrom.w));
            // A start or a rate stored at birth (CgVfxSpawnInput.MESH_ORIENTATION, MESH_ROTATION_RATE).
            if (_TurnRateAt >= 0) rate = vec3(look_word(_TurnRateAt), look_word(_TurnRateAt + 1), look_word(_TurnRateAt + 2));
            // Its clock: the age, or where the rate changes over life its scale integrated to the age (the Step's
            // step_turn_clock); the other integral is what the steps add.
            bool overLife = rateTo.w > 0.5;
            vec3 clock = overLife ? lifeSeed.x * look_curve(r + 4, t).xyz : vec3(positionAge.w);
            vec3 added = overLife ? lifeSeed.x * look_curve(r + 4 + _Texels, t).xyz : vec3(0.0);
            vec3 turned = rate * clock + added;
            // A collision damps the base rate from the clock it strikes at: rate * (base + scale * clock), the turn so
            // far kept (ParticleModules_Collision.cpp:421-426).
            if (_TurnStateAt >= 0) {
                vec3 base = vec3(look_word(_TurnStateAt), look_word(_TurnStateAt + 1), look_word(_TurnStateAt + 2));
                vec3 scale = vec3(look_word(_TurnStateAt + 3), look_word(_TurnStateAt + 4), look_word(_TurnStateAt + 5));
                turned = rate * (base + scale * clock) + added;
            }
            // The start's end's w: 1 inheriting, plus 2 turned toward its velocity at birth, which the stored turn then
            // holds (Unreal's axes) and the row's vec4 after the rate's integrals its mesh's axis.
            bool toward = to.w > 1.5;
            vec3 stored = _TurnAt >= 0 ? vec3(look_word(_TurnAt), look_word(_TurnAt + 1), look_word(_TurnAt + 2)) : vec3(0.0);
            vec3 start = _TurnAt >= 0 && !toward ? stored : mix(from.xyz, to.xyz, look_rolls(roll, from.w));
            if (toward) start += look_between(CURVES(r + 4 + (overLife ? 2 * _Texels : 0)).xyz, stored);
            vec3 rotator = start + turned;
            vec4 q = look_rotator(rotator);
            if (mod(to.w, 2.0) > 0.5) {
                int inst = _InstanceAt + int(LOOK_IN(FX_RECORD_HEAT_ID).z) * _InstanceRow;
                q = look_mul(look_instance(inst + 7), q);
            }
            d.scale = q;
        }
    } else if (part == 2) {
        // Its light: Cascade's colour scale and brightness at its relative time, each frame (ParticleModules.cpp:3266-3267).
        vec4 positionAge = uintBitsToFloat(LOOK_IN(FX_RECORD_POSITION_AGE));
        vec4 lifeSeed = uintBitsToFloat(LOOK_IN(FX_RECORD_LIFE_SEED));
        float t = min(positionAge.w / lifeSeed.x, 1.0);
        int at = int(LOOK_IN(FX_RECORD_HEAT_ID).w) * _RowVectors;
        int inst = _InstanceAt + int(LOOK_IN(FX_RECORD_HEAT_ID).z) * _InstanceRow;
        d.place = look_block(at + _LightAt, 7, t, floatBitsToUint(lifeSeed.y), positionAge.w,
                _LightBindAt >= 0 ? inst + _LightBindAt : -1, inst);
    } else if (part == 0) {
        vec4 positionAge = uintBitsToFloat(LOOK_IN(FX_RECORD_POSITION_AGE));
        vec4 velocitySize = uintBitsToFloat(LOOK_IN(FX_RECORD_VELOCITY_SIZE));
        vec4 lifeSeed = uintBitsToFloat(LOOK_IN(FX_RECORD_LIFE_SEED));
        uvec4 heatId = LOOK_IN(FX_RECORD_HEAT_ID);
        vec3 position = positionAge.xyz, velocity = velocitySize.xyz;
        float age = positionAge.w, life = lifeSeed.x, size = velocitySize.w;
        float seed = lifeSeed.y, spin = lifeSeed.z, heat = uintBitsToFloat(heatId.x);
        float t = min(age / life, 1.0);
        if (_OrbitAt >= 0) {
            // Cascade's Orbit: drawn at its offset turned by its rotation plus its rate times its age, Euler angles in
            // Unreal's (roll, pitch, yaw) (ParticleEmitterInstances.cpp CalculateOrbitOffset, reset each tick :1712-1720).
            vec3 offset = vec3(look_word(_OrbitAt), look_word(_OrbitAt + 1), look_word(_OrbitAt + 2));
            vec3 turn = vec3(look_word(_OrbitAt + 3), look_word(_OrbitAt + 4), look_word(_OrbitAt + 5));
            vec3 rate = vec3(look_word(_OrbitAt + 6), look_word(_OrbitAt + 7), look_word(_OrbitAt + 8));
            vec4 q = look_rotator(turn + rate * age);
            position += offset + 2.0 * cross(q.xyz, cross(q.xyz, offset) + q.w * offset);
        }
        int at = int(heatId.w) * _RowVectors, starts = at + 2 * _Texels;
        vec4 speeds = CURVES(starts + 4);
        float u = speeds.x < 0.0 ? t : clamp((length(velocity) - speeds.x) / (speeds.y - speeds.x), 0.0, 1.0);
        vec4 shape = look_curve(at, u), tint = look_curve(at + _Texels, u);
        uint bits = floatBitsToUint(seed);
        int inst = _InstanceAt + int(heatId.z) * _InstanceRow;
        if (_LifeBindAt >= 0) {
            // CgVfxLifeBind: user parameters this step, mesh scale and sprite size, then colour and alpha.
            shape *= look_instance(inst + _LifeBindAt);
            tint *= look_instance(inst + _LifeBindAt + 1);
        }
        for (int i = 0, l = at + _LifeRollAt; i < _LifeRolls; i++) {
            // CgVfxLifeRoll, Cascade's size and colour over life on a uniform: rolled again each step, keyed by its age;
            // a header, then two ends, or two curves by life where its code is 3 to 5; past 5, one end or the other
            // (Cascade's bUseExtremes).
            vec4 sel = CURVES(l);
            int code = int(sel.w + 0.5);
            bool picks = code >= 6;
            if (picks) code -= 6;
            int target = code % 3;
            vec3 lo, hi;
            if (code >= 3) {
                lo = look_curve(l + 1, t).xyz;
                hi = look_curve(l + 1 + _Texels, t).xyz;
                l += 1 + 2 * _Texels;
            } else {
                lo = CURVES(l + 1).xyz;
                hi = CURVES(l + 2).xyz;
                l += 3;
            }
            uint key = bits ^ floatBitsToUint(age);
            vec3 r = vec3(fx_rand(key, uint(i), 50u), fx_rand(key, uint(i), 51u), fx_rand(key, uint(i), 52u));
            vec3 rolled = vec3(r[int(sel.x)], r[int(sel.y)], r[int(sel.z)]);
            if (picks) rolled = step(vec3(0.5), rolled);
            vec3 m = mix(lo, hi, rolled);
            if (target == 0) shape.xyz *= m;
            else if (target == 1) tint.rgb *= m;
            else tint.a *= m.x;
        }
        vec4 colorRoll = vec4(fx_rand(bits, 0u, 20u), fx_rand(bits, 0u, 21u), fx_rand(bits, 0u, 22u), fx_rand(bits, 0u, 23u));
        vec3 scaleRoll = vec3(fx_rand(bits, 0u, 24u), fx_rand(bits, 0u, 25u), fx_rand(bits, 0u, 26u));
        vec4 color = mix(CURVES(starts), CURVES(starts + 1), colorRoll);
        if (_ColorAt >= 0) {
            // A start stored at birth (CgVfxSpawnInput.COLOR); an alpha below 0 keeps the rolled one.
            vec4 born = vec4(look_word(_ColorAt), look_word(_ColorAt + 1), look_word(_ColorAt + 2), look_word(_ColorAt + 3));
            color = vec4(born.rgb, born.a < 0.0 ? color.a : born.a);
        }
        color *= tint;
        d.place = vec4(position, size * shape.w);
        d.motion = vec4(velocity, t);
        d.state = vec4(seed, spin, heat, color.a);
        d.color = color;
        // Cascade's size by speed: x and y times clamp(speed * scale, 1, max).
        vec4 bySpeed = CURVES(starts + 5);
        vec2 speedScale = clamp(length(velocity) * bySpeed.xy, vec2(1.0), bySpeed.zw);
        vec3 start = _ScaleAt >= 0 ? vec3(look_word(_ScaleAt), look_word(_ScaleAt + 1), look_word(_ScaleAt + 2))
                : mix(CURVES(starts + 2).xyz, CURVES(starts + 3).xyz, scaleRoll);
        d.scale = vec4(start * shape.xyz * vec3(speedScale, 1.0), age);
        ivec3 base = ivec3(_WorldBaseX, _WorldBaseY, _WorldBaseZ), wrap = ivec3(_WorldWrapX, _WorldWrapY, _WorldWrapZ);
        ivec3 originBlock = ivec3(INSTANCES(inst + 3).xyz);
        vec3 originFrac = look_instance(inst + 4).xyz;
        vec2 light = vec2(15.0);
        if (_WorldLive != 0) {
            light = fx_world_light(_World, _WorldSections, base, wrap, fx_world_block(originBlock, originFrac, position),
                    vec2(15.0));
        }
        vec2 ground = vec2(0.0);
        if (_Floors != 0) {
            float floorY = look_instance(inst + 2).y;
            if (_WorldLive != 0) {
                int w = _PreviousAt + 1;
                float previousY = _PreviousAt < 0 ? position.y : uintBitsToFloat(LOOK_IN(w / 4)[w % 4]);
                floorY = fx_world_floor(_World, _WorldSections, base, wrap, originBlock, originFrac, position, previousY);
            }
            if (!isnan(floorY)) ground = vec2(position.y - floorY, 1.0);
        }
        // Light packed into one lane (block + 16 sky), its spawn id's low 24 bits in the next.
        d.light = vec4(floor(light.x + 0.5) + 16.0 * floor(light.y + 0.5), float(heatId.y & 16777215u), ground);
    }
    return d;
}
