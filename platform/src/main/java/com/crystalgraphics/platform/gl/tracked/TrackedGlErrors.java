package com.crystalgraphics.platform.gl.tracked;

import com.crystalgraphics.platform.gl.CgGL;

import java.util.HashSet;
import java.util.Set;

/**
 * The GL errors the tracked backend raises, kept until {@code glGetError} reads them, as GL keeps them. Each is
 * also logged once per call and value, since the driver's own message would have been the only clue.
 */
final class TrackedGlErrors {

    private int pending = CgGL.GL_NO_ERROR;
    private final Set<String> logged = new HashSet<>();

    void invalidEnum(String call, int value) {
        raise(CgGL.GL_INVALID_ENUM, call + ": 0x" + Integer.toHexString(value));
    }

    void invalidOperation(String what) {
        raise(CgGL.GL_INVALID_OPERATION, what);
    }

    void invalidValue(String what) {
        raise(CgGL.GL_INVALID_VALUE, what);
    }

    int take() {
        int e = pending;
        pending = CgGL.GL_NO_ERROR;
        return e;
    }

    private void raise(int error, String message) {
        if (pending == CgGL.GL_NO_ERROR) pending = error;
        if (logged.add(message)) System.err.println("[crystalgraphics] tracked backend: " + message);
    }
}
