package com.crystalgraphics.sequence.camera;

import java.util.Map;

import org.joml.Quaternionf;
import org.joml.Vector3d;

import com.crystalgraphics.camera.CgCameraPose;
import com.crystalgraphics.object.value.CgUnits;
import com.crystalgraphics.sequence.CgSequencePlayer;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;
import com.crystalgraphics.sequence.spawn.CgSequenceSpawned;
import com.crystalgraphics.world.CgAttachment;

/**
 * What a sequence's {@code camera} spawnable plays: Unreal's {@code ACineCameraActor}. Its {@code transform} track and
 * its {@code fov} property keys are read here as a pose, which a camera cut or a camera animation then uses.
 *
 * <pre>{@code
 * sequencer.spawner("camera", CgCineCamera::spawn);
 * CgCineCamera cam = (CgCineCamera) player.spawned(index);
 * cam.pose(pose);                           // this frame's, in the host's units and axes
 * }</pre>
 *
 * <ul>
 *   <li>It answers {@code translation}, {@code rotation} and {@code fov}; a track keying anything else fails at play,
 *       naming the slot.</li>
 *   <li>Its pose is in its sequence's space, from its place {@code at}; with no {@code at}, the sequence's origin.</li>
 * </ul>
 */
public final class CgCineCamera implements CgSequenceSpawned {

    private static final int TRANSLATION = 0, ROTATION = 1, FOV = 2;

    private final CgUnits units;
    private final CgAttachment at;
    private final float[] translation = new float[3], rotation = new float[3];
    private float fov;
    private final double[] point = new double[3];
    private final Quaternionf turn = new Quaternionf(), local = new Quaternionf();
    private final Vector3d offset = new Vector3d();

    CgCineCamera(CgUnits units, CgAttachment at, float fov) {
        this.units = units;
        this.at = at;
        this.fov = fov;
    }

    /** The spawner for {@code camera} spawnables: its settings' FOV, in degrees, until a track keys one. */
    public static CgCineCamera spawn(CgSequencePlayer player, CgSequenceDefinition.Spawnable spawnable, CgAttachment at) {
        float fov = CgCameraPose.DEFAULT_FOV;
        if (spawnable.tree().get("camera") instanceof Map<?, ?> camera && camera.get("cine") instanceof Map<?, ?> cine
                && cine.get("fov") instanceof Number n) {
            fov = n.floatValue();
        }
        return new CgCineCamera(player.definition().units(), at, fov);
    }

    /** This frame's pose into {@code out}: its place, then its keyed translation and turn, and its FOV. */
    public CgCameraPose pose(CgCameraPose out) {
        if (!at.place(point, turn)) {
            point[0] = point[1] = point[2] = 0;
            turn.identity();
        }
        units.rotationToHost(rotation[0], rotation[1], rotation[2], local);
        out.location(point[0], point[1], point[2]);
        out.location().add(turn.transform(offset.set(translation[0], translation[1], translation[2])));
        out.rotation().set(turn).mul(local);
        return out.fov(fov);
    }

    @Override
    public int slot(String name) {
        return switch (name) {
            case "translation" -> TRANSLATION;
            case "rotation" -> ROTATION;
            case "fov" -> FOV;
            default -> -1;
        };
    }

    @Override
    public void set(int slot, float[] values, int mask) {
        if (slot == FOV) {
            if ((mask & 1) != 0) fov = values[0];
            return;
        }
        float[] to = slot == TRANSLATION ? translation : rotation;
        for (int i = 0; i < 3; i++) if ((mask & 1 << i) != 0) to[i] = values[i];
    }

    @Override
    public void get(int slot, float[] out) {
        if (slot == FOV) {
            out[0] = fov;
            return;
        }
        System.arraycopy(slot == TRANSLATION ? translation : rotation, 0, out, 0, Math.min(3, out.length));
    }

    @Override
    public void destroy(boolean graceful) {
    }

    @Override
    public String toString() {
        return "CgCineCamera";
    }
}
