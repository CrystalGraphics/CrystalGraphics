/**
 * GPU buffer field type descriptors — the buffer-domain complement to {@code api/vertex/}.
 *
 * <p>This package provides typed descriptors for UBO and SSBO field layouts, mirroring
 * how {@code api/vertex/} provides typed descriptors for vertex attribute layouts.
 * The key types are:</p>
 * <ul>
 *   <li>{@link io.github.somehussar.crystalgraphics.api.buffer.CgGpuType} — enum of GLSL compound types
 *       (FLOAT, VEC2, VEC3, VEC4, MAT3, MAT4, INT, UINT, BOOL) with std140/std430 size and alignment data</li>
 *   <li>{@link io.github.somehussar.crystalgraphics.api.buffer.CgBufferField} — immutable value object
 *       describing a single named field within a buffer format (name, type, byte offset)</li>
 *   <li>{@link io.github.somehussar.crystalgraphics.api.buffer.CgBufferFormat} — immutable format descriptor
 *       built via a fluent builder; auto-computes field offsets with correct std140/std430 alignment padding</li>
 * </ul>
 *
 * <p>These types have no GL dependencies. They are pure data structures used by
 * {@link io.github.somehussar.crystalgraphics.gl.buffer.staging.CgBufferWriter} to drive
 * named field writes and by
 * {@link io.github.somehussar.crystalgraphics.gl.buffer.shader.CgShaderBuffer} to carry
 * format metadata through the buffer lifecycle.</p>
 */
package io.github.somehussar.crystalgraphics.api.buffer;
