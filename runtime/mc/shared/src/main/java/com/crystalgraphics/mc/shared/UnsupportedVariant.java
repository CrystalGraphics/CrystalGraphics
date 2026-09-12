package com.crystalgraphics.mc.shared;

/**
 * Thrown when the jar carries no variant for the loader and Minecraft version it finds itself on.
 *
 * <p>The message names what is supported, because the alternative — a loader's own "incompatible"
 * screen, or a {@code NoSuchMethodError} from a variant compiled against a different version —
 * tells a player nothing they can act on.</p>
 */
public final class UnsupportedVariant extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UnsupportedVariant(String message) {
        super(message);
    }
}
