// CgPropertyRows' shader environment: a frame's per-draw property rows, a vec4 each.
//
// INJECTED BY `#pragma cg_use rows`, after the buffer's declaration. The compiler reads every number of such a shader
// through a macro of its own name, choosing the draw's row where the material says it holds one:
//
//     #define _Radius (_cg_at_Radius < 0 ? _cgm_Radius : CG_ROW(CG_PROPERTY_ROW + _cg_at_Radius).x)
#pragma once

// Where the draw's row starts: its object record's custom3.x, unless the shader defines another before this.
#ifndef CG_PROPERTY_ROW
#define CG_PROPERTY_ROW int(CG_OBJECT_CUSTOM3.x + 0.5)
#endif
// Vec4 n of the frame's rows.
#define CG_ROW(n) (ROWS_DATA(n).value)
