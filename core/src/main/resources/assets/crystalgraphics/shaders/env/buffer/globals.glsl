// CgParameterCollection's shader environment: every collection's values, a vec4 each.
//
// INJECTED BY `#pragma cg_use globals`, after the buffer's declaration. The compiler then defines each parameter of
// every collection by name, a float or a vec4:
//
//     #define CG_GLOBAL_skill_darken (CG_GLOBAL_VEC4(3).y)
//     #define CG_GLOBAL_skill_tint (CG_GLOBAL_VEC4(4))
#pragma once

// Vec4 n of the collections' values.
#define CG_GLOBAL_VEC4(n) (GLOBAL_DATA(n).value)
