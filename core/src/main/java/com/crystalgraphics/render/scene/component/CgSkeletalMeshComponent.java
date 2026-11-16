package com.crystalgraphics.render.scene.component;

import com.crystalgraphics.animation.CgAnimCurveTarget;
import com.crystalgraphics.animation.CgAnimInstance;
import com.crystalgraphics.animation.CgBonePalettes;
import com.crystalgraphics.animation.CgCpuSkin;
import com.crystalgraphics.animation.CgPosedVertexSink;
import com.crystalgraphics.animation.CgSkinBounds;

import com.crystalgraphics.api.material.CgMaterialInstance;
import com.crystalgraphics.api.material.CgMaterialInterface;
import com.crystalgraphics.api.mesh.CgSkeletalMesh;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.gpu.resource.CgFrameRing;
import com.crystalgraphics.material.CgMaterialProperty;
import com.crystalgraphics.render.scene.batch.CgMeshBatches;
import com.crystalgraphics.render.scene.gpu.CgGpuScene;
import com.crystalgraphics.render.view.CgView;
import com.crystalgraphics.render.precache.CgPipelinePrecache;
import com.crystalgraphics.shape.bounds.CgAABB;
import com.crystalgraphics.world.CgAttachment;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntFunction;

/**
 * A skinned mesh posed in a scene, Unreal's {@code USkeletalMeshComponent}: where it stands, what it rides, its slots'
 * materials and their parameters, its overlay, which bones show, and the {@link CgAnimInstance} that poses it. Spawned,
 * it is posed and drawn every frame: nothing to submit.
 *
 * <pre>{@code
 * CgSkeletalMeshComponent goku = scene.skeletal(gokuMesh)
 *         .materials(slot -> kit.material(slot))               // a material a slot; or .material(slot, m)
 *         .at(x, y, z).rotation(facing)
 *         .customDepth(1)                                      // the outline post reads stencil 1
 *         .overlay(rimMaterial)                                // drawn again over every slot
 *         .spawn();
 *
 * goku.anim().play(CgAnimSection.of(idle).loop().build());    // what plays: its animation instance
 * goku.scalar("OutlineColorType", 1f);                         // on every slot declaring it
 * goku.dynamicMaterial(4).set1f("_Glow", 2f);                  // slot 4's own instance
 * goku.hideBone("weapon", CgBoneHide.BRANCH);
 * goku.at(x, y, z);                                            // moved
 * goku.remove();
 * }</pre>
 *
 * <p>Sockets: what rides one follows it the frame it moves.</p>
 *
 * <pre>{@code
 * CgMeshComponent staff = scene.mesh(staffMesh, wood).attach(goku, "hand_r").spawn();
 * owner.play(new Flame(look, x, y, z).follow(goku.attachment("hand_r")));     // an effect on the hand
 * }</pre>
 *
 * <p>Sharing a pose: a second mesh on the same skeleton drawn in the leader's pose, bone by name (Unreal's leader
 * pose); it stands where the leader does unless attached elsewhere.</p>
 *
 * <pre>{@code
 * CgSkeletalMeshComponent armour = scene.skeletal(armourMesh).materials(...).followPose(goku).spawn();
 * }</pre>
 *
 * <ul>
 *   <li>Every slot needs a material before it draws: a slot with none is refused at its draw, naming the slot.</li>
 *   <li>A parameter set by {@link #scalar} or {@link #vector} makes a slot's own instance the first time; a slot set
 *       to a {@link CgMaterialInstance} is set as it is (Unreal's {@code SetScalarParameterValueOnMaterials}).</li>
 *   <li>Its bounds come from its pose (each bone's reach of skin): never padded by hand.</li>
 *   <li>The frame thread's; posed in {@code POSE}, placed and its palette written in {@code ATTACH}.</li>
 * </ul>
 */
public final class CgSkeletalMeshComponent extends CgPrimitiveComponent<CgSkeletalMeshComponent> implements CgAnimCurveTarget {

    private final CgSkeletalMesh mesh;
    private final CgAnimInstance own;
    /** The component whose pose it draws, and each of its bones' in that pose; null drawing its own. */
    @Nullable
    private CgSkeletalMeshComponent leader;
    @Nullable
    private int[] leaderBones;
    @Nullable
    private Matrix4f[] inverseBinds;

