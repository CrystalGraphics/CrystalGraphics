package com.crystalgraphics.net.protocol;

import com.crystalgraphics.serialization.CgStateMap;

import javax.annotation.Nullable;

/**
 * The session-facing shape of a remote call — what {@code RpcRegistry.Handler} and
 * {@code RpcRegistry.Responder} were, outliving the class that held them.
 *
 * <p>{@code RpcRegistry} is gone: its correlation, its pending map and its timeout sweep are the
 * {@link CgMessageRouter}'s now, for every method rather than for RPC alone. These two interfaces are what
 * was worth keeping, because they are the contract a <em>caller</em> writes against, and the whole point
 * of the migration was that callers did not have to move.</p>
 *
 * <h3>Why this is not just {@link CgMessageRouter.RequestHandler}</h3>
 *
 * <p>The router deals in the session's raw ops representation, because it must: it routes payloads it is
 * not allowed to understand. A session's callers deal in {@link CgStateMap}, which is the readable face of
 * the same bytes. Collapsing the two would push {@code encode()}/{@code decode()} calls into every
 * handler in the codebase to save one adapter in each session.</p>
 */
public interface CgCall {

    /** Handles one call. Reply through {@code respond}, which may be invoked later. */
    interface Handler<T> {
        void invoke(CgStateMap<T> args, Responder<T> respond);
    }

    /** Answers a call. Exactly one of these, exactly once — the router enforces it. */
    interface Responder<T> {
        void ok(@Nullable CgStateMap<T> value);

        void fail(String error);
    }
}
