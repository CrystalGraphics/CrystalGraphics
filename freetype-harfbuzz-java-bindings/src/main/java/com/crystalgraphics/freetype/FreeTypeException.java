package com.crystalgraphics.freetype;

/**
 * Exception thrown when a FreeType operation fails.
 *
 * <p>Contains the original FreeType error code for programmatic error handling.</p>
 */
public class FreeTypeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int errorCode;

    /**
     * Creates a new FreeTypeException.
     *
     * @param errorCode the FreeType error code (non-zero)
     * @param message   human-readable description
     */
    public FreeTypeException(int errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * Returns the FreeType error code.
     *
     * @return the error code as returned by the native FreeType function
     */
    public int getErrorCode() {
        return errorCode;
    }
}
