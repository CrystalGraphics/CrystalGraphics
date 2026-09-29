package com.crystalgraphics.mc.shader;

import org.joml.Matrix4f;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** A persistent binding set written every frame stays the size of its uniforms. */
public class CgShaderBindingsImplTest {

    @Test
    public void aNameWrittenAgainReplacesItsValue() {
        CgShaderBindingsImpl bindings = new CgShaderBindingsImpl();
        for (int frame = 0; frame < 100; frame++) {
            bindings.mat4("u_model", new Matrix4f().translate(frame, 0, 0)).set1i("u_mode", frame);
        }
        assertEquals(2, bindings.size());
    }

    @Test
    public void clearForgetsTheNames() {
        CgShaderBindingsImpl bindings = new CgShaderBindingsImpl();
        bindings.set1f("u_a", 1f).set1f("u_b", 2f);
        bindings.clear();
        bindings.set1f("u_b", 3f);
        assertEquals(1, bindings.size());
    }
}
