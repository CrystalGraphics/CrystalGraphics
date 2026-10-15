package com.crystalgraphics.api.mesh;

import com.crystalgraphics.api.vertex.CgVertexFormat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Effect meshes from a few numbers: crescents, slashes, cones, domes and swirls revolved from a profile, and ribbons
 * spiralling along one. Ported from Effekseer's {@code ProceduralModelGenerator} (MIT,
 * {@code Dev/Cpp/Effekseer/Effekseer/Model/Effekseer.ProceduralModelGenerator.cpp}): its profiles, its revolved mesh and
 * ribbons, its tilt, wave and curl deformations, its axis and UV placement. Vertex colours are left out:
 * {@link CgVertexFormat#SPATIAL} has none, and a layer's shader colours by UV instead.
 *
 * <pre>{@code
 * // a crescent for a sword slash: a thin cylinder band through 140 degrees
 * static final CgMesh SLASH = CgProceduralMesh.revolve(CgProceduralMesh.cylinder(1f, 1.3f, 0.25f))
 *         .angles(-70f, 70f).divisions(48, 3).build();
 *
 * // a dome over an impact, swaying as it rises
 * static final CgMesh DOME = CgProceduralMesh.revolve(CgProceduralMesh.sphere(2f, 0f, 2f)).divisions(48, 12)
 *         .wave(1.5f, 0.15f).build();
 *
 * // three ribbons spiralling up a cone, wide at the base and pinched at the tip
 * static final CgMesh SWIRL = CgProceduralMesh.ribbons(CgProceduralMesh.cone(1f, 3f))
 *         .count(3).twist(1.5f).width(0.5f, 0.05f).build();
 *
 * layer.on(SLOT).mesh(SLASH)                      // drawn on a mesh slot (CgVfxLayer.mesh)
 * }</pre>
 *
 * <ul>
 *   <li>A profile is a curve in (radius, height), from {@code t} 0 to 1: the revolved mesh sweeps it round +Y, a ribbon
 *       runs along it. UV's u runs round, v from 1 at the start of the profile to 0 at its end.</li>
 *   <li>Data only: build on any thread, and draw it as any {@link CgMesh}. Each {@code build()} makes a new mesh.</li>
 *   <li>Faces wind counter-clockwise seen from outside; a ribbon has two sides, so draw it with culling off.</li>
 * </ul>
 */
public final class CgProceduralMesh {

    /** A profile: at {@code t} 0 to 1, its radius from the axis into {@code out[0]} and its height into {@code out[1]}. */
    @FunctionalInterface
    public interface Profile {
        void at(float t, float[] out);
    }

    /** Which axis the mesh is built round: +Y as generated, or +X or +Z (Effekseer's axis type). */
    public enum Axis { X, Y, Z }

    /** A ribbon's cross section: a flat strip, two strips crossed, or a line. */
    public enum Section { PLANE, CROSS, POINT }

    private CgProceduralMesh() {
    }

    /**
     * A sphere's arc of {@code radius}, from the height {@code depthMin} to {@code depthMax} (each clamped to the
     * radius): {@code sphere(r, -r, r)} is the whole sphere, {@code sphere(r, 0, r)} its upper half.
     */
    public static Profile sphere(float radius, float depthMin, float depthMax) {
        float max = clamp(depthMax, -radius, radius), min = clamp(depthMin, -radius, radius);
        float angleMax = (float) Math.atan2(max, Math.sqrt(radius * radius - max * max));
        float angleMin = (float) Math.atan2(min, Math.sqrt(radius * radius - min * min));
        return (t, out) -> {
            float angle = t >= 1f ? angleMax : t <= 0f ? angleMin : (angleMax - angleMin) * t + angleMin;
            out[0] = (float) Math.cos(angle) * radius;
            out[1] = (float) Math.sin(angle) * radius;
        };
    }

    /** A cone from its apex at the origin to {@code radius} at {@code depth}. */
    public static Profile cone(float radius, float depth) {
        return (t, out) -> {
            float h = depth * clamp(t, 0f, 1f);
            out[0] = radius / depth * h;
            out[1] = h;
        };
    }

    /** A cylinder, or a cone cut off: {@code radius1} at the bottom, {@code radius2} at {@code depth}. */
    public static Profile cylinder(float radius1, float radius2, float depth) {
        return (t, out) -> {
            float v = clamp(t, 0f, 1f);
            out[0] = (radius2 - radius1) * v + radius1;
            out[1] = depth * v;
        };
    }

    /** A natural cubic spline through four (radius, height) points, walked by the length between them. */
    public static Profile spline(float r1, float h1, float r2, float h2, float r3, float h3, float r4, float h4) {
        Spline spline = new Spline(new float[]{r1, r2, r3, r4}, new float[]{h1, h2, h3, h4});
        return spline::at;
    }

    /** A mesh revolved round +Y from {@code profile}. */
    public static Revolve revolve(Profile profile) {
        return new Revolve(profile);
    }

    /** Ribbons spiralling along {@code profile} round +Y. */
    public static Ribbons ribbons(Profile profile) {
        return new Ribbons(profile);
    }

    /** What both kinds share: the deformations, the axis and the UV placement. */
    public abstract static class Builder<B extends Builder<B>> {

        final Profile profile;
        private float tiltFreqX, tiltOffsetX, tiltPowerX, tiltFreqZ, tiltOffsetZ, tiltPowerZ;
        private final float[] waveFreq = new float[3], waveOffset = new float[3], wavePower = new float[3];
        private final float[] curlFreq = new float[3], curlOffset = new float[3], curlPower = new float[3];
        private int noiseSeed;
        private Axis axis = Axis.Y;
        private float uvU, uvV, uvWidth = 1f, uvHeight = 1f;

        Builder(Profile profile) {
            this.profile = profile;
        }

        @SuppressWarnings("unchecked")
        private B self() {
            return (B) this;
        }

        /**
         * Leans the mesh as it rises: a lean about Z of {@code powerX} radians and about X of {@code powerZ}, each a sine
         * of the height times its frequency plus its offset.
         */
        public B tilt(float frequencyX, float offsetX, float powerX, float frequencyZ, float offsetZ, float powerZ) {
            tiltFreqX = frequencyX;
            tiltOffsetX = offsetX;
            tiltPowerX = powerX;
            tiltFreqZ = frequencyZ;
            tiltOffsetZ = offsetZ;
            tiltPowerZ = powerZ;
            return self();
        }

        /** Sways it in x and z by {@code power} blocks as it rises, {@code frequency} radians a block, a quarter turn apart. */
        public B wave(float frequency, float power) {
            return wave(frequency, 0f, power, 0f, 0f, 0f, frequency, (float) (Math.PI / 2), power);
        }

        /** Shifts each axis by a sine of the height: {@code sin(y * frequency + offset) * power}, per axis. */
        public B wave(float freqX, float offsetX, float powerX, float freqY, float offsetY, float powerY, float freqZ,
                      float offsetZ, float powerZ) {
            set(waveFreq, freqX, freqY, freqZ);
            set(waveOffset, offsetX, offsetY, offsetZ);
            set(wavePower, powerX, powerY, powerZ);
            return self();
        }

        /** Roughens it by curl noise: sampled at {@code frequency} times the position, moving each vertex up to about {@code power} blocks. */
        public B curl(float frequency, float power) {
            return curl(frequency, frequency, frequency, 0f, 0f, 0f, power, power, power);
        }

        /** As {@link #curl(float, float)}, a frequency, an offset and a power per axis. */
        public B curl(float freqX, float freqY, float freqZ, float offsetX, float offsetY, float offsetZ, float powerX,
                      float powerY, float powerZ) {
            set(curlFreq, freqX, freqY, freqZ);
            set(curlOffset, offsetX, offsetY, offsetZ);
            set(curlPower, powerX, powerY, powerZ);
            return self();
        }

        /** The curl noise's seed: 0, Effekseer's, unless given. */
        public B noiseSeed(int seed) {
            noiseSeed = seed;
            return self();
        }

        /** Builds it round {@code axis} instead of +Y. */
        public B axis(Axis axis) {
            this.axis = axis;
            return self();
        }

        /** Places its UVs: each {@code (u, v)} becomes {@code (u * width + u0, v * height + v0)}. */
        public B uv(float u0, float v0, float width, float height) {
            uvU = u0;
            uvV = v0;
            uvWidth = width;
            uvHeight = height;
            return self();
        }

        private static void set(float[] to, float x, float y, float z) {
            to[0] = x;
            to[1] = y;
            to[2] = z;
        }

        /** The mesh, with smooth normals: a new one each call. */
        public CgMesh build() {
            Geometry g = new Geometry();
            generate(g);
            CgMeshNoise curl = curlPower[0] != 0f || curlPower[1] != 0f || curlPower[2] != 0f ? new CgMeshNoise(noiseSeed) : null;
            float[] c = new float[3];
            for (int i = 0; i < g.count; i++) deform(g, i, curl, c);
            if (axis != Axis.Y) g.swapAxis(axis);
            float[] normals = g.normals();
            return CgMesh.build(CgVertexFormat.SPATIAL, m -> {
                for (int i = 0; i < g.count; i++) {
                    m.vertex().position(g.x(i), g.y(i), g.z(i)).normal(normals[i * 3], normals[i * 3 + 1], normals[i * 3 + 2])
                            .uv(g.u(i) * uvWidth + uvU, g.v(i) * uvHeight + uvV).end();
                }
                for (int f = 0; f < g.faces.size(); f += 3) m.triangle(g.faces.get(f), g.faces.get(f + 1), g.faces.get(f + 2));
            });
        }

        /** Tilt, then wave, then curl: Effekseer's order. */
        private void deform(Geometry g, int i, CgMeshNoise curl, float[] c) {
            float x = g.x(i), y = g.y(i), z = g.z(i);
            if (tiltPowerX != 0f || tiltPowerZ != 0f) {
                float ax = (float) Math.sin(y * tiltFreqX + tiltOffsetX) * tiltPowerX;
                float az = (float) Math.sin(y * tiltFreqZ + tiltOffsetZ) * tiltPowerZ;
                float dxx = (float) Math.cos(ax), dxy = (float) Math.sin(ax);
                float dzy = (float) Math.sin(az), dzz = (float) Math.cos(az);
                // dirY = normalize(dirZ x dirX), then dirZ = normalize(dirX x dirY), as Effekseer's tilt does.
                float yx = dzy * 0f - dzz * dxy, yy = dzz * dxx, yz = -dzy * dxx;
                float yl = (float) Math.sqrt(yx * yx + yy * yy + yz * yz);
                yx /= yl;
                yy /= yl;
                yz /= yl;
                float zx = dxy * yz, zy = -dxx * yz, zz = dxx * yy - dxy * yx;
                float zl = (float) Math.sqrt(zx * zx + zy * zy + zz * zz);
                zx /= zl;
                zy /= zl;
                zz /= zl;
                float nx = dxx * x + zx * z, ny = y + dxy * x + zy * z, nz = zz * z;
                x = nx;
                y = ny;
                z = nz;
            }
            float wy = y;
            x += (float) Math.sin(wy * waveFreq[0] + waveOffset[0]) * wavePower[0];
            y += (float) Math.sin(wy * waveFreq[1] + waveOffset[1]) * wavePower[1];
            z += (float) Math.sin(wy * waveFreq[2] + waveOffset[2]) * wavePower[2];
            if (curl != null) {
                curl.curl(x * curlFreq[0] + curlOffset[0], y * curlFreq[1] + curlOffset[1], z * curlFreq[2] + curlOffset[2], c);
                x += c[0] * curlPower[0];
                y += c[1] * curlPower[1];
                z += c[2] * curlPower[2];
            }
            g.set(i, x, y, z);
        }

        abstract void generate(Geometry g);
    }

    /** A profile swept round +Y between two angles, twisting as it rises. */
    public static final class Revolve extends Builder<Revolve> {

        private float angleBegin, angleEnd = 360f, twist;
        private int around = 32, along = 8;

        private Revolve(Profile profile) {
            super(profile);
        }

        /** From {@code begin} to {@code end} degrees round +Y, 0 on +Z toward +X: 0 to 360 unless given. */
        public Revolve angles(float begin, float end) {
            angleBegin = begin;
            angleEnd = end;
            return this;
        }

        /** Vertices round ({@code around}) and along the profile ({@code along}), each at least 2: 32 and 8 unless given. */
        public Revolve divisions(int around, int along) {
            if (around < 2 || along < 2) throw new IllegalArgumentException("divisions(" + around + ", " + along + ")");
            this.around = around;
            this.along = along;
            return this;
        }

        /** Turns {@code turns} times round the axis from the start of the profile to its end: a swirl. */
        public Revolve twist(float turns) {
            twist = turns;
            return this;
        }

        @Override
        void generate(Geometry g) {
            float begin = (float) Math.toRadians(angleBegin), end = (float) Math.toRadians(angleEnd);
            boolean closed = Math.abs(angleBegin - angleEnd) % 360f < 1e-6f;
            float[] p = new float[2];
            for (int v = 0; v < along; v++) {
                float depth = v / (float) (along - 1);
                profile.at(depth, p);
                for (int u = 0; u < around; u++) {
                    float angle = (end - begin) * (u / (float) (around - 1)) + begin + twist * (float) (Math.PI * 2) * depth;
                    if (closed && u == around - 1) angle = begin + twist * (float) (Math.PI * 2) * depth;
                    g.add(p[0] * (float) Math.sin(angle), p[1], p[0] * (float) Math.cos(angle), u / (float) (around - 1), 1f - depth);
                }
            }
            for (int v = 0; v < along - 1; v++) {
                for (int u = 0; u < around - 1; u++) {
                    int v00 = u + v * around, v10 = v00 + 1, v01 = v00 + around, v11 = v01 + 1;
                    g.triangle(v00, v10, v11);
                    g.triangle(v00, v11, v01);
                }
            }
        }
    }

    /** Ribbons running along a profile, each starting a share of the way round and turning as it goes. */
    public static final class Ribbons extends Builder<Ribbons> {

        private Section section = Section.PLANE;
        private int count = 1, vertices = 32;
        private float twist, widthStart = 0.25f, widthEnd = 0.25f, angleStart, angleEnd, scatterAngle, scatterDepth;
        private long seed;

        private Ribbons(Profile profile) {
            super(profile);
        }

        /** Its cross section: a flat strip unless given. */
        public Ribbons section(Section section) {
            this.section = section;
            return this;
        }

        /** How many ribbons, spaced evenly round: 1 unless given. */
        public Ribbons count(int count) {
            this.count = count;
            return this;
        }

        /** Points along each ribbon: 32 unless given. */
        public Ribbons vertices(int vertices) {
            if (vertices < 2) throw new IllegalArgumentException("vertices(" + vertices + ")");
            this.vertices = vertices;
            return this;
        }

        /** Turns {@code turns} times round the axis along each ribbon. */
        public Ribbons twist(float turns) {
            twist = turns;
            return this;
        }

        /** Its width in blocks at the start of the profile and at its end. */
        public Ribbons width(float start, float end) {
            widthStart = start;
            widthEnd = end;
            return this;
        }

        /** Its roll about its own length, in degrees, at the start and at the end. */
        public Ribbons roll(float startDegrees, float endDegrees) {
            angleStart = startDegrees;
            angleEnd = endDegrees;
            return this;
        }

        /**
         * How much each ribbon strays from even: up to {@code angle} of a turn round, and starting and ending up to
         * {@code depth} / 2 of the profile in from its ends, drawn by {@link #seed}.
         */
        public Ribbons scatter(float angle, float depth) {
            scatterAngle = angle;
            scatterDepth = depth;
            return this;
        }

        /** What scatters them: 0 unless given. */
        public Ribbons seed(long seed) {
            this.seed = seed;
            return this;
        }

        @Override
        void generate(Geometry g) {
            float[][] cross = switch (section) {
                case PLANE -> new float[][]{{0.5f, 0f, 0f}, {0f, 0f, 0f}, {-0.5f, 0f, 0f}};
                case CROSS -> new float[][]{{0.5f, 0f, 0f}, {0f, 0f, 0f}, {-0.5f, 0f, 0f}, {0f, 0f, -0.5f}, {0f, 0f, 0f},
                        {0f, 0f, 0.5f}};
                case POINT -> new float[][]{{0f, 0f, 0f}};
            };
            int[] edges = switch (section) {
                case PLANE -> new int[]{0, 1, 1, 2};
                case CROSS -> new int[]{0, 1, 1, 2, 3, 4, 4, 5};
                case POINT -> new int[]{0};
            };
            float[] uvs = switch (section) {
                case PLANE -> new float[]{0f, 0.5f, 1f};
                case CROSS -> new float[]{0f, 0.5f, 1f, 0f, 0.5f, 1f};
                case POINT -> new float[]{0f};
            };
            SplittableRandom random = new SplittableRandom(seed);
            float tau = (float) (Math.PI * 2);
            List<float[]> points = new ArrayList<>();
            for (int l = 0; l < count; l++) {
                float depth = scatterDepth / 2f * (float) random.nextDouble();
                float endDepth = 1f - scatterDepth / 2f * (float) random.nextDouble();
                float angle = ((float) l / count + scatterAngle * ((float) random.nextDouble() - 0.5f)) * tau;
                float depthSpeed = (endDepth - depth) / vertices, rotateSpeed = twist * (endDepth - depth) * tau / vertices;
                points.clear();
                while (depth < endDepth) {
                    float[] pos = point(angle, depth), along = point(angle + 1e-4f * rotateSpeed, depth + 1e-4f * depthSpeed);
                    float[] round = point(angle + 1e-4f, depth), up = point(angle, depth + 1e-4f);
                    float[] normal = normalize(cross(sub(round, pos), sub(up, pos))), b = normalize(sub(along, pos));
                    points.add(new float[]{pos[0], pos[1], pos[2], normal[0], normal[1], normal[2], b[0], b[1], b[2]});
                    depth += depthSpeed;
                    angle += rotateSpeed;
                }
                if (points.size() < 2) continue;
                g.part();
                int first = g.count, n = points.size();
                for (int v = 0; v < n; v++) {
                    float[] q = points.get(v);
                    float[] normal = {q[3], q[4], q[5]}, binormal = {q[6], q[7], q[8]};
                    float[] tangent = normalize(cross(normal, binormal));
                    float percent = v / (float) (n - 1);
                    float scale = (widthEnd - widthStart) * percent + widthStart;
                    float roll = (float) Math.toRadians((angleEnd - angleStart) * percent + angleStart);
                    float c = (float) Math.cos(roll), s = (float) Math.sin(roll);
                    for (int i = 0; i < cross.length; i++) {
                        float[] k = cross[i];
                        float x = q[0], y = q[1], z = q[2];
                        for (int a = 0; a < 3; a++) {
                            float rt = tangent[a] * c + normal[a] * s, rn = -tangent[a] * s + normal[a] * c;
                            float off = (rt * k[0] + binormal[a] * k[1] + rn * k[2]) * scale;
                            if (a == 0) x += off;
                            else if (a == 1) y += off;
                            else z += off;
                        }
                        g.add(x, y, z, uvs[i], 1f - percent);
                    }
                }
                for (int v = 0; v < n - 1; v++) {
                    for (int i = 0; i + 1 < edges.length; i += 2) {
                        int v00 = first + edges[i] + v * cross.length, v10 = first + edges[i + 1] + v * cross.length;
                        int v01 = v00 + cross.length, v11 = v10 + cross.length;
                        g.triangle(v00, v10, v11);
                        g.triangle(v00, v11, v01);
                    }
                }
            }
        }

        private float[] point(float angle, float depth) {
            float[] p = new float[2];
            profile.at(depth, p);
            return new float[]{p[0] * (float) Math.sin(angle), p[1], p[0] * (float) Math.cos(angle)};
        }
    }

    /** Positions, UVs and faces as they are generated. */
    static final class Geometry {

        int count;
        private float[] data = new float[5 * 256];
        final List<Integer> faces = new ArrayList<>();
        /** Where each part starts: its first vertex and its first face index. Normals are welded within a part. */
        private final List<int[]> parts = new ArrayList<>();

        /** Starts a part: what follows shares no normal with what came before, as each Effekseer ribbon. */
        void part() {
            parts.add(new int[]{count, faces.size()});
        }

        void add(float x, float y, float z, float u, float v) {
            if (data.length < (count + 1) * 5) data = Arrays.copyOf(data, data.length * 2);
            int at = count++ * 5;
            data[at] = x;
            data[at + 1] = y;
            data[at + 2] = z;
            data[at + 3] = u;
            data[at + 4] = v;
        }

        void triangle(int a, int b, int c) {
            faces.add(a);
            faces.add(b);
            faces.add(c);
        }

        float x(int i) { return data[i * 5]; }
        float y(int i) { return data[i * 5 + 1]; }
        float z(int i) { return data[i * 5 + 2]; }
        float u(int i) { return data[i * 5 + 3]; }
        float v(int i) { return data[i * 5 + 4]; }

        void set(int i, float x, float y, float z) {
            data[i * 5] = x;
            data[i * 5 + 1] = y;
            data[i * 5 + 2] = z;
        }

        /** Y swapped with {@code axis}: a mirror, so every face's winding flips back. */
        void swapAxis(Axis axis) {
            int other = axis == Axis.X ? 0 : 2;
            for (int i = 0; i < count; i++) {
                float t = data[i * 5 + 1];
                data[i * 5 + 1] = data[i * 5 + other];
                data[i * 5 + other] = t;
            }
            for (int f = 0; f < faces.size(); f += 3) {
                int t = faces.get(f);
                faces.set(f, faces.get(f + 2));
                faces.set(f + 2, t);
            }
        }

        /**
         * Smooth normals: each face's, summed over every vertex of its part at one position (to 1/1024 of a block, so a
         * seam shares them), degenerate faces skipped, as Effekseer's {@code CalculateNormal}.
         */
        float[] normals() {
            float[] out = new float[count * 3];
            if (parts.isEmpty() || parts.get(0)[0] != 0) parts.add(0, new int[]{0, 0});
            for (int p = 0; p < parts.size(); p++) {
                boolean last = p == parts.size() - 1;
                normals(parts.get(p)[0], last ? count : parts.get(p + 1)[0], parts.get(p)[1],
                        last ? faces.size() : parts.get(p + 1)[1], out);
            }
            return out;
        }

        private void normals(int firstVertex, int endVertex, int firstFace, int endFace, float[] out) {
            Map<Long, float[]> byPosition = new HashMap<>();
            for (int f = firstFace; f < endFace; f += 3) {
                int a = faces.get(f), b = faces.get(f + 1), c = faces.get(f + 2);
                float[] e1 = {x(b) - x(a), y(b) - y(a), z(b) - z(a)}, e2 = {x(c) - x(a), y(c) - y(a), z(c) - z(a)};
                float[] e3 = {x(c) - x(b), y(c) - y(b), z(c) - z(b)};
                for (int k : new int[]{a, b, c}) byPosition.computeIfAbsent(key(k), q -> new float[3]);
                if (length(e1) < 1e-4f || length(e2) < 1e-4f || length(e3) < 1e-4f) continue;
                float[] n = normalize(CgProceduralMesh.cross(e1, e2));
                for (int k : new int[]{a, b, c}) {
                    float[] sum = byPosition.get(key(k));
                    sum[0] += n[0];
                    sum[1] += n[1];
                    sum[2] += n[2];
                }
            }
            for (int i = firstVertex; i < endVertex; i++) {
                float[] n = byPosition.get(key(i));
                if (n == null || length(n) == 0f) {
                    out[i * 3 + 1] = 1f;
                    continue;
                }
                float[] u = normalize(n);
                out[i * 3] = u[0];
                out[i * 3 + 1] = u[1];
                out[i * 3 + 2] = u[2];
            }
        }

        private long key(int i) {
            long qx = Math.round(x(i) * 1024f) & 0x1FFFFF, qy = Math.round(y(i) * 1024f) & 0x1FFFFF;
            long qz = Math.round(z(i) * 1024f) & 0x1FFFFF;
            return qx | qy << 21 | qz << 42;
        }
    }

    /** Effekseer's {@code SplineGenerator} in two dimensions, and the walk by length {@code RotatorSpline3} takes. */
    private static final class Spline {

        private final float[][] a = new float[4][], b = new float[4][], c = new float[4][], d = new float[4][];
        private final float[] lengths = new float[3];
        private final boolean[] same = new boolean[3];
        private float total;

        Spline(float[] r, float[] h) {
            for (int i = 0; i < 4; i++) {
                a[i] = new float[]{r[i], h[i]};
                b[i] = new float[2];
                c[i] = new float[2];
                d[i] = new float[2];
            }
            float[][] w = new float[4][2];
            for (int k = 0; k < 2; k++) {
                for (int i = 1; i < 3; i++) c[i][k] = (a[i - 1][k] - 2f * a[i][k] + a[i + 1][k]) * 3f;
                for (int i = 1; i < 3; i++) {
                    float t = 4f - w[i - 1][k];
                    c[i][k] = (c[i][k] - c[i - 1][k]) / t;
                    w[i][k] = 1f / t;
                }
                for (int i = 2; i > 0; i--) c[i][k] = c[i][k] - c[i + 1][k] * w[i][k];
                for (int i = 0; i < 3; i++) {
                    d[i][k] = (c[i + 1][k] - c[i][k]) / 3f;
                    b[i][k] = a[i + 1][k] - a[i][k] - c[i][k] - d[i][k];
                }
            }
            for (int i = 0; i < 3; i++) {
                float dr = a[i + 1][0] - a[i][0], dh = a[i + 1][1] - a[i][1];
                lengths[i] = (float) Math.sqrt(dr * dr + dh * dh);
                same[i] = dr == 0f && dh == 0f;
                total += lengths[i];
            }
        }

        void at(float t, float[] out) {
            float distance = total * clamp(t, 0f, 1f), value = 3f;
            for (int i = 0; i < 3; i++) {
                if (distance <= lengths[i]) {
                    value = i + (lengths[i] > 0f ? distance / lengths[i] : 0f);
                    break;
                }
                distance -= lengths[i];
            }
            int j = Math.min((int) Math.floor(value), 3);
            float dt = value - j;
            if (j < 3 && same[j]) {
                out[0] = a[j][0];
                out[1] = a[j][1];
                return;
            }
            for (int k = 0; k < 2; k++) out[k] = a[j][k] + (b[j][k] + (c[j][k] + d[j][k] * dt) * dt) * dt;
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static float[] sub(float[] p, float[] q) {
        return new float[]{p[0] - q[0], p[1] - q[1], p[2] - q[2]};
    }

    static float[] cross(float[] p, float[] q) {
        return new float[]{p[1] * q[2] - p[2] * q[1], p[2] * q[0] - p[0] * q[2], p[0] * q[1] - p[1] * q[0]};
    }

    static float length(float[] v) {
        return (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }

    static float[] normalize(float[] v) {
        float l = length(v);
        return l > 0f ? new float[]{v[0] / l, v[1] / l, v[2] / l} : v;
    }
}
