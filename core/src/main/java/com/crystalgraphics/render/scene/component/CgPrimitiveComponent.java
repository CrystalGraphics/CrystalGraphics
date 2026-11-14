package com.crystalgraphics.render.scene.component;

import com.crystalgraphics.api.material.CgMaterialInterface;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.gpu.resource.CgFrameRing;
import com.crystalgraphics.render.meshpass.CgCustomDepth;
import com.crystalgraphics.render.meshpass.CgPrimitiveFlag;
import com.crystalgraphics.render.meshpass.CgPrimitiveInfo;
import com.crystalgraphics.render.post.volume.CgPostSettings;
import com.crystalgraphics.trace.CgGpuTrace;
import com.crystalgraphics.render.scene.batch.CgCachedCommands;
import com.crystalgraphics.render.scene.batch.CgMeshBatches;
import com.crystalgraphics.render.scene.sort.CgSortLayer;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.render.view.CgView;
import com.crystalgraphics.shape.bounds.CgAABB;
import com.crystalgraphics.shape.bounds.CgAABBc;
import org.joml.Matrix4d;
import org.joml.Matrix4dc;
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
 *         .customDepth(OUTLINE)                 // in custom depth with a stencil value (CgCustomDepth.stencil)
 *         .flag(HEAT_SOURCE)                    // a mark a mesh pass of a consumer's reads
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
public abstract class CgPrimitiveComponent<T extends CgPrimitiveComponent<T>> implements CgPrimitiveInfo {

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
    @Nullable
    private CgCustomDepth customDepth;
    private int primitiveFlags;
    private boolean halfResolution, afterDistortion, castsShadow = true, writesVelocity;
    @Nullable
    private CgPostSettings grade;
    private int subject, debugLabel = -1;
    /** Bumped by every setting a mesh pass processor reads: its kept commands are built again. */
    private int passSettings;
    /** The host frame it last moved in: it has velocity in that frame. */
    private long movedFrame = Long.MIN_VALUE;
    /** Its mesh pass commands, kept across frames; a dynamic primitive's rows keep none. */
    final CgCachedCommands commands = new CgCachedCommands();

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
        commands.clear();
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
     * ignoring case), or at its origin for null. From now on its placement is relative to that point: what it was
     * given before attaching is kept as that offset, as Unreal's {@code KeepRelativeTransform}. It is placed each frame
     * in {@code ATTACH}, after the parent's pose.
     *
     * <pre>{@code
     * hat.attach(goku, "head");                              // a socket, else a bone
     * shell.attach(goku, null);                              // where goku stands
     * scene.mesh(halo, gold).at(0, 2, 0).attach(goku, null); // two blocks above him: placed before or after, the same
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
        if (this.parent == null) scene.attached(this);
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
        movedFrame = CgFrameRing.frame();
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

    /**
     * Draws into custom depth with {@code mark}'s stencil value: an allocated one ({@code CgCustomDepth.stencil(id)}),
     * a raw one, or {@link CgCustomDepth#DEPTH} for depth alone; null takes it out.
     */
    public T customDepth(@Nullable CgCustomDepth mark) {
        requireLive();
        if (mark == customDepth) return self();
        customDepth = mark;
        return passesChanged();
    }

    /** Draws into custom depth writing stencil value {@code stencil} (0 to 255): a kit's raw value. */
    public T customDepth(int stencil) {
        return customDepth(CgCustomDepth.stencil(stencil));
    }

    /** As {@link #customDepth(int)}, writing only the bits of {@code mask}. */
    public T customDepth(int stencil, int mask) {
        return customDepth(CgCustomDepth.stencil(stencil, mask));
    }

    /** Out of custom depth. */
    public T noCustomDepth() {
        return customDepth((CgCustomDepth) null);
    }

    /** The custom depth it is drawn into, or null for none. */
    @Override
    @Nullable
    public CgCustomDepth customDepth() {
        return customDepth;
    }

    /** Carries {@code flag}, which a mesh pass processor reads ({@code primitive.has(flag)}). */
    public T flag(CgPrimitiveFlag flag) {
        return flag(flag, true);
    }

    public T flag(CgPrimitiveFlag flag, boolean on) {
        requireLive();
        int next = on ? primitiveFlags | flag.bit() : primitiveFlags & ~flag.bit();
        if (next == primitiveFlags) return self();
        primitiveFlags = next;
        return passesChanged();
    }

    @Override
    public boolean has(CgPrimitiveFlag flag) {
        return (primitiveFlags & flag.bit()) != 0;
    }

    /**
     * Draws in the velocity pass every frame, moved or not: what changes shape on its own without moving (a material
     * moving its vertices). Moved primitives and skinned meshes draw there anyway.
     */
    public T writesVelocity(boolean writes) {
        requireLive();
        writesVelocity = writes;
        return self();
    }

    @Override
    public boolean hasVelocity() {
        return writesVelocity || movedFrame == CgFrameRing.frame();
    }

    /** A setting a processor reads changed: its kept commands are built again at the next view that draws it. */
    private T passesChanged() {
        passSettings++;
        return self();
    }

    /** What its kept commands were built under: changes with every setting a processor reads. */
    public final int passSettings() {
        return passSettings;
    }

    /**
     * Draws a translucent primitive at half the target's size, added over the scene depth-aware; a premultiplied one
     * (smoke, a volume) is blended with its last frame (the {@code world.halfResolution} flag turns it off per view).
     */
    public T halfResolution(boolean half) {
        requireLive();
        if (half == halfResolution) return self();
        this.halfResolution = half;
        return passesChanged();
    }

    /** Draws a translucent primitive after the distortion pass, unbent by the hazes behind it. */
    public T afterDistortion(boolean after) {
        requireLive();
        if (after == afterDistortion) return self();
        this.afterDistortion = after;
        return passesChanged();
    }

    /**
     * Grades what it draws by its own post look, composited where it shows (an effect's colour grade, its own bloom
     * threshold): Unreal's post volume per primitive, as the VFX engine's effects grade themselves. Null for the
     * scene's look.
     */
    public T grade(@Nullable CgPostSettings look) {
        requireLive();
        if (look == grade) return self();
        grade = look;
        return passesChanged();
    }

    @Nullable
    public CgPostSettings grade() {
        return grade;
    }

    @Override
    public boolean graded() {
        return grade != null;
    }

    /**
     * What an impact frame whose look names {@code key} draws as its subject: this primitive's glow, drawn apart before
     * anything else of ours, so nothing else covers it. 0 for none.
     */
    public T subject(int key) {
        requireLive();
        subject = key;
        return self();
    }

    public int subject() {
        return subject;
    }

    /** Groups its draws under {@code label} in a GPU capture (RenderDoc, Nsight), while GPU groups are traced. */
    public T debugLabel(@Nullable String label) {
        requireLive();
        debugLabel = label == null ? -1 : CgGpuTrace.name(label);
        return self();
    }

    /** Its debug label's trace name, -1 for none. */
    public int debugLabelName() {
        return debugLabel;
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
    /** Its object-to-world now: absolute, in doubles, its parent's socket applied where attached. Read, never keep. */
    public final Matrix4dc worldTransform() {
        return world();
    }

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
     * (Unreal's scene primitive bounds); null for none known. What culls it, and what its screen rect is made from.
     */
    @Nullable
    public CgAABBc worldBounds() {
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
        int b = out.add(mesh, material, submesh, layer, passFlags(), sortPriority, this, keepsCommands() ? commands : null);
        out.grade(b, grade);
        out.subject(b, subject);
        return b;
    }

    /** Whether its rows' commands are kept across frames: a persistent primitive's are, a dynamic one's are not. */
    boolean keepsCommands() {
        return true;
    }

    int passFlags() {
        return (halfResolution ? CgMeshBatches.HALF_RESOLUTION : 0) | (afterDistortion ? CgMeshBatches.AFTER_DISTORTION : 0)
                | (castsShadow ? CgMeshBatches.CASTS_SHADOW : 0);
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
        movedFrame = CgFrameRing.frame();
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
