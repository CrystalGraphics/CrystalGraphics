// A kernel lowered below compute (gpu-compute C5): where this invocation is, as kernel.glsl says it for a compute
// stage. A lowered kernel runs as a vertex, geometry or fragment stage; the engine sets cg_Dispatch per draw and calls
// _cg_setup (an element) or _cg_setup_texel (an image kernel's texel) before the kernel.
//
//     void Simulate() {
//         Particle p = STATE(CG_ELEMENT);       // the same names as in compute
//         ...
//     }
#pragma once

// Base xyz (unused below compute) and count xyz. A count of -1 is an indirect dispatch's: its group counts are the
// three uints at _cg_DispatchArgsAt in _cg_DispatchArgs.
uniform int cg_Dispatch[6];
uniform usamplerBuffer _cg_DispatchArgs;
uniform int _cg_DispatchArgsAt;

ivec3 _cg_count;
ivec3 _cg_id;
int _cg_element;

#define CG_LOCAL_SIZE     ivec3(CG_LOCAL_SIZE_X, CG_LOCAL_SIZE_Y, CG_LOCAL_SIZE_Z)
#define CG_DISPATCH_BASE  ivec3(0)
#define CG_DISPATCH_COUNT _cg_count
#define CG_DISPATCH_ID    _cg_id
#define CG_ELEMENT        _cg_element
#define CG_IN_RANGE       all(lessThan(_cg_id, _cg_count))
#define CG_TEXEL          _cg_id

void _cg_counts() {
    if (cg_Dispatch[3] >= 0) {
        _cg_count = ivec3(cg_Dispatch[3], cg_Dispatch[4], cg_Dispatch[5]);
    } else {
        _cg_count = ivec3(int(texelFetch(_cg_DispatchArgs, _cg_DispatchArgsAt).r),
                          int(texelFetch(_cg_DispatchArgs, _cg_DispatchArgsAt + 1).r),
                          int(texelFetch(_cg_DispatchArgs, _cg_DispatchArgsAt + 2).r)) * CG_LOCAL_SIZE;
    }
}

// Element e of the dispatch, x fastest.
void _cg_setup(int e) {
    _cg_counts();
    _cg_element = e;
    int across = max(_cg_count.x, 1), plane = max(_cg_count.x * _cg_count.y, 1);
    _cg_id = ivec3(e % across, (e / across) % max(_cg_count.y, 1), e / plane);
}

// An image kernel's texel p.
void _cg_setup_texel(ivec3 p) {
    _cg_counts();
    _cg_id = p;
    _cg_element = p.x + _cg_count.x * (p.y + _cg_count.y * p.z);
}
