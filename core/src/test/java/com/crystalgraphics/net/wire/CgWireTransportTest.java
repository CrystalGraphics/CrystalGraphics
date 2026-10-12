package com.crystalgraphics.net.wire;

import com.crystalgraphics.net.protocol.CgEnvelope;
import com.crystalgraphics.net.protocol.CgEnvelopeCodec;
import com.crystalgraphics.serialization.CgCodecException;
import com.crystalgraphics.serialization.CgPlainOps;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class CgWireTransportTest {

    private CgFrameMultiplexer serverFrames, clientFrames;
    private CgWireTransport server;
    private final List<Object> received = new ArrayList<>();

    @Before
    public void connect() {
        CgFrameMultiplexer[] peer = new CgFrameMultiplexer[2];
        serverFrames = new CgFrameMultiplexer(32 * 1024, false, frame -> peer[1].onFrameReceived(frame));
        clientFrames = new CgFrameMultiplexer(32 * 1024, true, frame -> peer[0].onFrameReceived(frame));
        peer[0] = serverFrames;
        peer[1] = clientFrames;
        server = new CgWireTransport(serverFrames);
        new CgWireTransport(clientFrames).setReceiver(received::add);
    }

    @Test
    public void aNotificationOfBytesArrivesAsItsEnvelope() {
        byte[] frame = {1, 2, 3, 4, 5, 99, 99};
        assertTrue(server.sendNotification("cg/objects", frame, 5));
        server.sendNotification("cg/objects", frame, 2);
        server.pump();
        clientFrames.pump();

        assertEquals(2, received.size());
        CgEnvelope.Notification<?> first = (CgEnvelope.Notification<?>) received.get(0);
        CgEnvelope.Notification<?> second = (CgEnvelope.Notification<?>) received.get(1);
        assertEquals("cg/objects", first.method());
        assertArrayEquals(new byte[] {1, 2, 3, 4, 5}, (byte[]) first.payload());
        assertArrayEquals(new byte[] {1, 2}, (byte[]) second.payload());
        assertSame("a method's name is decoded once", first.method(), second.method());
    }

    @Test
    public void envelopesStillTravelBesideThem() {
        server.send(CgEnvelopeCodec.encode(CgPlainOps.INSTANCE, new CgEnvelope.Notification<>("cg/hello", null)));
        server.sendNotification("cg/objects", new byte[] {7}, 1);
        server.pump();
        clientFrames.pump();

        assertEquals("cg/hello", ((CgEnvelope.Notification<?>) CgEnvelopeCodec.decode(CgPlainOps.INSTANCE, received.get(0))).method());
        assertArrayEquals(new byte[] {7}, (byte[]) ((CgEnvelope.Notification<?>) received.get(1)).payload());
    }

    @Test(expected = CgCodecException.class)
    public void aCutOffMethodIsRefused() {
        serverFrames.send(new byte[] {(byte) CgWireTransport.RAW_NOTIFICATION, 20, 'c', 'g'});
        serverFrames.flush();
        clientFrames.pump();
    }
}
