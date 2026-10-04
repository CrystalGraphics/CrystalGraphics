// Checked mode (compute/program/CgComputeCheck): a checked kernel's accessors call cg_Violation for an access out of
// range and skip it. The block is this dispatch's slot: how many, then the first one's site, line, index and size.
#pragma once

layout(std430) buffer CgCheck { uint cg_Checks[]; };

void cg_Violation(int site, int line, uvec3 at, uvec3 size) {
    if (atomicAdd(cg_Checks[0], 1u) == 0u) {
        cg_Checks[1] = uint(site);
        cg_Checks[2] = uint(line);
        cg_Checks[3] = at.x;
        cg_Checks[4] = at.y;
        cg_Checks[5] = at.z;
        cg_Checks[6] = size.x;
        cg_Checks[7] = size.y;
        cg_Checks[8] = size.z;
    }
}
