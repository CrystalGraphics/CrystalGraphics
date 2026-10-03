package com.crystalgraphics.platform.service;

import com.crystalgraphics.platform.CgService;

import java.util.function.BiConsumer;

/**
 * The whole platform contribution to networking: move an opaque {@code byte[]}, and say how big one may be.
 *
 * <p><b>Four methods, and that is the design.</b> Every decision worth getting wrong — framing, stream
 * ids, fragmentation, flow control, cancellation, what a message even means — lives in {@code core} above
 * this line. A loader never sees an {@code CgEnvelope}, never learns a stream id and never picks a chunk
 * size. It registers a channel and forwards arrays. That is what "packets are owned by core" reduces to
 * in practice, and it is the reason a second platform is an afternoon rather than a re-implementation.</p>
 *
 * <h3>Depend on the loader's networking, not on raw Netty</h3>
 *
 * <p>Implementations are expected to sit on {@code SimpleNetworkWrapper} (1.7.10) or
 * {@code PayloadRegistrar} / {@code SimpleChannel} / Fabric networking (1.20.x) rather than attaching
 * their own pipeline handlers. Those already solve channel registration, the login handshake, "is this
 * player's client running our mod", compression and the login/play phase split — the parts that are
 * genuinely painful and easy to get subtly wrong. Bypassing them costs <em>more</em> per-platform code,
 * not less, and it differs by version anyway.</p>
 *
 * <h3>What actually differs between versions — the whole list</h3>
 *
 * <table>
 *   <tr><th>Differs</th><th>Where it is absorbed</th></tr>
 *   <tr><td>Channel identity: a ≤20-char string on 1.7.10, a {@code ResourceLocation} on 1.20.x</td>
 *       <td>Private to the adapter; {@code core} never names a channel</td></tr>
 *   <tr><td>Payload type: raw {@code ByteBuf} on 1.7.10, a registered {@code CustomPacketPayload}
 *           record with a {@code StreamCodec} from 1.20.5</td>
 *       <td>Private to the adapter; both reduce to carrying a {@code byte[]}</td></tr>
 *   <tr><td>Player handle: {@code EntityPlayerMP} / {@code ServerPlayer}</td>
 *       <td>{@link #sendToPlayer}'s {@code Object}, never inspected above this seam</td></tr>
 *   <tr><td>Delivery thread: Netty on 1.7.10, main thread on Fabric and NeoForge</td>
 *       <td>{@link #setInboundHandler} — enqueue-only, so either is correct</td></tr>
 *   <tr><td>Frame ceiling: four different numbers across two eras</td>
 *       <td>{@link #maxFrameBytes()}, asked for and never assumed</td></tr>
 * </table>
 *
 * <p><b>Nothing else reaches {@code core}</b>, which is what makes "does this work on a version nobody
 * has written an adapter for" a question with a testable answer rather than a hope:
 * {@code FrameMultiplexerTest.everyPlatformCeilingCarriesTheSameMessagesIntact} runs the engine at every
 * ceiling in the table above plus one below and one above them all.</p>
 *
 * <h3>The one thing that is not this interface's problem, and bites anyway</h3>
 *
 * <p>{@code core} compiles to <b>Java 21 bytecode</b>. A loader module must downgrade it to its target's
 * JVM floor, or every class fails to load with {@code UnsupportedClassVersionError} — which is a
 * packaging property, not a networking one, and is why it is recorded here rather than discovered per
 * adapter:</p>
 *
 * <table>
 *   <tr><th>Target</th><th>JVM</th><th>Needed</th></tr>
 *   <tr><td>1.7.10</td><td>Java 8</td><td>jvmDowngrader to 8 — <i>in place and verified</i></td></tr>
 *   <tr><td>1.20.1 / 1.20.4</td><td>Java 17</td><td>the same step with {@code downgradeTargetVersion = 17}</td></tr>
 *   <tr><td>1.21 and later</td><td>Java 21</td><td>nothing</td></tr>
 * </table>
 *
 * <p>The 1.7.10 pipeline is the reference and its second half is the half that is forgotten: {@code
 * downgradeJar} rewrites a record's supertype to a jvmDowngrader stub, and {@code shadeDowngradedApi}
 * is what puts that stub <em>in the jar</em>, relocated under the mod's own package so two mods shipping
 * jvmDowngrader do not collide. Run only the first and every record in {@code core} — {@code
 * CgEnvelope}'s four kinds among them — loads against a superclass that is
 * nowhere on the classpath.</p>
 *
 * <h3>{@link #maxFrameBytes()} is asked for, never assumed</h3>
 *
 * <p>Measured from the sources rather than documentation, and it is asymmetric in a way no constant
 * survives: <b>client→server is ~32 KB on every version</b> — 32,766 on 1.7.10 and 32,767 on 1.20.1,
 * because vanilla writes the payload length as a <em>signed short</em> in both eras — while server→client
 * is 2,097,050 on 1.7.10 (Forge widens it) and 1,048,576 on 1.20.1. A hardcoded number is wrong on three
 * of those four. Reporting it lets {@code CgFrameMultiplexer} size fragments correctly with no version check
 * anywhere in {@code core}.</p>
 */
