package io.github.somehussar.crystalgraphics.api.shader;

import lombok.Getter;

/**
 * Thrown by {@link CgShaderPreprocessor} when preprocessing fails:
 * shader source not found, circular {@code #include} detected, or
 * other resolution errors.
 *
 * <p>This is a {@link RuntimeException} so callers that don't want to
 * handle it explicitly (e.g. managed shader compile paths) can let it
 * propagate naturally and log it.</p>
 */
public final class CgPreprocessorException extends RuntimeException {

    /** The normalized path of the shader file that triggered this error, or null. */
    @Getter private final String shaderPath;

    /** The 1-based line number within {@link #shaderPath} where the error occurred, or 0. */
    @Getter private final int line;

    public CgPreprocessorException(String message, String shaderPath, int line) {
        super(message);
        this.shaderPath = shaderPath;
        this.line = line;
    }

    public CgPreprocessorException(String message, String shaderPath, int line, Throwable cause) {
        super(message, cause);
        this.shaderPath = shaderPath;
        this.line = line;
    }
}
