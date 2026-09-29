package com.crystalgraphics.platform.device;

/** One compiled stage, from Vulkan GLSL (the tracked backend's rewrite of a program's GL GLSL). */
public interface CgShaderModule extends CgDeviceObject {

    enum Stage { VERTEX, FRAGMENT }

    Stage stage();

    /** Thrown by {@link CgDevice#createShaderModule} with the compiler's log; the program reports it at link. */
    final class CompileException extends RuntimeException {
        public CompileException(String log) { super(log); }
    }
}
