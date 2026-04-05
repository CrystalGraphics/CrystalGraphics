package io.github.somehussar.crystalgraphics.harness.util;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.logging.Logger;

public final class HarnessShaderUtil {

    private static final Logger LOGGER = Logger.getLogger(HarnessShaderUtil.class.getName());

    public static int compileProgram(String vertSource, String fragSource) {
        int vert = compileShader(GL20.GL_VERTEX_SHADER, vertSource);
        int frag = compileShader(GL20.GL_FRAGMENT_SHADER, fragSource);

        int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, vert);
        GL20.glAttachShader(program, frag);
        GL20.glLinkProgram(program);

        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetProgramInfoLog(program, 4096);
            GL20.glDeleteProgram(program);
            GL20.glDeleteShader(vert);
            GL20.glDeleteShader(frag);
            throw new RuntimeException("Shader link failed:\n" + log);
        }

        GL20.glDeleteShader(vert);
        GL20.glDeleteShader(frag);
        return program;
    }

    public static String loadResource(String path) {
        InputStream in = HarnessShaderUtil.class.getResourceAsStream(path);
        if (in == null) {
            throw new RuntimeException("Shader resource not found: " + path);
        }
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read shader: " + path, e);
        } finally {
            try { in.close(); } catch (IOException ignored) { }
        }
    }

    private static int compileShader(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);

        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shader, 4096);
            GL20.glDeleteShader(shader);
            String typeName = (type == GL20.GL_VERTEX_SHADER) ? "vertex" : "fragment";
            throw new RuntimeException(typeName + " shader compilation failed:\n" + log);
        }
        return shader;
    }

    private HarnessShaderUtil() { }
}
