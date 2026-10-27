package com.crystalgraphics.api.animation;

import org.joml.Matrix4f;
import org.joml.Quaternionfc;
import org.joml.Vector3fc;

import java.util.Objects;

/**
 * A named place on a bone that effects attach to: Unreal's {@code USkeletalMeshSocket} (its {@code SocketName},
 * {@code BoneName}, {@code RelativeLocation}, {@code RelativeRotation} and {@code RelativeScale}). Its transform is
 * {@code bone × relative} (USkeletalMeshSocket::GetSocketTransform, SkeletalMesh.cpp:7342-7355).
 *
 * <pre>{@code
 * CgSocket palm = CgSocket.of("right_hand", "hand_r", new Vector3f(0, -0.08f, 0.02f));
 * CgSkeletalMesh goku = loaded.withSockets(List.of(palm));
 * instance.socket("right_hand");          // the socket; "hand_r" alone answers the bone itself
 * }</pre>
 *
 * <ul>
 *   <li>Immutable; any thread. Lengths in the mesh's own units, as its bones'.</li>
 *   <li>Names compare ignoring case, as Unreal's {@code FName}.</li>
 * </ul>
 */
public record CgSocket(String name, String bone, float tx, float ty, float tz, float qx, float qy, float qz, float qw,
                       float sx, float sy, float sz) {

    public CgSocket {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(bone, "bone");
        float length = CgAnimSequence.unitLength(qx, qy, qz, qw);
        if (length == 0f) throw new IllegalArgumentException("socket " + name + ": a rotation of length 0");
        qx /= length;
        qy /= length;
        qz /= length;
        qw /= length;
    }

    /** A socket at {@code location} on {@code bone}, unrotated and unscaled. */
    public static CgSocket of(String name, String bone, Vector3fc location) {
        return new CgSocket(name, bone, location.x(), location.y(), location.z(), 0, 0, 0, 1, 1, 1, 1);
    }

    /** A socket at {@code location} on {@code bone}, turned and scaled relative to it. */
    public static CgSocket of(String name, String bone, Vector3fc location, Quaternionfc rotation, Vector3fc scale) {
        return new CgSocket(name, bone, location.x(), location.y(), location.z(), rotation.x(), rotation.y(),
                rotation.z(), rotation.w(), scale.x(), scale.y(), scale.z());
    }

    /** Its transform relative to its bone: translation, rotation, then scale. */
    public Matrix4f relative(Matrix4f out) {
        return out.translationRotateScale(tx, ty, tz, qx, qy, qz, qw, sx, sy, sz);
    }
}
