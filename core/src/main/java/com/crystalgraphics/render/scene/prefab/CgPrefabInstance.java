package com.crystalgraphics.render.scene.prefab;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.scene.component.CgMeshComponent;
import com.crystalgraphics.render.scene.component.CgPrimitiveComponent;
import com.crystalgraphics.render.scene.component.CgSceneRoot;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;

/**
 * A {@link CgPrefab} spawned into a scene as components: a {@link CgSceneRoot} a node, at its rest under its parent's,
 * and a {@link CgMeshComponent} a part, riding its node. Unreal's actor spawned from a Blueprint, its component tree
 * made from the template. Made by {@code scene.spawn(prefab)}.
 *
 * <pre>{@code
 * CgPrefabInstance lantern = scene.spawn(LANTERN).at(x, y, z).rotation(facing).spawn();
 * lantern.node("flame").scale(0.8f);                  // a node moves what rides it
 * lantern.mesh("glass").customDepth(1);               // a part is a mesh component like any other
 * lantern.remove();                                   // every component it made
 * }</pre>
 *
 * <p>Riding another, as an actor's prefab rides the actor's root:</p>
 * <pre>{@code
 * CgPrefabInstance saddle = scene.spawn(SADDLE).attach(horse, "back").spawn();
 * }</pre>
 *
 * <ul>
 *   <li>A part draws its materials with its node's values over them, one a section of its mesh.</li>
 *   <li>A node whose opacity is below {@link CgPrefab#MIN_OPACITY} spawns its parts hidden; a part's {@code cast_shadow}
 *       is its {@code castsShadow}. Any other mesh-slot setting, and a part drawing the VFX unit sphere, is refused at
 *       {@link #spawn}, naming the node.</li>
 *   <li>The client world's thread, as every component's.</li>
 * </ul>
 */
public final class CgPrefabInstance {

    private final CgScene scene;
    private final CgPrefab prefab;
    private final CgSceneRoot root;
    @Nullable
    private CgSceneRoot[] nodes;
    @Nullable
    private CgMeshComponent[] parts;

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
     * Makes and spawns its components, roots first. Answers this.
     *
     * @throws CgAssetException naming the node, where a part sets what a scene does not draw or draws the unit sphere
     * @throws IllegalStateException where it is spawned already
     */
    public CgPrefabInstance spawn() {
        if (nodes != null) throw new IllegalStateException(prefab.id() + " is spawned already");
        requireDrawable();
        root.spawn();
        int n = prefab.nodes();
        CgSceneRoot[] made = new CgSceneRoot[n];
        CgMeshComponent[] drawn = new CgMeshComponent[prefab.firstPart(n)];
        float[] rest = new float[CgPrefab.REST];
        Quaternionf turn = new Quaternionf();
        for (int i = 0; i < n; i++) {
            prefab.rest(i, rest);
            int p = prefab.parent(i);
            made[i] = scene.root().at(rest[0], rest[1], rest[2]).rotation(turn.set(rest[3], rest[4], rest[5], rest[6]))
                    .scale(rest[7], rest[8], rest[9]).attach(p < 0 ? root : made[p], null).spawn();
            boolean shown = prefab.opacity(i) >= CgPrefab.MIN_OPACITY;
            for (int k = prefab.firstPart(i); k < prefab.firstPart(i + 1); k++) {
                List<CgMaterialDefinition> materials = prefab.drawnMaterials(k);
                CgMeshComponent part = scene.mesh(prefab.partMesh(k), CgMaterial.of(materials.get(0)))
                        .materials(slot -> CgMaterial.of(materials.get(Math.min(slot, materials.size() - 1))));
                if (prefab.partSettings(k).get("cast_shadow") instanceof Boolean casts) part.castsShadow(casts);
                drawn[k] = part.visible(shown).attach(made[i], null).spawn();
            }
        }
        nodes = made;
        parts = drawn;
        return this;
    }

    /** Node {@code name}'s root: moving it moves its parts and children. Null where it has none. */
    @Nullable
    public CgSceneRoot node(String name) {
        int i = prefab.node(name);
        return i < 0 ? null : spawned()[i];
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

    /** Removes every component it made, parts first, its root last. */
    public void remove() {
        if (parts != null) for (CgMeshComponent part : parts) part.remove();
        if (nodes != null) for (int i = nodes.length - 1; i >= 0; i--) nodes[i].remove();
        root.remove();
        parts = null;
        nodes = null;
    }

    private CgSceneRoot[] spawned() {
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
