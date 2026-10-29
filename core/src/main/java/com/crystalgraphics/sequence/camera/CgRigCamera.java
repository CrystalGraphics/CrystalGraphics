package com.crystalgraphics.sequence.camera;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Quaternionf;
import org.joml.Vector3d;

import com.crystalgraphics.asset.camera.CgCameraRigDefinition;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.camera.CgCameraContext;
import com.crystalgraphics.camera.CgCameraDirector;
import com.crystalgraphics.camera.CgCameraRig;
import com.crystalgraphics.object.value.CgUnits;
import com.crystalgraphics.sequence.CgSequencePlayer;
import com.crystalgraphics.sequence.definition.CgSequenceBinding;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;
import com.crystalgraphics.sequence.spawn.CgSequenceSpawned;
import com.crystalgraphics.world.CgAttachment;

/**
 * What a sequence's {@code camera: { rig: ... }} spawnable plays: Gameplay Cameras' {@code UGameplayCameraRigComponent}.
 * Its rig runs in a camera context of its own, whose roles are its play's and whose pose is its place; its
 * {@code property} tracks key the rig's parameters, {@code rig.<name>}. A {@code camera_cut} onto it pushes the context
 * on the viewer's camera system, and the rig blends in and out by its own transitions.
 *
 * <pre>{@code
 * # a sequence
 * spawnables:
 *   - { id: cam, name: skill_camera, camera: { rig: mymod:skill_camera } }
 * tracks:
 *   - { id: fov, kind: property, binding: { camera: cam }, property: rig.fov,
 *       sections: [ { id: s, range: [0, 6000], value: { keys: [[0, 60]] } } ] }
 *   - { id: cut, kind: camera_cut, sections: [ { id: c, range: [0, 6000], camera: { camera: cam } } ] }
 * }</pre>
 *
 * <ul>
 *   <li>A track keys {@code rig.<name>} in the parameter's own quantity, in the sequence's units, converted as it
 *       compiles. A name the rig does not declare fails at compile, naming it.</li>
 *   <li>A rotation is keyed as the sequence writes a rotation (Unreal's roll, pitch and yaw in a kit).</li>
 *   <li>Its context's pose is its place {@code at}, every frame; with no {@code at}, the sequence's origin. A rig's
 *       {@code owning_context} values are in it.</li>
 * </ul>
 */
public final class CgRigCamera implements CgSequenceSpawned {

    private final CgCameraRig rig;
    private final CgCameraContext context;
    private final CgAttachment at;
    private final CgUnits units;
    /** Each parameter's components as last set: a vector's in host units, a rotation's as written, a number in [0]. */
    private final float[][] values;
    private final double[] point = new double[3];
    private final Quaternionf turn = new Quaternionf();

    private CgRigCamera(CgSequencePlayer player, CgCameraRigDefinition definition, CgAttachment at) {
        CgSequenceDefinition d = player.definition();
        this.rig = new CgCameraRig(definition);
        this.at = at;
        this.units = d.units();
        Map<String, CgAttachment> roles = new HashMap<>();
        for (int i = 0; i < d.roles().size(); i++) {
            String name = d.roles().get(i).name();
            roles.put(name, player.attachment(new CgSequenceBinding(CgSequenceBinding.Subject.ROLE, i, CgSequenceBinding.Part.WHOLE, null),
                    "roles." + name));
        }
        this.context = new CgCameraContext(d.id()).director(CgCameraDirector.single(rig)).owner(player)
                .flags(player.playbackSettings().flags()).roles(roles::get);
        values = new float[rig.parameters()][3];
        List<CgCameraRigDefinition.Parameter> params = definition.parameters();
        for (int i = 0; i < values.length; i++) {
            if (rig.isVector(i)) {
                Vector3d v = (Vector3d) rig.parameter(rig.parameterName(i));
                values[i][0] = (float) v.x;
                values[i][1] = (float) v.y;
                values[i][2] = (float) v.z;
            } else if (rig.isRotation(i)) {
                // As the rig writes its default, which is the convention a kit keys it in.
                if (params.get(i).defaultValue() instanceof List<?> l && l.size() == 3) {
                    for (int c = 0; c < 3; c++) values[i][c] = l.get(c) instanceof Number n ? n.floatValue() : 0;
                }
            } else {
                Object v = rig.parameter(rig.parameterName(i));
                values[i][0] = v instanceof Boolean b ? (b ? 1 : 0) : ((Number) v).floatValue();
            }
        }
        update();
    }

    /** The {@code camera} spawner's rig branch: a spawnable whose {@code camera} names a {@code rig}. */
    static CgRigCamera spawn(CgSequencePlayer player, CgSequenceDefinition.Spawnable spawnable, CgAttachment at) {
        if (!(spawnable.definition() instanceof CgCameraRigDefinition rig)) {
            throw new CgAssetException(player.definition().id() + ": spawnables." + spawnable.id() + ".camera.rig: is not compiled");
        }
        return new CgRigCamera(player, rig, at);
    }

    /** The context a cut pushes: its rig, its play's roles, and its place as its pose. */
    public CgCameraContext context() {
        return context;
    }

    public CgCameraRig rig() {
        return rig;
    }

    /** {@code UpdateEvaluationContext}: its place is its context's pose, each frame after its tracks. */
    @Override
    public void update() {
        if (!at.place(point, turn)) {
            point[0] = point[1] = point[2] = 0;
            turn.identity();
        }
        context.initial().location(point[0], point[1], point[2]);
        context.initial().rotation().set(turn);
    }

    @Override
    public int slot(String name) {
        return name.startsWith("rig.") ? rig.indexOf(name.substring(4)) : -1;
    }

    @Override
    public void set(int slot, float[] values, int mask) {
        float[] to = this.values[slot];
        for (int i = 0; i < 3 && i < values.length; i++) if ((mask & 1 << i) != 0) to[i] = values[i];
        if (rig.isVector(slot)) rig.set(slot, to[0], to[1], to[2]);
        else if (rig.isRotation(slot)) rig.set(slot, units.rotationToHost(to[0], to[1], to[2], turn));
        else rig.set(slot, to[0]);
    }

    @Override
    public void get(int slot, float[] out) {
        System.arraycopy(values[slot], 0, out, 0, Math.min(3, out.length));
    }

    @Override
    public void destroy(boolean graceful) {
    }

    @Override
    public String toString() {
        return "CgRigCamera[" + rig.definition().id() + "]";
    }
}
