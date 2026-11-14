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
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3d;

import javax.annotation.Nullable;
import java.util.Objects;

/**
 * What every primitive in a {@code CgScene} has, Unreal's {@code UPrimitiveComponent}: where it is, what it rides,
 * whether it shows, how it sorts, which passes it joins and the values its material reads. Made by the scene
 * ({@code scene.mesh(...)}), set up, then {@link #spawn spawned}; the same setters change it while it lives.
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
 * <p>Attached, it rides another primitive, or a socket of a skeletal one: Unreal's {@code AttachToComponent} keeping
 * the relative transform. Its {@code at}, {@code rotation} and {@code scale} are then in the socket's space.</p>
 *
 * <pre>{@code
 * CgMeshComponent staff = scene.mesh(staffMesh, wood).attach(goku, "hand_r").spawn();   // follows the hand
 * staff.at(0, 0.1, 0);                                                                   // a tenth up the hand
 * staff.detach();                                                                        // stays where it was
 * }</pre>
 *
 * <ul>
 *   <li>The frame thread's, as its scene is. Attachments are resolved in {@code ATTACH}, parents first.</li>
 *   <li>Setting a value equal to the current one does nothing.</li>
 *   <li>A removed component throws on any setter; {@link #removed()} says so. Removing a parent detaches its children
 *       where they stand.</li>
 * </ul>
 *
 * @param <T> the component's own type, so setters chain
 */
public abstract class CgPrimitiveComponent<T extends CgPrimitiveComponent<T>> {

    final CgScenePrimitives scene;
    /** Its place in its scene's list while spawned, else -1. */
    int index = -1;
    private boolean spawned, removed;

    /** Its position, rotation and scale: absolute, or in its parent socket's space while attached; a local transform after. */
    private double x, y, z;
    private final Quaternionf rotation = new Quaternionf();
    private float scaleX = 1f, scaleY = 1f, scaleZ = 1f;
    @Nullable
    private Matrix4fc local;

    /** What it rides and where on it; null standing alone. */
    @Nullable
    private CgPrimitiveComponent<?> parent;
    @Nullable
    private String parentSocketName;
    private int parentSocket = NO_SOCKET;

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

    /** Its object-to-world, kept current by every move and attachment update: read from any thread, written on the frame's. */
    private final Matrix4d world = new Matrix4d();
    private final Matrix4d relativeScratch = new Matrix4d(), parentScratch = new Matrix4d(), localScratch = new Matrix4d();
    private final Matrix4f socketScratch = new Matrix4f();
    private final Vector3d boundsMin = new Vector3d(), boundsMax = new Vector3d();
    /** Its world box, made again at the first ask after it moves or its bounds change. */
    private final CgAABB worldBounds = new CgAABB();
    private byte worldBoundsState = STALE;
    private static final byte STALE = 0, KNOWN = 1, NONE = 2;

