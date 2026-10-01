// CgShapeTable's shader environment: the boxes a recording's quads are drawn as, read through SHAPE_DATA(n).
//
// INJECTED BY `#pragma cg_use shape`, immediately after the table's own declaration. An entry's fields, as vec4s:
// meta (kind, flags, box width, box height), radiiX and radiiY (top-left, top-right, bottom-right, bottom-left),
// borderWidths (left, top, right, bottom), fillColor, borderColor, borderTop, borderBottom, and for a nine-slice
// sliceBorder, sliceOuterUv (u0 v0 u3 v3), sliceInnerUv (u1 v1 u2 v2), sliceTiles (x, y, source width, height) and
// sliceMode (repeat x, repeat y, centre filled). Colours are straight alpha.
#pragma once

// CgShapeTable's kinds and flags, which must agree.
#define CG_SHAPE_PLAIN 0
#define CG_SHAPE_PREMULTIPLIED 1
#define CG_SHAPE_FLAT 2
#define CG_SHAPE_TEXTURE 3
#define CG_SHAPE_NINE_SLICE 4

#define CG_SHAPE_BORDER 1
#define CG_SHAPE_SPLIT_BORDER 2
