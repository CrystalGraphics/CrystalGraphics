package com.crystalgraphics.render.graph;

import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.render.draw.CgOrder;
import com.crystalgraphics.render.draw.CgPassConstants;
import org.junit.Test;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

/** The undeclared-access check (this task runs it as {@code throw}): a binding resolves only what its step recorded. */
public class CgGraphCheckTest {

    private static final CgTextureDesc DESC = CgTextureDesc.of(8, 8, CgFormat.RGBA8_UNORM);

    @Test
    public void aBindingOutsideTheStepsThatRecordedItIsRefused() {
        CgGraphTexture texture = CgGraphTexture.transientTexture("t", DESC);
        CgGraphBuffer buffer = CgGraphBuffer.transientBuffer("b", CgBufferDesc.of(64, CgBufferUsage.STORAGE));
        assertThrows(IllegalStateException.class, texture::rhiView);
        assertThrows(IllegalStateException.class, buffer::boundId);
    }

    @Test
    public void aStepMayBindWhatItReads_andNothingAfterItEnds() {
        CgGraphTexture read = CgGraphTexture.requested("read", DESC), other = CgGraphTexture.requested("other", DESC);
        CgRecording rec = new CgRecording();
        CgRasterPass pass = rec.raster("pass").color(CgGraphTexture.requested("target", DESC), CgLoad.load())
                .constants(new CgPassConstants()).order(CgOrder.LOOKBACK).reads(read);
        pass.end();
        CgFrame frame = new CgFrameBuilder().build(new CgFrameGraph().add(rec.seal()));

        CgPass before = CgGraphCheck.allow(frame, 0, 1, null);
        try {
            assertNull("allowed, and resolved to nothing outside an execution", read.rhiView());
            assertThrows(IllegalStateException.class, other::rhiView);
        } finally {
            CgGraphCheck.allow(frame, 0, -1, before);
        }
        assertThrows(IllegalStateException.class, read::rhiView);
    }
}
