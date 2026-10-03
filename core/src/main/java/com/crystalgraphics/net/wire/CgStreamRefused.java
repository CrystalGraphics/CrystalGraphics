package com.crystalgraphics.net.wire;

import com.crystalgraphics.serialization.CgCodecException;


/**
 * One stream was refused. The connection is fine.
 *
 * <h3>The distinction, and why it needed a type</h3>
 *
 * <p>HTTP/2 draws exactly this line and this class is named for it: a <b>stream error</b>
 * (RFC 9113 §5.4.2) resets one stream and the connection carries on, while a <b>connection error</b>
 * (§5.4.1) means the peer is not speaking the protocol and there is nothing left to salvage.
 * {@link CgFrameMultiplexer} had both conditions and one exception type, so it could not act on the
 * difference — and it did not: refusing a single oversized transfer threw out of {@code pump},
 * abandoning every frame queued behind it and skipping that tick's credit replenishment and flush, on
 * a connection whose other streams were healthy.</p>
 *
 * <p>Extends {@link CgCodecException} so nothing that already catches wire failures stops catching this
 * one — the change narrows what escapes {@code pump}, and must not widen what escapes anything else.</p>
 *
 * <p><b>Thrown, not returned</b>, because it is raised deep inside frame handling and every caller
 * between there and {@code pump} would otherwise have to thread a result it cannot act on. It travels
 * exactly one frame's worth of stack and is caught at the loop that owns the decision.</p>
 */
public class CgStreamRefused extends CgCodecException {

    private static final long serialVersionUID = 1L;

    public CgStreamRefused(String message) {
        super(message);
    }
}
