package com.crystalgraphics.render.scene.component;

import org.joml.Matrix4fc;

import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.scene.batch.CgMeshBatches;
import com.crystalgraphics.render.view.CgView;
import com.crystalgraphics.shape.bounds.CgAABB;

/**
 * A primitive Minecraft draws: placed, attached to a socket, shown, hidden, spawned and removed as any primitive, its
 * drawing the host's own renderer in Minecraft's world pass. Minecraft's item stacks, entities and blocks in a scene are
 * its kinds ({@code mc.render.world}); a mod with an object of its own extends it the same way.
 *
 * <pre>{@code
 * final class Banner extends CgHostDrawnComponent<Banner> {
 *     Banner(CgScene scene) { super(scene); }
 *     @Override protected void hostDraw(Matrix4fc model) { ... Minecraft's renderer under model ... }
 *     @Override protected void hostRelease() { ... drop what hostDraw made ... }
 * }
 * new Banner(CgScene.client()).at(x, y, z).spawn();
 * }</pre>
 *
 * <ul>
 *   <li>Only the client scene is drawn by Minecraft: made for another scene, it throws, until that scene has a host
 *       world of its own.</li>
 *   <li>No velocity of ours and no mesh batch: our passes see it only as Minecraft's depth, which our lights read, as
 *       they do Minecraft's own entities.</li>
 * </ul>
 */
public abstract class CgHostDrawnComponent<T extends CgHostDrawnComponent<T>> extends CgPrimitiveComponent<T> {

    protected CgHostDrawnComponent(CgScene scene) {
        super(scene.primitives());
        if (scene != CgScene.client() && scene.hostLevel() == null) {
            throw new IllegalArgumentException(getClass().getSimpleName() + ": Minecraft draws only into the client scene or"
                    + " a scene with a host level (scene.hostLevel), and " + scene + " has neither");
        }
    }

    /**
     * Draws it with Minecraft's renderer, render thread, from the host's world draw point: {@code model} is its
     * object-to-world with the camera at the origin, as Minecraft draws its own entities.
     */
    protected abstract void hostDraw(Matrix4fc model);

    /** Drops what {@link #hostDraw} made, as it is removed. */
    protected void hostRelease() {
    }

    @Override
    final void onSpawn() {
        scene.hostDrawn.add(this);
    }

    @Override
    final void onRemove() {
        scene.hostDrawn.remove(this);
        hostRelease();
    }

    @Override
    final void onMoved() {
    }

    @Override
    final void onLooked() {
    }

    @Override
    final boolean kindBounds(CgAABB out) {
        return hostBounds(out);
    }

    /**
     * Its bounds in object space, what the world draw point culls it by against the camera's frustum, as Minecraft culls
     * its own: false for none, never culled. Asked again only after it moves or {@link #hostBoundsChanged}.
     */
    protected boolean hostBounds(CgAABB out) {
        return false;
    }

    /**
     * Whether it draws {@code distanceSq} (blocks, squared) from the camera, as Minecraft decides for its own: an
     * entity's render distance, a block entity's view distance. True by default: no distance of its own.
     */
    protected boolean hostInRange(double distanceSq) {
        return true;
    }

    /** What {@link #hostBounds} answers changed: the game's object for it was made or replaced. */
    protected final void hostBoundsChanged() {
        boundsChanged();
    }

    /** Called by the world draw point, so a kind's range needs no package access. */
    public final boolean inRangeHosted(double distanceSq) {
        return hostInRange(distanceSq);
    }

    @Override
    final void gather(CgView view, CgCamera camera, CgMeshBatches out, CgSceneGather gather) {
    }

    /** Called by the world draw point, so a kind's draw needs no package access. */
    public final void drawHosted(Matrix4fc model) {
        hostDraw(model);
    }
}