    /** What it draws: its pose's skinning, the hidden bones collapsed; the owner's view's where a bone is hidden from it. */
    private final Matrix4f[] skinning;
    /** Last frame's {@link #skinning}, for the velocity pass's previous position; held once a palette was written. */
    /** Last frame's skinning, and the latest of this frame's, which becomes it at the next frame's first write. */
    private final Matrix4f[] previousSkinning, latestSkinning;
    private boolean previousHeld, latestHeld;
    @Nullable
    private Matrix4f[] ownerSkinning;
    private final CgBoneHide[] hidden, hiddenFromOwner;
    private final boolean[] collapsed, collapsedForOwner, branchHidden;
    private boolean visibilityDirty, anyHidden, anyHiddenFromOwner;
    private int palette = -1, ownerPalette = -1;
    private long paletteFrame = -1;
    private final CgAABB posedBounds = new CgAABB();
    private boolean bounded;

    /** Each slot's material as set, and its dynamic instance where one was asked for or set; made: ours to delete. */
    private final CgMaterialInterface[] materials;
    private final CgMaterialInstance[] dynamic;
    private final boolean[] made;
    /** Per parameter name: how many numbers it is, and each slot's material's own value (null where not declared). */
    private final Map<String, Declared> declared = new HashMap<>();

    private record Declared(int components, float[][] bySlot) {
    }

    /** Unreal's {@code OverlayMaterial}, {@code MaterialSlotsOverlayMaterial} and {@code OverlayMaterialMaxDrawDistance}. */
    @Nullable
    private CgMaterialInterface overlay;
    private final CgMaterialInterface[] slotOverlays;
    private float overlayMaxDistance;

    private int slot = -1;
    @Nullable
    private CgCpuSkin cpuSkin;
    private final Matrix4f placement = new Matrix4f();

    CgSkeletalMeshComponent(CgScenePrimitives scene, CgSkeletalMesh mesh) {
        super(scene);
        this.mesh = mesh;
        this.own = new CgAnimInstance(mesh).curveTarget(this).placedBy(out -> out.set(world()));
        int slots = mesh.mesh().submeshCount();
        this.materials = new CgMaterialInterface[slots];
        this.dynamic = new CgMaterialInstance[slots];
        this.made = new boolean[slots];
        this.slotOverlays = new CgMaterialInterface[slots];
        int bones = mesh.skeleton().boneCount();
        this.skinning = new Matrix4f[bones];
        this.previousSkinning = new Matrix4f[bones];
        this.latestSkinning = new Matrix4f[bones];
        for (int b = 0; b < bones; b++) {
            skinning[b] = new Matrix4f();
            previousSkinning[b] = new Matrix4f();
            latestSkinning[b] = new Matrix4f();
        }
        this.hidden = new CgBoneHide[bones];
        this.hiddenFromOwner = new CgBoneHide[bones];
        Arrays.fill(hidden, CgBoneHide.NONE);
        Arrays.fill(hiddenFromOwner, CgBoneHide.NONE);
        this.collapsed = new boolean[bones];
        this.collapsedForOwner = new boolean[bones];
        this.branchHidden = new boolean[bones];
    }

    public CgSkeletalMesh mesh() {
        return mesh;
    }

    /** What poses it: its own animation instance, or its leader's while it {@linkplain #followPose follows} one. */
    public CgAnimInstance anim() {
        return leader != null ? leader.anim() : own;
    }

    // -- Materials -------------------------------------------------------------

    /** Draws submesh {@code slot} with {@code material}: Unreal's {@code SetMaterial}. */
    public CgSkeletalMeshComponent material(int slot, CgMaterialInterface material) {
        requireLive();
        Objects.requireNonNull(material, "material");
        dropDynamic(slot);
        materials[slot] = material;
        if (material instanceof CgMaterialInstance instance) dynamic[slot] = instance;
        precache(material);
        declared.clear();
        return this;
    }

    /** Every slot's material from {@code bySlot}. */
    public CgSkeletalMeshComponent materials(IntFunction<? extends CgMaterialInterface> bySlot) {
        for (int s = 0; s < materials.length; s++) material(s, Objects.requireNonNull(bySlot.apply(s), "material for slot " + s));
        return this;
    }

