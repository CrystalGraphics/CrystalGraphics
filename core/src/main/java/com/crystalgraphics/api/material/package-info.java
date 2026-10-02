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
 * CgMaterial material = CgMaterial.load("mymod:shaders/terrain.shader");   // once
 * material.applyProperties(b -> b.vec4("_Color", 1f, 0f, 0f, 1f));
 *
 * CgWorldRenderer.get().draw(mesh, material).at(x, y, z).submit();        // each frame it draws
 * }</pre>
 *
 * <h3>Caller-owned lifecycle</h3>
 * <p>{@code CgMaterial}, {@link com.crystalgraphics.gl.buffer.shader.CgUniformBuffer},
 * and {@link com.crystalgraphics.gl.buffer.shader.CgShaderBuffer} are
 * caller-owned objects. The caller must call {@code delete()} on each before
 * {@link com.crystalgraphics.gl.lifecycle.CgGraphicsLifecycle#destroyContext()}.</p>
 */
package com.crystalgraphics.api.material;
