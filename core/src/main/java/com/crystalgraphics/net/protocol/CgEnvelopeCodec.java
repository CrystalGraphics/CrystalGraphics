package com.crystalgraphics.net.protocol;

import com.crystalgraphics.serialization.CgCodecException;
import com.crystalgraphics.serialization.CgCodecs;
import com.crystalgraphics.serialization.CgDynamicOps;


/**
 * The envelope on the wire — and the only codec in the protocol that is allowed to know every case.
 *
 * <p>It has four branches because {@link CgEnvelope} has four types, and <b>it is meant never to grow a
 * fifth</b>. That is the contrast with {@code UIPacketCodec}, whose encode and decode switches gained an
 * arm for every message anyone added: this one is finished.</p>
 *
 * <p><b>Payloads are carried, never inspected.</b> A payload arrives as an opaque {@code T} in the
 * session's own ops and is handed to whichever handler claimed the method. Three things follow, and all
 * three are the point:</p>
 *
 * <ul>
 *   <li>A subsystem's wire format is private to that subsystem — {@code workspace/*} can change shape
 *       without this file knowing.</li>
 *   <li>A message can be <em>routed</em> without being parsed, so an oversized or unwanted payload is
 *       refused before it costs anything to decode.</li>
 *   <li>There is no central place where two subsystems' field names can collide.</li>
 * </ul>
 *
 * <h3>Field names are short because they are on the wire</h3>
 *
 * <p>{@code k}/{@code i}/{@code m}/{@code p} rather than {@code kind}/{@code id}/{@code method}/
 * {@code payload}. Every byte here is paid on every message, and the client→server budget is ~32 KB per
 * frame — see {@code plan/net-wire.md}. The method name stays spelled out, because it is the one field a
 * human reads when a capture is dumped.</p>
 */
public final class CgEnvelopeCodec {

    /**
     * Bumped when the envelope's own shape changes — not when a method is added or removed.
     *
     * <p>Which is most of why this number should now stay still: under {@code UIPacket} any new message
     * was arguably a protocol change, and here the vocabulary moves without the grammar moving. A peer
     * that does not know a method says so with {@link CgProtocolErrors#METHOD_NOT_FOUND}, per message,
     * rather than failing the whole connection over a version integer.</p>
     */
    // 2: ui/treeDelta became ui/treeOps (an edit script rather than a re-description), ids stopped
    // being positional, and state deltas gained attribute and inline-style entries. A peer speaking 1
    // would misread every one of those, so the existing version check refuses it -- which is the whole
    // reason this number exists.
    public static final int VERSION = 2;

    // Wire tags. Explicit values, never an enum ordinal: reordering the constants must not be able to
    // silently change what a byte means to a peer built yesterday.
    private static final String KIND_REQUEST = "q";
    private static final String KIND_RESPONSE = "r";
    private static final String KIND_NOTIFY = "n";
    private static final String KIND_CANCEL = "x";

    private CgEnvelopeCodec() {
    }

    public static <T> T encode(CgDynamicOps<T> ops, CgEnvelope envelope) {
        if (envelope instanceof CgEnvelope.Request<?> request) {
            @SuppressWarnings("unchecked")
            T payload = (T) request.payload();
            CgCodecs.MapCodecBuilder<T> out = CgCodecs.map(ops)
                    .field("k", CgCodecs.STRING, KIND_REQUEST)
                    .field("i", CgCodecs.INT, request.id())
                    .field("m", CgCodecs.STRING, request.method());
            if (payload != null) out.raw("p", payload);
            return out.build();
        }
        if (envelope instanceof CgEnvelope.Response<?> response) {
            @SuppressWarnings("unchecked")
            T payload = (T) response.payload();
            CgCodecs.MapCodecBuilder<T> out = CgCodecs.map(ops)
                    .field("k", CgCodecs.STRING, KIND_RESPONSE)
                    .field("i", CgCodecs.INT, response.id())
                    .field("ok", CgCodecs.BOOL, response.ok());
            if (payload != null) out.raw("p", payload);
            out.optional("e", CgCodecs.STRING, response.error() == null ? "" : response.error(), "");
            return out.build();
        }
        if (envelope instanceof CgEnvelope.Notification<?> notification) {
            @SuppressWarnings("unchecked")
            T payload = (T) notification.payload();
            CgCodecs.MapCodecBuilder<T> out = CgCodecs.map(ops)
                    .field("k", CgCodecs.STRING, KIND_NOTIFY)
                    .field("m", CgCodecs.STRING, notification.method());
            if (payload != null) out.raw("p", payload);
            return out.build();
        }
        if (envelope instanceof CgEnvelope.Cancel cancel) {
            return CgCodecs.map(ops)
                    .field("k", CgCodecs.STRING, KIND_CANCEL)
                    .field("i", CgCodecs.INT, cancel.id())
                    .build();
        }
        // Unreachable while CgEnvelope has four implementations, and a real failure the moment a fifth is
        // added without touching this file -- which is the one edit this design still requires.
        throw new CgCodecException("no encoder for envelope " + envelope.getClass().getName());
    }

    public static <T> CgEnvelope decode(CgDynamicOps<T> ops, T input) {
        CgCodecs.MapCodecReader<T> in = CgCodecs.read(ops, input);
        String kind = in.field("k", CgCodecs.STRING);
        switch (kind) {
            case KIND_REQUEST:
                return new CgEnvelope.Request<>(
                        in.field("i", CgCodecs.INT),
                        in.field("m", CgCodecs.STRING),
                        in.has("p") ? in.raw("p") : null);
            case KIND_RESPONSE:
                return new CgEnvelope.Response<>(
                        in.field("i", CgCodecs.INT),
                        in.field("ok", CgCodecs.BOOL),
                        in.has("p") ? in.raw("p") : null,
                        in.optional("e", CgCodecs.STRING, ""));
            case KIND_NOTIFY:
                return new CgEnvelope.Notification<>(
                        in.field("m", CgCodecs.STRING),
                        in.has("p") ? in.raw("p") : null);
            case KIND_CANCEL:
                return new CgEnvelope.Cancel(in.field("i", CgCodecs.INT));
            default:
                throw new CgCodecException("unknown envelope kind '" + kind + "'");
        }
    }
}
