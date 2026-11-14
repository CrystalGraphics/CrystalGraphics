package com.crystalgraphics.world.actor;

import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.scene.component.CgPrimitiveComponent;
import com.crystalgraphics.render.scene.component.CgSceneRoot;

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
 *       first (a socket of another) to keep that.</li>
 *   <li>The root stands at the entity's feet, turned by its body yaw as Minecraft turns a living model
 *       ({@code 180 - bodyYaw} about up); offset a component with {@code at}, relative to the root.</li>
 *   <li>The client world's thread. {@code tick} runs in {@code PLAYS}, before {@code POSE}.</li>
 * </ul>
 */
public abstract class CgEntityActor {

    private final List<CgPrimitiveComponent<?>> components = new ArrayList<>();
    CgEntity entity;
    CgSceneRoot root;

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

    /** Removes what it added, the root last. */
    final void removeAll() {
        for (int i = components.size() - 1; i >= 0; i--) components.get(i).remove();
        components.clear();
        root.remove();
    }
}