public interface CgNetworkChannel {

    /**
     * The smallest ceiling any supported platform imposes, and therefore one that is safe everywhere.
     *
     * <p>It is <b>not</b> a 1.7.10 number even though it happens to be 1.7.10's: client→server is a
     * vanilla <em>signed short</em> in every era, so the four measured limits are 32,766 (1.7.10 c→s),
     * 32,767 (1.20.x c→s), 1,048,576 (1.20.x s→c) and 2,097,050 (1.7.10 s→c, which Forge widens). This is
     * the minimum of those, so an implementation that cannot determine its own limit — or a host with no
     * networking at all — can return it without being wrong on any platform.</p>
     *
     * <p>It is a <em>floor</em>, never a default to build on: a platform that can carry more should say
     * so, and one that carries less than this does not exist among the targets. {@link #maxFrameBytes()}
     * is still asked for rather than assumed, which is what makes {@code core} correct for a version
     * nobody has written an adapter for yet.</p>
     */
    int SAFE_FLOOR_BYTES = 32_766;

    /**
     * The slot, rather than part of {@code CgPlatformService}'s closed bundle: a loader with no
     * networking is a legitimate configuration, and the engine runs where there is no peer at all.
     */
    CgService<CgNetworkChannel> SERVICE = CgService.of("crystalgraphics:network", new CgNetworkChannel() {
        @Override
        public int maxFrameBytes() {
            return SAFE_FLOOR_BYTES;
        }

        @Override
        public void sendToServer(byte[] frame) {
        }

        @Override
        public void sendToPlayer(Object player, byte[] frame) {
        }

        @Override
        public void setInboundHandler(BiConsumer<Object, byte[]> handler) {
        }

        @Override
        public boolean isAvailable() {
            return false;
        }
    });

    /**
     * The largest single frame this platform will carry, in bytes.
     *
     * <p>Answer the <em>smaller</em> of the two directions if they differ and one implementation serves
     * both. Over-reporting is the dangerous mistake: an oversized payload throws from inside the loader
     * mid-send, with the connection already committed.</p>
     */
    int maxFrameBytes();

    /** Client → server. */
    void sendToServer(byte[] frame);

    /**
     * Server → one client.
     *
     * @param player the platform's own player handle, opaque to {@code core} — an {@code EntityPlayerMP}
     *               on 1.7.10, a {@code ServerPlayer} on 1.20.x. It is used only as an identity to route
     *               by and is never inspected above this seam, which is what keeps {@code core} free of
     *               {@code net.minecraft} imports that its build guard would refuse anyway
     */
    void sendToPlayer(Object player, byte[] frame);

    /**
     * Installs the inbound sink. <b>Safe to call from any thread.</b>
     *
     * <p>The handler hands straight to {@code CgFrameMultiplexer#onFrameReceived}, whose whole body is one
     * add to a {@code ConcurrentLinkedQueue} — everything real happens in {@code pump()} on the thread
     * that owns the tree. So an adapter should not add a hop of its own, and <em>equally</em> need not
     * fight a platform that hops for it: Fabric's {@code registerGlobalReceiver} and NeoForge's default
     * {@code IPayloadHandler} both deliver on the main thread and cannot be told otherwise, while
     * 1.7.10's {@code SimpleNetworkWrapper} delivers on the Netty thread. Both are correct here.
     *
     * <p>This used to read "must <em>not</em> schedule onto the game thread itself", which is not
     * something two of the four targets let an adapter promise. What actually matters is weaker and
     * achievable: <b>enqueue, never dispatch</b>. Ordering survives either way, because one queue is fed
     * in arrival order whichever thread does the feeding.</p>
     *
     * <p>The first argument is the sender: the player handle on a server, and ignored on a client, where
     * there is only ever one peer.</p>
     */
    void setInboundHandler(BiConsumer<Object, byte[]> handler);

    /**
     * Whether a real channel is behind this.
     *
     * <p>The absent-value answers {@code false}, which is what a caller checks before offering a remote
     * workspace. It deliberately does not throw: a host with no networking is a legitimate deployment —
     * the harness is one — and it should degrade to the in-process transport rather than fail.</p>
     */
    boolean isAvailable();
}
