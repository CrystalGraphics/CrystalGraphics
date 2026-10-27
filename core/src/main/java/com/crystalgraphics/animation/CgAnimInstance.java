package com.crystalgraphics.animation;

import com.crystalgraphics.api.animation.CgAnimSection;
import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.api.animation.CgPose;
import com.crystalgraphics.api.animation.CgSocket;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgSkeletalMesh;
import com.crystalgraphics.gl.buffer.CgFrameRing;
import com.crystalgraphics.shape.bounds.CgAABB;
import com.crystalgraphics.world.CgAttachment;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

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
    private long paletteFrame = -1;
    private boolean posed;
    private final List<CgAnimSection> sections = new ArrayList<>();
    private final CgPose pose;
    private final Matrix4f[] skinning;
    private CgPose[] scratch = new CgPose[0];
    private float[] weights = new float[0];
    /** This frame's slot entries, cleared by {@link #evaluate()}: Unreal's montage slot. */
    private CgAnimSequence[] slotClips = new CgAnimSequence[0];
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

    public CgAnimInstance(CgSkeletalMesh mesh) {
        this.mesh = Objects.requireNonNull(mesh, "mesh");
        this.materials = new CgMaterial[mesh.mesh().submeshCount()];
        CgAABB box = mesh.mesh().bounds(new CgAABB());
        this.meshRadius = (float) Math.sqrt(box.halfX() * box.halfX() + box.halfY() * box.halfY() + box.halfZ() * box.halfZ());
        this.pose = new CgPose(mesh.skeleton());
        this.skinning = new Matrix4f[mesh.skeleton().boneCount()];
        for (int b = 0; b < skinning.length; b++) skinning[b] = new Matrix4f();
        pose.resolve();
        pose.skinning(mesh, skinning);
        reserve();
    }

    public CgSkeletalMesh mesh() {
        return mesh;
    }

    /** Adds a section to its timeline. */
    public CgAnimInstance play(CgAnimSection section) {
        sections.add(Objects.requireNonNull(section, "section"));
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
        if (slotCount == slotClips.length) {
            int n = Math.max(2, slotCount * 2);
            slotClips = Arrays.copyOf(slotClips, n);
            slotTimes = Arrays.copyOf(slotTimes, n);
            slotWeights = Arrays.copyOf(slotWeights, n);
        }
        slotClips[slotCount] = Objects.requireNonNull(clip, "clip");
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
    }

    public CgAnimInstance remove(CgAnimSection section) {
        sections.remove(section);
        return this;
    }

    /** Takes every section off its timeline, leaving its time. */
    public CgAnimInstance clear() {
        sections.clear();
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
                section.sequence().sample(scratch[n].rest(), section.animationTime(time));
                weights[n++] = w;
                total += w;
            }
            if (n == 0) {
                scratch[0].rest();
                weights[n++] = source;
            } else {
                for (int i = 0; i < n; i++) weights[i] = weights[i] / total * source;
            }
        }
        float scale = slotTotal > 1f ? 1f / slotTotal : 1f;
        for (int i = 0; i < slotCount; i++) {
            if (slotWeights[i] <= 0f) continue;
            slotClips[i].sample(scratch[n].rest(), slotTimes[i]);
            weights[n++] = slotWeights[i] * scale;
        }
        Arrays.fill(slotClips, 0, slotCount, null);
        slotCount = 0;
        if (n == 0) {
            pose.rest();
        } else {
            pose.blend(scratch, weights, n);
        }
        pose.resolve();
        pose.skinning(mesh, skinning);
        return this;
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

    void palette(int base) {
        palette = base;
        paletteFrame = CgFrameRing.frame();
    }

    // ---- where it stands, and its sockets

    /** Stands it at absolute {@code (x, y, z)}, unturned and unscaled. */
    public CgAnimInstance place(double x, double y, double z) {
        return place(x, y, z, IDENTITY);
    }

    /**
     * Stands it at absolute {@code (x, y, z)}, turned and scaled by {@code transform} about that point: Unreal's
     * component-to-world. Where it is drawn ({@code CgWorldRenderer.draw(instance, material)}) and where its sockets are.
     */
    public CgAnimInstance place(double x, double y, double z, Matrix4fc transform) {
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
        return this;
    }

    /** Submesh {@code slot}'s material, or null where none is set. */
    @Nullable
    public CgMaterial material(int slot) {
        return materials[slot];
    }

    /** How many material slots it has: one a submesh of its mesh. */
    public int materialSlots() {
        return materials.length;
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
        Matrix4f m = socketScratch.set(transform);
        if (socket != NO_SOCKET) {
            if ((socket & SOCKET) != 0) {
                CgSocket s = mesh.sockets().get(socket & ~SOCKET);
                m.mul(pose.component(mesh.skeleton().indexOfIgnoringCase(s.bone()))).mul(s.relative(relativeScratch));
            } else {
                m.mul(pose.component(socket));
            }
        }
        if (point != null) {
            point[0] = x + m.m30();
            point[1] = y + m.m31();
            point[2] = z + m.m32();
        }
        // Unnormalized: the placement scales the columns, which getNormalizedRotation assumes unit.
        if (turn != null) m.getUnnormalizedRotation(turn);
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
