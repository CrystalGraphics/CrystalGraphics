package com.crystalgraphics.msdfgen;

/**
 * Result codes returned by MSDFgen native functions.
 * <p>
 * All native API methods return an {@code int} result code.
 * Use {@link #check(int)} to verify success or throw on error.
 *
 * @since 1.0.0
 */
public final class MSDFResult {

    /** Operation completed successfully. */
    public static final int SUCCESS = 0;
    /** Operation failed for an unspecified reason. */
    public static final int ERR_FAILED = 1;
    /** An invalid argument was provided. */
    public static final int ERR_INVALID_ARG = 2;
    /** An invalid type was specified (e.g., wrong bitmap type). */
    public static final int ERR_INVALID_TYPE = 3;
    /** An invalid size was specified. */
    public static final int ERR_INVALID_SIZE = 4;
    /** An invalid index was specified. */
    public static final int ERR_INVALID_INDEX = 5;

    private MSDFResult() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Checks if the result code indicates success.
     *
     * @param result the result code from a native call
     * @return {@code true} if the result is {@link #SUCCESS}
     */
    public static boolean isSuccess(int result) {
        return result == SUCCESS;
    }

    /**
     * Checks the result code and throws if it indicates an error.
     *
     * @param result the result code from a native call
     * @throws MSDFException if the result indicates an error
     */
    public static void check(int result) {
        if (result != SUCCESS) {
            throw new MSDFException(result);
        }
    }

    /**
     * Returns a human-readable description of a result code.
     *
     * @param result the result code
     * @return a descriptive string
     */
    public static String describe(int result) {
        switch (result) {
            case SUCCESS: return "Success";
            case ERR_FAILED: return "Operation failed";
            case ERR_INVALID_ARG: return "Invalid argument";
            case ERR_INVALID_TYPE: return "Invalid type";
            case ERR_INVALID_SIZE: return "Invalid size";
            case ERR_INVALID_INDEX: return "Invalid index";
            default: return "Unknown error (" + result + ")";
        }
    }
}
