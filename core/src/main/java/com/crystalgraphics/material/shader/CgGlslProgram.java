package com.crystalgraphics.material.shader;

import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.github.bsideup.jabel.Desugar;

/**
 * A pass's program as GLSL, before {@code #include}s are resolved: what {@link CgGlslWriter} writes and a shader
 * compiles.
 *
 * @param vertex       the vertex stage, from its {@code #version}
 * @param fragment     the fragment stage
 * @param vertexFormat what its vertex stage pulls from the mesh store: {@link CgVertexFormat#NONE} for a program
 *                     placing its vertices itself
 */
@Desugar
public record CgGlslProgram(String vertex, String fragment, CgVertexFormat vertexFormat) {
}
