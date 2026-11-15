package com.crystalgraphics.demo.anim;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMeshShapes;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.scene.component.CgInstancedMeshComponent;
import com.crystalgraphics.shape.bounds.CgAABB;
import org.joml.Matrix4d;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.List;

/**
 * A station's blocks: boxes of ours, drawn as Minecraft draws a block's faces ({@code demo/matte.shader}), standing on
 * whatever ground the demo stands on. Each box's bounds are kept for what traces them, since in Minecraft they are
 * not the world's blocks.
 *
 * <pre>{@code
 * set.stairs(x, y, z, AnimDemoSet.Facing.NORTH, 0xFF8A7A66);   // a block's stair, rising toward -z
 * set.slab(x, y + 1, z, 0xFF9A9A9A);
 * }</pre>
 */
final class AnimDemoSet {

    /** Which way a stair rises: the side its high half stands on. */
    enum Facing { NORTH, SOUTH, EAST, WEST }

    private static final String MATERIAL = "crystalgraphics:shaders/demo/matte.shader";

    private final CgInstancedMeshComponent boxes;
    private final List<CgAABB> bounds = new ArrayList<>();
    private final Matrix4d place = new Matrix4d();
    private final Quaternionf none = new Quaternionf();

    AnimDemoSet() {
        boxes = CgScene.client().instanced(CgMeshShapes.cube(), CgMaterial.load(MATERIAL)).spawn();
    }

    void block(double x, double y, double z, int colour) {
        box(x, y, z, x + 1, y + 1, z + 1, colour);
    }

    /** A bottom slab. */
    void slab(double x, double y, double z, int colour) {
        box(x, y, z, x + 1, y + 0.5, z + 1, colour);
    }

    void stairs(double x, double y, double z, Facing rises, int colour) {
        box(x, y, z, x + 1, y + 0.5, z + 1, colour);
        switch (rises) {
            case NORTH -> box(x, y + 0.5, z, x + 1, y + 1, z + 0.5, colour);
            case SOUTH -> box(x, y + 0.5, z + 0.5, x + 1, y + 1, z + 1, colour);
            case EAST -> box(x + 0.5, y + 0.5, z, x + 1, y + 1, z + 1, colour);
            case WEST -> box(x, y + 0.5, z, x + 0.5, y + 1, z + 1, colour);
        }
    }

    /** A box from ({@code x0}, {@code y0}, {@code z0}) to ({@code x1}, {@code y1}, {@code z1}), {@code 0xAARRGGBB}. */
    void box(double x0, double y0, double z0, double x1, double y1, double z1, int colour) {
        int i = boxes.add((x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2, none, 1f);
        boxes.transform(i, place.translation((x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2).scale(x1 - x0, y1 - y0, z1 - z0));
        boxes.customData(i, 0, (colour >> 16 & 0xFF) / 255f, (colour >> 8 & 0xFF) / 255f, (colour & 0xFF) / 255f, 1f);
        bounds.add(new CgAABB(x0, y0, z0, x1, y1, z1));
    }

    /** Every box's bounds, in the world. */
    List<CgAABB> bounds() {
        return bounds;
    }

    void delete() {
        boxes.remove();
    }
}
