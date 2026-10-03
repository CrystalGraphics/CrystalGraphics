// Float atomics on uint storage, which every device has: a compare-and-swap loop on the value's bits. MEM is a
// uint in a buffer or in shared memory holding a float's bits; each macro is a statement.
//
//     shared uint total;                        // floatBitsToUint(0.0) to start
//     CG_ATOMIC_ADD_FLOAT(total, weight);
//     ...
//     float sum = uintBitsToFloat(total);
#pragma once

#define CG_ATOMIC_ADD_FLOAT(MEM, V) { uint _cg_seen, _cg_was = (MEM); do { _cg_seen = _cg_was; _cg_was = atomicCompSwap((MEM), _cg_seen, floatBitsToUint(uintBitsToFloat(_cg_seen) + (V))); } while (_cg_was != _cg_seen); }
#define CG_ATOMIC_MIN_FLOAT(MEM, V) { uint _cg_seen, _cg_was = (MEM); do { _cg_seen = _cg_was; _cg_was = atomicCompSwap((MEM), _cg_seen, floatBitsToUint(min(uintBitsToFloat(_cg_seen), (V)))); } while (_cg_was != _cg_seen); }
#define CG_ATOMIC_MAX_FLOAT(MEM, V) { uint _cg_seen, _cg_was = (MEM); do { _cg_seen = _cg_was; _cg_was = atomicCompSwap((MEM), _cg_seen, floatBitsToUint(max(uintBitsToFloat(_cg_seen), (V)))); } while (_cg_was != _cg_seen); }
