package com.crystalgraphics.mc.legacy.platform.net;

import com.crystalgraphics.platform.service.CgNetworkChannel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.network.FMLEventChannel;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.internal.FMLProxyPacket;
import net.minecraftforge.fml.relauncher.Side;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.INetHandler;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.PacketBuffer;

import java.util.function.BiConsumer;

/**
 * The whole of CrystalGraphics' Forge 1.8–1.12.2 networking.
 *
 * <p>It carries opaque frames and nothing else. Every protocol decision — framing, stream ids,
 * fragmentation, credit flow control, what a packet means — is in {@code core}, above
 * {@link CgNetworkChannel}. This class knows how to hand a {@code byte[]} to Forge and how big one may
 * be, which is the entire contract.</p>
 *
 * <h3>An event-driven channel, not {@code SimpleNetworkWrapper}</h3>
 *
 * <p>The first version used {@code SimpleNetworkWrapper} with an {@code IMessage} and a discriminator.
 * It registered during {@code preInit}, reported no error, and dispatched through
 * {@code FMLOutboundHandler} with a live {@code NetworkDispatcher} — everything on the send path
 * verified in a running client — and the handler <b>never fired</b>, silently, in either direction.</p>
 *
 * <p>{@code newEventDrivenChannel} is what CustomNPC+ and most long-lived LaunchWrapper-era mods use, and it is the
 * better fit here regardless of that: {@code SimpleNetworkWrapper} exists to marshal <em>typed</em>
 * messages — matching a discriminator byte to a message class and instantiating it reflectively — and we
 * want none of that. The frame's structure is decided in {@code core} and every byte of it is ours. An
 * event channel hands over a raw {@link ByteBuf} in both directions, which is exactly the contract this
 * class implements.</p>
 *
 * <p><b>Fewer moving parts is the point.</b> The codec, the discriminator table, the reflective
 * instantiation and the per-side pipeline handlers were all machinery we paid for and none of it carried
 * anything we needed.</p>
 *
 * <h3>The limits, measured rather than assumed</h3>
 *
 * <p>{@code C17PacketCustomPayload} throws above <b>32,766</b> bytes and {@code S3FPacketCustomPayload}
 * above <b>2,097,050</b>. This reports the smaller, because one channel serves both directions and
 * over-reporting fails inside Forge mid-send with the connection already committed. The client bound is
 * not a Forge decision and will not move: vanilla writes the length as a signed short.</p>
 *
 * <p>An event channel writes the buffer verbatim with no wrapper of its own, so there is no per-message
 * overhead to subtract. That removes a real bug the {@code SimpleNetworkWrapper} version had: a
 * four-byte length prefix pushed frames past the ceiling and Forge refused them mid-send.</p>
 *
 * <p>FML does <b>not</b> fragment for us. {@code CgFrameMultiplexer} does it instead, once, for every
 * platform.</p>
 */
public final class NetworkChannelLegacy implements CgNetworkChannel {

    /**
     * Twenty characters is the hard ceiling — {@code readStringFromBuffer(20)} in both custom-payload
     * packets — and a longer name fails at connect time rather than at registration.
     */
    private static final String CHANNEL = "crystalgraphics";

    /** @see NetworkChannelLegacy */
    private static final int MAX_FRAME_BYTES = 32_766;

    /** Logs the first few hops each way. */
    private static final boolean TRACE = Boolean.getBoolean("crystalgraphics.net.trace");

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");

    private final FMLEventChannel channel;
    private volatile BiConsumer<Object, byte[]> inbound = (sender, frame) -> { };

    private int sentCount;
    private int receivedCount;

    private NetworkChannelLegacy() {
        this.channel = NetworkRegistry.INSTANCE.newEventDrivenChannel(CHANNEL);
        // Subscribes this object's @SubscribeEvent methods to THIS CHANNEL's bus, not the global one --
        // a channel event only ever reaches handlers registered on that channel.
        this.channel.register(this);
    }

    /** Registers the channel. Once, from common init: a dedicated server needs it as much as a client. */
    static NetworkChannelLegacy create() {
        NetworkChannelLegacy created = new NetworkChannelLegacy();
        if (TRACE) LOGGER.info("[net] channel '{}' registered", CHANNEL);
        return created;
    }

