package com.crystalgraphics.text.msdf;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;

/** {@link CgMsdfGenerator#toTexels}: the bytes GL would store for the same float upload into RGBA8. */
public class CgMsdfTexelsTest {

    @Test
    public void mtsdfChannelsClampAndRoundAsGlDoes() {
        float[] field = {-0.5f, 0f, 0.5f, 1f, 1.5f, 0.2f, Float.NaN, 0.998f};
        assertArrayEquals(bytes(0, 0, 128, 255, 255, 51, 0, 254), CgMsdfGenerator.toTexels(field, 4));
    }

    @Test
    public void msdfGetsAnOpaqueAlpha() {
        float[] field = {0.25f, 0.5f, 0.75f, 1f, 0f, 0.1f};
        assertArrayEquals(bytes(64, 128, 191, 255, 255, 0, 26, 255), CgMsdfGenerator.toTexels(field, 3));
    }

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) out[i] = (byte) values[i];
        return out;
    }
}
