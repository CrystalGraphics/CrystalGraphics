package com.crystalgraphics.render.scene.component;

import com.crystalgraphics.api.material.CgMaterialInterface;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.render.scene.batch.CgMeshBatches;
import com.crystalgraphics.render.scene.sort.CgSortLayer;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.render.view.CgView;
import com.crystalgraphics.shape.bounds.CgAABB;
import com.crystalgraphics.shape.bounds.CgAABBc;
import org.joml.Matrix4d;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;

import javax.annotation.Nullable;

/**
 * What every primitive in a {@code CgScene} has, Unreal's {@code UPrimitiveComponent}: where it is, whether it shows,
 * how it sorts, which passes it joins and the values its material reads. Made by the scene ({@code scene.mesh(...)}),
 * set up, then {@link #spawn spawned}; the same setters change it while it lives.
 *
 * <pre>{@code
 * CgMeshComponent rock = scene.mesh(rockMesh, stone)
 *         .at(x, y, z).rotation(facing).scale(2f)
 *         .customDepth(3)                       // in custom depth with stencil 3
 *         .spawn();
 * rock.at(x, y + 1, z);                         // moved: its GPU record is rewritten, nothing else
 * rock.customData(0, 1f, 0.5f, 0f, 1f);         // CG_OBJECT_CUSTOM0
 * rock.remove();
 * }</pre>
 *
 * <ul>
 *   <li>The frame thread's, as its scene is.</li>
 *   <li>Setting a value equal to the current one does nothing.</li>
 *   <li>A removed component throws on any setter; {@link #removed()} says so.</li>
 * </ul>
 *
 * @param <T> the component's own type, so setters chain
 */
public abstract class CgPrimitiveComponent<T extends CgPrimitiveComponent<T>> {

    final CgScenePrimitives scene;
    /** Its place in its scene's list while spawned, else -1. */
    int index = -1;
    private boolean spawned, removed;

    /** Absolute position; rotation and scale about it; a local transform after them. */
    double x, y, z;
    private final Quaternionf rotation = new Quaternionf();
    private float scaleX = 1f, scaleY = 1f, scaleZ = 1f;
    @Nullable
    private Matrix4fc local;

    boolean visible = true;
    float maxDistance = Float.POSITIVE_INFINITY;
    @Nullable
    private CgAABB statedBounds;

    CgSortLayer layer = CgSortLayer.DEFAULT;
    int sortPriority;
    int stencil = -1, stencilMask = 0xFF;
    private boolean halfResolution, afterDistortion, castsShadow = true;

    final float[] customData = new float[16];
    float emission = 1f, blockLight = 15f, skyLight = 15f;

    private final Matrix4d world = new Matrix4d(), localWorld = new Matrix4d();

    CgPrimitiveComponent(CgScenePrimitives scene) {
        this.scene = scene;
    }

    @SuppressWarnings("unchecked")
    private T self() {
        return (T) this;
    }

    /** Adds it to its scene: from now on it is drawn by every view that sees it. */
    public T spawn() {
        requireLive();
        if (spawned) throw new IllegalStateException(this + " is already spawned");
        spawned = true;
        scene.added(this);
        onSpawn();
        return self();
    }

    /** Takes it out of its scene; its GPU records are freed. */
    public void remove() {
        if (removed) return;
        removed = true;
        if (spawned) {
            onRemove();
            scene.removed(this);
        }
    }

    public boolean removed() {
        return removed;
    }

    public boolean spawned() {
        return spawned && !removed;
    }

    // -- Placement --------------------------------------------------------

    /** Its absolute position. */
    public T at(double x, double y, double z) {
        requireLive();
        if (x == this.x && y == this.y && z == this.z) return self();
        this.x = x;
        this.y = y;
        this.z = z;
        return moved();
    }

    public T rotation(Quaternionfc q) {
        requireLive();
        if (rotation.equals(q)) return self();
        rotation.set(q);
        return moved();
    }

    public T scale(float s) {
        return scale(s, s, s);
    }

    public T scale(float x, float y, float z) {
        requireLive();
        if (x == scaleX && y == scaleY && z == scaleZ) return self();
        scaleX = x;
        scaleY = y;
        scaleZ = z;
        return moved();
    }