    /** Slot {@code slot}'s material as it draws: its dynamic instance where it has one; null where none is set. */
    @Nullable
    public CgMaterialInterface material(int slot) {
        return dynamic[slot] != null ? dynamic[slot] : materials[slot];
    }

    /**
     * Slot {@code slot}'s own instance, made over its material the first time (Unreal's
     * {@code CreateDynamicMaterialInstance}): values set on it change this component's slot alone.
     *
     * <pre>{@code
     * goku.dynamicMaterial(4).set1f("_Glow", 2f);
     * }</pre>
     *
     * @throws IllegalStateException where the slot has no material
     */
    public CgMaterialInstance dynamicMaterial(int slot) {
        requireLive();
        CgMaterialInstance d = dynamic[slot];
        if (d != null) return d;
        CgMaterialInterface m = materials[slot];
        if (m == null) throw new IllegalStateException(this + "'s slot " + slot + " has no material to instance");
        d = m.material().instance();
        dynamic[slot] = d;
        made[slot] = true;
        return d;
    }

    /** How many material slots it has: one a submesh of its mesh. */
    public int materialSlots() {
        return materials.length;
    }

    /**
     * Sets the scalar {@code name} on every slot whose material declares it, each through its own instance: Unreal's
     * {@code SetScalarParameterValueOnMaterials} (MeshComponent.cpp:621). What its clips' material curves call.
     */
    @Override
    public void scalar(String name, float value) {
        float[][] own = declared(name).bySlot;
        for (int s = 0; s < materials.length; s++) if (own[s] != null) dynamicMaterial(s).set1f(name, value);
    }

    /** Puts the scalar {@code name} back to each slot's material's own value. */
    @Override
    public void resetScalar(String name) {
        float[][] own = declared(name).bySlot;
        for (int s = 0; s < materials.length; s++) if (own[s] != null && dynamic[s] != null) dynamic[s].set1f(name, own[s][0]);
    }

    /** Sets the vector {@code name} on every slot whose material declares it: Unreal's {@code SetVectorParameterValueOnMaterials}. */
    public void vector(String name, float x, float y, float z, float w) {
        float[][] own = declared(name).bySlot;
        for (int s = 0; s < materials.length; s++) if (own[s] != null) dynamicMaterial(s).vec4(name, x, y, z, w);
    }

    /** Puts the vector {@code name} back to each slot's material's own value. */
    public void resetVector(String name) {
        float[][] own = declared(name).bySlot;
        for (int s = 0; s < materials.length; s++) {
            float[] d = own[s];
            if (d != null && dynamic[s] != null) dynamic[s].vec4(name, d[0], d[1], d[2], d[3]);
        }
    }

    /**
     * How many numbers parameter {@code name} is (1 a scalar, up to 4 a vector or colour) on the first slot whose
     * material declares it; 0 where none does. What a sequence's material track types its slot by.
     */
    public int parameterComponents(String name) {
        return declared(name).components;
    }

    /** The first declaring slot's material's own value of {@code name} into {@code out} (four numbers); false where none declares it. */
    public boolean parameterDefault(String name, float[] out) {
        for (float[] own : declared(name).bySlot) {
            if (own != null) {
                System.arraycopy(own, 0, out, 0, 4);
                return true;
            }
        }
        return false;
    }

    /** Each slot's material's own value for {@code name} (four numbers), null where it does not declare it: found once a name. */
    private Declared declared(String name) {
        Declared d = declared.get(name);
        if (d != null) return d;
        float[][] bySlot = new float[materials.length][];
        int components = 0;
        for (int s = 0; s < materials.length; s++) {
            if (materials[s] == null) continue;
            for (CgMaterialProperty p : materials[s].material().properties()) {
                if (!p.getName().equals(name) || p.getType().isSampler()) continue;
                bySlot[s] = Arrays.copyOf(p.getFloatValue(), 4);
                if (components == 0) components = p.getType().getComponents();
            }
        }
        d = new Declared(components, bySlot);
        declared.put(name, d);
        return d;
    }

