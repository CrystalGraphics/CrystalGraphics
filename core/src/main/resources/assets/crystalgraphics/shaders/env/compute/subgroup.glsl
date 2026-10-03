// Subgroup operations for kernels. Where the device has KHR_shader_subgroup's basic, vote, arithmetic, ballot and
// shuffle operations in compute (CG_SUBGROUPS), each macro is that operation; elsewhere the work group is the
// subgroup, through shared memory. A kernel written against these runs on both:
//
//     float sum = CG_SUBGROUP_ADD(value);                        // across this subgroup
//     if (CG_SUBGROUP_ELECT()) partial[CG_SUBGROUP_INDEX] = sum;  // one per subgroup: CG_SUBGROUP_COUNT of them
//     barrier();
//
// - Call them in control flow uniform across the work group: the emulation synchronises with barrier().
// - Scalars only, float, int or uint: the emulation keeps one uint per invocation.
// - Emulated, CG_SUBGROUP_BALLOT needs a work group of at most 128; the compiler refuses a larger one.
#pragma once

#ifdef CG_SUBGROUPS

#define CG_SUBGROUP_SIZE              int(gl_SubgroupSize)
#define CG_SUBGROUP_INVOCATION        int(gl_SubgroupInvocationID)
#define CG_SUBGROUP_INDEX             int(gl_SubgroupID)
#define CG_SUBGROUP_COUNT             int(gl_NumSubgroups)
#define CG_SUBGROUP_ADD(x)            subgroupAdd(x)
#define CG_SUBGROUP_MIN(x)            subgroupMin(x)
#define CG_SUBGROUP_MAX(x)            subgroupMax(x)
#define CG_SUBGROUP_INCLUSIVE_ADD(x)  subgroupInclusiveAdd(x)
#define CG_SUBGROUP_EXCLUSIVE_ADD(x)  subgroupExclusiveAdd(x)
#define CG_SUBGROUP_ALL(b)            subgroupAll(b)
#define CG_SUBGROUP_ANY(b)            subgroupAny(b)
#define CG_SUBGROUP_BALLOT(b)         subgroupBallot(b)
#define CG_SUBGROUP_BALLOT_COUNT(b)   int(subgroupBallotBitCount(subgroupBallot(b)))
#define CG_SUBGROUP_BROADCAST(x, id)  subgroupShuffle((x), uint(id))
#define CG_SUBGROUP_FIRST(x)          subgroupBroadcastFirst(x)
#define CG_SUBGROUP_ELECT()           subgroupElect()
#define CG_SUBGROUP_BARRIER()         { subgroupMemoryBarrierShared(); subgroupBarrier(); }

#else

#define CG_SUBGROUP_SIZE              CG_GROUP_SIZE
#define CG_SUBGROUP_INVOCATION        CG_LOCAL_INDEX
#define CG_SUBGROUP_INDEX             0
#define CG_SUBGROUP_COUNT             1
#define CG_SUBGROUP_ADD(x)            _cg_sgAdd(x)
#define CG_SUBGROUP_MIN(x)            _cg_sgMin(x)
#define CG_SUBGROUP_MAX(x)            _cg_sgMax(x)
#define CG_SUBGROUP_INCLUSIVE_ADD(x)  _cg_sgInclusiveAdd(x)
#define CG_SUBGROUP_EXCLUSIVE_ADD(x)  _cg_sgExclusiveAdd(x)
#define CG_SUBGROUP_ALL(b)            (_cg_sgMin(uint(b)) != 0u)
#define CG_SUBGROUP_ANY(b)            (_cg_sgMax(uint(b)) != 0u)
#define CG_SUBGROUP_BALLOT(b)         _cg_sgBallot(b)
#define CG_SUBGROUP_BALLOT_COUNT(b)   int(_cg_sgAdd(uint(b)))
#define CG_SUBGROUP_BROADCAST(x, id)  _cg_sgBroadcast((x), uint(id))
#define CG_SUBGROUP_FIRST(x)          _cg_sgBroadcast((x), 0u)
#define CG_SUBGROUP_ELECT()           (gl_LocalInvocationIndex == 0u)
#define CG_SUBGROUP_BARRIER()         { memoryBarrierShared(); barrier(); }

// One uint per invocation, and the ballot's four words.
shared uint _cg_sg[CG_GROUP_SIZE];
shared uint _cg_sgWords[4];

#define _CG_SG_SYNC() memoryBarrierShared(); barrier()

