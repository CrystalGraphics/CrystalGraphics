package io.github.somehussar.crystalgraphics.harness;

import com.crystalgraphics.freetype.FTBitmap;
import com.crystalgraphics.freetype.FTFace;
import com.crystalgraphics.freetype.FTGlyphMetrics;
import com.crystalgraphics.freetype.FTLoadFlags;
import com.crystalgraphics.freetype.FTRenderMode;
import com.crystalgraphics.freetype.FreeTypeLibrary;
import com.crystalgraphics.harfbuzz.HBBuffer;
import com.crystalgraphics.harfbuzz.HBFont;
import com.crystalgraphics.harfbuzz.HBGlyphInfo;
import com.crystalgraphics.harfbuzz.HBGlyphPosition;
import com.crystalgraphics.harfbuzz.HBShape;
import com.crystalgraphics.text.FreeTypeHarfBuzzIntegration;
import io.github.somehussar.crystalgraphics.api.font.CgFontKey;
import io.github.somehussar.crystalgraphics.api.font.CgFontStyle;
import io.github.somehussar.crystalgraphics.api.font.CgGlyphKey;
import io.github.somehussar.crystalgraphics.api.font.CgAtlasRegion;
import io.github.somehussar.crystalgraphics.gl.text.CgGlyphAtlas;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

final class TextSceneScene implements HarnessScene {

    private static final Logger LOGGER = Logger.getLogger(TextSceneScene.class.getName());

    private static final String TEST_STRING = "Hello, this is a test";
    private static final int FONT_SIZE_PX = 32;
    private static final int ATLAS_SIZE = 512;
    private static final int FBO_WIDTH = 800;
    private static final int FBO_HEIGHT = 600;

    @Override
    public void run(HarnessContext ctx, String outputDir) {
        String fontPath = AtlasDumpScene.findSystemFont();
        LOGGER.info("[Harness] Text scene: font=" + fontPath);
        LOGGER.info("[Harness] Text scene: size=" + FONT_SIZE_PX + "px");
        LOGGER.info("[Harness] Text scene: text=\"" + TEST_STRING + "\"");

        FreeTypeLibrary ftLib = FreeTypeLibrary.create();
        FTFace face = ftLib.newFace(fontPath, 0);
        face.setPixelSizes(0, FONT_SIZE_PX);

        HBFont hbFont = FreeTypeHarfBuzzIntegration.createHBFontFromFTFace(face);

        HBBuffer buffer = HBBuffer.create();
        buffer.addUTF8(TEST_STRING);
        buffer.guessSegmentProperties();
        HBShape.shape(hbFont, buffer);

        HBGlyphInfo[] infos = buffer.getGlyphInfos();
        HBGlyphPosition[] positions = buffer.getGlyphPositions();

        CgFontKey fontKey = new CgFontKey(fontPath, CgFontStyle.REGULAR, FONT_SIZE_PX);
        CgGlyphAtlas atlas = CgGlyphAtlas.create(ATLAS_SIZE, ATLAS_SIZE, CgGlyphAtlas.Type.BITMAP);

        List<GlyphQuad> quads = new ArrayList<GlyphQuad>();
        float penX = 0.0f;
        float penY = 0.0f;
        long frame = 1;

        float totalAdvance = 0.0f;
        for (int i = 0; i < positions.length; i++) {
            totalAdvance += positions[i].getXAdvance() / 64.0f;
        }
        penX = (FBO_WIDTH - totalAdvance) * 0.5f;
        penY = (FBO_HEIGHT + FONT_SIZE_PX) * 0.5f;

        for (int i = 0; i < infos.length; i++) {
            int glyphIndex = infos[i].getCodepoint();
            float xOffset = positions[i].getXOffset() / 64.0f;
            float yOffset = positions[i].getYOffset() / 64.0f;
            float xAdvance = positions[i].getXAdvance() / 64.0f;

            face.loadGlyph(glyphIndex, FTLoadFlags.FT_LOAD_DEFAULT);
            face.renderGlyph(FTRenderMode.FT_RENDER_MODE_NORMAL);

            FTBitmap bitmap = face.getGlyphBitmap();
            int w = bitmap.getWidth();
            int h = bitmap.getHeight();

            if (w > 0 && h > 0) {
                byte[] pixels = normalizeBitmapBuffer(bitmap);
                FTGlyphMetrics metrics = face.getGlyphMetrics();
                float bearingX = metrics.getHoriBearingX() / 64.0f;
                float bearingY = metrics.getHoriBearingY() / 64.0f;

                CgGlyphKey glyphKey = new CgGlyphKey(fontKey, glyphIndex, false, 0);
                CgAtlasRegion region = atlas.getOrAllocate(
                        glyphKey, pixels, w, h, bearingX, bearingY, frame);

                if (region != null && region.getWidth() > 0) {
                    float qx = penX + xOffset + region.getBearingX();
                    float qy = penY + yOffset - region.getBearingY();
                    quads.add(new GlyphQuad(qx, qy, region.getWidth(), region.getHeight(),
                            region.getU0(), region.getV0(), region.getU1(), region.getV1()));
                }
            }

            penX += xAdvance;
        }

        LOGGER.info("[Harness] Shaped " + infos.length + " glyphs, " + quads.size() + " visible quads");

        // Dump atlas texture
        ScreenshotUtil.captureTexture(atlas.getTextureId(), ATLAS_SIZE, ATLAS_SIZE,
                0x8229, outputDir, "bitmap-atlas.png");

        // Render text scene to FBO
        renderTextToFbo(quads, atlas.getTextureId(), outputDir);

        atlas.delete();
        buffer.destroy();
        hbFont.destroy();
        face.destroy();
        ftLib.destroy();

        LOGGER.info("[Harness] Text scene complete.");
    }

