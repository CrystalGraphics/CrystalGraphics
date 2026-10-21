package com.crystalgraphics.vfx.particle;

import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshLoader;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.shape.CgShape;
import com.crystalgraphics.shape.primitive.CgBox;
import com.crystalgraphics.shape.primitive.CgCapsule;
import com.crystalgraphics.shape.primitive.CgCone;
import com.crystalgraphics.shape.primitive.CgCylinder;
import com.crystalgraphics.shape.primitive.CgRing;
import com.crystalgraphics.shape.primitive.CgShell;
import com.crystalgraphics.shape.primitive.CgSphere;
import com.crystalgraphics.vfx.particle.gpu.CgVfxWords;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxSpawnMeshes;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * Where an emitter's particles are born and which way they leave: a collision shape, how it is sampled, where it sits
 * about the source, and the launch direction's mode. The Java half of the Step kernel's {@code step_place}: every shape
 * is sampled in one frame with its axis on +Y, then turned and moved by the rotation and offset written here.
 *
 * <p>The samplers are uniform: bevy_hanabi's ({@code modifier/position.rs}), with a box's faces picked by area and a
 * cone filled by volume, where it does neither. A mesh's triangle is picked by area through an alias table
 * ({@link CgVfxSpawnMeshes}) and a point in it by one uniform barycentric: Effekseer's point cache draws each weight on
 * its own, which crowds the corners.</p>
 */
final class CgVfxSpawnShape {

    static final int POINT = 0, SPHERE = 1, BOX = 2, CYLINDER = 3, CONE = 4, CAPSULE = 5, MESH = 6, ENTITY = 7;
    static final int VOLUME = 0, SURFACE = 1, EDGE = 2, VERTICES = 3;
    static final int OUTWARD = 0, INWARD = 1, DIRECTION = 2, LAUNCH = 3;
    static final float TAU = (float) (2 * Math.PI);

    static final CgVfxSpawnShape DEFAULT = new Builder().build("");

    /** What the builder was given, kept so a copy is built the same way. */
    private final Builder given;
    /** From the sampling frame to the source's: its rotation, and its offset from the source. */
    private final float qx, qy, qz, qw, ox, oy, oz;
    /** A mesh's, once its path has loaded; and how far it reaches at scale 1, NaN until asked. */
    private volatile CgMesh loaded;
    private volatile float meshRadius = Float.NaN;

    private CgVfxSpawnShape(Builder given, Quaternionf q, Vector3f offset) {
        this.given = given;
        qx = q.x;
        qy = q.y;
        qz = q.z;
        qw = q.w;
        ox = offset.x;
        oy = offset.y;
        oz = offset.z;
    }

    int kind() {
        return given.kind;
    }

    /** Whether it launches toward its middle ({@code add_velocity}'s {@code inward} mode). */
    boolean inward() {
        return given.mode == INWARD;
    }

    /**
     * Its six vec4s from the parameter row's fifth: (up min, up max, up bias, direction mode), (direction xyz, cos of its
     * spread), (kind, placement | 4 for the upper half, arc, ordered), (numbers by kind), rotation, (offset xyz, 0).
     */
    void write(CgVfxWords out, float upMin, float upMax, float upBias) {
        Builder s = given;
        // A point's and a sphere's band is where they are born: their launch is the way out.
        int mode = s.mode == LAUNCH && s.kind <= SPHERE ? OUTWARD : s.mode;
        out.vec4(upMin, upMax, upBias, mode)
           .vec4(s.dirX, s.dirY, s.dirZ, s.cosSpread)
           .vec4(s.kind, s.placement | (s.hemisphere ? 4 : 0), s.arc, s.ordered);
        if (s.kind == MESH) {
            CgVfxSpawnMeshes.Section m = CgVfxSpawnMeshes.of(mesh());
            if (s.placement == VERTICES) out.vec4(m.firstVertex(), m.vertices(), s.a, 0f);
            else out.vec4(m.firstTriangle(), m.triangles(), s.a, 0f);
        } else {
            out.vec4(s.a, s.b, s.c, 0f);
        }
        out.vec4(qx, qy, qz, qw)
           .vec4(ox, oy, oz, 0f);
    }

