package com.crystalgraphics.api.animation;

import com.crystalgraphics.asset.kind.CgBodyKinds;
import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.object.value.CgQuantity;
import com.crystalgraphics.object.value.CgUnits;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A {@code skeleton} asset compiled: its model's sockets on bones, Unreal's {@code USkeleton} sockets, in the host's
 * units. The mesh takes them with {@link com.crystalgraphics.api.mesh.CgSkeletalMesh#withSockets}.
 *
 * <pre>{@code
 * CgSkeletonDefinition.register();                                            // once, at init
 * CgAssetLoad load = pipeline.request(CgBodyKinds.SKELETON, "kakarot_kit:Art/MDL/Cpl/Cpl001/Model/Cpl001p1_Skeleton");
 * CgSkeletalMesh goku = mesh.withSockets(((CgSkeletonDefinition) load.definition()).sockets());
 *
 * // a file read by hand, in its kit's units:
 * CgSkeletonDefinition d = CgSkeletonDefinition.compile(id, tree, CgUnits.of(unitsYaml.get("units"), CgUnits.HOST));
 * }</pre>
 *
 * <ul>
 *   <li>A socket's offset and turn are in its bone's own space, converted as the bones were: Unreal's in a glTF from
 *       Unreal's or CUE4Parse's exporter, whose bone frames are Unreal's through {@link CgUnits#UNREAL}'s axes
 *       ({@code Gltf.cs:130-132, 274-280}).</li>
 *   <li>Only {@code from: bone} sockets are a mesh's. {@code entity} and {@code derived} ones are points on whatever
 *       plays the role, not on the model, and are left to their reader.</li>
 * </ul>
 *
 * @param id      its asset id
 * @param sockets its bone sockets, in file order
 */
public record CgSkeletonDefinition(String id, List<CgSocket> sockets) {

    public static final CgAssetCompiler<CgSkeletonDefinition> COMPILER =
            (asset, deps) -> compile(asset.id(), asset.resolved(), deps.units());

    public CgSkeletonDefinition {
        sockets = Collections.unmodifiableList(new ArrayList<>(sockets));
    }

    /** Registers {@link #COMPILER} as the skeleton kind's. */
    public static void register() {
        CgAssetCompiler.register(CgBodyKinds.SKELETON, COMPILER);
    }

    /** {@code tree}'s bone sockets, its values written in {@code units}. */
    public static CgSkeletonDefinition compile(String id, Map<?, ?> tree, CgUnits units) {
        if (!"unreal_rotator".equals(units.rotation()) && !"quaternion".equals(units.rotation())) {
            throw new IllegalArgumentException(id + ": sockets read rotators or quaternions, not " + units.rotation());
        }
        List<CgSocket> out = new ArrayList<>();
        Object list = tree.get("sockets");
        if (list instanceof List<?> sockets) {
            for (Object o : sockets) {
                Map<?, ?> s = (Map<?, ?>) o;
                if (!"bone".equals(s.get("from"))) continue;
                Vector3f location = vector(units.toHost(s.get("location"), CgQuantity.OFFSET), 0f);
                Vector3f scale = vector(units.toHost(s.get("scale"), CgQuantity.SCALE), 1f);
                out.add(CgSocket.of(String.valueOf(s.get("name")), String.valueOf(s.get("bone")), location,
                        rotation(s.get("rotation"), units), scale));
            }
        }
        return new CgSkeletonDefinition(id, out);
    }

    private static Vector3f vector(Object v, float absent) {
        if (!(v instanceof List<?> l)) return new Vector3f(absent);
        return new Vector3f(((Number) l.get(0)).floatValue(), ((Number) l.get(1)).floatValue(), ((Number) l.get(2)).floatValue());
    }

    /** A rotator ({@code pitch}, {@code yaw}, {@code roll}) through Unreal's axes, or a quaternion as written. */
    private static Quaternionf rotation(Object v, CgUnits units) {
        if (v == null) return new Quaternionf();
        if (v instanceof Map<?, ?> r) {
            double k = units.radians();
            float[] q = CgUnits.rotatorToHost(number(r.get("roll")) * k, number(r.get("pitch")) * k, number(r.get("yaw")) * k);
            return new Quaternionf(q[0], q[1], q[2], q[3]);
        }
        List<?> q = (List<?>) v;
        return new Quaternionf(number(q.get(0)), number(q.get(1)), number(q.get(2)), number(q.get(3))).normalize();
    }

    private static float number(Object o) {
        return o == null ? 0f : ((Number) o).floatValue();
    }
}
