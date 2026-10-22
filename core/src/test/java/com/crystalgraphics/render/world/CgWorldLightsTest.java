package com.crystalgraphics.render.world;

import org.joml.Matrix4f;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class CgWorldLightsTest {

    @Test
    public void aBobFoldedIntoTheProjectionComesBackOut() {
        // As Minecraft 1.20.1 builds it: its perspective, then bobView's roll, pitch and shift (GameRenderer.bobView).
        Matrix4f perspective = new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, 0.05f, 1024f);
        Matrix4f bob = new Matrix4f().translate(0.04f, -0.07f, 0f).rotateZ(0.05f).rotateX(0.03f);
        Matrix4f projection = new Matrix4f(perspective).mul(bob);
        Matrix4f gotPerspective = new Matrix4f(), gotBob = new Matrix4f();
        CgWorldLights.splitPerspective(projection, gotPerspective, gotBob);
        assertTrue(gotBob + " is not " + bob, gotBob.equals(bob, 1e-5f));
        assertTrue(gotPerspective + " is not " + perspective, gotPerspective.equals(perspective, 1e-4f));
    }

    @Test
    public void aFlippedYStaysThePerspectives() {
        Matrix4f perspective = new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, 0.05f, 1024f).m11(-1.4f);
        Matrix4f bob = new Matrix4f().translate(0.02f, 0.05f, 0f).rotateZ(-0.04f);
        Matrix4f gotPerspective = new Matrix4f(), gotBob = new Matrix4f();
        CgWorldLights.splitPerspective(new Matrix4f(perspective).mul(bob), gotPerspective, gotBob);
        assertTrue(gotBob + " is not " + bob, gotBob.equals(bob, 1e-5f));
        assertTrue(gotPerspective + " is not " + perspective, gotPerspective.equals(perspective, 1e-4f));
    }

    @Test
    public void noBobIsTheIdentity() {
        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(90), 1.5f, 0.1f, 512f)
                .m20(0.1f).m21(-0.05f);   // off centre, as a jittered or split view
        Matrix4f perspective = new Matrix4f(), bob = new Matrix4f();
        CgWorldLights.splitPerspective(projection, perspective, bob);
        assertTrue(bob.equals(new Matrix4f(), 1e-6f));
        assertTrue(perspective.equals(projection, 1e-6f));
    }
}