    /** A local transform after its rotation and scale, about its position; null for none. Read when it moves. */
    public T transform(@Nullable Matrix4fc local) {
        requireLive();
        this.local = local;
        return moved();
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    // -- Visibility -------------------------------------------------------

    public T visible(boolean visible) {
        requireLive();
        this.visible = visible;
        return self();
    }

    /** Hidden from any view farther than {@code blocks} from it. */
    public T maxDistance(float blocks) {
        requireLive();
        this.maxDistance = blocks;
        return self();
    }

    /** Its bounds in object space (before its transform), in place of its mesh's; null for the mesh's. */
    public T bounds(@Nullable CgAABBc box) {
        requireLive();
        this.statedBounds = box == null ? null : new CgAABB(box);
        return self();
    }

    // -- Sorting ------------------------------------------------------------

    public T layer(CgSortLayer layer) {
        requireLive();
        this.layer = layer;
        return self();
    }

    /** Breaks ties within its layer: a greater priority draws later (Unreal's translucency sort priority). */
    public T sortPriority(int priority) {
        requireLive();
        this.sortPriority = priority;
        return self();
    }

    // -- Passes -------------------------------------------------------------

    /** Draws into custom depth, writing {@code stencil} (0 to 255). */
    public T customDepth(int stencil) {
        return customDepth(stencil, 0xFF);
    }

    public T customDepth(int stencil, int mask) {
        requireLive();
        if (stencil < 0 || stencil > 255) throw new IllegalArgumentException("stencil " + stencil + " is not 0 to 255");
        this.stencil = stencil;
        this.stencilMask = mask;
        return self();
    }

    /** Out of custom depth. */
    public T noCustomDepth() {
        requireLive();
        this.stencil = -1;
        return self();
    }

    public T halfResolution(boolean half) {
        requireLive();
        this.halfResolution = half;
        return self();
    }

    public T afterDistortion(boolean after) {
        requireLive();
        this.afterDistortion = after;
        return self();
    }

    public T castsShadow(boolean casts) {
        requireLive();
        this.castsShadow = casts;
        return self();
    }

    // -- Look -----------------------------------------------------------------

    /** {@code CG_OBJECT_CUSTOM0..3}: Unreal's custom primitive data, slot 0 to 3. */
    public T customData(int slot, float x, float y, float z, float w) {
        requireLive();
        if (slot < 0 || slot > 3) throw new IndexOutOfBoundsException("custom data " + slot + "; there are four");
        int c = slot * 4;
        if (customData[c] == x && customData[c + 1] == y && customData[c + 2] == z && customData[c + 3] == w) return self();
        customData[c] = x;
        customData[c + 1] = y;
        customData[c + 2] = z;
        customData[c + 3] = w;
        return looked();
    }

    /** What its glow is multiplied by ({@code CG_OBJECT_EMISSION}), 1 by default. */
    public T emission(float scale) {
        requireLive();
        if (scale == emission) return self();
        emission = scale;
        return looked();
    }

    /** The world's light at it: block and sky light, 0 to 15 each; full by default. */
    public T light(float block, float sky) {
        requireLive();
        if (block == blockLight && sky == skyLight) return self();
        blockLight = block;
        skyLight = sky;
        return looked();
    }

    public T fullBright() {
        return light(15f, 15f);
    }

    // -- For the scene and the kinds ----------------------------------------------

    /** Its object-to-world, absolute: position, rotation, scale, then its local transform. */
    final Matrix4d world() {
        world.translation(x, y, z).rotate(rotation).scale(scaleX, scaleY, scaleZ);
        if (local != null) world.mul(localWorld.set(local));
        return world;
    }

    /** Its bounds in object space, stated or else its kind's; false for none known. */
    final boolean localBounds(CgAABB out) {
        if (statedBounds != null) {
            out.set(statedBounds);
            return true;
        }
        return kindBounds(out);
    }

    /** A row of its settings in {@code out}, answering its index: what each kind adds per mesh it draws. */
    final int batch(CgMeshBatches out, CgMesh mesh, CgMaterialInterface material, int submesh) {
        return out.add(mesh, material, submesh, layer, passFlags(), sortPriority, stencil, stencilMask);
    }

    int passFlags() {
        return (halfResolution ? CgMeshBatches.HALF_RESOLUTION : 0) | (afterDistortion ? CgMeshBatches.AFTER_DISTORTION : 0)
                | (castsShadow ? CgMeshBatches.CASTS_SHADOW : 0) | (stencil >= 0 ? CgMeshBatches.CUSTOM_DEPTH : 0);
    }

    /** Whether {@code camera} (absolute) is near enough to see it. */
    final boolean inReach(CgCamera camera) {
        if (maxDistance == Float.POSITIVE_INFINITY) return true;
        double dx = camera.x() - x, dy = camera.y() - y, dz = camera.z() - z;
        return dx * dx + dy * dy + dz * dz <= (double) maxDistance * maxDistance;
    }

    /** Its kind's records written for the first time, when it is spawned. */
    abstract void onSpawn();

    /** Its kind's records freed. */
    abstract void onRemove();

    /** Its position changed while spawned: its records' transforms rewritten. */
    abstract void onMoved();

    /** Its values changed while spawned: its records' values rewritten. */
    abstract void onLooked();

    /** Its kind's bounds in object space (a mesh's); false for none. */
    abstract boolean kindBounds(CgAABB out);

    /** Adds what {@code view} draws of it this frame. */
    abstract void gather(CgView view, CgCamera camera, CgMeshBatches out, CgSceneGather gather);

    private T moved() {
        if (spawned()) onMoved();
        return self();
    }

    private T looked() {
        if (spawned()) onLooked();
        return self();
    }

    final void requireLive() {
        if (removed) throw new IllegalStateException(this + " is removed");
    }
}
