package io.github.somehussar.crystalgraphics.harness.scene;

import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;
import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import io.github.somehussar.crystalgraphics.harness.HarnessSceneLifecycle;
import io.github.somehussar.crystalgraphics.harness.util.HarnessFboHelper;
import io.github.somehussar.crystalgraphics.harness.util.HarnessShaderUtil;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.FloatBuffer;
import java.util.logging.Logger;

public class TriangleScene2D implements HarnessSceneLifecycle {

    private static final Logger LOGGER = Logger.getLogger(TriangleScene2D.class.getName());

    static final String VERT_SOURCE =
            "#version 130\n" +
            "in vec2 a_pos;\n" +
            "in vec3 a_color;\n" +
            "out vec3 v_color;\n" +
            "void main() {\n" +
            "    gl_Position = vec4(a_pos, 0.0, 1.0);\n" +
            "    v_color = a_color;\n" +
            "}\n";

    static final String FRAG_SOURCE =
            "#version 130\n" +
            "in vec3 v_color;\n" +
            "out vec4 fragColor;\n" +
            "void main() {\n" +
            "    fragColor = vec4(v_color, 1.0);\n" +
            "}\n";

    static final float[] TRI_DATA = {
         0.0f,  0.5f,  1.0f, 0.0f, 0.0f,
        -0.5f, -0.5f,  0.0f, 1.0f, 0.0f,
         0.5f, -0.5f,  0.0f, 0.0f, 1.0f
    };
    
    @Override
    public void init(HarnessContext ctx) {
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        String outputDir = ctx.getOutputDir();
        String outputFile = ctx.getOutputSettings().buildBaseFilename("png");
        int fboWidth = ctx.getScreenWidth();
        int fboHeight = ctx.getScreenHeight();

        HarnessFboHelper fbo = HarnessFboHelper.create(fboWidth, fboHeight, true);
        fbo.bind();
        fbo.clear(0.1f, 0.1f, 0.1f, 1.0f);

        renderTriangle();

        GL11.glFinish();

        fbo.captureToFile(outputDir, outputFile);

        LOGGER.info("[Harness] FBO dimensions: " + fboWidth + "x" + fboHeight);

        fbo.unbind();
        fbo.delete();

        LOGGER.info("[Harness] Triangle scene complete.");
    }

    @Override
    public void dispose() {
    }

    private void renderTriangle() {
        int program = HarnessShaderUtil.compileProgram(VERT_SOURCE, FRAG_SOURCE);
        int vao = GL30.glGenVertexArrays();
        int vbo = GL15.glGenBuffers();

        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);

        FloatBuffer buf = BufferUtils.createFloatBuffer(TRI_DATA.length);
        buf.put(TRI_DATA).flip();
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
