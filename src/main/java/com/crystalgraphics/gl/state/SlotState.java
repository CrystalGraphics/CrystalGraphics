package com.crystalgraphics.gl.state;

/**
 * Internal contract for a captured GL state slot. Implementations are the static
 * inner classes of {@link CgGlStates}. Used by {@link CgGlScope} to restore captured state.
 */
interface SlotState {
    void restore();
}
