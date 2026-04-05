package io.github.somehussar.crystalgraphics.harness.tool;

import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Agent-debug tool #6: Drain and name all pending GL errors.
 */
public  class GlErrorChecker {

    private static final Logger LOGGER = Logger.getLogger(GlErrorChecker.class.getName());

    /**
     * Drain all pending GL errors. Returns a list of error descriptions.
     * If no errors are pending, returns an empty list.
     */
    public static List<String> drainErrors() {
        List<String> errors = new ArrayList<String>();
        int count = 0;
        int err;
        while ((err = GL11.glGetError()) != GL11.GL_NO_ERROR) {
            String name = errorName(err);
            errors.add("0x" + Integer.toHexString(err) + " (" + name + ")");
            count++;
            if (count > 64) {
                errors.add("... (stopped after 64 errors)");
                break;
            }
        }
        return errors;
    }

    /**
     * Check for GL errors and log them. Returns true if any errors were found.
     */
    public static boolean checkAndLog(String context) {
        List<String> errors = drainErrors();
        if (errors.isEmpty()) {
            return false;
        }
        for (String error : errors) {
            LOGGER.warning("[GlErrorChecker] " + context + ": " + error);
        }
        return true;
    }

    /**
     * Maps a GL error code to a human-readable name.
     */
    public static String errorName(int error) {
        switch (error) {
            case GL11.GL_NO_ERROR:          return "GL_NO_ERROR";
            case GL11.GL_INVALID_ENUM:      return "GL_INVALID_ENUM";
            case GL11.GL_INVALID_VALUE:     return "GL_INVALID_VALUE";
            case GL11.GL_INVALID_OPERATION: return "GL_INVALID_OPERATION";
            case GL11.GL_STACK_OVERFLOW:    return "GL_STACK_OVERFLOW";
            case GL11.GL_STACK_UNDERFLOW:   return "GL_STACK_UNDERFLOW";
            case GL11.GL_OUT_OF_MEMORY:     return "GL_OUT_OF_MEMORY";
            case 0x0506:                    return "GL_INVALID_FRAMEBUFFER_OPERATION";
            default:                        return "UNKNOWN";
        }
    }

    private GlErrorChecker() { }
}
