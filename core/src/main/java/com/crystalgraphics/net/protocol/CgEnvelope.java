package com.crystalgraphics.net.protocol;


/**
 * What every message is, independent of what it says.
 *
 * <p><b>The envelope is closed; the vocabulary is open.</b> That distinction is the whole design, and it
 * is why a fixed set of four types here is right where {@code UIPacket}'s growing set of nine was wrong.
 * These four are the <em>grammar</em> — ask, answer, tell, take it back — and a protocol does not grow
 * new grammar. Everything a message is actually <em>about</em> is a {@link Request#method()} or
 * {@link Notification#method()} string, and that set is open: adding one is a registration next to the
 * code that owns it, not an edit to a union everything shares.</p>
 *
 * <h3>What this replaces, and why it was costing</h3>
 *
 * <p>{@code UIPacket} was a sealed union of nine records. Adding a message meant editing <b>four</b>
 * places — the union, {@code UIPacketCodec.encode}, {@code UIPacketCodec.decode}, and every session's
 * {@code handle} chain — and all four are shared by every subsystem, so the workspace, the UI and the
 * script runtime edited the same three files and conflicted there.</p>
 *
 * <p>The codebase already knew the answer one layer up: {@code RpcRegistry.register(method, handler)} and
 * {@code ServerUiSession.on(element, kind, handler)} are both open registries keyed by a string, and
 * nothing enumerates their contents. The packet layer was the last closed union, in the one place a
 * closed union hurts most.</p>
 *
 * <h3>Request and Notification are different things</h3>
 *
 * <p>Taken from LSP, which is explicit about it: <i>"every processed request must send a response back …
 * notifications don't require responses."</i> {@code UIPacket} mixed the two with no way to tell them
 * apart — {@code StateDelta} was a notification and {@code RpcCall} was a request, and only the handler
 * knew which. Making it structural means the router can answer an unknown REQUEST with an error (a caller
 * waiting on a reply always gets one) while an unknown NOTIFICATION is logged and dropped, which is the
 * correct treatment for each and impossible to get right without the distinction.</p>
 *
 * <h3>{@code RpcCall} and {@code RpcResult} are gone, and that is the tell</h3>
 *
 * <p>They existed only because the union had no general ask/answer, so RPC built its own correlation on
 * top of it — a second id space, a second pending-call map, a second timeout. With {@link Request} and
 * {@link Response} carrying an id, that is the framework's job once, for every method.</p>
 *
 * <p>Payloads stay in the session's {@code CgDynamicOps} representation and are <b>not decoded here</b>.
 * Only the handler registered for a method knows the shape, and it applies its own {@code CgCodec}. So the
 * envelope codec never grows a branch, a subsystem's wire format stays private to it, and a large payload
 * can be routed — or refused — without being parsed.</p>
 */
public interface CgEnvelope {

    /** {@code REQUEST}/{@code RESPONSE} correlation, or {@link #NO_ID} for a notification. */
    int NO_ID = -1;

    /**
     * Ask, and expect an answer.
     *
     * @param id      correlates the {@link Response}; unique per sender, per connection
     * @param method  namespaced, LSP-style — {@code "workspace/read"}, {@code "ui/description"}
     * @param payload in the session's ops representation, opaque until a handler claims it
     */
    record Request<T>(int id, String method, T payload) implements CgEnvelope {
    }

    /**
     * The answer to exactly one {@link Request}.
     *
     * <p>{@code ok} rather than a null-error convention: an error is a value a handler may legitimately
     * want to send as a payload, so "did it succeed" cannot be inferred from what is present.</p>
     */
    record Response<T>(int id, boolean ok, T payload, String error) implements CgEnvelope {

        public static <T> Response<T> ok(int id, T payload) {
            return new Response<>(id, true, payload, "");
        }

        public static <T> Response<T> failed(int id, String error) {
            return new Response<>(id, false, null, error);
        }
    }

    /** Tell, and expect nothing. The fan-out shape: state deltas, UI events, window lifecycle. */
    record Notification<T>(String method, T payload) implements CgEnvelope {
    }

    /**
     * Withdraw a {@link Request} still in flight.
     *
     * <p>No payload, so unlike the other three it needs no type parameter — which is worth leaving
     * visible rather than adding an unused one for symmetry.</p>
     */
    record Cancel(int id) implements CgEnvelope {
    }
}
