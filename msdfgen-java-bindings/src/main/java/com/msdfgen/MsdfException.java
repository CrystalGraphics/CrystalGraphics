package com.msdfgen;

/**
 * Exception thrown when an MSDFgen native operation fails.
 *
 * @since 1.0.0
 */
public class MsdfException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int errorCode;

    /**
     * Creates a new MsdfException with the given error code.
     *
     * @param errorCode the native error code
     */
    public MsdfException(int errorCode) {
        super("MSDFgen error: " + MsdfResult.describe(errorCode) + " (code=" + errorCode + ")");
        this.errorCode = errorCode;
    }

    /**
     * Creates a new MsdfException with a custom message.
     *
     * @param message the error message
     */
    public MsdfException(String message) {
        super(message);
        this.errorCode = MsdfResult.ERR_FAILED;
    }

    /**
     * Creates a new MsdfException with a custom message and cause.
     *
     * @param message the error message
     * @param cause   the underlying cause
     */
    public MsdfException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = MsdfResult.ERR_FAILED;
    }

    /**
     * Returns the native error code.
     *
     * @return the error code constant from {@link MsdfResult}
     */
    public int getErrorCode() {
        return errorCode;
    }
}