    private void renderTextToFbo(List<GlyphQuad> quads, int atlasTexId, String outputDir) {
        int fbo = GL30.glGenFramebuffers();
        int colorTex = GL11.glGenTextures();

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, colorTex);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8,
                FBO_WIDTH, FBO_HEIGHT, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,
                GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, colorTex, 0);

        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            throw new RuntimeException("Text scene FBO incomplete: 0x" + Integer.toHexString(status));
        }

        GL11.glViewport(0, 0, FBO_WIDTH, FBO_HEIGHT);
        GL11.glClearColor(0.15f, 0.15f, 0.2f, 1.0f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        String vertSource =
                "#version 130\n" +
                "in vec2 a_pos;\n" +
                "in vec2 a_uv;\n" +
                "out vec2 v_uv;\n" +
                "uniform mat4 u_projection;\n" +
                "void main() {\n" +
                "    gl_Position = u_projection * vec4(a_pos, 0.0, 1.0);\n" +
                "    v_uv = a_uv;\n" +
                "}\n";
        String fragSource =
                "#version 130\n" +
                "in vec2 v_uv;\n" +
                "out vec4 fragColor;\n" +
                "uniform sampler2D u_atlas;\n" +
                "void main() {\n" +
                "    float alpha = texture2D(u_atlas, v_uv).r;\n" +
                "    fragColor = vec4(1.0, 1.0, 1.0, alpha);\n" +
                "}\n";

        int program = HarnessShaderUtil.compileProgram(vertSource, fragSource);

        // Build orthographic projection: left=0, right=FBO_WIDTH, bottom=FBO_HEIGHT, top=0
        FloatBuffer projBuf = BufferUtils.createFloatBuffer(16);
        ortho(0, FBO_WIDTH, FBO_HEIGHT, 0, -1, 1, projBuf);

        int vao = GL30.glGenVertexArrays();
        int vbo = GL15.glGenBuffers();

        // 4 floats per vertex (pos.x, pos.y, uv.x, uv.y), 6 vertices per quad
        float[] vertexData = new float[quads.size() * 6 * 4];
        int idx = 0;
        for (GlyphQuad q : quads) {
            float x0 = q.x;
            float y0 = q.y;
            float x1 = q.x + q.w;
            float y1 = q.y + q.h;

            // Triangle 1
            vertexData[idx++] = x0; vertexData[idx++] = y0; vertexData[idx++] = q.u0; vertexData[idx++] = q.v0;
            vertexData[idx++] = x1; vertexData[idx++] = y0; vertexData[idx++] = q.u1; vertexData[idx++] = q.v0;
            vertexData[idx++] = x1; vertexData[idx++] = y1; vertexData[idx++] = q.u1; vertexData[idx++] = q.v1;
            // Triangle 2
            vertexData[idx++] = x0; vertexData[idx++] = y0; vertexData[idx++] = q.u0; vertexData[idx++] = q.v0;
            vertexData[idx++] = x1; vertexData[idx++] = y1; vertexData[idx++] = q.u1; vertexData[idx++] = q.v1;
            vertexData[idx++] = x0; vertexData[idx++] = y1; vertexData[idx++] = q.u0; vertexData[idx++] = q.v1;
        }

        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        FloatBuffer fbuf = BufferUtils.createFloatBuffer(vertexData.length);
        fbuf.put(vertexData).flip();
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, fbuf, GL15.GL_STATIC_DRAW);

        int stride = 4 * 4;
        int posLoc = GL20.glGetAttribLocation(program, "a_pos");
        int uvLoc = GL20.glGetAttribLocation(program, "a_uv");
        if (posLoc >= 0) {
            GL20.glVertexAttribPointer(posLoc, 2, GL11.GL_FLOAT, false, stride, 0);
            GL20.glEnableVertexAttribArray(posLoc);
        }
        if (uvLoc >= 0) {
            GL20.glVertexAttribPointer(uvLoc, 2, GL11.GL_FLOAT, false, stride, 8);
            GL20.glEnableVertexAttribArray(uvLoc);
        }

        GL20.glUseProgram(program);
        int projLoc = GL20.glGetUniformLocation(program, "u_projection");
        GL20.glUniformMatrix4(projLoc, false, projBuf);

        int atlasLoc = GL20.glGetUniformLocation(program, "u_atlas");
        GL20.glUniform1i(atlasLoc, 0);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, atlasTexId);

        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, quads.size() * 6);
        GL11.glFinish();

        ScreenshotUtil.captureFboColorTexture(fbo, colorTex,
                FBO_WIDTH, FBO_HEIGHT, outputDir, "text-scene.png");

        GL20.glUseProgram(0);
        GL30.glBindVertexArray(0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GL11.glDisable(GL11.GL_BLEND);

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        GL15.glDeleteBuffers(vbo);
        GL30.glDeleteVertexArrays(vao);
        GL20.glDeleteProgram(program);
        GL30.glDeleteFramebuffers(fbo);
        GL11.glDeleteTextures(colorTex);
    }

    // Column-major orthographic projection matrix
    private static void ortho(float left, float right, float bottom, float top,
                              float near, float far, FloatBuffer out) {
        out.clear();
        float dx = right - left;
        float dy = top - bottom;
        float dz = far - near;
        out.put(2.0f / dx); out.put(0);          out.put(0);           out.put(0);
        out.put(0);          out.put(2.0f / dy);  out.put(0);           out.put(0);
        out.put(0);          out.put(0);          out.put(-2.0f / dz);  out.put(0);
        out.put(-(right + left) / dx);
        out.put(-(top + bottom) / dy);
        out.put(-(far + near) / dz);
        out.put(1.0f);
        out.flip();
    }

    private byte[] normalizeBitmapBuffer(com.crystalgraphics.freetype.FTBitmap bitmap) {
        byte[] source = bitmap.getBuffer();
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int pitch = bitmap.getPitch();
        if (pitch == width) {
            return source;
        }
        byte[] packed = new byte[width * height];
        int absPitch = Math.abs(pitch);
        for (int row = 0; row < height; row++) {
            int srcRow = pitch >= 0 ? row : (height - 1 - row);
            System.arraycopy(source, srcRow * absPitch, packed, row * width, width);
        }
        return packed;
    }

    private static final class GlyphQuad {
        final float x, y, w, h;
        final float u0, v0, u1, v1;

        GlyphQuad(float x, float y, float w, float h, float u0, float v0, float u1, float v1) {
            this.x = x; this.y = y; this.w = w; this.h = h;
            this.u0 = u0; this.v0 = v0; this.u1 = u1; this.v1 = v1;
        }
    }
}
