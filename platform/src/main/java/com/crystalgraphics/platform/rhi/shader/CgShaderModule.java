package com.crystalgraphics.platform.rhi.shader;

import com.crystalgraphics.platform.rhi.resource.CgRHIResource;

/** One compiled stage, loaded from SPIR-V. */
public interface CgShaderModule extends CgRHIResource {

    enum Stage { VERTEX, FRAGMENT, COMPUTE }

    Stage stage();

    /** Thrown by {@link CgGlslCompiler#compile} with the compiler's log; the program reports it at link. */
    final class CompileException extends RuntimeException {
        public CompileException(String log) { super(log); }
    }
}
