// A pool's particle record (CgVfxRecord), read by the shared kernels: _RecordVectors uvec4s a record, the core four
// first at these places in every layout, the shape's tail after. A kernel declares its records buffer as uvec4 and
// reads vector k of record e at e * _RecordVectors + k.
#pragma once

// position xyz, age
const int FX_RECORD_POSITION_AGE = 0;
// velocity xyz, size at birth
const int FX_RECORD_VELOCITY_SIZE = 1;
// life, seed, spin, spin rate
const int FX_RECORD_LIFE_SEED = 2;
// heat (float bits), id, slot, parameter row
const int FX_RECORD_HEAT_ID = 3;
