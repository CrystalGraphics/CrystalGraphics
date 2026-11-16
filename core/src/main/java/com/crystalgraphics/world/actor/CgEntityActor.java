package com.crystalgraphics.world.actor;

import com.crystalgraphics.animation.CgAnimInstance;
import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.asset.kind.CgSequenceKinds;
import com.crystalgraphics.asset.load.CgVfxAssets;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.net.object.CgNetObject;
import com.crystalgraphics.platform.world.CgEntityMovement;
import com.crystalgraphics.sequence.CgSequencePlayer;
import com.crystalgraphics.sequence.CgSequenceTarget;
import com.crystalgraphics.sequence.CgSequencer;
import com.crystalgraphics.sequence.animation.CgSequenceSkeletal;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;
import com.crystalgraphics.world.CgEntityProperties;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.scene.component.CgPrimitiveComponent;
import com.crystalgraphics.render.scene.component.CgSceneRoot;
import com.crystalgraphics.render.scene.component.CgSkeletalMeshComponent;
import com.crystalgraphics.render.scene.prefab.CgPrefabInstance;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What draws a host entity, Unreal's {@code AActor} bound to it: components made in {@link #onSpawn}, changed in
 * {@link #tick}, removed with it. Its root follows the entity's interpolated pose each frame before animation poses,
 * so every component {@linkplain #add added} moves with it. Its code is behaviour; what it draws is components.
 *
 * <pre>{@code
 * public final class DragonActor extends CgEntityActor {
 *     private CgSkeletalMeshComponent body;
 *     private boolean wasCharging;
 *
 *     @Override protected void onSpawn(CgEntity dragon) {
 *         body = add(scene().skeletal(DRAGON_MESH).materials(DRAGON_SKINS::get).customDepth(1));
 *         body.anim().play(IDLE);
 *     }
 *
 *     @Override protected void tick(CgEntity dragon, float dt) {
 *         boolean charging = dragon.flag(CgEntityQuery.SPRINTING);
 *         if (charging != wasCharging) body.anim().play(charging ? CHARGE : IDLE);
 *         body.scalar("Glow", charging ? 2f : 0f);
 *         wasCharging = charging;
 *     }
 * }
 *
 * CgEntityActors.register("mymod:dragon", DragonActor::new);   // once, at load
 * }</pre>
 *
 * <ul>
 *   <li>{@code add} attaches a component to the root where it rides nothing yet, and spawns it: attach it elsewhere
 *       first (a socket of another) to keep that. Its placement is kept as its offset from the root, given before
 *       {@code add} or after:
 *       <pre>{@code
 * add(scene().mesh(SADDLE, LEATHER).at(0, 1.4, -0.2));       // built placed
 * add(scene().mesh(HORN, BONE)).at(0, 2.1, 0.8).scale(0.5f);  // placed after
 *       }</pre></li>
 *   <li>The root stands at the entity's feet, turned by its body yaw ({@code -bodyYaw} about up): +z, a glTF mesh's
 *       front, faces the entity's facing.</li>
 *   <li>Its side's game thread. On a client {@code tick} runs in {@code PLAYS}, before {@code POSE}; on a server in
 *       {@code GAMEPLAY}, each tick.</li>
 * </ul>
 *
 * <p><b>Both sides.</b> A registered type's actor spawns on the server too, for each entity of the type in the server's
 * levels, with the same code: there its components live in {@link CgScene#server()}, never drawn, its skeletal meshes
 * posed each tick for their bones, sockets and root motion. {@link #server()} tells the two apart where they differ.</p>
 *
 * <p><b>Root motion moves its entity</b>, as Unreal's character is moved by its mesh's: name the body whose montages
 * carry it. The server's actor moves its entity, swept against blocks, then turns it; on a client only the local
 * player's actor moves its entity, at once, and any other follows its server.</p>
 * <pre>{@code
 * @Override protected void onSpawn(CgEntity fighter) {
 *     body = rootMotion(add(scene().skeletal(FIGHTER).materials(SKINS::get)));
 * }
 * }</pre>
 *
 * <p>An actor registered with a prefab has it spawned on its root before {@code onSpawn}; its code keeps only
 * behaviour:</p>
 * <pre>{@code
 * CgEntityActors.register("mymod:wyvern", WyvernActor::new).prefab("mymod:wyvern");
 *
 * @Override protected void onSpawn(CgEntity wyvern) {
 *     wings = prefab().mesh("wings");                       // a node's part, a mesh component like any other
 *     prefab().node("jaw").rotation(open);                  // a node: what rides it follows
 * }
 * }</pre>
 */
public abstract class CgEntityActor {

    private final List<CgPrimitiveComponent<?>> components = new ArrayList<>();
    CgEntity entity;
    CgSceneRoot root;
    @Nullable
    CgPrefabInstance prefab;
    boolean server;
    /** The host's entity, where its side has it: the server's always, a client's looked up when it moves. */
    @Nullable
    Object host;
    @Nullable
    private CgSkeletalMeshComponent rootMotion;
    private final Matrix4f step = new Matrix4f(), toWorld = new Matrix4f();
    private final Vector3f t = new Vector3f();
    private final Quaternionf q = new Quaternionf();
    /** The reaction playing, until it ends or the next replaces it. */
    @Nullable
    private CgSequencePlayer reacting;
    /** Its body's grip sockets, found once for the body they were found on. */
    @Nullable
    private CgSkeletalMeshComponent gripBody;
    private int rightGrip, leftGrip;
    private final Matrix4d grip = new Matrix4d();
    /** What its reactions' rules read its entity through; made with its first reaction. */
    @Nullable
    private CgReaction.Probe probe;

    /** The entity appeared on this side: make its components. */
    protected abstract void onSpawn(CgEntity entity);

    /** Each frame, its root already where the entity is: change its components. Draws nothing itself. */
    protected void tick(CgEntity entity, float dt) {
    }

    /** The entity left: anything beyond its components to let go of. Its components are removed after. */
    protected void onRemove(CgEntity entity) {
    }

    /** The scene its components live in: the client's, or on a server {@link CgScene#server()}, never drawn. */
    protected final CgScene scene() {
        return server ? CgScene.server() : CgScene.client();
    }

    /** Whether it is the server's actor of its entity rather than a client's. */
    protected final boolean server() {
        return server;
    }

    /**
     * Has {@code body}'s montages' root motion move its entity from now on (Unreal's character movement), and answers
     * it. Its component's world transform puts the clip's motion in the world, so a body scaled or lifted under the
     * root moves by what is drawn.
     *
     * <ul>
     *   <li>The entity's own movement still runs (its AI, its gravity): a mod holds its AI still while a move plays.</li>
     *   <li>A client's actor of any entity but its own player gives its root motion up: its server's moves it.</li>
     * </ul>
     */
    protected final <C extends CgSkeletalMeshComponent> C rootMotion(C body) {
        this.rootMotion = body;
        return body;
    }

    /**
     * This tick's root motion, taken from the body; where {@code moves}, put in the world by the body's transform and
     * the entity moved by it, then turned. Taken either way, so a copy not moving its entity never carries one over.
     */
    final void move(boolean moves) {
        CgSkeletalMeshComponent body = rootMotion;
        if (body == null || !body.anim().montages().consumeRootMotion(1f, step) || !moves || host == null) return;
        float yaw = CgEntityMovement.yaw(host);
        if (Float.isNaN(yaw)) return;
        step.getTranslation(t);
        toWorld.set(body.worldTransform()).transformDirection(t);
        if (t.x != 0f || t.y != 0f || t.z != 0f) CgEntityMovement.move(host, t.x, t.y, t.z);
        // the turn after the move, as the clip's root turned (ours turns toward +x, Minecraft's yaw toward -x)
        step.getNormalizedRotation(q);
        float turn = (float) (2 * Math.atan2(q.y, q.w));
        if (turn != 0f) CgEntityMovement.turn(host, yaw - (float) Math.toDegrees(turn));
    }

    /**
     * The server's actor's entity, the loader's own object: what a server-side query or movement takes
     * ({@code CgEntityQueries.of(serverEntity())}, {@code CgEntityMovement.rotate(serverEntity(), yaw, 0f)}). Null on a client.
     */
    @Nullable
    protected final Object serverEntity() {
        return server ? host : null;
    }

    /**
     * Its entity, the loader's own object on this side: the server's, or the client's copy looked up by id; null where
     * the client has none loaded. What a move it plays holds ({@code crystalgraphics:move_lock}).
     */
    @Nullable
    public final Object hostEntity() {
        return entity.entity();
    }

    /** Its root: where the entity stands this frame. */
    public final CgSceneRoot root() {
        return root;
    }

    /** Its registration's prefab, spawned on its root; null where it names none. */
    @Nullable
    protected final CgPrefabInstance prefab() {
        return prefab;
    }

    /** The entity it draws. */
    public final CgEntity entity() {
        return entity;
    }

    /**
     * {@code component}, riding the root unless it rides something already, spawned, and removed with this actor. A
     * skeletal mesh's graph has its {@code entity} variables set from the entity every frame.
     */
    protected final <C extends CgPrimitiveComponent<C>> C add(C component) {
        if (component.attachParent() == null) component.attach(root, null);
        if (!component.spawned()) component.spawn();
        components.add(component);
        if (component instanceof CgSkeletalMeshComponent body) feed(body);
        return component;
    }

    /** Has {@code body}'s graph's {@code entity} bindings set from this actor's entity, each frame before it runs. */
    final void feed(CgSkeletalMeshComponent body) {
        body.anim().onUpdate(new EntityFeed(entity));
    }

    /**
     * Its body: the root-motion body where it names one, else the first skeletal mesh it added, else its prefab's
     * first; null where it has none. What its registration's graph runs on and its reactions play on.
     */
    @Nullable
    public final CgSkeletalMeshComponent body() {
        if (rootMotion != null) return rootMotion;
        for (CgPrimitiveComponent<?> c : components) if (c instanceof CgSkeletalMeshComponent s) return s;
        if (prefab == null) return null;
        CgSkeletalMeshComponent[] first = {null};
        prefab.forEachSkeletal(s -> {
            if (first[0] == null) first[0] = s;
        });
        return first[0];
    }

    /**
     * Where its body holds {@code hand}'s item this frame (the entity's {@code CgEntityProperties.MAIN} or {@code OFF}):
     * the grip socket's transform into {@code out}, in blocks, from the entity's feet where the host draws it, unturned.
     * The right arm holds the main hand unless the entity is left-handed. A grip socket is {@code right_hand_item} and
     * {@code left_hand_item}, else the hand's own, {@code right_hand} and {@code left_hand}.
     *
     * @return {@link CgEntityActors#RIGHT} or {@link CgEntityActors#LEFT}, the arm holding it; {@link CgEntityActors#NO_GRIP}
     *         where it has no body or the body no such socket
     */
    final int grip(int hand, Matrix4f out) {
        CgSkeletalMeshComponent body = body();
        if (body == null) return CgEntityActors.NO_GRIP;
        CgAnimInstance anim = body.anim();
        if (body != gripBody) {
            gripBody = body;
            rightGrip = socket(anim, "right_hand_item", "right_hand");
            leftGrip = socket(anim, "left_hand_item", "left_hand");
        }
        CgNetObject motion = entity.properties(CgEntityProperties.MOTION);
        boolean leftHanded = motion != null && motion.getBool(CgEntityProperties.LEFT_HANDED);
        boolean right = (hand == CgEntityProperties.MAIN) != leftHanded;
        int socket = right ? rightGrip : leftGrip;
        if (socket == CgAnimInstance.NO_SOCKET) return CgEntityActors.NO_GRIP;
        anim.socket(socket, out);
        grip.set(body.worldTransform()).mul(out.m00(), out.m01(), out.m02(), out.m03(), out.m10(), out.m11(), out.m12(), out.m13(),
                out.m20(), out.m21(), out.m22(), out.m23(), out.m30(), out.m31(), out.m32(), out.m33(), grip);
        grip.translateLocal(-entity.x(), -entity.y(), -entity.z());
        out.set(grip);
        return right ? CgEntityActors.RIGHT : CgEntityActors.LEFT;
    }

    private static int socket(CgAnimInstance anim, String grip, String hand) {
        int s = anim.socket(grip);
        return s != CgAnimInstance.NO_SOCKET ? s : anim.socket(hand);
    }

    /**
     * Each frame before {@code tick}: the first reaction whose event fired this frame, and whose rule holds, plays,
     * stopping the one before; a reaction that ended is let go.
     */
    final void react(List<CgReaction> reactions) {
        if (reacting != null && (reacting.finished() || reacting.stopped())) {
            reacting.stop();
            reacting = null;
        }
        if (reactions.isEmpty()) return;
        CgNetObject motion = entity.properties(CgEntityProperties.MOTION);
        if (motion == null) return;
        if (probe == null) probe = new CgReaction.Probe(entity);
        for (int i = 0; i < reactions.size(); i++) {
            CgReaction r = reactions.get(i);
            if (!r.fires(motion, probe)) continue;
            play(r);
            return;
        }
    }

    private void play(CgReaction r) {
        CgSkeletalMeshComponent body = body();
        if (body == null) throw new IllegalStateException("no body to play " + r.sequence() + " on: its prefab has no skeletal mesh");
        CgSequenceDefinition d = CgVfxAssets.<CgSequenceDefinition>ref(CgSequenceKinds.SEQUENCE, r.sequence()).get();
        if (d == null) throw new IllegalStateException("its reaction's sequence " + r.sequence() + " did not load");
        CgWorld world = server ? CgWorld.serverWorld() : CgWorld.client();
        CgSequencer sequencer = CgSequencer.of(world);
        if (sequencer == null) sequencer = world.add(new CgSequencer());
        Reacting target = new Reacting(this, body, r.slot());
        Map<String, CgSequenceTarget> roles = new HashMap<>();
        for (CgSequenceDefinition.Role role : d.roles()) roles.put(role.name(), target);
        if (reacting != null) reacting.stop();
        reacting = sequencer.play(d, roles);
    }

    /** The body as a reaction's every role: its clips by id, every section on the reaction's slot. */
    private record Reacting(CgEntityActor actor, CgSkeletalMeshComponent body, String slot) implements CgSequenceSkeletal {
        @Override @Nullable public Object entity() {
            return actor.hostEntity();
        }

        @Override public CgAnimInstance instance() {
            return body.anim();
        }

        @Override @Nullable public CgAnimSequence clip(String id) {
            CgAnimSequence clip = body.mesh().sequence(id);
            return clip != null ? clip : body.mesh().sequence(id.substring(Math.max(id.lastIndexOf('/'), id.lastIndexOf(':')) + 1));
        }

        @Override public String montageSlot() {
            return slot;
        }

        @Override public int slot(String name) {
            return -1;
        }

        @Override public void set(int slot, float[] values, int mask) {
        }

        @Override public void get(int slot, float[] out) {
        }
    }

    /** Removes what it added and its prefab, the root last. */
    final void removeAll() {
        if (reacting != null) reacting.stop();
        reacting = null;
        for (int i = components.size() - 1; i >= 0; i--) components.get(i).remove();
        components.clear();
        if (prefab != null) prefab.remove();
        prefab = null;
        root.remove();
    }
}
