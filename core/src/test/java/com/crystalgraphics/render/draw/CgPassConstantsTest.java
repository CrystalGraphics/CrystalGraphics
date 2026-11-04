package com.crystalgraphics.render.draw;

import com.crystalgraphics.gl.buffer.staging.CgBufferWriter;
import com.crystalgraphics.gl.buffer.staging.CgStagingBuffer;
import org.joml.Matrix4f;
import org.junit.Test;

import static org.junit.Assert.*;

/** The pass's blocks must lay out exactly what {@code cg_env.glsl}'s {@code CgViewBlock} and {@code CgEnvironmentBlock} declare. */
public class CgPassConstantsTest {

    @Test
    public void passConstantsLayOutTheViewAndEnvironmentBlocks() {
        Matrix4f view = new Matrix4f().translation(1, 2, 3);
        Matrix4f projection = new Matrix4f().setOrtho(0, 640, 480, 0, -1, 1);
        CgPassConstants constants = new CgPassConstants().time(2f).resolution(640, 480).camera(4, 5, 6).depth(true, false)
                .origin(100.5, 64, -2000.25).sun(0f, 2f, 0f, 0.5f).sunShare(0.4f).fog(0.1f, 0.2f, 0.3f, 8f, 64f)
                .encodedTarget(true).entityLights(true);
        constants.view.set(view);
        constants.projection.set(projection);
        Matrix4f previous = new Matrix4f().perspective(1f, 1.5f, 0.05f, 100f).translate(0.5f, 0f, -1f);
        constants.history(previous, 70000, true);
        float[] ours = new float[CgPassConstants.FLOATS];
        constants.write(ours, 0);

        CgBufferWriter viewWriter = new CgBufferWriter(new CgStagingBuffer(CgPassConstants.VIEW_FLOATS), CgPassConstants.VIEW_FORMAT);
        viewWriter.reset().beginRecord()
                .mat4("cg_ViewMatrix", view)
                .mat4("cg_ProjMatrix", projection)
                .vec4("cg_Time", 0.1f, 2f, 4f, 6f)
                .vec2("cg_Resolution", 640, 480)
                .vec4("cg_CameraPos", 4, 5, 6, 1)
                .vec4("cg_DepthParams", 1, 0, 0, 1)
                .vec4("cg_WorldOrigin", 100.5f, 64, -2000.25f, 0)
                .mat4("cg_PrevViewProj", previous)
                .vec4("cg_FrameParams", 70000 & 0xFFFF, 1, 0, 1);
        CgBufferWriter environmentWriter = new CgBufferWriter(new CgStagingBuffer(CgPassConstants.ENVIRONMENT_FLOATS),
                CgPassConstants.ENVIRONMENT_FORMAT);
        environmentWriter.reset().beginRecord()
                .vec4("cg_SunDirection", 0, 1, 0, 0.5f)
                .vec4("cg_FogColor", 0.1f, 0.2f, 0.3f, 1)
                .vec4("cg_FogParams", 8, 64, 0.4f, 1);

        assertEquals(CgPassConstants.VIEW_FLOATS, CgPassConstants.VIEW_FORMAT.getFloatCount());
        assertEquals(CgPassConstants.ENVIRONMENT_FLOATS, CgPassConstants.ENVIRONMENT_FORMAT.getFloatCount());
        float[] theirs = new float[CgPassConstants.FLOATS];
        System.arraycopy(viewWriter.rawData(), 0, theirs, 0, CgPassConstants.VIEW_FLOATS);
        System.arraycopy(environmentWriter.rawData(), 0, theirs, CgPassConstants.VIEW_FLOATS, CgPassConstants.ENVIRONMENT_FLOATS);
        assertArrayEquals(theirs, ours, 0f);

        float[] again = new float[CgPassConstants.FLOATS];
        new CgPassConstants().read(ours, 0).write(again, 0);
        assertArrayEquals(ours, again, 0f);
    }
}
