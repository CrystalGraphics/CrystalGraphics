package com.crystalgraphics.gl.texture;

import com.crystalgraphics.api.texture.CgTextureSpec;
import com.crystalgraphics.util.io.CgTextureIO.CgImageData;
import org.junit.After;
import org.junit.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Textures from files: loaded at first use, cached per spec, a stand-in for what will not load. No GL: uploads queue. */
public class CgTextureFromFilesTest {

    private final Path dir;

    public CgTextureFromFilesTest() throws IOException {
        dir = Files.createTempDirectory("cg-textures");
    }

    @After
    public void free() {
        CgTextureManager.get().freeAll();
    }

    private String png(String name, int w, int h) throws IOException {
        File file = dir.resolve(name).toFile();
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB), "png", file);
        return file.getAbsolutePath();
    }

    @Test
    public void aFileIsReadAtFirstUseNotAtCreate() throws IOException {
        String path = dir.resolve("later.png").toFile().getAbsolutePath();
        CgTexture2D texture = CgTexture2D.create(path, CgTextureSpec.RGBA8_LINEAR);
        png("later.png", 6, 3);
        assertEquals(6, texture.getWidth());
        assertEquals(3, texture.getHeight());
        assertFalse(texture.isMissing());
    }

    @Test
    public void oneFileIsOneTexturePerSpec() throws IOException {
        String path = png("icon.png", 4, 4);
        CgTexture2D linear = CgTexture2D.create(path, CgTextureSpec.RGBA8_LINEAR);
        assertSame(linear, CgTexture2D.create(path, CgTextureSpec.RGBA8_LINEAR));
        assertSame(linear, CgTexture2D.create(path, CgTextureSpec.RGBA8_LINEAR.toBuilder().build()));
        assertNotSame(linear, CgTexture2D.create(path, CgTextureSpec.RGBA8_NEAREST));
    }

    @Test
    public void aFileThatWillNotLoadIsTheCheckerboard() {
        CgTexture2D texture = CgTexture2D.create(dir.resolve("absent.png").toString(), CgTextureSpec.RGBA8_LINEAR);
        assertTrue(texture.isMissing());
        assertEquals(8, texture.getWidth());
        assertTrue(CgTextureManager.get().getFallback().isMissing());
    }

    @Test
    public void framesOfDifferentSizesShareTheLargestLayer() throws IOException {
        png("frame_0.png", 4, 2);
        png("frame_1.png", 2, 2);
        CgTexture2DArray frames = CgTexture2DArray.frames(CgTextureSpec.RGBA8_LINEAR,
                dir.resolve("frame_%d.png").toString(), 2);
        assertEquals(4, frames.getWidth());
        assertEquals(2, frames.getHeight());
        assertEquals(2, frames.getDepth());
        assertFalse(frames.isMissing());
    }

    @Test
    public void aSmallerFrameIsCentredClearRoundIt() {
        ByteBuffer pixels = ByteBuffer.allocateDirect(2 * 2);
        for (int i = 0; i < 4; i++) pixels.put((byte) 9);
        pixels.flip();
        ByteBuffer layer = CgTexture2DArray.centred(new CgImageData(pixels, 2, 2, 1), 4, 2, 1);
        byte[] row = new byte[8];
        layer.get(row);
        assertEquals("[0, 9, 9, 0, 0, 9, 9, 0]", Arrays.toString(row));
    }
}
