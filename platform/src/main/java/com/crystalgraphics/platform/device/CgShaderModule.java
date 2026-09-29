package com.crystalgraphics.platform.device;

/** One compiled stage, loaded from SPIR-V. */
public interface CgShaderModule extends CgDeviceObject {

    enum Stage { VERTEX, FRAGMENT }

    Stage stage();

    /** Thrown by {@link CgGlslCompiler#compile} with the compiler's log; the program reports it at link. */
    final class CompileException extends RuntimeException {
        public CompileException(String log) { super(log); }
    }
}
