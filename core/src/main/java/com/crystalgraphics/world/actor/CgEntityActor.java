package com.crystalgraphics.world.actor;

import com.crystalgraphics.platform.world.CgEntityMovement;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.scene.component.CgPrimitiveComponent;
import com.crystalgraphics.render.scene.component.CgSceneRoot;
import com.crystalgraphics.render.scene.component.CgSkeletalMeshComponent;
import com.crystalgraphics.render.scene.prefab.CgPrefabInstance;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

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

    /** {@code component}, riding the root unless it rides something already, spawned, and removed with this actor. */
    protected final <C extends CgPrimitiveComponent<C>> C add(C component) {
        if (component.attachParent() == null) component.attach(root, null);
        if (!component.spawned()) component.spawn();
        components.add(component);
        return component;
    }

    /** Removes what it added and its prefab, the root last. */
    final void removeAll() {
        for (int i = components.size() - 1; i >= 0; i--) components.get(i).remove();
        components.clear();
        if (prefab != null) prefab.remove();
        prefab = null;
        root.remove();
    }
}
