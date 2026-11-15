package com.crystalgraphics.world.actor;

import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.scene.component.CgPrimitiveComponent;
import com.crystalgraphics.render.scene.component.CgSceneRoot;
import com.crystalgraphics.render.scene.prefab.CgPrefabInstance;

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
 *         body.anim().play(CgAnimSection.of(IDLE).loop().build());
 *     }
 *
 *     @Override protected void tick(CgEntity dragon, float dt) {
 *         boolean charging = dragon.flag(CgEntityQuery.SPRINTING);
 *         if (charging && !wasCharging) body.anim().play(CgAnimSection.of(CHARGE).at(body.anim().time()).build());
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
 *   <li>The client world's thread. {@code tick} runs in {@code PLAYS}, before {@code POSE}.</li>
 * </ul>
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

    /** The entity appeared on this client: make its components. */
    protected abstract void onSpawn(CgEntity entity);

    /** Each frame, its root already where the entity is: change its components. Draws nothing itself. */
    protected void tick(CgEntity entity, float dt) {
    }

    /** The entity left: anything beyond its components to let go of. Its components are removed after. */
    protected void onRemove(CgEntity entity) {
    }

    /** The scene its components live in: the client's. */
    protected final CgScene scene() {
        return CgScene.client();
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
