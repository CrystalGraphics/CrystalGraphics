package com.crystalgraphics.render.draw;

import com.crystalgraphics.gl.buffer.staging.CgBufferWriter;
import com.crystalgraphics.gl.buffer.staging.CgStagingBuffer;
import org.joml.Matrix4f;
import org.junit.Test;

import static org.junit.Assert.*;

/** The pass block must lay out exactly what {@code cg_env.glsl}'s {@code CgFrameBlock} declares. */
public class CgPassConstantsTest {

    @Test
    public void passConstantsLayOutTheFrameBlock() {
        Matrix4f view = new Matrix4f().translation(1, 2, 3);
        Matrix4f projection = new Matrix4f().setOrtho(0, 640, 480, 0, -1, 1);
        CgPassConstants constants = new CgPassConstants().time(2f).resolution(640, 480).camera(4, 5, 6).depth(true, false)
                .origin(100.5, 64, -2000.25);
        constants.view.set(view);
        constants.projection.set(projection);
        float[] ours = new float[CgPassConstants.FLOATS];
        constants.write(ours, 0);

        CgBufferWriter writer = new CgBufferWriter(new CgStagingBuffer(CgPassConstants.FLOATS), CgPassConstants.FORMAT);
        writer.reset().beginRecord()
                .mat4("cg_ViewMatrix", view)
                .mat4("cg_ProjMatrix", projection)
                .vec4("cg_Time", 0.1f, 2f, 4f, 6f)
                .vec2("cg_Resolution", 640, 480)
                .vec4("cg_CameraPos", 4, 5, 6, 1)
                .vec4("cg_DepthParams", 1, 0, 0, 0)
                .vec4("cg_WorldOrigin", 100.5f, 64, -2000.25f, 0);

        assertEquals(CgPassConstants.FLOATS, CgPassConstants.FORMAT.getFloatCount());
        float[] theirs = new float[CgPassConstants.FLOATS];
        System.arraycopy(writer.rawData(), 0, theirs, 0, CgPassConstants.FLOATS);
        assertArrayEquals(theirs, ours, 0f);
    }

}