    /** A socket handle naming none: its origin. */
    static final int NO_SOCKET = -1;

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
        if (parent != null) resolveAttachment();
        scene.added(this);
        onSpawn();
        return self();
    }

    /** Takes it out of its scene; its GPU records are freed and what rides it is detached where it stands. */
    public void remove() {
        if (removed) return;
        if (parent != null) scene.detached(this);
        removed = true;
        if (spawned) {
            onRemove();
            scene.removed(this);
        }
        scene.parentRemoved(this);
    }

    public boolean removed() {
        return removed;
    }

    public boolean spawned() {
        return spawned && !removed;
    }

    // -- Placement --------------------------------------------------------

    /** Its position: absolute, or the offset in its parent socket's space while attached. */
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

    /** Its absolute position, attached or not. */
    public double x() {
        return world().m30();
    }

    public double y() {
        return world().m31();
    }

    public double z() {
        return world().m32();
    }

    // -- Attachment -------------------------------------------------------

    /**
     * Rides {@code parent}: at {@code socket} on a skeletal parent (its mesh's socket of that name, else its bone,
     * ignoring case), or at its origin for null. From now on its placement is relative to that point, identity until
     * set; it is placed each frame in {@code ATTACH}, after the parent's pose.
     *
     * <pre>{@code
     * hat.attach(goku, "head");     // a socket, else a bone
     * shell.attach(goku, null);     // where goku stands
     * }</pre>
     *
     * @throws IllegalArgumentException where {@code parent} is of another scene, rides this one, or has no such socket
     */
    public T attach(CgPrimitiveComponent<?> parent, @Nullable String socket) {
        requireLive();
        Objects.requireNonNull(parent, "parent");
        if (parent.scene != scene) throw new IllegalArgumentException(this + " and " + parent + " are in different scenes");
        if (parent.removed) throw new IllegalArgumentException(parent + " is removed");
        for (CgPrimitiveComponent<?> p = parent; p != null; p = p.parent) {
            if (p == this) throw new IllegalArgumentException(this + " cannot attach to " + parent + ": it would ride itself");
        }
        int handle = NO_SOCKET;
        if (socket != null) {
            handle = parent.socketHandle(socket);
            if (handle == NO_SOCKET) throw new IllegalArgumentException(parent + " has no socket or bone '" + socket + "'");
        }
        if (this.parent == null) {
            x = y = z = 0;
            rotation.identity();
            scaleX = scaleY = scaleZ = 1f;
            local = null;
            scene.attached(this);
        }
        this.parent = parent;
        this.parentSocket = handle;
        this.parentSocketName = socket;
        resolveAttachment();
        return self();
    }

    /** Stops riding its parent, standing where it last stood: Unreal's {@code KeepWorldTransform} detach. */
    public T detach() {
        requireLive();
        if (parent == null) return self();
        Matrix4d w = new Matrix4d(world);
        parent = null;
        parentSocket = NO_SOCKET;
        parentSocketName = null;
        scene.detached(this);
        x = w.m30();
        y = w.m31();
        z = w.m32();
        Vector3d s = w.getScale(new Vector3d());
        scaleX = (float) s.x;
        scaleY = (float) s.y;
        scaleZ = (float) s.z;
        w.getNormalizedRotation(rotation);
        local = null;
        return moved();
    }

    /** What it rides, or null. */
    @Nullable
    public CgPrimitiveComponent<?> attachParent() {
        return parent;
    }

    /** The socket it rides on its parent, or null for the parent's origin or none. */
    @Nullable
    public String attachSocket() {
        return parentSocketName;
    }

    /** How many parents above it: the order {@code ATTACH} places in. */
    final int attachDepth() {
        int d = 0;
        for (CgPrimitiveComponent<?> p = parent; p != null; p = p.parent) d++;
        return d;
    }

    /**
     * Places it from its parent's socket as this frame stands: the scene's, in {@code ATTACH}. Unreal's
     * {@code CalcNewComponentToWorld_GeneralCase}.
     */
    final void resolveAttachment() {
        CgPrimitiveComponent<?> p = parent;
        if (p == null) return;
        p.socketWorld(parentSocket, parentScratch, socketScratch).mul(relative(relativeScratch));
        if (parentScratch.equals(world)) return;
        world.set(parentScratch);
        worldBoundsState = STALE;
        if (spawned()) onMoved();
    }

    /** Its parent was removed: it stands where it is. */
    final void parentGone() {
        if (!removed) detach();
    }

    /**
     * The handle of its socket {@code name}, or {@link #NO_SOCKET} for none: a primitive has none of its own; a skeletal
     * mesh answers its sockets and bones.
     */
    int socketHandle(String name) {
        return NO_SOCKET;
    }

    /** Socket {@code socket}'s object-to-world into {@code out}, {@code scratch} for the socket's own; its own for {@link #NO_SOCKET}. */
    Matrix4d socketWorld(int socket, Matrix4d out, Matrix4f scratch) {
        return out.set(world);
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

    /** Its bounds in object space (before its transform), in place of its kind's; null for its kind's. */
    public T bounds(@Nullable CgAABBc box) {
        requireLive();
        this.statedBounds = box == null ? null : new CgAABB(box);
        worldBoundsState = STALE;
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

    /** Its custom depth stencil value, or -1 where it is not drawn there. */
    public int customDepthStencil() {
        return stencil;
    }

    public int customDepthMask() {
        return stencilMask;
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

    /** Custom data slot {@code slot}'s component {@code c} (0 to 3). */
    public float customData(int slot, int c) {
        return customData[slot * 4 + c];
    }

    /** What its glow is multiplied by ({@code CG_OBJECT_EMISSION}), 1 by default. */
    public T emission(float scale) {
        requireLive();
        if (scale == emission) return self();
        emission = scale;
        return looked();
    }

    public float emission() {
        return emission;
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

    public float blockLight() {
        return blockLight;
    }

    public float skyLight() {
        return skyLight;
    }

    // -- For the scene and the kinds ----------------------------------------------

    /** Its object-to-world, absolute: under its parent's socket while attached, else position, rotation, scale, local. */
    final Matrix4d world() {
        return world;
    }

    /** Its own transform: position, rotation, scale, then its local transform. */
    private Matrix4d relative(Matrix4d out) {
        out.translation(x, y, z).rotate(rotation).scale(scaleX, scaleY, scaleZ);
        if (local != null) out.mul(localScratch.set(local));
        return out;
    }

    /** Its turn and scale about its position, as floats: {@link #world()} less its translation. */
    public final Matrix4f basis(Matrix4f out) {
        Matrix4d w = world();
        return out.set((float) w.m00(), (float) w.m01(), (float) w.m02(), 0f,
                (float) w.m10(), (float) w.m11(), (float) w.m12(), 0f,
                (float) w.m20(), (float) w.m21(), (float) w.m22(), 0f,
                0f, 0f, 0f, 1f);
    }

    /** Its bounds in object space, stated or else its kind's; false for none known. */
    final boolean localBounds(CgAABB out) {
        if (statedBounds != null) {
            out.set(statedBounds);
            return true;
        }
        return kindBounds(out);
    }

    /**
     * Its bounds in the world, absolute: its object-space bounds under its transform, kept until it moves or they change
     * (Unreal's scene primitive bounds); null for none known.
     */
    @Nullable
    CgAABBc worldBounds() {
        if (worldBoundsState == STALE) {
            worldBoundsState = NONE;
            if (localBounds(worldBounds)) {
                world.transformAab(worldBounds.minX(), worldBounds.minY(), worldBounds.minZ(), worldBounds.maxX(),
                        worldBounds.maxY(), worldBounds.maxZ(), boundsMin, boundsMax);
                worldBounds.set(boundsMin.x, boundsMin.y, boundsMin.z, boundsMax.x, boundsMax.y, boundsMax.z);
                worldBoundsState = KNOWN;
            }
        }
        return worldBoundsState == KNOWN ? worldBounds : null;
    }

    /** Its kind's bounds changed (a skeletal mesh's pose): its world box is made again at the next ask. */
    final void boundsChanged() {
        worldBoundsState = STALE;
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
        Matrix4d w = world();
        double dx = camera.x() - w.m30(), dy = camera.y() - w.m31(), dz = camera.z() - w.m32();
        return dx * dx + dy * dy + dz * dz <= (double) maxDistance * maxDistance;
    }

    /** Whether it is drawn: visible, and within reach of the point {@code (cx, cy, cz)}. */
    public final boolean drawnFrom(double cx, double cy, double cz) {
        if (!visible) return false;
        if (maxDistance == Float.POSITIVE_INFINITY) return true;
        Matrix4d w = world();
        double dx = cx - w.m30(), dy = cy - w.m31(), dz = cz - w.m32();
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
        if (parent != null) {
            resolveAttachment();
            return self();
        }
        relative(world);
        worldBoundsState = STALE;
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
