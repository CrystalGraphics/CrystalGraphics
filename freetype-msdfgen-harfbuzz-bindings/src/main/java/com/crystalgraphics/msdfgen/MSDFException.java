package com.crystalgraphics.msdfgen;

/**
 * Exception thrown when an MSDFgen native operation fails.
 *
 * @since 1.0.0
 */
public class MSDFException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int errorCode;

    /**
     * Creates a new MsdfException with the given error code.
     *
     * @param errorCode the native error code
     */
    public MSDFException(int errorCode) {
        super("MSDFgen error: " + MSDFResult.describe(errorCode) + " (code=" + errorCode + ")");
        this.errorCode = errorCode;
    }

    /**
     * Creates a new MsdfException with a custom message.
     *
     * @param message the error message
     */
    public MSDFException(String message) {
        super(message);
        this.errorCode = MSDFResult.ERR_FAILED;
    }

    /**
     * Creates a new MsdfException with a custom message and cause.
     *
     * @param message the error message
     * @param cause   the underlying cause
     */
    public MSDFException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = MSDFResult.ERR_FAILED;
    }

    /**
     * Returns the native error code.
     *
     * @return the error code constant from {@link MSDFResult}
     */
    public int getErrorCode() {
        return errorCode;
    }
}