    @Override
    public int maxFrameBytes() {
        return MAX_FRAME_BYTES;
    }

    @Override
    public void sendToServer(byte[] frame) {
        // copiedBuffer, not wrappedBuffer: FML keeps the buffer past this call, and wrapping would alias
        // an array the caller is free to reuse.
        channel.sendToServer(targeted(frame, Side.SERVER));
        if (TRACE && ++sentCount <= 6) {
            LOGGER.info("[net] -> server: {} bytes (send #{})", frame.length, sentCount);
        }
    }

    @Override
    public void sendToPlayer(Object player, byte[] frame) {
        channel.sendTo(targeted(frame, Side.CLIENT), (EntityPlayerMP) player);
        if (TRACE && ++sentCount <= 6) {
            LOGGER.info("[net] -> client: {} bytes (send #{})", frame.length, sentCount);
        }
    }

    /**
     * A packet that knows which side it is going to — and <b>{@code setTarget} is not optional</b>.
     *
     * <p>{@code new FMLProxyPacket(ByteBuf, String)} leaves {@code target} null; only the constructors
     * taking a {@code C17}/{@code S3F} custom payload set it. That difference decides whether a packet
     * is delivered <em>in single player</em>, and it is invisible everywhere else:</p>
     *
     * <ul>
     *   <li><b>Remote connection</b> — the packet is serialised, and the receiving side rebuilds it with
     *       {@code new FMLProxyPacket(c17)}, which sets {@code target} itself. A null target never
     *       survives the wire, so it never matters.</li>
     *   <li><b>Single player</b> — the connection is local and the same object arrives by reference.
     *       {@code FMLProxyPacket.processPacket} then does
     *       {@code NetworkRegistry.getChannel(this.channel, this.target)} with a null side, finds no
     *       channel, and <b>drops the packet with no log and no exception</b>.</li>
     * </ul>
     *
     * <p>Which is precisely the symptom this cost a day of: every send succeeded, the dispatcher was
     * live, the channel was registered on both sides, and nothing was ever received.</p>
     */
    private static FMLProxyPacket targeted(byte[] frame, Side target) {
        FMLProxyPacket packet = new FMLProxyPacket(new PacketBuffer(Unpooled.copiedBuffer(frame)), CHANNEL);
        packet.setTarget(target);
        return packet;
    }

    @Override
    public void setInboundHandler(BiConsumer<Object, byte[]> handler) {
        this.inbound = handler == null ? (sender, frame) -> { } : handler;
        if (TRACE) LOGGER.info("[net] inbound handler installed");
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    // ── Inbound. Both fire on the Netty thread. ─────────────────────────────

    /** A frame from a client. The sender is the player, which is what a session map keys on. */
    @SubscribeEvent
    public void onServerPacket(FMLNetworkEvent.ServerCustomPacketEvent event) {
        deliver(NetworkLegacy.player((NetHandlerPlayServer) handlerOf(event)), packetOf(event));
    }

    /** A frame from the server. One peer, so the sender is null. */
    @SubscribeEvent
    public void onClientPacket(FMLNetworkEvent.ClientCustomPacketEvent event) {
        deliver(null, packetOf(event));
    }

    private static INetHandler handlerOf(FMLNetworkEvent.CustomPacketEvent<?> event) {
        //? if <1.9 {
        /*return event.handler;
        *///?} else {
        return event.getHandler();
        //?}
    }

    private static FMLProxyPacket packetOf(FMLNetworkEvent.CustomPacketEvent<?> event) {
        //? if <1.9 {
        /*return event.packet;
        *///?} else {
        return event.getPacket();
        //?}
    }

    private void deliver(Object sender, FMLProxyPacket packet) {
        ByteBuf payload = packet.payload();
        byte[] frame = new byte[payload.readableBytes()];
        payload.readBytes(frame);
        if (TRACE && ++receivedCount <= 6) {
            LOGGER.info("[net] <- from {}: {} bytes (recv #{})",
                    sender == null ? "server" : "client", frame.length, receivedCount);
        }
        // Straight through. CgFrameMultiplexer.onFrameReceived only enqueues, and the connection pumps it on the
        // game thread -- hopping here would duplicate a hop core already owns.
        inbound.accept(sender, frame);
    }
}