    private void dropDynamic(int slot) {
        if (made[slot] && dynamic[slot] != null) dynamic[slot].delete();
        dynamic[slot] = null;
        made[slot] = false;
    }

    /**
     * Queues {@code material}'s skinned forms to be built before it draws, as a component precaches its materials with
     * its vertex factory (Unreal's {@code PrecachePSOs}): whatever is set while loading is built there.
     */
    private static void precache(CgMaterialInterface material) {
        CgPipelinePrecache.register("crystalgraphics:skeletal", pipelines -> pipelines.add(material, false, true));
    }

    // -- Overlay ---------------------------------------------------------------

    /**
     * Draws every slot a second time in {@code material}, Unreal's {@code OverlayMaterial}: the inverted-hull outline's
     * home, its material pushing the hull out and culling front faces. Null for none.
     *
     * <pre>{@code
     * goku.overlay(outline).overlayMaxDistance(40f);   // every slot, not past 40 blocks
     * goku.overlay(3, eyeLine);                        // slot 3's own; null falls back to the mesh's
     * }</pre>
     */
    public CgSkeletalMeshComponent overlay(@Nullable CgMaterialInterface material) {
        requireLive();
        overlay = material;
        if (material != null) precache(material);
        return this;
    }

    /** Slot {@code slot}'s overlay, Unreal's {@code MaterialSlotsOverlayMaterial}: null falls back to {@link #overlay(CgMaterialInterface)}'s. */
    public CgSkeletalMeshComponent overlay(int slot, @Nullable CgMaterialInterface material) {
        requireLive();
        slotOverlays[slot] = material;
        if (material != null) precache(material);
        return this;
    }

    /** The overlay slot {@code slot} draws in: its own, else the mesh's; null for none. */
    @Nullable
    public CgMaterialInterface overlay(int slot) {
        return slotOverlays[slot] != null ? slotOverlays[slot] : overlay;
    }

    /** How far from the camera its overlay draws, in blocks; 0, the default, as far as it draws. */
    public CgSkeletalMeshComponent overlayMaxDistance(float blocks) {
        if (!(blocks >= 0f)) throw new IllegalArgumentException("overlay distance " + blocks);
        overlayMaxDistance = blocks;
        return this;
    }

    public float overlayMaxDistance() {
        return overlayMaxDistance;
    }

    // -- Bones -------------------------------------------------------------------

    /**
     * Hides bone {@code name} in every view, by {@code how} (Unreal's {@code HideBoneByName} for
     * {@link CgBoneHide#BRANCH}): its skinning collapses, never its pose.
     *
     * @throws IllegalArgumentException where the skeleton has no {@code name}
     */
    public CgSkeletalMeshComponent hideBone(String name, CgBoneHide how) {
        hidden[own.bone(name)] = Objects.requireNonNull(how, "how");
        visibilityDirty = true;
        return this;
    }

    /**
     * Hides bone {@code name} in its owner's view alone (Unreal's {@code bOwnerNoSee}, a bone at a time): drawn for every
     * other view, which then reads a second palette, {@link #palette(boolean)}'s.
     *
     * <pre>{@code
     * body.hideBoneFromOwner("head", CgBoneHide.BRANCH);   // the player does not see their own head
     * }</pre>
     */
    public CgSkeletalMeshComponent hideBoneFromOwner(String name, CgBoneHide how) {
        hiddenFromOwner[own.bone(name)] = Objects.requireNonNull(how, "how");
        visibilityDirty = true;
        return this;
    }

    public CgBoneHide boneHidden(String name) {
        return hidden[own.bone(name)];
    }

