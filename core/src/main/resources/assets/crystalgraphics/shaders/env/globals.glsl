// CgParameterCollection: every collection's values, a vec4 each, for a program naming a CG_GLOBAL_ parameter. Each
// parameter is defined by name, a float or a vec4:
//
//     #define CG_GLOBAL_skill_darken (CG_GLOBAL_VEC4(3).y)
//     #define CG_GLOBAL_skill_tint (CG_GLOBAL_VEC4(4))
#pragma once

// Vec4 n of the collections' values.
#define CG_GLOBAL_VEC4(n) (GLOBAL_DATA(n).value)
