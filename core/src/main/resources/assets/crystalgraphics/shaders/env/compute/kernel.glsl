// A kernel's environment: where this invocation is in its dispatch. Every kernel includes it, after cg_env.glsl and
// the compiler's CG_LOCAL_SIZE_X/Y/Z, CG_GROUP_SIZE and CG_DIMENSIONS.
//
//     void Simulate() {
//         Particle p = STATE(CG_ELEMENT);       // this invocation's element, x fastest
//         ...
//     }
//
// A dispatch wider than the device's group count runs as several, each from its own base: read CG_DISPATCH_ID,
// never gl_GlobalInvocationID.
#pragma once

// Base xyz and count xyz, set per dispatch by the engine. A count of -1 is an indirect dispatch's: its groups.
// Compiled as written (CG_EXPLICIT), a push constant with the append counts' words, in vec4s: a GL device feeds it as
// a uniform block, whose std140 strides a scalar array 16 bytes (CgKernelEmitter.PUSH_BYTES).
#ifdef CG_EXPLICIT
layout(push_constant) uniform CgKernelPush { ivec4 cg_DispatchBase; ivec4 cg_DispatchCount; ivec4 cg_KernelWords[CG_KERNEL_WORDS / 4]; };
#define CG_PUSHED_BASE  cg_DispatchBase.xyz
#define CG_PUSHED_COUNT cg_DispatchCount.xyz
#else
uniform int cg_Dispatch[6];
#define CG_PUSHED_BASE  ivec3(cg_Dispatch[0], cg_Dispatch[1], cg_Dispatch[2])
#define CG_PUSHED_COUNT ivec3(cg_Dispatch[3], cg_Dispatch[4], cg_Dispatch[5])
#endif

#define CG_LOCAL_SIZE     ivec3(CG_LOCAL_SIZE_X, CG_LOCAL_SIZE_Y, CG_LOCAL_SIZE_Z)
#define CG_DISPATCH_BASE  CG_PUSHED_BASE
// The elements asked for along each axis. Groups round up, so the last group holds invocations past it.
#define CG_DISPATCH_COUNT (CG_PUSHED_COUNT.x >= 0 ? CG_PUSHED_COUNT : ivec3(gl_NumWorkGroups) * CG_LOCAL_SIZE)
#define CG_DISPATCH_ID    (ivec3(gl_GlobalInvocationID) + CG_DISPATCH_BASE)
#define CG_ELEMENT        (CG_DISPATCH_ID.x + CG_DISPATCH_COUNT.x * (CG_DISPATCH_ID.y + CG_DISPATCH_COUNT.y * CG_DISPATCH_ID.z))
// Whether this invocation is one of the elements asked for. Every shape but general returns before the kernel
// when it is not; a general kernel asks itself, after its barriers.
#define CG_IN_RANGE       all(lessThan(CG_DISPATCH_ID, CG_DISPATCH_COUNT))
// The texel an image kernel writes: CG_TEXEL.xy for a 2D image.
#define CG_TEXEL          CG_DISPATCH_ID
#define CG_GROUP_ID       (ivec3(gl_WorkGroupID) + CG_DISPATCH_BASE / CG_LOCAL_SIZE)
#define CG_LOCAL_ID       ivec3(gl_LocalInvocationID)
#define CG_LOCAL_INDEX    int(gl_LocalInvocationIndex)
