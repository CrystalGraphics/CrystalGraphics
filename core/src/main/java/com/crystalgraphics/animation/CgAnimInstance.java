package com.crystalgraphics.animation;

import com.crystalgraphics.render.world.CgPipelinePrecache;
import com.crystalgraphics.api.animation.CgAnimSection;
import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.api.animation.CgMirrorTable;
import com.crystalgraphics.api.animation.CgPose;
import com.crystalgraphics.api.animation.CgSocket;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.api.mesh.CgSkeletalMesh;
import com.crystalgraphics.api.material.CgMaterialValues;
import com.crystalgraphics.gl.material.CgMaterialProperty;
import com.crystalgraphics.gl.buffer.CgFrameRing;
import com.crystalgraphics.shape.bounds.CgAABB;
import com.crystalgraphics.world.CgAttachment;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A skeletal mesh posed by this engine: the clips it plays as sections on a timeline of its own, and each frame the
 * pose at that time, its skinning matrices and the palette its draw names. Unreal's skeletal mesh component with
 * Sequencer's animation instance: sections cross-fade by their weights, normalised as Sequencer's
 * {@code MultiWayBlend} does (AnimNode_MultiWayBlend.cpp:69-78), the rest pose where none plays.
 *
 * <pre>{@code
 * CgAnimInstance kurama = CgAnimation.client().add(new CgAnimInstance(mesh));   // posed in POSE every frame
 * kurama.place(x, y, z, turnAndScale);
 * kurama.play(CgAnimSection.of(mesh.sequence("idle")).until(Double.POSITIVE_INFINITY).build());
 *
 * owner.play(new Flame(look, x, y, z).follow(kurama.attachment("hand_r")));   // rides the hand as posed this frame
 * world.draw(kurama, skin).submit();                               // in SUBMIT, the same frame
 * }</pre>
 *
 * <p>Seeking: the pose is a pure function of the time, so a seek lands exactly where playing would.</p>
 *
 * <pre>{@code
 * kurama.seek(3.25);              // the same pose playing from 0 reaches at 3.25 s
 * kurama.playing(false);          // held there; advance() moves it by hand
 * }</pre>
 *
 * <ul>
 *   <li>Changed from the client world's thread (its schedule); evaluated on a worker inside {@code POSE}, never
 *       touched there by anything else.</li>
 *   <li>{@link #palette()} is valid the frame {@code POSE} wrote it; -1 before its first.</li>
 *   <li>Allocates nothing per frame once its sections are added.</li>
 * </ul>
 */
public final class CgAnimInstance {

    /** {@link #socket(String)}'s answer for a name it has neither as a socket nor a bone: where the instance stands. */
    public static final int NO_SOCKET = -1;
    private static final int SOCKET = 1 << 30;
    private static final Matrix4fc IDENTITY = new Matrix4f();

    private final CgSkeletalMesh mesh;
    private double x, y, z;
    private final Matrix4f transform = new Matrix4f();
    private final Matrix4f socketScratch = new Matrix4f(), relativeScratch = new Matrix4f();
    /** What it is attached to, and where on it; null when it stands on its own. */
    @Nullable
    private CgAnimInstance parent;
    private int parentSocket = NO_SOCKET;
    /** While attached, {@link #place}'s offset and turn in the parent socket's space: Unreal's relative transform. */
    private double relX, relY, relZ;
    private final Matrix4f relative = new Matrix4f();
    /** Read by {@link #follow} alone, so children of one parent follow it from several workers at once. */
    private final Matrix4f followScratch = new Matrix4f(), followRelative = new Matrix4f();
    @Nullable
    private CgCpuSkin cpuSkin;
    /** 0 standing alone, else its parent's plus one: the order {@code CgAnimation} poses in. */
    int depth;
    private long paletteFrame = -1;
    private boolean posed;
    private final List<CgAnimSection> sections = new ArrayList<>();
    private final List<CgMirrorTable> sectionMirrors = new ArrayList<>();
    private final CgPose pose;
    private final Matrix4f[] skinning;
    /** The owner's view's skinning, where a bone is hidden from it; null until one is. */
    @Nullable
    private Matrix4f[] ownerSkinning;
    /** Per bone, how it is hidden in every view and in the owner's alone; and which bones collapse in each. */
    private final Hide[] hidden, hiddenFromOwner;
    private final boolean[] collapsed, collapsedForOwner, branchHidden;
    private boolean visibilityDirty, anyHidden, anyHiddenFromOwner;
    private int ownerPalette = -1;
    private final List<CgBoneWrite> writes = new ArrayList<>();
    private CgPose[] scratch = new CgPose[0];
    private float[] weights = new float[0];
    /** This frame's slot entries, cleared by {@link #evaluate()}: Unreal's montage slot. */
    private CgAnimSequence[] slotClips = new CgAnimSequence[0];
    private CgMirrorTable[] slotMirrors = new CgMirrorTable[0];
    private float[] slotTimes = new float[0], slotWeights = new float[0];
    private int slotCount;
    private double time;
    private float rate = 1f;
    private boolean playing = true;
    private int palette = -1;
    /** Each submesh's material, Unreal's {@code OverrideMaterials}; null where none is set. */
    private final CgMaterial[] materials;
    /** Its bind pose's half diagonal in the mesh's units, and its placement's largest scale: what its bounds pad from. */
    private final float meshRadius;
    private float placedScale = 1f, boundsScale = 1f;
    /** Unreal's {@code OverlayMaterial}, {@code MaterialSlotsOverlayMaterial} and {@code OverlayMaterialMaxDrawDistance}. */
    @Nullable
    private CgMaterial overlay;
    private final CgMaterial[] slotOverlays;
    private float overlayMaxDistance;
    /** Its CustomDepth stencil value and write mask; -1 for not drawn there. */
    private int customDepthStencil = -1, customDepthMask;
    /** Which slots hold its own copy of a material (Unreal's dynamic material instances), what {@link #scalar} sets. */
    private final boolean[] own;
    /** Per scalar name: which own slots declare it, and each one's default; made the first time the name is set. */
    private final Map<String, float[]> scalarDefaults = new HashMap<>();
    private final Map<String, float[][]> vectorDefaults = new HashMap<>();
    private final CgMaterialCurves curves = new CgMaterialCurves();
    /** The clip and its time behind each of this frame's blended poses, for its curves. */
    private CgAnimSequence[] sampledClips = new CgAnimSequence[0];
    private float[] sampledTimes = new float[0];
    private final ScalarWriter writer = new ScalarWriter();
    /** Its layered slots, blended over the body in the order added (Unreal's Layered blend per bone nodes). */
    private final List<CgAnimLayer> layers = new ArrayList<>();

    public CgAnimInstance(CgSkeletalMesh mesh) {
        this.mesh = Objects.requireNonNull(mesh, "mesh");
        this.materials = new CgMaterial[mesh.mesh().submeshCount()];
        this.slotOverlays = new CgMaterial[materials.length];
        this.own = new boolean[materials.length];
        CgAABB box = mesh.mesh().bounds(new CgAABB());
        this.meshRadius = (float) Math.sqrt(box.halfX() * box.halfX() + box.halfY() * box.halfY() + box.halfZ() * box.halfZ());
        this.pose = new CgPose(mesh.skeleton());
        this.skinning = new Matrix4f[mesh.skeleton().boneCount()];
        for (int b = 0; b < skinning.length; b++) skinning[b] = new Matrix4f();
        this.hidden = new Hide[skinning.length];
        this.hiddenFromOwner = new Hide[skinning.length];
        Arrays.fill(hidden, Hide.NONE);
        Arrays.fill(hiddenFromOwner, Hide.NONE);
        this.collapsed = new boolean[skinning.length];
        this.collapsedForOwner = new boolean[skinning.length];
        this.branchHidden = new boolean[skinning.length];
        pose.resolve();
        pose.skinning(mesh, skinning);
        reserve();
    }

    public CgSkeletalMesh mesh() {
        return mesh;
    }

    /** Adds a section to its timeline. */
    public CgAnimInstance play(CgAnimSection section) {
        return play(section, null);
    }

    /**
     * Adds a section whose pose is mirrored by {@code mirror}, null for as authored: Unreal's mirror node over it.
     *
     * <pre>{@code
     * body.play(CgAnimSection.of(swingRight).build(), mirror);   // authored for the right arm, swung with the left
     * }</pre>
     */
    public CgAnimInstance play(CgAnimSection section, @Nullable CgMirrorTable mirror) {
        sections.add(Objects.requireNonNull(section, "section"));
        sectionMirrors.add(mirror);
        reserve();
        return this;
    }

    /**
     * Plays {@code clip} at {@code position} seconds with {@code weight} over its own sections, this frame's pose only:
     * a montage slot's entry, what a sequence's {@code animation} track sets every frame it is inside a section
     * (Unreal's {@code UpdateAnimTrack}). Its sections keep {@code 1 - } the slot's total weight; past 1 the slot's
     * entries are normalised and its sections are gone (AnimInstanceProxy.cpp:2170, 2021-2033).
     *
     * <pre>{@code
     * body.slot(roar, 0.4f, 1f);        // this frame: roar at 0.4 s, whatever its sections play
     * body.slot(roar, 0.4f, 0.25f);     // a quarter roar over three quarters of its sections
     * }</pre>
     *
     * Entries are cleared by the next {@link #evaluate()}: set them every frame they should hold. Two set before it
     * both count.
     */
    public CgAnimInstance slot(CgAnimSequence clip, float position, float weight) {
        return slot(clip, position, weight, null);
    }

    /** As {@link #slot(CgAnimSequence, float, float)}, the clip's pose mirrored by {@code mirror} (null for as authored). */
    public CgAnimInstance slot(CgAnimSequence clip, float position, float weight, @Nullable CgMirrorTable mirror) {
        if (slotCount == slotClips.length) {
            int n = Math.max(2, slotCount * 2);
            slotClips = Arrays.copyOf(slotClips, n);
            slotMirrors = Arrays.copyOf(slotMirrors, n);
            slotTimes = Arrays.copyOf(slotTimes, n);
            slotWeights = Arrays.copyOf(slotWeights, n);
        }
        slotClips[slotCount] = Objects.requireNonNull(clip, "clip");
        slotMirrors[slotCount] = mirror;
        slotTimes[slotCount] = position;
        slotWeights[slotCount++] = weight;
        reserve();
        return this;
    }

    /** A scratch pose and weight for every section, slot entry and the rest pose. */
    private void reserve() {
        int n = sections.size() + slotCount + 1;
        if (scratch.length >= n) return;
        scratch = Arrays.copyOf(scratch, n);
        for (int i = 0; i < n; i++) if (scratch[i] == null) scratch[i] = new CgPose(mesh.skeleton());
        weights = Arrays.copyOf(weights, n);
        sampledClips = Arrays.copyOf(sampledClips, n);
        sampledTimes = Arrays.copyOf(sampledTimes, n);
    }

    public CgAnimInstance remove(CgAnimSection section) {
        int at = sections.indexOf(section);
        if (at >= 0) {
            sections.remove(at);
            sectionMirrors.remove(at);
        }
        return this;
    }

    /** Takes every section off its timeline, leaving its time. */
    public CgAnimInstance clear() {
        sections.clear();
        sectionMirrors.clear();
        return this;
    }

    public List<CgAnimSection> sections() {
        return List.copyOf(sections);
    }

    /** Its timeline's time, seconds. */
    public double time() {
        return time;
    }

    /** Moves its timeline to {@code seconds}: the next pose is the one playing reaches there. */
    public CgAnimInstance seek(double seconds) {
        time = seconds;
        return this;
    }

    /** Moves its timeline by {@code seconds} times its rate: what {@code POSE} does each frame while it plays. */
    public CgAnimInstance advance(double seconds) {
        time += seconds * rate;
        return this;
    }

    /** Timeline seconds a second of play; 1 by default, 0 freezes it as {@code playing(false)} does. */
    public CgAnimInstance rate(float rate) {
        this.rate = rate;
        return this;
    }

    public float rate() {
        return rate;
    }

    /** Whether {@code POSE} advances it each frame. A paused instance is still posed at its time. */
    public CgAnimInstance playing(boolean playing) {
        this.playing = playing;
        return this;
    }

    public boolean playing() {
        return playing;
    }

    /**
     * Poses it at its time: each section's clip sampled at its clip time, the sections blended by their weights
     * normalised, under this frame's {@link #slot} entries; then resolved and its skinning matrices made. What
     * {@code POSE} runs; by hand for a tool or a test.
     */
    public CgAnimInstance evaluate() {
        float slotTotal = 0f;
        for (int i = 0; i < slotCount; i++) slotTotal += Math.max(0f, slotWeights[i]);
        float source = Math.min(1f, Math.max(0f, 1f - slotTotal));
        int n = 0;
        if (source > 0f) {
            float total = 0f;
            for (int s = 0; s < sections.size(); s++) {
                CgAnimSection section = sections.get(s);
                float w = section.weight(time);
                if (w <= 0f) continue;
                float at = section.animationTime(time);
                section.sequence().sample(scratch[n].rest(), at);
                if (sectionMirrors.get(s) != null) scratch[n].mirror(sectionMirrors.get(s));
                sampledClips[n] = section.sequence();
                sampledTimes[n] = at;
                weights[n++] = w;
                total += w;
            }
            if (n == 0) {
                scratch[0].rest();
                sampledClips[n] = null;
                weights[n++] = source;
            } else {
                for (int i = 0; i < n; i++) weights[i] = weights[i] / total * source;
            }
        }
        float scale = slotTotal > 1f ? 1f / slotTotal : 1f;
        for (int i = 0; i < slotCount; i++) {
            if (slotWeights[i] <= 0f) continue;
            slotClips[i].sample(scratch[n].rest(), slotTimes[i]);
            if (slotMirrors[i] != null) scratch[n].mirror(slotMirrors[i]);
            sampledClips[n] = slotClips[i];
            sampledTimes[n] = slotTimes[i];
            weights[n++] = slotWeights[i] * scale;
        }
        // Curves blend with the pose's weights (Unreal's BlendPosesTogether blends a pose's curves with it)
        curves.begin();
        for (int i = 0; i < n; i++) if (sampledClips[i] != null) curves.add(sampledClips[i], sampledTimes[i], weights[i]);
        Arrays.fill(sampledClips, 0, n, null);
        Arrays.fill(slotClips, 0, slotCount, null);
        Arrays.fill(slotMirrors, 0, slotCount, null);
        slotCount = 0;
        if (n == 0) {
            pose.rest();
        } else {
            pose.blend(scratch, weights, n);
        }
        for (int i = 0; i < layers.size(); i++) layers.get(i).apply(time, pose, curves);
        for (int i = 0; i < writes.size(); i++) {
            CgBoneWrite write = writes.get(i);
            if (write.needsComponent()) pose.resolve();
            write.apply(pose);
        }
        pose.resolve();
        pose.skinning(mesh, skinning);
        collapse();
        return this;
    }

    /**
     * Collapses the hidden bones' skinning matrices to a point, never their pose: sockets and children still read the
     * bone. The owner's view collapses every view's hidden bones and its own.
     */
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
        for (Hide h : hiddenFromOwner) fromOwner |= h != Hide.NONE;
        anyHiddenFromOwner = fromOwner && rebuild(hiddenFromOwner, hidden, collapsedForOwner);
        if (anyHiddenFromOwner && ownerSkinning == null) {
            ownerSkinning = new Matrix4f[skinning.length];
            for (int b = 0; b < skinning.length; b++) ownerSkinning[b] = new Matrix4f();
        }
    }

    /** Fills {@code out} from {@code hide} and, where not null, {@code also}; whether any bone collapses. */
    private boolean rebuild(Hide[] hide, @Nullable Hide[] also, boolean[] out) {
        boolean any = false;
        for (int b = 0; b < out.length; b++) {
            int parent = mesh.skeleton().parent(b);
            Hide h = hide[b];
            Hide a = also != null ? also[b] : Hide.NONE;
            branchHidden[b] = (parent >= 0 && branchHidden[parent]) || h == Hide.BRANCH || a == Hide.BRANCH;
            out[b] = branchHidden[b] || h == Hide.BONE || a == Hide.BONE;
            any |= out[b];
        }
        return any;
    }

    // ---- what code changes after the clips

    /** How a bone is hidden: Unreal's {@code HideBone}, or GeckoLib's own-cubes toggle. */
    public enum Hide {
        /** Drawn. */
        NONE,
        /** Only the bone's own vertices: what is below it still draws (GeckoLib's {@code setHidden}). */
        BONE,
        /** The bone and every bone below it (Unreal's {@code HideBone}). */
        BRANCH
    }

    /**
     * Hides bone {@code name} in every view, by {@code how}: its skinning matrix collapses, never its pose, so its
     * sockets and the bones below it still move. Unreal's {@code HideBoneByName} for {@link Hide#BRANCH}.
     *
     * <pre>{@code
     * role.hide("head", CgAnimInstance.Hide.BONE);        // the head's cubes go, a hat on a child bone stays
     * role.hide("left_arm", CgAnimInstance.Hide.BRANCH);  // the arm and everything below it
     * role.hide("head", CgAnimInstance.Hide.NONE);        // drawn again
     * }</pre>
     *
     * @throws IllegalArgumentException where the skeleton has no {@code name}
     */
    public CgAnimInstance hide(String name, Hide how) {
        hidden[bone(name)] = Objects.requireNonNull(how, "how");
        visibilityDirty = true;
        return this;
    }

    /**
     * Hides bone {@code name} in its owner's view alone (Unreal's {@code bOwnerNoSee}, a bone at a time): drawn for
     * every other view. While one is set, {@code POSE} writes a second palette, {@link #palette(boolean)}'s.
     *
     * <pre>{@code
     * role.hideFromOwner("head", CgAnimInstance.Hide.BRANCH);   // the player does not see their own head
     * world.draw(role, skin).bones(role.palette(ownerView)).submit();
     * }</pre>
     */
    public CgAnimInstance hideFromOwner(String name, Hide how) {
        hiddenFromOwner[bone(name)] = Objects.requireNonNull(how, "how");
        visibilityDirty = true;
        return this;
    }

    public Hide hidden(String name) {
        return hidden[bone(name)];
    }

    /**
     * A bone write on {@code name}, applied every frame after the clips and layers in the order made ({@link
     * CgBoneWrite}, Unreal's modify bone). It does nothing until one of its parts is set.
     *
     * <pre>{@code
     * CgBoneWrite drop = role.write("model");
     * drop.translation(CgBoneWrite.Mode.ADD, CgBoneWrite.Space.COMPONENT, 0f, -crouch, 0f);   // each frame
     * }</pre>
     */
    public CgBoneWrite write(String name) {
        CgBoneWrite write = new CgBoneWrite(bone(name));
        writes.add(write);
        return write;
    }

    /** Stops applying {@code write}. */
    public CgAnimInstance remove(CgBoneWrite write) {
        writes.remove(write);
        return this;
    }

    private int bone(String name) {
        int b = mesh.skeleton().indexOfIgnoringCase(name);
        if (b < 0) throw new IllegalArgumentException(this + ": the skeleton has no bone " + name);
        return b;
    }

    /** The pose {@link #evaluate()} last made: local and, resolved, in the mesh's space. */
    public CgPose pose() {
        return pose;
    }

    /** Its skinning matrices as of the last {@link #evaluate()}, one a bone. */
    public Matrix4f[] skinning() {
        return skinning;
    }

    /** Where its palette starts this frame, for its draw's {@code bones(base)}; -1 where {@code POSE} wrote none this frame. */
    public int palette() {
        return paletteFrame == CgFrameRing.frame() ? palette : -1;
    }

    /**
     * The palette for its owner's view when {@code ownerView}, with the bones {@link #hideFromOwner} hid collapsed; the
     * one every view takes otherwise, and wherever no bone is hidden from its owner. -1 as {@link #palette()}.
     */
    public int palette(boolean ownerView) {
        if (!ownerView || ownerPalette < 0) return palette();
        return paletteFrame == CgFrameRing.frame() ? ownerPalette : -1;
    }

    void palette(int base, int ownerBase) {
        palette = base;
        ownerPalette = ownerBase;
        paletteFrame = CgFrameRing.frame();
    }

    /**
     * Writes submesh {@code submesh} as {@code POSE} posed it into {@code sink}, skinned on the CPU, a quad at a time:
     * Unreal's CPU skinning ({@code FSkeletalMeshObjectCPUSkin}). For a host that must draw the vertices through its
     * own path, as a shader pack lights, fogs and shadows a Minecraft entity only when its renderer writes them.
     *
     * <pre>{@code
     * // in the entity renderer, after POSE, once per material slot, under a pack:
     * if (CgShaderPacks.packInUse()) {
     *     for (int s = 0; s < body.materialSlots(); s++) {
     *         VertexConsumer out = buffers.getBuffer(RenderType.entityCutoutNoCull(textures[s]));
     *         body.cpuSkin(s, firstPerson, (x, y, z, u, v, argb, nx, ny, nz) -> ...);   // see CgPosedVertexSink
     *     }
     * }
     * }</pre>
     *
     * <ul>
     *   <li>{@code ownerView}: the bones {@link #hideFromOwner} hid collapse, as {@link #palette(boolean)}'s.</li>
     *   <li>Positions are relative to where it stands, turned and scaled by {@link #place}'s transform.</li>
     *   <li>Its mesh must keep its CPU copy: never {@code dropCpuCopy()} a mesh drawn this way.</li>
     *   <li>The mesh is decoded once and kept while its revision holds; a call allocates nothing after the first.</li>
     *   <li>On the render thread, after {@code POSE}: not while it is being posed.</li>
     * </ul>
     */
    public void cpuSkin(int submesh, boolean ownerView, CgPosedVertexSink sink) {
        if (cpuSkin == null) cpuSkin = new CgCpuSkin();
        Matrix4f[] bones = ownerView && anyHiddenFromOwner ? ownerSkinning : skinning;
        cpuSkin.skin(mesh.mesh(), submesh, bones, transform, sink);
    }

    /** The owner's view's skinning matrices, or null where no bone is hidden from it: a second palette's. */
    @Nullable
    Matrix4f[] ownerSkinning() {
        return anyHiddenFromOwner ? ownerSkinning : null;
    }

    // ---- where it stands, and its sockets

    /** Stands it at absolute {@code (x, y, z)}, unturned and unscaled. */
    public CgAnimInstance place(double x, double y, double z) {
        return place(x, y, z, IDENTITY);
    }

    /**
     * Stands it at absolute {@code (x, y, z)}, turned and scaled by {@code transform} about that point: Unreal's
     * component-to-world. Where it is drawn ({@code CgWorldRenderer.draw(instance, material)}) and where its sockets are.
     * While {@link #attachTo attached}, the offset, turn and scale in the parent socket's space instead, applied from
     * the next {@code POSE}.
     */
    public CgAnimInstance place(double x, double y, double z, Matrix4fc transform) {
        if (parent != null) {
            relX = x;
            relY = y;
            relZ = z;
            relative.set(transform);
            return this;
        }
        return stand(x, y, z, transform);
    }

    private CgAnimInstance stand(double x, double y, double z, Matrix4fc transform) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.transform.set(transform);
        float sx = (float) Math.sqrt(transform.m00() * transform.m00() + transform.m01() * transform.m01() + transform.m02() * transform.m02());
        float sy = (float) Math.sqrt(transform.m10() * transform.m10() + transform.m11() * transform.m11() + transform.m12() * transform.m12());
        float sz = (float) Math.sqrt(transform.m20() * transform.m20() + transform.m21() * transform.m21() + transform.m22() * transform.m22());
        placedScale = Math.max(sx, Math.max(sy, sz));
        return this;
    }

    // ---- what it is drawn with

    /**
     * Draws submesh {@code slot} with {@code material}, null to clear it: Unreal's {@code SetMaterial}
     * (MeshComponent.cpp:63-99). {@code CgWorldRenderer.draw(instance)} draws each slot in its own.
     *
     * <pre>{@code
     * for (int s = 0; s < goku.materialSlots(); s++) goku.material(s, CgMaterial.of(kitMaterials.get(s)));
     * world.draw(goku);   // in SUBMIT: every slot, skinned, in its material
     * }</pre>
     */
    public CgAnimInstance material(int slot, @Nullable CgMaterial material) {
        materials[slot] = material;
        own[slot] = false;
        precache(material);
        scalarDefaults.clear();
        vectorDefaults.clear();
        return this;
    }

    /**
     * Draws submesh {@code slot} in a copy of {@code definition}'s material of its own, Unreal's dynamic material
     * instance: what {@link #scalar} and its clips' material curves set, so they change this instance alone.
     *
     * <pre>{@code
     * for (int s = 0; s < goku.materialSlots(); s++) goku.material(s, kitMaterials.get(s));   // his own copies
     * goku.scalar("OutlineColorType", 1f);                                                     // on each that declares it
     * }</pre>
     */
    public CgAnimInstance material(int slot, CgMaterialDefinition definition) {
        materials[slot] = CgMaterial.newInstance(definition, null);
        own[slot] = true;
        precache(materials[slot]);
        scalarDefaults.clear();
        vectorDefaults.clear();
        return this;
    }

    /**
     * A slot named {@code slot} blended over {@code bone}'s branch, the new one blended over those before it
     * ({@link CgAnimLayer}). Kakarot's face: {@code layer("FacialSlot", "facialbase", 1)}.
     *
     * @throws IllegalArgumentException where the skeleton has no {@code bone}, or a layer of that slot exists
     */
    public CgAnimLayer layer(String slot, String bone, int blendDepth) {
        bone(bone);   // before the layer is added
        return addLayer(slot).branch(bone, blendDepth);
    }

    /**
     * A slot named {@code slot} with an empty mask, blended over the layers before it: give it branches or bone weights.
     *
     * <pre>{@code
     * CgAnimLayer arms = role.addLayer("Arms").branch("right_arm", 0).branch("left_arm", 0);
     * CgAnimLayer lean = role.addLayer("Lean").bone("body", 0.5f).bone("head", 0.25f);
     * }</pre>
     *
     * @throws IllegalArgumentException where a layer of that slot exists
     */
    public CgAnimLayer addLayer(String slot) {
        if (layer(slot) != null) throw new IllegalArgumentException(this + " has a layer " + slot + " already");
        CgAnimLayer layer = new CgAnimLayer(mesh.skeleton(), slot);
        layers.add(layer);
        return layer;
    }

    /** Its layer for slot {@code slot}, or null: where a sequence's section on that slot plays. */
    @Nullable
    public CgAnimLayer layer(String slot) {
        for (int i = 0; i < layers.size(); i++) if (layers.get(i).slot().equals(slot)) return layers.get(i);
        return null;
    }

    /**
     * Queues {@code material}'s skinned forms to be built before it draws, as a component precaches its materials with
     * its vertex factory (Unreal's {@code PrecachePSOs}): whatever is set while loading is built there.
     */
    private static void precache(@Nullable CgMaterial material) {
        if (material != null) CgPipelinePrecache.register("crystalgraphics:anim", pipelines -> pipelines.add(material, false, true));
    }

    /** Submesh {@code slot}'s material, or null where none is set. */
    @Nullable
    public CgMaterial material(int slot) {
        return materials[slot];
    }

    /**
     * Sets the scalar {@code name} to {@code value} on each of its own materials that declares it: Unreal's
     * {@code SetScalarParameterValueOnMaterials} (MeshComponent.cpp:621). A material set with {@link
     * #material(int, CgMaterial)} is shared and left alone. Client world's thread.
     */
    public void scalar(String name, float value) {
        float[] defaults = declared(name);
        writer.name = name;
        writer.value = value;
        for (int s = 0; s < materials.length; s++) if (!Float.isNaN(defaults[s])) materials[s].applyProperties(writer);
    }

    /** Puts the scalar {@code name} back to each own material's default, as a curve no clip keys any more does. */
    public void resetScalar(String name) {
        float[] defaults = declared(name);
        writer.name = name;
        for (int s = 0; s < materials.length; s++) {
            if (Float.isNaN(defaults[s])) continue;
            writer.value = defaults[s];
            materials[s].applyProperties(writer);
        }
    }

    /**
     * Sets the vector {@code name} on each of its own materials that declares it: Unreal's
     * {@code SetVectorParameterValueOnMaterials}. Client world's thread.
     */
    public void vector(String name, float x, float y, float z, float w) {
        float[][] defaults = declaredVector(name);
        writer.name = name;
        writer.vector = true;
        writer.x = x;
        writer.y = y;
        writer.z = z;
        writer.w = w;
        for (int s = 0; s < materials.length; s++) if (defaults[s] != null) materials[s].applyProperties(writer);
        writer.vector = false;
    }

    /** Puts the vector {@code name} back to each own material's default. */
    public void resetVector(String name) {
        float[][] defaults = declaredVector(name);
        writer.name = name;
        writer.vector = true;
        for (int s = 0; s < materials.length; s++) {
            if (defaults[s] == null) continue;
            writer.x = defaults[s][0];
            writer.y = defaults[s][1];
            writer.z = defaults[s][2];
            writer.w = defaults[s][3];
            materials[s].applyProperties(writer);
        }
        writer.vector = false;
    }

    /** Each slot's default vector for {@code name} where its own material declares it, null where not. */
    private float[][] declaredVector(String name) {
        float[][] defaults = vectorDefaults.get(name);
        if (defaults != null) return defaults;
        defaults = new float[materials.length][];
        for (int s = 0; s < materials.length; s++) {
            if (!own[s] || materials[s] == null) continue;
            for (CgMaterialProperty p : materials[s].properties()) {
                if (p.getName().equals(name) && !p.getType().isSampler()) defaults[s] = Arrays.copyOf(p.getFloatValue(), 4);
            }
        }
        vectorDefaults.put(name, defaults);
        return defaults;
    }

    /** Each slot's default for {@code name} where its own material declares it, NaN where not: found once a name. */
    private float[] declared(String name) {
        float[] defaults = scalarDefaults.get(name);
        if (defaults != null) return defaults;
        defaults = new float[materials.length];
        Arrays.fill(defaults, Float.NaN);
        for (int s = 0; s < materials.length; s++) {
            if (!own[s] || materials[s] == null) continue;
            for (CgMaterialProperty p : materials[s].properties()) {
                if (p.getName().equals(name) && !p.getType().isSampler()) defaults[s] = p.getFloatValue()[0];
            }
        }
        scalarDefaults.put(name, defaults);
        return defaults;
    }

    /** Applies this frame's material curves: after {@code POSE}, on the client world's thread ({@link CgAnimation}). */
    void applyCurves() {
        curves.apply(this);
    }

    /** Writes one scalar or vector; reused, so setting one allocates nothing. */
    private static final class ScalarWriter implements Consumer<CgMaterialValues> {
        String name;
        float value, x, y, z, w;
        boolean vector;

        @Override
        public void accept(CgMaterialValues b) {
            if (vector) b.vec4(name, x, y, z, w);
            else b.set1f(name, value);
        }
    }

    /** How many material slots it has: one a submesh of its mesh. */
    public int materialSlots() {
        return materials.length;
    }

    /**
     * Draws every slot a second time in {@code material}, Unreal's {@code OverlayMaterial}: the inverted-hull outline's
     * home, its material pushing the hull out and culling front faces. Null for none.
     *
     * <pre>{@code
     * goku.overlay(outline).overlayMaxDistance(40f);   // every slot, culled past 40 m
     * goku.overlay(3, eyeLine);                        // slot 3's own; null falls back to the mesh's
     * }</pre>
     */
    public CgAnimInstance overlay(@Nullable CgMaterial material) {
        overlay = material;
        return this;
    }

    /** Slot {@code slot}'s overlay, Unreal's {@code MaterialSlotsOverlayMaterial}: null falls back to {@link #overlay(CgMaterial)}'s. */
    public CgAnimInstance overlay(int slot, @Nullable CgMaterial material) {
        slotOverlays[slot] = material;
        return this;
    }

    /** The overlay slot {@code slot} draws in: its own, else the mesh's; null for none. */
    @Nullable
    public CgMaterial overlay(int slot) {
        return slotOverlays[slot] != null ? slotOverlays[slot] : overlay;
    }

    /** How far from the camera its overlay draws, in metres; 0, the default, as far as the mesh draws. */
    public CgAnimInstance overlayMaxDistance(float metres) {
        if (!(metres >= 0f)) throw new IllegalArgumentException("overlay distance " + metres);
        overlayMaxDistance = metres;
        return this;
    }

    public float overlayMaxDistance() {
        return overlayMaxDistance;
    }

    /**
     * Draws it into CustomDepth too, writing {@code stencil} into the bits {@code writeMask} names: Unreal's
     * {@code bRenderCustomDepth}, {@code CustomDepthStencilValue} and {@code CustomDepthStencilWriteMask}, what outline
     * and stencil post materials read ({@code CgWorldRenderer.Draw.customDepth}).
     *
     * <pre>{@code
     * goku.customDepth(1, 0xFF);   // stencil 1, every bit
     * goku.noCustomDepth();
     * }</pre>
     */
    public CgAnimInstance customDepth(int stencil, int writeMask) {
        if (stencil < 0 || stencil > 255 || writeMask < 1 || writeMask > 255) {
            throw new IllegalArgumentException("stencil " + stencil + " under write mask " + writeMask + ": both 0 to 255, the mask at least 1");
        }
        customDepthStencil = stencil;
        customDepthMask = writeMask;
        return this;
    }

    public CgAnimInstance noCustomDepth() {
        customDepthStencil = -1;
        return this;
    }

    /** Its CustomDepth stencil value, or -1 where it is not drawn there. */
    public int customDepthStencil() {
        return customDepthStencil;
    }

    public int customDepthWriteMask() {
        return customDepthMask;
    }

    /**
     * Grows the bounds it is culled by, about their centre: Unreal's {@code BoundsScale} (SkinnedMeshComponent.cpp:2300),
     * for a pose reaching past the bind pose's bounds. 1 unless set.
     */
    public CgAnimInstance boundsScale(float scale) {
        if (!(scale >= 1f)) throw new IllegalArgumentException("bounds scale " + scale + " below 1");
        boundsScale = scale;
        return this;
    }

    /** What its draws pad their bounds by, in the world: the bind pose's radius grown by {@link #boundsScale}. */
    public float boundsPad() {
        return (boundsScale - 1f) * meshRadius * placedScale;
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

    /** Its turn and scale about where it stands. */
    public Matrix4fc transform() {
        return transform;
    }

    /**
     * The socket named {@code name}, as Unreal finds one (USkinnedMeshComponent::GetSocketTransform,
     * SkinnedMeshComponent.cpp:3525-3587): its mesh's socket of that name, else the bone of that name, ignoring case;
     * {@link #NO_SOCKET} for neither, which stands where the instance does. Look it up once and keep it.
     *
     * <pre>{@code
     * int hand = kurama.socket("hand_r");
     * kurama.socket(hand, point, turn);       // this frame's, once POSE has run
     * }</pre>
     */
    public int socket(String name) {
        List<CgSocket> sockets = mesh.sockets();
        for (int i = 0; i < sockets.size(); i++) if (sockets.get(i).name().equalsIgnoreCase(name)) return SOCKET | i;
        int bone = mesh.skeleton().indexOfIgnoringCase(name);
        return bone >= 0 ? bone : NO_SOCKET;
    }

    /**
     * Socket {@code socket}'s absolute place into {@code point} and its turn into {@code turn} (either may be null),
     * from the pose {@link #evaluate()} last made: {@code where it stands × bone × socket}.
     */
    public void socket(int socket, @Nullable double[] point, @Nullable Quaternionf turn) {
        Matrix4f m = socketMatrix(socket);
        if (point != null) {
            point[0] = x + m.m30();
            point[1] = y + m.m31();
            point[2] = z + m.m32();
        }
        // Unnormalized: the placement scales the columns, which getNormalizedRotation assumes unit.
        if (turn != null) m.getUnnormalizedRotation(turn);
    }

    /**
     * Socket {@code socket}'s world scale into {@code out}, from the same pose: the scale of Unreal's
     * {@code GetSocketTransform}, which a cue placed with {@code scale: inherit} spawns at.
     */
    public Vector3f socketScale(int socket, Vector3f out) {
        return socketMatrix(socket).getScale(out);
    }

    private Matrix4f socketMatrix(int socket) {
        return socketMatrix(socket, socketScratch, relativeScratch);
    }

    /** Where it stands × bone × socket into {@code out}, relative to {@code (x, y, z)}; {@code scratch} for the socket's own. */
    private Matrix4f socketMatrix(int socket, Matrix4f out, Matrix4f scratch) {
        Matrix4f m = out.set(transform);
        if (socket != NO_SOCKET) {
            if ((socket & SOCKET) != 0) {
                CgSocket s = mesh.sockets().get(socket & ~SOCKET);
                m.mul(pose.component(mesh.skeleton().indexOfIgnoringCase(s.bone()))).mul(s.relative(scratch));
            } else {
                m.mul(pose.component(socket));
            }
        }
        return m;
    }

    // ---- attached to another instance

    /**
     * Attaches it to {@code parent}'s socket: from the next {@code POSE} it stands there, posed after the parent, so it
     * follows the socket the frame the parent moves. Unreal's {@code AttachToComponent} keeping the relative transform:
     * {@link #place} now sets the offset, turn and scale in the socket's space, identity until set. For what hangs from
     * a body's bone and is drawn as its own mesh: hair, a tail, a hat.
     *
     * <pre>{@code
     * CgAnimInstance hair = CgAnimation.client().add(new CgAnimInstance(hairMesh));
     * hair.attachTo(body, "head");                     // a socket of the body's mesh, else its bone, ignoring case
     * hair.place(0, 0.1, 0, new Matrix4f());           // optional: a tenth of a block up the head bone
     * // each frame before POSE: strand.rotation(REPLACE, PARENT, sway), strand = hair.write("strand_0") kept from setup
     * // in SUBMIT: world.draw(hair);
     * }</pre>
     *
     * <ul>
     *   <li>{@code socket} null attaches to where the parent stands. A name the parent's mesh has neither as a socket
     *       nor a bone is refused, never placed at the parent's feet.</li>
     *   <li>Attaching to itself or to anything attached below it is refused.</li>
     *   <li>Both are posed by {@code CgAnimation}; a parent posed by nothing is read as it last stood.</li>
     *   <li>Hiding the parent's bone does not hide it: hide it from its owner by its own
     *       {@code hideFromOwner(root, Hide.BRANCH)}, as Unreal's attached components keep their own visibility.</li>
     * </ul>
     */
    public CgAnimInstance attachTo(CgAnimInstance parent, @Nullable String socket) {
        Objects.requireNonNull(parent, "parent");
        for (CgAnimInstance p = parent; p != null; p = p.parent) {
            if (p == this) throw new IllegalArgumentException(this + " cannot attach to " + parent + ": it would attach to itself");
        }
        int handle = NO_SOCKET;
        if (socket != null) {
            handle = parent.socket(socket);
            if (handle == NO_SOCKET) throw new IllegalArgumentException(parent.mesh + " has no socket or bone '" + socket + "'");
        }
        if (this.parent == null) {
            relX = relY = relZ = 0;
            relative.identity();
        }
        this.parent = parent;
        this.parentSocket = handle;
        return this;
    }

    /** Stops following its parent, standing where it last stood: Unreal's {@code KeepWorldTransform} detach. */
    public CgAnimInstance detach() {
        parent = null;
        parentSocket = NO_SOCKET;
        depth = 0;
        return this;
    }

    /** What it is attached to, or null. */
    @Nullable
    public CgAnimInstance attachParent() {
        return parent;
    }

    /** Recomputes {@link #depth} from its parents. */
    int depthFromParents() {
        int d = 0;
        for (CgAnimInstance p = parent; p != null; p = p.parent) d++;
        return depth = d;
    }

    /**
     * Stands it at its parent's socket, as posed, times its relative transform: Unreal's
     * {@code CalcNewComponentToWorld_GeneralCase}. {@code CgAnimation} calls it once the parent is evaluated.
     */
    void follow() {
        CgAnimInstance p = parent;
        if (p == null) return;
        Matrix4f m = p.socketMatrix(parentSocket, followScratch, followRelative);
        double ox = m.m30(), oy = m.m31(), oz = m.m32();
        // The relative offset lands in the socket's space; the parent's absolute place stays in doubles.
        float ax = (float) relX, ay = (float) relY, az = (float) relZ;
        double wx = p.x + ox + m.m00() * ax + m.m10() * ay + m.m20() * az;
        double wy = p.y + oy + m.m01() * ax + m.m11() * ay + m.m21() * az;
        double wz = p.z + oz + m.m02() * ax + m.m12() * ay + m.m22() * az;
        m.setTranslation(0, 0, 0).mul(relative);
        stand(wx, wy, wz, m);
    }

    /**
     * Socket {@code name} as an attachment: what follows it rides the socket as {@code POSE} posed it this frame, and
     * stops once this instance is no longer posed. A name the mesh has neither as a socket nor a bone places where the
     * instance stands, as Unreal's {@code GetSocketTransform} answers. Its other sockets are its named points.
     *
     * <pre>{@code
     * owner.play(new Flame(look, x, y, z).follow(kurama.attachment("hand_r")));
     * }</pre>
     */
    public CgAttachment attachment(String name) {
        int handle = socket(name);
        return new CgAttachment() {
            @Override
            public boolean place(double[] point, @Nullable Quaternionf turn) {
                if (!posed) return false;
                socket(handle, point, turn);
                return true;
            }

            @Override
            public boolean point(String other, double[] out) {
                int s = socket(other);
                if (s == NO_SOCKET) return false;
                socket(s, out, null);
                return true;
            }

            @Override
            public boolean point(String other, double[] out, @Nullable Quaternionf turn) {
                int s = socket(other);
                if (!posed || s == NO_SOCKET) return false;
                socket(s, out, turn);
                return true;
            }

            @Override
            public Vector3f scale(@Nullable String other, Vector3f out) {
                int s = other == null ? handle : socket(other);
                return posed ? socketScale(s, out) : out.set(1f);
            }
        };
    }

    /** Whether {@code CgAnimation} poses it: false once removed, which ends what follows it. */
    public boolean posed() {
        return posed;
    }

    void posed(boolean posed) {
        this.posed = posed;
    }

    @Override
    public String toString() {
        return "CgAnimInstance(" + mesh + " at " + time + ")";
    }
}
