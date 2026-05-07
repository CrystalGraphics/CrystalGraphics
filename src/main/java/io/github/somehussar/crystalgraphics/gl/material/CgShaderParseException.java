package io.github.somehussar.crystalgraphics.gl.material;

/**
 * Thrown by {@link CgShaderParser} when a {@code .shader} source file
 * violates the CrystalShader format specification.
 *
 * <p>This is a {@link RuntimeException} so callers are not forced to catch
 * it in render-path code, but format errors should be treated as fatal
 * during asset loading.</p>
 */
public class CgShaderParseException extends RuntimeException {

    public CgShaderParseException(String message) {
        super(message);
    }

    public CgShaderParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
