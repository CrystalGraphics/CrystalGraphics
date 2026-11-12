// Every VFX draw's header, CG_OBJECT_CUSTOM3: where its row of bound values starts (PerDrawValues reads it as
// CG_PROPERTY_ROW), the effect's age and seed, and its presence, 1 unless an ending fades it.
//
// A #domain surface material drawn by a VFX factory reads it as cg_Vfx, either stage, and never applies the presence:
// the factory fades what the material adds by it (Factory { Fade cg_Vfx.presence }).
//
//     float flicker = fx_flicker(cg_Vfx.age, cg_Vfx.seed);
#pragma once

struct CgVfx {
    float row;        // its row of bound values
    float age;        // the effect's age, seconds
    float seed;       // the effect's seed
    float presence;   // 1 unless an ending fades it
};

// The object record is readable in both stages, so nothing is interpolated: a macro, as the record is declared after
// an include.
#define cg_Vfx CgVfx(CG_OBJECT_CUSTOM3.x, CG_OBJECT_CUSTOM3.y, CG_OBJECT_CUSTOM3.z, CG_OBJECT_CUSTOM3.w)

// The old format's spellings, until its last shader is rewritten (P2.5 F5)
#define FX_ROW (CG_OBJECT_CUSTOM3.x)
#define FX_AGE (CG_OBJECT_CUSTOM3.y)
#define FX_SEED (CG_OBJECT_CUSTOM3.z)
#define FX_PRESENCE (CG_OBJECT_CUSTOM3.w)
