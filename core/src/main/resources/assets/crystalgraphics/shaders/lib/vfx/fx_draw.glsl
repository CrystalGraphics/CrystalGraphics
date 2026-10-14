// Every VFX draw's header, CG_OBJECT_CUSTOM3: where its row of bound values starts (#pragma cg_use rows reads it as
// CG_PROPERTY_ROW), the effect's age and seed, and its presence, 1 unless an ending fades it.
//
//     fragColor = vec4(colour, alpha) * FX_PRESENCE;       // premultiplied or added
//     fragColor.a *= FX_PRESENCE;                          // blended over what is behind
#pragma once

#define FX_ROW (CG_OBJECT_CUSTOM3.x)
#define FX_AGE (CG_OBJECT_CUSTOM3.y)
#define FX_SEED (CG_OBJECT_CUSTOM3.z)
#define FX_PRESENCE (CG_OBJECT_CUSTOM3.w)
