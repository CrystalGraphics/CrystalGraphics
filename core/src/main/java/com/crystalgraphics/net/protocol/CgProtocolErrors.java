package com.crystalgraphics.net.protocol;

/**
 * The errors the framework itself produces, as opposed to the ones a handler chooses.
 *
 * <p>Strings rather than JSON-RPC's numeric codes. The numbers exist so a machine can branch without
 * parsing prose, and nothing here branches on them — both peers are this codebase, the set is tiny, and
 * every one of these ends up in a log a human reads. A number would need a lookup table next to it that
 * says exactly what the string already says.</p>
 *
 * <p>Prefixed so a framework error is never mistaken for a handler's. A handler that fails with
 * {@code "no such file"} and the router failing with {@code "protocol/methodNotFound"} are different
 * kinds of answer, and a caller that wants to retry needs to tell them apart.</p>
 */
public final class CgProtocolErrors {

    /**
     * Nobody registered a handler for this method.
     *
     * <p>The reason REQUEST and NOTIFICATION being structurally distinct matters: a request that nothing
     * handles still gets an answer, so a caller waiting on a reply is never left hanging by a peer that
     * simply does not know the method. Under {@code UIPacket} an unrecognised message fell off the end of
     * an {@code instanceof} chain and the sender waited for its timeout.</p>
     *
     * <p>It is also the version story. A newer peer calling a method an older one lacks gets this, per
     * message, and can fall back — which degrades better than an envelope version integer that fails the
     * whole connection over a vocabulary difference.</p>
     */
    public static final String METHOD_NOT_FOUND = "protocol/methodNotFound";

    /** The handler threw. Its own message is appended; the prefix says whose fault it was. */
    public static final String HANDLER_FAILED = "protocol/handlerFailed";

    /** No answer within the deadline. The request is forgotten locally; the peer may still reply. */
    public static final String TIMEOUT = "protocol/timeout";

    /** Withdrawn by the sender, or abandoned because the connection went away. */
    public static final String CANCELLED = "protocol/cancelled";

    private CgProtocolErrors() {
    }
}
