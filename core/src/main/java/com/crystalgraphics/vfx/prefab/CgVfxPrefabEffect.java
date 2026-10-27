package com.crystalgraphics.vfx.prefab;

import com.crystalgraphics.vfx.CgVfxEffect;
import com.crystalgraphics.vfx.CgVfxFrame;
import com.crystalgraphics.world.CgAttachment;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;

/**
 * A prefab played on its own: its {@link CgVfxPrefabInstance} drawn at the effect's origin, turned by its orientation, with
 * the prefab's own look. What a sequence's {@code prefab} spawnable plays; Unreal's spawned actor.
 *
 * <pre>{@code
 * CgVfxPrefabEffect core = VFX.play(new CgVfxPrefabEffect(CORE, x, y, z).follow(hand));
 * core.instance().opacity(CORE.node("ball01"), 0.5f);
 * core.stop();                                    // gone at its next tick: a prefab has nothing in flight
 * }</pre>
 *
 * <ul>
 *   <li>It plays until stopped or killed.</li>
 *   <li>Its look is {@link CgVfxPrefab#look()}: a keyed variant's parameters are set on the effect.</li>
 * </ul>
 */
public final class CgVfxPrefabEffect extends CgVfxEffect<CgVfxPrefabEffect> {

    private final CgVfxPrefabInstance instance;
    private final Matrix4f place = new Matrix4f();

    public CgVfxPrefabEffect(CgVfxPrefab prefab, double x, double y, double z) {
        super(prefab.look(), x, y, z);
        this.instance = new CgVfxPrefabInstance(prefab);
    }

    /** Its nodes as they stand: what to key. */
    public CgVfxPrefabInstance instance() {
        return instance;
    }

    /** Its nodes placed and evaluated now, so {@link #attachment}s answer this frame's: after their tracks are applied. */
    public void evaluate() {
        instance.place(place.rotation(orientation()));
        instance.evaluate();
    }

    /**
     * Node {@code node} as an attachment, as of the last {@link #evaluate}: what another effect placed on it follows.
     * Unreal's attachment to a component of a spawned actor.
     */
    public CgAttachment attachment(int node) {
        Quaternionf turn = new Quaternionf();
        return (point, out) -> {
            if (state() == State.DEAD) return false;
            Matrix4fc m = instance.world(node);
            point[0] = originX + m.m30();
            point[1] = originY + m.m31();
            point[2] = originZ + m.m32();
            if (out != null) out.set(m.getNormalizedRotation(turn));
            return true;
        };
    }

    @Override
    protected void tick(float dt) {
        if (state() == State.STOPPING) die();
    }

    @Override
    protected void submit(CgVfxFrame frame) {
        instance.place(place.rotation(orientation()));
        instance.submit(this, frame);
    }
}
