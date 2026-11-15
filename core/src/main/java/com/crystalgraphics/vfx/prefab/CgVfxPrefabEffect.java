package com.crystalgraphics.vfx.prefab;

import com.crystalgraphics.render.scene.prefab.CgPrefab;
import com.crystalgraphics.render.scene.prefab.CgPrefabPose;
import com.crystalgraphics.vfx.CgVfxEffect;
import com.crystalgraphics.vfx.CgVfxFrame;
import com.crystalgraphics.vfx.look.CgVfxParam;
import com.crystalgraphics.world.CgAttachment;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;

/**
 * A prefab played as an effect: its {@link CgPrefabPose} drawn at the effect's origin, turned by its orientation, through
 * its {@link CgVfxPrefabLook}. What a sequence's {@code prefab} spawnable plays; Unreal's spawned actor, its parts sorting
 * with the play's systems.
 *
 * <pre>{@code
 * CgVfxPrefabEffect core = VFX.play(new CgVfxPrefabEffect(CORE, x, y, z).follow(hand));
 * core.pose().opacity(CORE.node("ball01"), 0.5f);
 * core.stop();                                    // gone at its next tick: a prefab has nothing in flight
 * }</pre>
 *
 * <ul>
 *   <li>It plays until stopped or killed.</li>
 *   <li>A keyed look's parameters are set on the effect, each at its rest to begin with.</li>
 *   <li>A node below {@link CgPrefab#MIN_OPACITY} draws nothing.</li>
 * </ul>
 */
public final class CgVfxPrefabEffect extends CgVfxEffect<CgVfxPrefabEffect> {

    private final CgVfxPrefabLook prefabLook;
    private final CgPrefabPose pose;
    private final Matrix4f place = new Matrix4f(), turn = new Matrix4f();

    /** {@code prefab} with its own look. */
    public CgVfxPrefabEffect(CgPrefab prefab, double x, double y, double z) {
        this(CgVfxPrefabLook.of(prefab), x, y, z);
    }

    /** A prefab with {@code look}, a {@link CgVfxPrefabLook#keyed} variant where a sequence keys its values. */
    public CgVfxPrefabEffect(CgVfxPrefabLook look, double x, double y, double z) {
        super(look.look(), x, y, z);
        this.prefabLook = look;
        CgPrefab prefab = look.prefab();
        this.pose = new CgPrefabPose(prefab);
        // A keyed vector's rest: a colour parameter declares none of its own.
        for (int n = 0; n < prefab.nodes(); n++) {
            for (String property : look.keyedNames(n)) {
                if (look.param(n, property) instanceof CgVfxParam.Color c) {
                    float[] r = look.keyedRest(n, property);
                    set(c, r[0], r.length > 1 ? r[1] : 0f, r.length > 2 ? r[2] : 0f, r.length > 3 ? r[3] : 0f);
                }
            }
        }
    }

    /** Its nodes as they stand: what to key. */
    public CgPrefabPose pose() {
        return pose;
    }

    /** What it draws with. */
    public CgVfxPrefabLook prefabLook() {
        return prefabLook;
    }

    /** Its nodes placed and evaluated now, so {@link #attachment}s answer this frame's: after their tracks are applied. */
    public void evaluate() {
        pose.place(place.rotation(orientation())).evaluate();
    }

    /**
     * Node {@code node} as an attachment, as of the last {@link #evaluate}: what another effect placed on it follows.
     * Unreal's attachment to a component of a spawned actor.
     */
    public CgAttachment attachment(int node) {
        Quaternionf q = new Quaternionf();
        return (point, out) -> {
            if (state() == State.DEAD) return false;
            Matrix4fc m = pose.world(node);
            point[0] = originX + m.m30();
            point[1] = originY + m.m31();
            point[2] = originZ + m.m32();
            if (out != null) out.set(m.getNormalizedRotation(q));
            return true;
        };
    }

    @Override
    protected void tick(float dt) {
        if (state() == State.STOPPING) die();
    }

    @Override
    protected void submit(CgVfxFrame frame) {
        evaluate();
        CgPrefab prefab = pose.prefab();
        for (int i = 0; i < prefab.nodes(); i++) {
            int first = prefab.firstPart(i), end = prefab.firstPart(i + 1);
            float presence = pose.opacity(i);
            if (first == end || presence < CgPrefab.MIN_OPACITY) continue;
            Matrix4fc m = pose.world(i);
            turn.set(m).setTranslation(0f, 0f, 0f);
            for (int k = first; k < end; k++) {
                frame.mesh(this, prefabLook.partSlot(k), prefab.partMesh(k), m.m30(), m.m31(), m.m32(), turn, presence,
                        0f, 0f, 0f, 0f);
            }
        }
    }
}