uint _cg_sgPack(float v) { return floatBitsToUint(v); }
uint _cg_sgPack(int v) { return uint(v); }
uint _cg_sgPack(uint v) { return v; }
float _cg_sgUnpack(uint u, float like) { return uintBitsToFloat(u); }
int _cg_sgUnpack(uint u, int like) { return int(u); }
uint _cg_sgUnpack(uint u, uint like) { return u; }
float _cg_sgSum(float a, float b) { return a + b; }
int _cg_sgSum(int a, int b) { return a + b; }
uint _cg_sgSum(uint a, uint b) { return a + b; }

// One line each: a continued line is GLSL 4.20's. A tree over the next power of two, answered from slot 0.
#define _CG_SG_REDUCE(T, NAME, OP) T NAME(T v) { uint i = gl_LocalInvocationIndex; _cg_sg[i] = _cg_sgPack(v); _CG_SG_SYNC(); for (uint s = uint(CG_GROUP_POW2) >> 1; s > 0u; s >>= 1) { if (i < s && i + s < uint(CG_GROUP_SIZE)) _cg_sg[i] = _cg_sgPack(OP(_cg_sgUnpack(_cg_sg[i], v), _cg_sgUnpack(_cg_sg[i + s], v))); _CG_SG_SYNC(); } T r = _cg_sgUnpack(_cg_sg[0], v); _CG_SG_SYNC(); return r; }
// Hillis-Steele: each step adds the slot s behind. RESULT reads this invocation's sum, or the one before it.
#define _CG_SG_SCAN(T, NAME, RESULT) T NAME(T v) { uint i = gl_LocalInvocationIndex; _cg_sg[i] = _cg_sgPack(v); _CG_SG_SYNC(); for (uint s = 1u; s < uint(CG_GROUP_SIZE); s <<= 1) { T a = _cg_sgUnpack(_cg_sg[i], v); if (i >= s) a = _cg_sgSum(a, _cg_sgUnpack(_cg_sg[i - s], v)); _CG_SG_SYNC(); _cg_sg[i] = _cg_sgPack(a); _CG_SG_SYNC(); } T r = RESULT; _CG_SG_SYNC(); return r; }
#define _CG_SG_BROADCAST(T) T _cg_sgBroadcast(T v, uint id) { _cg_sg[gl_LocalInvocationIndex] = _cg_sgPack(v); _CG_SG_SYNC(); T r = _cg_sgUnpack(_cg_sg[id], v); _CG_SG_SYNC(); return r; }

_CG_SG_REDUCE(float, _cg_sgAdd, _cg_sgSum)
_CG_SG_REDUCE(int, _cg_sgAdd, _cg_sgSum)
_CG_SG_REDUCE(uint, _cg_sgAdd, _cg_sgSum)
_CG_SG_REDUCE(float, _cg_sgMin, min)
_CG_SG_REDUCE(int, _cg_sgMin, min)
_CG_SG_REDUCE(uint, _cg_sgMin, min)
_CG_SG_REDUCE(float, _cg_sgMax, max)
_CG_SG_REDUCE(int, _cg_sgMax, max)
_CG_SG_REDUCE(uint, _cg_sgMax, max)
_CG_SG_SCAN(float, _cg_sgInclusiveAdd, _cg_sgUnpack(_cg_sg[i], v))
_CG_SG_SCAN(int, _cg_sgInclusiveAdd, _cg_sgUnpack(_cg_sg[i], v))
_CG_SG_SCAN(uint, _cg_sgInclusiveAdd, _cg_sgUnpack(_cg_sg[i], v))
_CG_SG_SCAN(float, _cg_sgExclusiveAdd, (i == 0u ? 0.0 : _cg_sgUnpack(_cg_sg[i - 1u], v)))
_CG_SG_SCAN(int, _cg_sgExclusiveAdd, (i == 0u ? 0 : _cg_sgUnpack(_cg_sg[i - 1u], v)))
_CG_SG_SCAN(uint, _cg_sgExclusiveAdd, (i == 0u ? 0u : _cg_sgUnpack(_cg_sg[i - 1u], v)))
_CG_SG_BROADCAST(float)
_CG_SG_BROADCAST(int)
_CG_SG_BROADCAST(uint)

#if CG_GROUP_SIZE <= 128
uvec4 _cg_sgBallot(bool b) {
    uint i = gl_LocalInvocationIndex;
    if (i == 0u) {
        _cg_sgWords[0] = 0u;
        _cg_sgWords[1] = 0u;
        _cg_sgWords[2] = 0u;
        _cg_sgWords[3] = 0u;
    }
    _CG_SG_SYNC();
    if (b) atomicOr(_cg_sgWords[i >> 5], 1u << (i & 31u));
    _CG_SG_SYNC();
    uvec4 r = uvec4(_cg_sgWords[0], _cg_sgWords[1], _cg_sgWords[2], _cg_sgWords[3]);
    _CG_SG_SYNC();
    return r;
}
#endif

#endif