    /**
     * Draws {@code leader}'s pose rather than its own, bone by name (Unreal's {@code SetLeaderPoseComponent}): a mesh on
     * the same skeleton, an armour or an outline shell. Its own instance stops posing; where it is not attached it rides
     * the leader's origin. A bone the leader lacks stays in its bind pose. Null draws its own pose again.
     *
     * @throws IllegalArgumentException where {@code leader} is itself or follows another
     */
    public CgSkeletalMeshComponent followPose(@Nullable CgSkeletalMeshComponent leader) {
        requireLive();
        if (leader == this) throw new IllegalArgumentException(this + " cannot follow its own pose");
        if (leader != null && leader.leader != null) throw new IllegalArgumentException(leader + " follows a pose itself: follow its leader");
        boolean wasOwn = this.leader == null;
        this.leader = leader;
        if (leader == null) {
            leaderBones = null;
            if (spawned()) scene.pose(own, true);
            return this;
        }
        int bones = mesh.skeleton().boneCount();
        leaderBones = new int[bones];
        inverseBinds = new Matrix4f[bones];
        for (int b = 0; b < bones; b++) {
            leaderBones[b] = leader.mesh.skeleton().indexOfIgnoringCase(mesh.skeleton().name(b));
            inverseBinds[b] = mesh.inverseBind(b, new Matrix4f());
        }
        if (wasOwn && spawned()) scene.pose(own, false);
        if (attachParent() == null) attach(leader, null);
        return this;
    }

    /** The component whose pose it draws, or null. */
    @Nullable
    public CgSkeletalMeshComponent leaderPose() {
        return leader;
    }

    // -- Sockets -------------------------------------------------------------------

    /**
     * Socket {@code name} as an attachment: what follows it rides the socket as posed and placed this frame, and stops
     * once this component is removed. A name its mesh has neither as a socket nor a bone places at its origin, as
     * Unreal's {@code GetSocketTransform} answers. Its other sockets are its named points.
     *
     * <pre>{@code
     * owner.play(new Flame(look, x, y, z).follow(kurama.attachment("hand_r")));
     * }</pre>
     */
    public CgAttachment attachment(String name) {
        return new Socket(own.socket(name));
    }

    @Override
    int socketHandle(String name) {
        return own.socket(name);
    }

    @Override
    Matrix4d socketWorld(int socket, Matrix4d out, Matrix4f scratch) {
        out.set(world());
        if (socket == CgAnimInstance.NO_SOCKET) return out;
        return out.mul(new Matrix4d(socketInMesh(socket, scratch)));
    }

    /** A socket in its mesh's space: its own pose's, or the leader's bone of that name while it follows one. */
    private Matrix4f socketInMesh(int socket, Matrix4f out) {
        return own.socket(socket, out);
    }

    private final class Socket implements CgAttachment {
        private final int handle;
        private final Matrix4d m = new Matrix4d();
        private final Matrix4f scratch = new Matrix4f();

        Socket(int handle) {
            this.handle = handle;
        }

        @Override
        public boolean place(double[] point, @Nullable Quaternionf turn) {
            if (!spawned()) return false;
            return at(handle, point, turn);
        }

        private boolean at(int socket, double[] point, @Nullable Quaternionf turn) {
            socketWorld(socket, m, scratch);
            if (point != null) {
                point[0] = m.m30();
                point[1] = m.m31();
                point[2] = m.m32();
            }
            // Unnormalized: the placement scales the columns, which getNormalizedRotation assumes unit.
            if (turn != null) m.getUnnormalizedRotation(turn);
            return true;
        }

        @Override
        public boolean point(String other, double[] out) {
            int s = own.socket(other);
            if (s == CgAnimInstance.NO_SOCKET) return false;
            return at(s, out, null);
        }

        @Override
        public boolean point(String other, double[] out, @Nullable Quaternionf turn) {
            int s = own.socket(other);
            if (!spawned() || s == CgAnimInstance.NO_SOCKET) return false;
            return at(s, out, turn);
        }

        @Override
        public Vector3f scale(@Nullable String other, Vector3f out) {
            if (!spawned()) return out.set(1f);
            socketWorld(other == null ? handle : own.socket(other), m, scratch);
            Vector3d s = m.getScale(new Vector3d());
            return out.set((float) s.x, (float) s.y, (float) s.z);
        }
    }

    // -- Drawing ---------------------------------------------------------------------

    /** Where its palette starts this frame; -1 before {@code ATTACH} first wrote one this frame. */
    public int palette() {
        return paletteFrame == CgFrameRing.frame() ? palette : -1;
    }

    /** The palette for its owner's view when {@code ownerView}, with the bones hidden from the owner collapsed. */
    public int palette(boolean ownerView) {
        if (!ownerView || ownerPalette < 0) return palette();
        return paletteFrame == CgFrameRing.frame() ? ownerPalette : -1;
    }

