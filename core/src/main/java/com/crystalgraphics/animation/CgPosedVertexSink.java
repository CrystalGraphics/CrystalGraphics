package com.crystalgraphics.animation;

/**
 * Where {@link CgAnimInstance#cpuSkin} writes a posed submesh, a quad at a time: four calls to {@link #vertex} per quad,
 * in winding order. For a host whose own vertex path must see the vertices, as a shader pack sees an entity's.
 *
 * <pre>{@code
 * // A Minecraft entity renderer, 1.20.1: the pack lights, fogs and shadows what goes through its render type
 * VertexConsumer out = buffers.getBuffer(RenderType.entityCutoutNoCull(texture));
 * PoseStack.Pose pose = poseStack.last();
 * body.cpuSkin(0, false, (x, y, z, u, v, argb, nx, ny, nz) -> out.vertex(pose.pose(), x, y, z)
 *         .color(argb).uv(u, v).overlayCoords(overlay).uv2(light).normal(pose.normal(), nx, ny, nz).endVertex());
 * }</pre>
 *
 * <ul>
 *   <li>Positions are relative to where the instance stands ({@code x()}, {@code y()}, {@code z()}), turned and scaled by
 *       its placement: the host's pose at that point adds the rest.</li>
 *   <li>Normals are unit length, in the same frame.</li>
 * </ul>
 */
@FunctionalInterface
public interface CgPosedVertexSink {

    /** One corner: position, texture coordinate, the mesh's vertex colour as {@code 0xAARRGGBB}, and normal. */
    void vertex(float x, float y, float z, float u, float v, int argb, float nx, float ny, float nz);
}
