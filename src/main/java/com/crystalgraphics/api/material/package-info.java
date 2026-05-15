/**
 * Public CrystalShader material API.
 *
 * <p>{@link com.crystalgraphics.api.material.CgMaterial} is the main entry point:
 * it loads a {@code .shader} file, compiles it, holds property values, and provides
 * {@code bind()} / {@code unbind()} for draw-time use.</p>
 *
 * <p>{@link com.crystalgraphics.api.vertex.CgVertexFormat#SPATIAL} is the
 * predefined {@link com.crystalgraphics.api.vertex.CgVertexFormat} constant
 * compatible with the material pipeline's {@code cg_env.glsl} attribute contract.</p>
 *
 * <h3>Typical usage</h3>
 * <pre>{@code
 * // Load once (blocking compile):
 * CgMaterial material = CgMaterial.load("mymod:shaders/terrain.shader");
 * material.setVec4("_Color", 1f, 0f, 0f, 1f);
 *
 * // Per-frame non-instanced:
 * CgBufferWriter w = frameUbo.writer();
 * w.reset();
 * w.mat4(viewMatrix).mat4(projMatrix);
 * frameUbo.upload();
 *
 * objectBuffer.writeSingle(modelMatrix);
 * material.bind(frameUbo, objectBuffer, 1);
 * mesh.drawDirect();
 * material.unbind();
 *
 * // Per-frame instanced:
 * objectBuffer.beginWrite(N);
 * for (int i = 0; i < N; i++) objectBuffer.putMatrix(matrices[i]);
 * objectBuffer.endWrite();
 *
 * material.bind(frameUbo, objectBuffer, N);
 * mesh.drawInstanced(N);
 * material.unbind();
 * }</pre>
 *
 * <h3>Caller-owned lifecycle</h3>
 * <p>{@code CgMaterial}, {@link com.crystalgraphics.gl.buffer.shader.CgUniformBuffer},
 * and {@link com.crystalgraphics.gl.buffer.shader.CgShaderBuffer} are
 * caller-owned objects. The caller must call {@code delete()} on each before
 * {@link com.crystalgraphics.gl.lifecycle.CgGraphicsLifecycle#destroyContext()}.</p>
 */
package com.crystalgraphics.api.material;
