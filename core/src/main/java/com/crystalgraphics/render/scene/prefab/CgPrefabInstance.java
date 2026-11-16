package com.crystalgraphics.render.scene.prefab;

import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.scene.component.CgMeshComponent;
import com.crystalgraphics.render.scene.component.CgPrimitiveComponent;
import com.crystalgraphics.render.scene.component.CgSceneRoot;
import com.crystalgraphics.render.scene.component.CgSkeletalMeshComponent;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A {@link CgPrefab} spawned into a scene as components: a node a {@link CgSceneRoot} at its rest under its parent's
 * (a {@link CgSkeletalMeshComponent} where it is a skeletal mesh), a {@link CgMeshComponent} a part riding its node, and
 * an effect node's VFX play following it. Unreal's actor spawned from a Blueprint, its component tree made from the
 * template. Made by {@code scene.spawn(prefab)}.
 *
 * <pre>{@code
 * CgPrefabInstance lantern = scene.spawn(LANTERN).at(x, y, z).rotation(facing).spawn();
 * lantern.node("flame").scale(0.8f);                  // a node moves what rides it
 * lantern.mesh("glass").customDepth(1);               // a part is a mesh component like any other
 * lantern.remove();                                   // every component it made, its effects stopped
 * }</pre>
 *
 * <p>A skeletal node and an effect node:</p>
 * <pre>{@code
 * CgPrefabInstance wyvern = scene.spawn(WYVERN).attach(root, null).spawn();
 * wyvern.skeletal("body").anim().play(roar);          // its own clip over the prefab's
 * wyvern.effect("flame").play();                      // a node's effect, not started at the spawn
 * }</pre>
 *
 * <ul>
 *   <li>A part draws its materials with its node's values over them, one a section of its mesh; a skeletal node its
 *       materials one a slot, the last for any slot past them.</li>
 *   <li>A node whose opacity is below {@link CgPrefab#MIN_OPACITY} spawns its parts hidden; a part's {@code cast_shadow}
 *       is its {@code castsShadow}. Any other mesh-slot setting, and a part drawing the VFX unit sphere, is refused at
 *       {@link #spawn}, naming the node.</li>
 *   <li>An effect node plays through {@link CgPrefabEffects#HOST}, the VFX engine's.</li>
 *   <li>The client world's thread, as every component's.</li>
 * </ul>
 */
public final class CgPrefabInstance {

    private final CgScene scene;
    private final CgPrefab prefab;
    private final CgSceneRoot root;
    @Nullable
    private CgPrimitiveComponent<?>[] nodes;
    @Nullable
    private CgMeshComponent[] parts;
    @Nullable
    private CgPrefabEffects.Played[] effects;

    /** {@code prefab} for {@code scene}, unspawned: what {@code scene.spawn(prefab)} answers. */
    public CgPrefabInstance(CgScene scene, CgPrefab prefab) {
        this.scene = scene;
        this.prefab = prefab;
        this.root = scene.root();
    }

    public CgPrefab prefab() {
        return prefab;
    }

    /** Where its root nodes stand: its own root, what to move or attach. */
    public CgSceneRoot root() {
        return root;
    }

    public CgPrefabInstance at(double x, double y, double z) {
        root.at(x, y, z);
        return this;
    }

    public CgPrefabInstance rotation(Quaternionfc q) {
        root.rotation(q);
        return this;
    }

    public CgPrefabInstance scale(float s) {
        root.scale(s);
        return this;
    }

    /** Its root rides {@code parent}, at {@code socket} on a skeletal one or at its origin for null. */
    public CgPrefabInstance attach(CgPrimitiveComponent<?> parent, @Nullable String socket) {
        root.attach(parent, socket);
        return this;
    }

    /**
     * Makes and spawns its components, roots first, then starts the effects its nodes start with. Answers this.
     *
     * @throws CgAssetException naming the node, where a part sets what a scene does not draw or draws the unit sphere
     * @throws IllegalStateException where it is spawned already
     */
    public CgPrefabInstance spawn() {
        if (nodes != null) throw new IllegalStateException(prefab.id() + " is spawned already");
        requireDrawable();
        root.spawn();
        int n = prefab.nodes();
        CgPrimitiveComponent<?>[] made = new CgPrimitiveComponent<?>[n];
        CgMeshComponent[] drawn = new CgMeshComponent[prefab.firstPart(n)];
        CgPrefabEffects.Played[] played = new CgPrefabEffects.Played[n];
        float[] rest = new float[CgPrefab.REST];
        Quaternionf turn = new Quaternionf();
        for (int i = 0; i < n; i++) {
            prefab.rest(i, rest);
            int p = prefab.parent(i);
            CgPrimitiveComponent<?> node = component(i, made);
            node.at(rest[0], rest[1], rest[2]).rotation(turn.set(rest[3], rest[4], rest[5], rest[6]))
                    .scale(rest[7], rest[8], rest[9]).attach(p < 0 ? root : made[p], prefab.parentSocket(i)).spawn();
            made[i] = node;
            boolean shown = prefab.opacity(i) >= CgPrefab.MIN_OPACITY;
            for (int k = prefab.firstPart(i); k < prefab.firstPart(i + 1); k++) {
                List<CgMaterialDefinition> materials = prefab.drawnMaterials(k);
                CgMeshComponent part = scene.mesh(prefab.partMesh(k), CgMaterial.of(materials.get(0)))
                        .materials(slot -> CgMaterial.of(materials.get(Math.min(slot, materials.size() - 1))));
                if (prefab.partSettings(k).get("cast_shadow") instanceof Boolean casts) part.castsShadow(casts);
                drawn[k] = part.visible(shown).attach(node, null).spawn();
            }
            CgPrefab.Effect effect = prefab.effect(i);
            if (effect != null) {
                played[i] = new CgPrefabEffects.Played(effect.effect(), node.attachment());
                if (effect.auto()) played[i].play();
            }
        }
        nodes = made;
        parts = drawn;
        effects = played;
        return this;
    }

    /** Node {@code i}'s own component, unspawned: its skeletal mesh (its leader among {@code made}), else a root. */
    private CgPrimitiveComponent<?> component(int i, CgPrimitiveComponent<?>[] made) {
        CgPrefab.Skeletal s = prefab.skeletal(i);
        if (s == null) return scene.root();
        List<CgMaterialDefinition> materials = s.materials();
        CgSkeletalMeshComponent body = scene.skeletal(s.mesh())
                .materials(slot -> CgMaterial.of(materials.get(Math.min(slot, materials.size() - 1))));
        if (s.play() != null) {
            CgAnimSequence clip = s.mesh().sequence(s.play());
            if (clip == null) throw refused(i, "plays " + s.play() + ", which its mesh has no sequence of");
            body.anim().play(clip);
        }
        if (s.followPose() != null) body.followPose((CgSkeletalMeshComponent) made[prefab.node(s.followPose())]);
        return body;
    }

    /** Node {@code name}'s component: a root, or its skeletal mesh. Moving it moves its parts and children. Null where it has none. */
    @Nullable
    public CgPrimitiveComponent<?> node(String name) {
        int i = prefab.node(name);
        return i < 0 ? null : spawned()[i];
    }

    /** Skeletal node {@code name}'s component, or null where it is none. */
    @Nullable
    public CgSkeletalMeshComponent skeletal(String name) {
        return node(name) instanceof CgSkeletalMeshComponent s ? s : null;
    }

    /** Hands {@code each} every skeletal node's component, in node order: what animates the prefab. */
    public void forEachSkeletal(Consumer<CgSkeletalMeshComponent> each) {
        for (CgPrimitiveComponent<?> node : spawned()) if (node instanceof CgSkeletalMeshComponent s) each.accept(s);
    }

    /** Node {@code name}'s effect, playing or not, or null where it plays none. */
    @Nullable
    public CgPrefabEffects.Played effect(String name) {
        int i = prefab.node(name);
        spawned();
        return i < 0 ? null : effects[i];
    }

    /** Node {@code name}'s first part, or null where it has none or draws nothing. */
    @Nullable
    public CgMeshComponent mesh(String name) {
        return mesh(name, 0);
    }

    /** Node {@code name}'s part {@code k}, in its file's order, or null. */
    @Nullable
    public CgMeshComponent mesh(String name, int k) {
        int i = prefab.node(name);
        spawned();
        if (i < 0 || k < 0 || prefab.firstPart(i) + k >= prefab.firstPart(i + 1)) return null;
        return parts[prefab.firstPart(i) + k];
    }

    /** Stops its effects and removes every component it made, parts first, its root last. */
    public void remove() {
        if (effects != null) for (CgPrefabEffects.Played e : effects) if (e != null) e.stop();
        if (parts != null) for (CgMeshComponent part : parts) part.remove();
        if (nodes != null) for (int i = nodes.length - 1; i >= 0; i--) nodes[i].remove();
        root.remove();
        effects = null;
        parts = null;
        nodes = null;
    }

    private CgPrimitiveComponent<?>[] spawned() {
        if (nodes == null) throw new IllegalStateException(prefab.id() + " is not spawned");
        return nodes;
    }

    private void requireDrawable() {
        for (int i = 0; i < prefab.nodes(); i++) {
            for (int k = prefab.firstPart(i); k < prefab.firstPart(i + 1); k++) {
                for (Map.Entry<String, Object> s : prefab.partSettings(k).entrySet()) {
                    if (!s.getKey().equals("cast_shadow") && !Boolean.FALSE.equals(s.getValue())) {
                        throw refused(i, "sets " + s.getKey() + " on a mesh, which a scene's mesh component does not draw");
                    }
                }
                // A path's mesh is a file: only a part with neither path nor mesh draws the sphere
                if (prefab.partPath(k) == null && prefab.partMesh(k) == null) {
                    throw refused(i, "draws the VFX unit sphere, which a scene has none of");
                }
            }
        }
    }

    private CgAssetException refused(int node, String what) {
        return new CgAssetException("scene.spawn: " + prefab.id() + "'s node " + prefab.name(node) + " " + what);
    }
}
