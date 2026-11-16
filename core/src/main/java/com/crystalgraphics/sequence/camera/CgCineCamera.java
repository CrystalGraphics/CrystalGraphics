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
 * What a sequence's {@code camera} spawnable plays: Unreal's {@code ACineCameraActor}. Its {@code transform} track, its
 * {@code fov} and its lens's keys ({@code focal_length} in mm, {@code aperture} the f-stop, {@code focus_distance}) are
 * read here as a pose, which a camera cut or a camera animation then uses; a focus blurs the picture.
 *
 * <pre>{@code
 * CgSequenceCameras.install(sequencer, cameras, animations);   // the camera spawner makes one per cine: camera
 * CgCineCamera cam = (CgCineCamera) player.spawned(index);
 * cam.pose(pose);                           // this frame's, in the host's units and axes
 * }</pre>
 *
 * <ul>
 *   <li>It answers {@code translation}, {@code rotation}, {@code fov}, {@code focal_length}, {@code aperture} and
 *       {@code focus_distance}; a track keying anything else fails at play, naming the slot.</li>
 *   <li>A focal length keyed or set ({@code cine: { focal_length }}) sets the FOV, on its sensor's width.</li>
 *   <li>Its pose is in its sequence's space, from its place {@code at}; with no {@code at}, the sequence's origin.</li>
 * </ul>
 */
public final class CgCineCamera implements CgSequenceSpawned {

    private static final int TRANSLATION = 0, ROTATION = 1, FOV = 2, FOCAL_LENGTH = 3, APERTURE = 4, FOCUS_DISTANCE = 5;

    private final CgUnits units;
    private final CgAttachment at;
    private final float[] translation = new float[3], rotation = new float[3];
    private float fov;
    /** The lens, NaN unset: focal length in mm, the f-stop, the focus distance in the host's units. */
    private float focalLength = Float.NaN, aperture = Float.NaN, focusDistance = Float.NaN;
    private final double[] point = new double[3];
    private final Quaternionf turn = new Quaternionf(), local = new Quaternionf();
    private final Vector3d offset = new Vector3d();

    CgCineCamera(CgUnits units, CgAttachment at, float fov) {
        this.units = units;
        this.at = at;
        this.fov = fov;
    }

    /** The {@code camera} spawner's cine branch: its settings' FOV, in degrees, until a track keys one. */
    static CgCineCamera spawn(CgSequencePlayer player, CgSequenceDefinition.Spawnable spawnable, CgAttachment at) {
        float fov = CgCameraPose.DEFAULT_FOV;
        CgCineCamera cam;
        if (spawnable.tree().get("camera") instanceof Map<?, ?> camera && camera.get("cine") instanceof Map<?, ?> cine) {
            if (cine.get("fov") instanceof Number n) fov = n.floatValue();
            cam = new CgCineCamera(player.definition().units(), at, fov);
            if (cine.get("focal_length") instanceof Number n) cam.focalLength = n.floatValue();
            if (cine.get("focus") instanceof Number n) cam.focusDistance = n.floatValue();
            if (cine.get("aperture") instanceof Number n) cam.aperture = n.floatValue();
            return cam;
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
        out.fov(fov);
        if (focalLength > 0f) out.focalLength(focalLength);
        return out.aperture(aperture).focusDistance(focusDistance);
    }

    @Override
    public int slot(String name) {
        return switch (name) {
            case "translation" -> TRANSLATION;
            case "rotation" -> ROTATION;
            case "fov" -> FOV;
            case "focal_length" -> FOCAL_LENGTH;
            case "aperture" -> APERTURE;
            case "focus_distance" -> FOCUS_DISTANCE;
            default -> -1;
        };
    }

    @Override
    public void set(int slot, float[] values, int mask) {
        if (slot >= FOV) {
            if ((mask & 1) == 0) return;
            switch (slot) {
                case FOV -> fov = values[0];
                case FOCAL_LENGTH -> focalLength = values[0];
                case APERTURE -> aperture = values[0];
                default -> focusDistance = values[0];
            }
            return;
        }
        float[] to = slot == TRANSLATION ? translation : rotation;
        for (int i = 0; i < 3; i++) if ((mask & 1 << i) != 0) to[i] = values[i];
    }

    @Override
    public void get(int slot, float[] out) {
        if (slot >= FOV) {
            out[0] = switch (slot) {
                case FOV -> fov;
                case FOCAL_LENGTH -> focalLength;
                case APERTURE -> aperture;
                default -> focusDistance;
            };
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
