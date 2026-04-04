package io.github.somehussar.crystalgraphics.harness;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.FloatBuffer;
import java.util.logging.Logger;

final class FboTriangleScene implements HarnessScene {

    private static final Logger LOGGER = Logger.getLogger(FboTriangleScene.class.getName());
    private static final int FBO_WIDTH = 800;
    private static final int FBO_HEIGHT = 600;

    @Override
    public void run(HarnessContext ctx, String outputDir) {
        int fbo = GL30.glGenFramebuffers();
        int colorTex = GL11.glGenTextures();
        int depthRb = GL30.glGenRenderbuffers();

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, colorTex);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8,
                FBO_WIDTH, FBO_HEIGHT, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, depthRb);
        GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL11.GL_DEPTH_COMPONENT,
                FBO_WIDTH, FBO_HEIGHT);
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, 0);

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,
                GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, colorTex, 0);
        GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER,
                GL30.GL_DEPTH_ATTACHMENT, GL30.GL_RENDERBUFFER, depthRb);

        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            throw new RuntimeException("FBO incomplete: status=0x" + Integer.toHexString(status));
        }

        GL11.glViewport(0, 0, FBO_WIDTH, FBO_HEIGHT);
        GL11.glClearColor(0.1f, 0.1f, 0.1f, 1.0f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);

        renderTriangle();

        GL11.glFinish();

        ScreenshotUtil.captureFboColorTexture(fbo, colorTex,
                FBO_WIDTH, FBO_HEIGHT, outputDir, "fbo-triangle.png");

        LOGGER.info("[Harness] FBO dimensions: " + FBO_WIDTH + "x" + FBO_HEIGHT);

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        GL30.glDeleteFramebuffers(fbo);
        GL30.glDeleteRenderbuffers(depthRb);
        GL11.glDeleteTextures(colorTex);

        LOGGER.info("[Harness] FBO triangle scene complete.");
    }

    private void renderTriangle() {
        String vertSource =
                "#version 130\n" +
                "in vec2 a_pos;\n" +
                "in vec3 a_color;\n" +
                "out vec3 v_color;\n" +
                "void main() {\n" +
                "    gl_Position = vec4(a_pos, 0.0, 1.0);\n" +
                "    v_color = a_color;\n" +
                "}\n";
        String fragSource =
                "#version 130\n" +
                "in vec3 v_color;\n" +
                "out vec4 fragColor;\n" +
                "void main() {\n" +
                "    fragColor = vec4(v_color, 1.0);\n" +
                "}\n";

        int program = HarnessShaderUtil.compileProgram(vertSource, fragSource);
        int vao = GL30.glGenVertexArrays();
        int vbo = GL15.glGenBuffers();

        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);

        FloatBuffer buf = BufferUtils.createFloatBuffer(TriangleScene.TRI_DATA.length);
        buf.put(TriangleScene.TRI_DATA).flip();
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buf, GL15.GL_STATIC_DRAW);

        int stride = 5 * 4;
        int posLoc = GL20.glGetAttribLocation(program, "a_pos");
        int colorLoc = GL20.glGetAttribLocation(program, "a_color");
        if (posLoc >= 0) {
            GL20.glVertexAttribPointer(posLoc, 2, GL11.GL_FLOAT, false, stride, 0);
            GL20.glEnableVertexAttribArray(posLoc);
        }
        if (colorLoc >= 0) {
            GL20.glVertexAttribPointer(colorLoc, 3, GL11.GL_FLOAT, false, stride, 8);
            GL20.glEnableVertexAttribArray(colorLoc);
        }

        GL20.glUseProgram(program);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        GL20.glUseProgram(0);

        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL15.glDeleteBuffers(vbo);
        GL30.glDeleteVertexArrays(vao);
        GL20.glDeleteProgram(program);
    }
}