    /** Its bounds this frame in its mesh's space, before its placement: from its pose, or stated with {@code bounds(box)}. */
    public CgAABB drawnBounds(CgAABB out) {
        if (!localBounds(out)) mesh.mesh().bounds(out);
        return out;
    }

    /** Whether its overlay draws for a camera at {@code (cx, cy, cz)}: within its overlay distance, or no limit. */
    public boolean overlayDrawnFrom(double cx, double cy, double cz) {
        if (overlayMaxDistance <= 0f) return true;
        double dx = x() - cx, dy = y() - cy, dz = z() - cz;
        return dx * dx + dy * dy + dz * dz <= (double) overlayMaxDistance * overlayMaxDistance;
    }

    /**
     * Writes submesh {@code submesh} as posed this frame into {@code sink}, skinned on the CPU: Unreal's CPU skinning,
     * for a host that must draw the vertices through its own path, as a shader pack lights and fogs a Minecraft entity
     * only when its renderer writes them.
     *
     * <pre>{@code
     * // in the entity renderer, after ATTACH, once per material slot, under a pack:
     * for (int s = 0; s < body.materialSlots(); s++) {
     *     VertexConsumer out = buffers.getBuffer(RenderType.entityCutoutNoCull(textures[s]));
     *     body.cpuSkin(s, firstPerson, (x, y, z, u, v, argb, nx, ny, nz) -> ...);
     * }
     * }</pre>
     *
     * <ul>
     *   <li>Positions are relative to where it stands, turned and scaled by its placement.</li>
     *   <li>Its mesh must keep its CPU copy: never {@code dropCpuCopy()} a mesh drawn this way.</li>
     *   <li>On the render thread, after {@code ATTACH}.</li>
     * </ul>
     */
    public void cpuSkin(int submesh, boolean ownerView, CgPosedVertexSink sink) {
        if (cpuSkin == null) cpuSkin = new CgCpuSkin();
        Matrix4f[] bones = ownerView && anyHiddenFromOwner ? ownerSkinning : skinning;
        cpuSkin.skin(mesh.mesh(), submesh, bones, basis(placement), sink);
    }

    /** This frame's skinning from its pose, the hidden bones collapsed, its bounds, then its palettes: the scene's, in {@code ATTACH}. */
    void writePalettes() {
        if (leader != null) {
            Matrix4f[] lead = leader.anim().skinning();
            for (int b = 0; b < skinning.length; b++) {
                int l = leaderBones[b];
                if (l < 0) {
                    skinning[b].identity();
                } else {
                    // The leader's pose of the bone, then this mesh's own inverse bind
                    leader.anim().pose().component(l, skinning[b]).mul(inverseBinds[b]);
                }
            }
            if (lead.length == 0) return;
        } else {
            Matrix4f[] from = own.skinning();
            for (int b = 0; b < skinning.length; b++) skinning[b].set(from[b]);
        }
        CgSkinBounds.posed(mesh, skinning, posedBounds);
        bounded = true;
        boundsChanged();
        collapse();
        // Last frame's beside this frame's, as Unreal's skin cache keeps the previous bone buffer for velocity: taken at a
        // frame's first write, so writing again in the same frame keeps last frame's rather than this one's
        if (paletteFrame != CgFrameRing.frame() && latestHeld) {
            for (int b = 0; b < latestSkinning.length; b++) previousSkinning[b].set(latestSkinning[b]);
            previousHeld = true;
        }
        int previous = previousHeld ? CgBonePalettes.write(previousSkinning, previousSkinning.length) : -1;
        palette = CgBonePalettes.write(skinning, skinning.length);
        ownerPalette = anyHiddenFromOwner ? CgBonePalettes.write(ownerSkinning, ownerSkinning.length) : -1;
        paletteFrame = CgFrameRing.frame();
        for (int b = 0; b < skinning.length; b++) latestSkinning[b].set(skinning[b]);
        latestHeld = true;
        // Its record's spare lanes, as the skinned factory reads them: this frame's palette, the vertex's width, and
        // last frame's palette plus 1 (0 for none)
        if (slot >= 0) {
            scene.gpu().spare(slot, palette, mesh.mesh().format().equals(CgVertexFormat.SKINNED_WIDE) ? 1f : 0f, previous + 1f, 0f);
        }
    }