    /** The farthest a particle is born from the source, a sweep's front aside. */
    float extent() {
        Builder s = given;
        float far = switch (s.kind) {
            case SPHERE -> s.b;
            case BOX -> (float) Math.sqrt(s.a * s.a + s.b * s.b + s.c * s.c);
            case CYLINDER -> (float) Math.sqrt(s.b * s.b + s.c * s.c);
            case CONE -> s.a;
            case CAPSULE -> s.a + s.b;
            case MESH -> meshRadius() * s.a;
            default -> 0f;
        };
        return far + (float) Math.sqrt(ox * ox + oy * oy + oz * oz);
    }

    /** Its mesh, a path loaded the first time it is asked. */
    private CgMesh mesh() {
        if (given.mesh != null) return given.mesh;
        CgMesh mesh = loaded;
        if (mesh == null) loaded = mesh = CgMeshLoader.load(given.meshPath, CgVertexFormat.SPATIAL);
        return mesh;
    }

    private float meshRadius() {
        float r = meshRadius;
        if (r != r) meshRadius = r = CgVfxSpawnMeshes.of(mesh()).radius();
        return r;
    }

    /** This shape at {@code s} times its size: its lengths and place, never its angles. */
    CgVfxSpawnShape scaled(float scale, String name) {
        Builder s = new Builder(this);
        s.a *= scale;
        if (s.kind != CONE) s.b *= scale;
        s.c *= scale;
        s.midX *= scale;
        s.midY *= scale;
        s.midZ *= scale;
        s.atX *= scale;
        s.atY *= scale;
        s.atZ *= scale;
        return s.build(name);
    }

    /** What the emitter's builder calls set, resolved at {@link #build}. */
    static final class Builder {

        int kind = POINT, placement = VOLUME, ordered, mode = OUTWARD;
        boolean hemisphere;
        /** Its numbers by kind: a sphere's inner and outer radius, a box's half extents, a cylinder's inner and outer
         * radius and half height, a cone's range and half angle, a capsule's half length and radius, a mesh's scale. */
        float a, b, c, arc = TAU;
        CgMesh mesh;
        String meshPath;
        float dirX, dirY = 1f, dirZ, cosSpread = 1f;
        /** The shape's own axis and middle, in its own space; where it is pointed (NaN for its own); where it sits. */
        float axisX, axisY = 1f, axisZ, midX, midY, midZ;
        float pointX = Float.NaN, pointY, pointZ, atX, atY, atZ;

        Builder() {
        }

        Builder(CgVfxSpawnShape shape) {
            copy(shape.given);
        }

        void shape(CgShape shape, String name) {
            mesh = null;
            meshPath = null;
            axisX = axisZ = midX = midY = midZ = 0f;
            axisY = 1f;
            a = b = c = 0f;
            if (shape instanceof CgSphere sphere) {
                kind = SPHERE;
                b = (float) sphere.radius();
            } else if (shape instanceof CgShell shell) {
                if (shell.isFront()) throw new IllegalArgumentException(name + ": a shell's front is a hitbox's, sized by it");
                kind = SPHERE;
                a = (float) shell.inner();
                b = (float) shell.outer();
            } else if (shape instanceof CgBox box) {
                kind = BOX;
                a = (float) box.halfX();
                b = (float) box.halfY();
                c = (float) box.halfZ();
            } else if (shape instanceof CgCylinder cylinder) {
                kind = CYLINDER;
                b = (float) cylinder.radius();
                c = (float) cylinder.halfHeight();
            } else if (shape instanceof CgRing ring) {
                if (ring.isFront()) throw new IllegalArgumentException(name + ": a ring's front is a hitbox's, sized by it");
                kind = CYLINDER;
                a = (float) ring.inner();
                b = (float) ring.outer();
                c = (float) ring.halfHeight();
            } else if (shape instanceof CgCone cone) {
                kind = CONE;
                a = (float) cone.range();
                b = (float) cone.halfAngle();
                axisY = 0f;
                axisZ = 1f;
            } else if (shape instanceof CgCapsule capsule) {
                Vector3d p = capsule.a(new Vector3d()), q = capsule.b(new Vector3d());
                double length = p.distance(q);
                kind = CAPSULE;
                a = (float) (length / 2);
                b = (float) capsule.radius();
                midX = (float) ((p.x + q.x) / 2);
                midY = (float) ((p.y + q.y) / 2);
                midZ = (float) ((p.z + q.z) / 2);
                if (length > 0) {
                    axisX = (float) ((q.x - p.x) / length);
                    axisY = (float) ((q.y - p.y) / length);
                    axisZ = (float) ((q.z - p.z) / length);
                }
            } else {
                throw new IllegalArgumentException(name + ": particles are born in a sphere, shell, box, cylinder, ring, cone "
                        + "or capsule, not a " + shape.getClass().getSimpleName());
            }
        }

