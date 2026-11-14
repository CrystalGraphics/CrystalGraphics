package com.crystalgraphics.render.scene.component;

import com.crystalgraphics.api.material.CgMaterialInterface;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshShapes;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.render.CgFrameClock;
import com.crystalgraphics.render.scene.batch.CgMeshBatches;
import com.crystalgraphics.render.scene.gpu.CgGpuScene;
import com.crystalgraphics.render.view.CgView;
import com.crystalgraphics.shape.bounds.CgAABB;
import org.joml.Matrix4d;

import java.util.Objects;

/**
 * A material projected onto whatever is inside a box: Unreal's {@code UDecalComponent}. Its material is a
 * {@code #domain decal} one; the box is its size either side of its origin, the decal looking down its local X (turn it
 * to face what it lands on). Drawn by the decal pass after the opaque scene, in its sort order, faded in and out over
 * its life and out as it shrinks on screen.
 *
 * <pre>{@code
 * CgDecalComponent crack = scene.decal(crackMaterial)
 *         .at(x, y, z).rotation(new Quaternionf().rotateZ((float) Math.PI / 2))   // X down: onto the ground
 *         .size(0.5f, 2f, 2f)                                // Unreal's DecalSize: half the box, X its depth
 *         .sortOrder(1)                                      // over decals of a lower order
 *         .fadeIn(0f, 0.2f).fadeOut(3f, 1.5f, true)          // gone 4.5 s after it spawned
 *         .spawn();
 * }</pre>
 *
 * <ul>
 *   <li>The box is the engine's cube scaled by twice its size, under the component's transform: a scale stretches it
 *       too.</li>
 *   <li>Fades are seconds from its spawn, on the frame clock. With no fade out it stays until removed; with
 *       {@code removeAfter}, it removes itself once faded.</li>
 *   <li>Its lifetime and screen-size fade reach its material as {@code CG_OBJECT_SPARE.xy}; a decal material reads them
 *       through its template, never itself.</li>
 * </ul>
 */
public final class CgDecalComponent extends CgPrimitiveComponent<CgDecalComponent> {

    private CgMaterialInterface material;
    private float sizeX = 0.5f, sizeY = 1f, sizeZ = 1f;
    private float fadeInDelay, fadeInDuration, fadeOutDelay, fadeOutDuration, fadeScreenSize;
    private boolean removeAfterFade;
    private double spawnedAt;
    /** The lifetime fade last written, 0 whole to 1 gone. */
    private float writtenFade = -1f;
    private int slot = -1;
    private final Matrix4d boxed = new Matrix4d();

    CgDecalComponent(CgScenePrimitives scene, CgMaterialInterface material) {
        super(scene);
        this.material = Objects.requireNonNull(material, "material");
    }

    /** Its material: a {@code #domain decal} one. */
    public CgDecalComponent material(CgMaterialInterface material) {
        requireLive();
        this.material = Objects.requireNonNull(material, "material");
        return this;
    }

    public CgMaterialInterface material() {
        return material;
    }

    /** Half its box along each local axis, X its depth: Unreal's {@code DecalSize}. */
    public CgDecalComponent size(float x, float y, float z) {
        requireLive();
        sizeX = x;
        sizeY = y;
        sizeZ = z;
        boundsChanged();
        if (spawned()) onMoved();
        return this;
    }

    /** Where it draws among decals: a higher order over a lower one, Unreal's {@code SortOrder}. */
    public CgDecalComponent sortOrder(int order) {
        return sortPriority(Math.max(-128, Math.min(127, order)));
    }

    /** Rises from nothing over {@code duration} seconds, {@code delay} after it spawns. */
    public CgDecalComponent fadeIn(float delay, float duration) {
        requireLive();
        fadeInDelay = delay;
        fadeInDuration = duration;
        return this;
    }

    /** Fades out over {@code duration} seconds, {@code delay} after it spawns; then removed where {@code removeAfter}. */
    public CgDecalComponent fadeOut(float delay, float duration, boolean removeAfter) {
        requireLive();
        fadeOutDelay = delay;
        fadeOutDuration = duration;
        removeAfterFade = removeAfter;
        return this;
    }

    /** Fades out as it shrinks below this share of the screen's height, Unreal's {@code FadeScreenSize}; 0 for never. */
    public CgDecalComponent fadeScreenSize(float share) {
        requireLive();
        fadeScreenSize = share;
        if (spawned()) writeFades();
        return this;
    }

    /** Its lifetime's opacity now, 0 to 1: its fade in and fade out. */
    public float lifetimeOpacity() {
        double age = CgFrameClock.seconds() - spawnedAt;
        float in = fadeInDuration <= 0f ? (age >= fadeInDelay ? 1f : 0f)
                : (float) Math.max(0.0, Math.min(1.0, (age - fadeInDelay) / fadeInDuration));
        float out = fadeOutDuration <= 0f ? 1f
                : (float) Math.max(0.0, Math.min(1.0, 1.0 - (age - fadeOutDelay) / fadeOutDuration));
        return in * out;
    }

    /** The scene's, each frame: its fade written while it changes, and its removal once faded out where asked. */
    void tick() {
        writeFades();
        if (removeAfterFade && fadeOutDuration > 0f && CgFrameClock.seconds() - spawnedAt >= fadeOutDelay + fadeOutDuration) {
            remove();
        }
    }

    private void writeFades() {
        float fade = 1f - lifetimeOpacity();
        if (fade == writtenFade) return;
        writtenFade = fade;
        if (slot >= 0) scene.gpu().spare(slot, fade, fadeScreenSize, 0f, 0f);
    }

    /** Its object-to-world with its box: the cube's half of 0.5 made its size. */
    private Matrix4d boxed() {
        return boxed.set(world()).scale(sizeX * 2.0, sizeY * 2.0, sizeZ * 2.0);
    }

    @Override
    void onSpawn() {
        spawnedAt = CgFrameClock.seconds();
        slot = scene.gpu().add();
        scene.gpu().transform(slot, boxed());
        scene.writeValues(this, slot);
        writtenFade = -1f;
        writeFades();
    }

    @Override
    void onRemove() {
        scene.gpu().remove(slot);
        slot = -1;
    }

    @Override
    void onMoved() {
        scene.gpu().transform(slot, boxed());
    }

    @Override
    void onLooked() {
        scene.writeValues(this, slot);
    }

    @Override
    boolean kindBounds(CgAABB out) {
        out.set(-sizeX, -sizeY, -sizeZ, sizeX, sizeY, sizeZ);
        return true;
    }

    @Override
    void gather(CgView view, CgCamera camera, CgMeshBatches out, CgSceneGather gather) {
        CgMesh cube = CgMeshShapes.cube();
        int row = batch(out, cube, material, -1);
        out.object(row, CgGpuScene.id(slot));
    }

    /** Its record's object id in its scene's GPU scene, or -1 before it is spawned. */
    public int objectId() {
        return slot < 0 ? -1 : CgGpuScene.id(slot);
    }

    @Override
    public String toString() {
        return "CgDecalComponent(" + material + ")";
    }
}
