package com.crystalgraphics.net.object;

import java.util.Objects;

import com.crystalgraphics.net.CgPeer;
import com.crystalgraphics.serialization.CgCodec;

/**
 * A command that answers: a watcher (or the owner) asks something of one object, and the server answers with a value
 * or refuses with a reason. Listed in its type with {@code calls(...)}, handled with {@code onCall}.
 *
 * <pre>{@code
 * public static final CgNetCall<String, List<String>> SEARCH =
 *         CgNetCall.of("search", CgCodecs.STRING, CgCodecs.listOf(CgCodecs.STRING));
 * public static final CgNetType<Shop> SHOP = CgNetType.define("mymod:shop", Shop.CODEC).calls(SEARCH).build();
 *
 * SHOP.onCall(SEARCH, (shop, player, text, reply) -> reply.ok(shop.find(text)));      // server, at init
 * shopObject.call(SEARCH, "sword").then(results -> list.setItems(results))           // client, a watcher
 *         .onError(error -> status.setText(error.code()));
 * }</pre>
 *
 * <ul>
 *   <li>Every call ends: an answer, a refusal with its reason (one of {@link CgNetCommand}'s {@code REFUSED_*}
 *       or the handler's own), or a timeout after {@link com.crystalgraphics.net.CgRequest#DEFAULT_TIMEOUT_MILLIS}.</li>
 *   <li>A reply kept and answered later is fine; only the first answer counts.</li>
 *   <li>Rate and size are capped per sender as a command's are: {@link #perSecond}, {@link #maxBytes}.</li>
 * </ul>
 */
public final class CgNetCall<A, R> {

    final CgNetCommand<A> command;
    final CgCodec<R> result;

    private CgNetCall(CgNetCommand<A> command, CgCodec<R> result) {
        this.command = command;
        this.result = result;
        command.call = this;
    }

    /** A call named {@code name} within its type, taking {@code args} and answering {@code result}. */
    public static <A, R> CgNetCall<A, R> of(String name, CgCodec<A> args, CgCodec<R> result) {
        return new CgNetCall<>(CgNetCommand.of(name, args), Objects.requireNonNull(result, "result"));
    }

    /** How many one player may make a second, as {@link CgNetCommand#perSecond}. */
    /** Its arguments' codec. */
    public CgCodec<A> args() {
        return command.codec;
    }

    /** Its answer's codec. */
    public CgCodec<R> result() {
        return result;
    }

    public CgNetCall<A, R> perSecond(float count) {
        command.perSecond(count);
        return this;
    }

    /** The largest argument the server reads, in bytes, as {@link CgNetCommand#maxBytes}. */
    public CgNetCall<A, R> maxBytes(int bytes) {
        command.maxBytes(bytes);
        return this;
    }

    /** May be made by any viewer, not only the owner or a watcher. */
    public CgNetCall<A, R> fromAnyViewer() {
        command.fromAnyViewer();
        return this;
    }

    public String name() {
        return command.name;
    }

    /** Calls refused or dropped, since the server started. */
    public long refused() {
        return command.refused();
    }

    /** How a call's handler answers: once, now or later. */
    public interface Reply<R> {
        void ok(R value);

        /** Refuses with {@code reason}, a key the caller can translate: {@code "mymod/out_of_stock"}. */
        void fail(String reason);
    }

    /** A call's server handler: the subject, who asked, what they sent, and how to answer. */
    @FunctionalInterface
    public interface Handler<T, A, R> {
        void handle(T subject, CgPeer sender, A value, Reply<R> reply);
    }
}