        void mesh(CgMesh mesh, String path) {
            kind = MESH;
            placement = SURFACE;
            this.mesh = mesh;
            meshPath = path;
            a = 1f;
            b = c = 0f;
            axisX = axisZ = midX = midY = midZ = 0f;
            axisY = 1f;
        }

        void entity() {
            kind = ENTITY;
            mesh = null;
            meshPath = null;
            a = b = c = 0f;
            axisX = axisZ = midX = midY = midZ = 0f;
            axisY = 1f;
        }

        void pointing(float x, float y, float z, String name) {
            float length = (float) Math.sqrt(x * x + y * y + z * z);
            if (!(length > 0f)) throw new IllegalArgumentException(name + ": pointing(" + x + ", " + y + ", " + z + ")");
            pointX = x / length;
            pointY = y / length;
            pointZ = z / length;
        }

        void direction(float x, float y, float z, float spreadDegrees, String name) {
            float length = (float) Math.sqrt(x * x + y * y + z * z);
            if (!(length > 0f)) throw new IllegalArgumentException(name + ": direction(" + x + ", " + y + ", " + z + ")");
            if (!(spreadDegrees >= 0f && spreadDegrees <= 180f)) {
                throw new IllegalArgumentException(name + ": a spread of " + spreadDegrees + " degrees, not 0 to 180");
            }
            mode = DIRECTION;
            dirX = x / length;
            dirY = y / length;
            dirZ = z / length;
            cosSpread = (float) Math.cos(Math.toRadians(spreadDegrees));
        }

        CgVfxSpawnShape build(String name) {
            if (kind == BOX && (ordered > 0 || arc < TAU)) {
                throw new IllegalArgumentException(name + ": a box has no way round it for ordered() or arc()");
            }
            if (kind == POINT && placement != VOLUME) {
                throw new IllegalArgumentException(name + ": surface() and edge() need a shape(...)");
            }
            if (placement == VERTICES && kind != MESH) throw new IllegalArgumentException(name + ": vertices() needs a mesh(...)");
            if (kind == MESH) {
                if (placement == EDGE) throw new IllegalArgumentException(name + ": a mesh spawns on its surface or vertices(), not edge()");
                if (hemisphere || arc < TAU) throw new IllegalArgumentException(name + ": a mesh has no hemisphere() or arc()");
                if (ordered > 0 && placement != VERTICES) {
                    throw new IllegalArgumentException(name + ": ordered() on a mesh walks its vertices(): call that too");
                }
            }
            if (kind == ENTITY && (ordered > 0 || arc < TAU || !Float.isNaN(pointX) || atX != 0f || atY != 0f || atZ != 0f)) {
                throw new IllegalArgumentException(name + ": an entity's box is where it stands, axis aligned: no ordered(), "
                        + "arc(), pointing() or at()");
            }
            // From the sampling frame's +Y to the shape's own axis, then to where it is pointed.
            Quaternionf q = new Quaternionf().rotationTo(0f, 1f, 0f, axisX, axisY, axisZ);
            Vector3f offset = new Vector3f(midX, midY, midZ);
            if (!Float.isNaN(pointX)) {
                Quaternionf turn = new Quaternionf().rotationTo(axisX, axisY, axisZ, pointX, pointY, pointZ);
                q.premul(turn);
                turn.transform(offset);
            }
            Builder given = new Builder();
            given.copy(this);
            return new CgVfxSpawnShape(given, q, offset.add(atX, atY, atZ));
        }

        private void copy(Builder s) {
            kind = s.kind;
            placement = s.placement;
            ordered = s.ordered;
            mode = s.mode;
            hemisphere = s.hemisphere;
            a = s.a;
            b = s.b;
            c = s.c;
            arc = s.arc;
            dirX = s.dirX;
            dirY = s.dirY;
            dirZ = s.dirZ;
            cosSpread = s.cosSpread;
            axisX = s.axisX;
            axisY = s.axisY;
            axisZ = s.axisZ;
            midX = s.midX;
            midY = s.midY;
            midZ = s.midZ;
            pointX = s.pointX;
            pointY = s.pointY;
            pointZ = s.pointZ;
            atX = s.atX;
            atY = s.atY;
            atZ = s.atZ;
            mesh = s.mesh;
            meshPath = s.meshPath;
        }
    }
}