    /** Collapses the hidden bones' skinning to a point; the owner's view collapses every view's hidden bones and its own. */
    private void collapse() {
        if (visibilityDirty) rebuildVisibility();
        if (anyHiddenFromOwner) {
            for (int b = 0; b < skinning.length; b++) {
                ownerSkinning[b].set(skinning[b]);
                if (collapsedForOwner[b]) ownerSkinning[b].scale(0f);
            }
        }
        if (anyHidden) for (int b = 0; b < skinning.length; b++) if (collapsed[b]) skinning[b].scale(0f);
    }

    /**
     * Unreal's {@code RebuildVisibilityArray} (SkinnedMeshComponent.cpp:2124-2166): a bone hidden with its branch hides
     * every bone below it; one hidden alone hides only its own vertices.
     */
    private void rebuildVisibility() {
        visibilityDirty = false;
        anyHidden = rebuild(hidden, null, collapsed);
        boolean fromOwner = false;
        for (CgBoneHide h : hiddenFromOwner) fromOwner |= h != CgBoneHide.NONE;
        anyHiddenFromOwner = fromOwner && rebuild(hiddenFromOwner, hidden, collapsedForOwner);
        if (anyHiddenFromOwner && ownerSkinning == null) {
            ownerSkinning = new Matrix4f[skinning.length];
            for (int b = 0; b < skinning.length; b++) ownerSkinning[b] = new Matrix4f();
        }
    }

    private boolean rebuild(CgBoneHide[] hide, @Nullable CgBoneHide[] also, boolean[] out) {
        boolean any = false;
        for (int b = 0; b < out.length; b++) {
            int parent = mesh.skeleton().parent(b);
            CgBoneHide h = hide[b];
            CgBoneHide a = also != null ? also[b] : CgBoneHide.NONE;
            branchHidden[b] = (parent >= 0 && branchHidden[parent]) || h == CgBoneHide.BRANCH || a == CgBoneHide.BRANCH;
            out[b] = branchHidden[b] || h == CgBoneHide.BONE || a == CgBoneHide.BONE;
            any |= out[b];
        }
        return any;
    }

    // -- The scene's ---------------------------------------------------------------------

    @Override
    void onSpawn() {
        slot = scene.gpu().add();
        scene.writeRecord(this, slot);
        if (leader == null) scene.pose(own, true);
    }

    @Override
    void onRemove() {
        scene.gpu().remove(slot);
        slot = -1;
        if (leader == null) scene.pose(own, false);
        for (int s = 0; s < materials.length; s++) dropDynamic(s);
    }

    @Override
    void onMoved() {
        scene.gpu().transform(slot, world());
    }

    @Override
    void onLooked() {
        scene.writeValues(this, slot);
    }

    @Override
    boolean kindBounds(CgAABB out) {
        if (!bounded) return false;
        out.set(posedBounds);
        return true;
    }

    /** A skinned mesh changes shape every frame it is posed: always in the velocity pass (Unreal's AlwaysHasVelocity). */
    @Override
    boolean deforms() {
        return true;
    }

    /** Its record's object id in its scene's GPU scene, or -1 before it is spawned. */
    public int objectId() {
        return slot < 0 ? -1 : CgGpuScene.id(slot);
    }

    @Override
    void gather(CgView view, CgCamera camera, CgMeshBatches out, CgSceneGather gather) {
        int bones = palette();
        if (bones < 0) return;
        int id = CgGpuScene.id(slot);
        boolean overlays = overlayDrawnFrom(camera.x(), camera.y(), camera.z());
        for (int s = 0; s < materials.length; s++) {
            CgMaterialInterface m = material(s);
            if (m == null) continue;
            int row = batch(out, mesh.mesh(), m, s);
            out.object(row, id);
            out.bones(row, bones);
            CgMaterialInterface o = overlays ? overlay(s) : null;
            if (o != null) {
                row = batch(out, mesh.mesh(), o, s);
                out.object(row, id);
                out.bones(row, bones);
            }
        }
    }

    @Override
    public String toString() {
        return "CgSkeletalMeshComponent(" + mesh + ")";
    }
}
