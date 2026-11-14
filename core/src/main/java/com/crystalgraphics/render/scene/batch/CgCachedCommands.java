package com.crystalgraphics.render.scene.batch;

import com.crystalgraphics.api.material.CgMaterialInterface;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.meshpass.CgMeshPassCommands;

import javax.annotation.Nullable;
import java.util.Arrays;

/**
 * A persistent primitive's mesh pass commands, kept across frames and views: Unreal's {@code StaticMeshCommandInfos},
 * the cached {@code FMeshDrawCommand}s of each static mesh per pass. An entry per (row of the primitive, pass type)
 * holds what the pass's processor added for that row, with what it was built from; a view reuses it while the row's
 * material, mesh, submesh, forward pipeline and the primitive's settings are the same, and builds it again otherwise.
 *
 * <pre>{@code
 * CgCachedCommands cache = batches.cache(b);                 // the row's primitive's
 * int row = batches.ordinal(b), type = CgEnginePasses.BASE.index();
 * if (!cache.valid(row, type, material, mesh, submesh, forward, settings)) {
 *     out.clear();
 *     processor.add(batch, primitive, out);
 *     cache.store(row, type, material, mesh, submesh, forward, settings, relevance, out);
 * }
 * for (int i = cache.first(row, type), end = i + cache.count(row, type); i < end; i++) use(cache.pipeline(i), ...);
 * }</pre>
 *
 * <ul>
 *   <li>The frame thread's, as its primitive is.</li>
 *   <li>Rebuilt entries leave their old commands behind; the arrays are compacted once more than half is dead.</li>
 * </ul>
 */
public final class CgCachedCommands {

    private int rows, types;
    /** Per entry (row * types + type): its key, whether it is built, where its commands are and how many. */
    private CgMaterialInterface[] keyMaterial = new CgMaterialInterface[0];
    private CgMesh[] keyMesh = new CgMesh[0];
    private CgPipeline[] keyForward = new CgPipeline[0];
    private int[] keySubmesh = new int[0], keySettings = new int[0], relevance = new int[0];
    private boolean[] built = new boolean[0];
    private int[] first = new int[0], count = new int[0];

    private int size, live;
    private CgPipeline[] pipelines = new CgPipeline[4];
    private CgMaterialInterface[] materials = new CgMaterialInterface[4];
    private CgMesh[] meshes = new CgMesh[4];
    private int[] submeshes = new int[4];

    /** Whether row {@code row}'s commands for pass {@code type} were built from these, and stand. */
    public boolean valid(int row, int type, CgMaterialInterface material, CgMesh mesh, int submesh,
                         @Nullable CgPipeline forward, int settings) {
        if (row >= rows || type >= types) return false;
        int e = row * types + type;
        return built[e] && keyMaterial[e] == material && keyMesh[e] == mesh && keySubmesh[e] == submesh
                && keyForward[e] == forward && keySettings[e] == settings;
    }

    /** Row {@code row}'s commands for pass {@code type}: what {@code out} holds, built from the rest. */
    public void store(int row, int type, CgMaterialInterface material, CgMesh mesh, int submesh,
                      @Nullable CgPipeline forward, int settings, int rowRelevance, CgMeshPassCommands out) {
        size(row + 1, type + 1);
        int e = row * types + type;
        if (built[e]) live -= count[e];
        if (size > 64 && live * 2 < size) compact();
        reserve(out.size());
        first[e] = size;
        count[e] = out.size();
        for (int i = 0; i < out.size(); i++, size++) {
            pipelines[size] = out.pipeline(i);
            materials[size] = out.material(i);
            meshes[size] = out.mesh(i);
            submeshes[size] = out.submesh(i);
        }
        live += out.size();
        built[e] = true;
        keyMaterial[e] = material;
        keyMesh[e] = mesh;
        keySubmesh[e] = submesh;
        keyForward[e] = forward;
        keySettings[e] = settings;
        relevance[e] = rowRelevance;
    }

    /** The relevance row {@code row} had when its entry for {@code type} was built. */
    public int relevance(int row, int type) {
        return relevance[row * types + type];
    }

    public int first(int row, int type) {
        return first[row * types + type];
    }

    public int count(int row, int type) {
        return count[row * types + type];
    }

    public CgPipeline pipeline(int i) {
        return pipelines[i];
    }

    public CgMaterialInterface material(int i) {
        return materials[i];
    }

    public CgMesh mesh(int i) {
        return meshes[i];
    }

    public int submesh(int i) {
        return submeshes[i];
    }

    /** Commands kept, dead or alive: what {@link #compact} reclaims. */
    public int size() {
        return size;
    }

    /** Forgets everything: the primitive left its scene. */
    public void clear() {
        Arrays.fill(built, false);
        Arrays.fill(keyMaterial, null);
        Arrays.fill(keyMesh, null);
        Arrays.fill(keyForward, null);
        Arrays.fill(pipelines, 0, size, null);
        Arrays.fill(materials, 0, size, null);
        Arrays.fill(meshes, 0, size, null);
        size = live = 0;
    }

    private void size(int needRows, int needTypes) {
        if (needRows <= rows && needTypes <= types) return;
        int r = Math.max(rows, needRows), t = Math.max(types, needTypes), n = r * t;
        CgMaterialInterface[] km = new CgMaterialInterface[n];
        CgMesh[] kme = new CgMesh[n];
        CgPipeline[] kf = new CgPipeline[n];
        int[] ks = new int[n], kse = new int[n], rel = new int[n], f = new int[n], c = new int[n];
        boolean[] b = new boolean[n];
        for (int row = 0; row < rows; row++) {
            for (int type = 0; type < types; type++) {
                int from = row * types + type, to = row * t + type;
                km[to] = keyMaterial[from];
                kme[to] = keyMesh[from];
                kf[to] = keyForward[from];
                ks[to] = keySubmesh[from];
                kse[to] = keySettings[from];
                rel[to] = relevance[from];
                f[to] = first[from];
                c[to] = count[from];
                b[to] = built[from];
            }
        }
        keyMaterial = km;
        keyMesh = kme;
        keyForward = kf;
        keySubmesh = ks;
        keySettings = kse;
        relevance = rel;
        first = f;
        count = c;
        built = b;
        rows = r;
        types = t;
    }

    private void reserve(int more) {
        if (size + more <= pipelines.length) return;
        int n = Math.max(size + more, pipelines.length * 2);
        pipelines = Arrays.copyOf(pipelines, n);
        materials = Arrays.copyOf(materials, n);
        meshes = Arrays.copyOf(meshes, n);
        submeshes = Arrays.copyOf(submeshes, n);
    }

    /** Moves every built entry's commands to the front, in entry order. */
    private void compact() {
        CgPipeline[] p = new CgPipeline[pipelines.length];
        CgMaterialInterface[] m = new CgMaterialInterface[pipelines.length];
        CgMesh[] me = new CgMesh[pipelines.length];
        int[] s = new int[pipelines.length];
        int at = 0;
        for (int e = 0; e < built.length; e++) {
            if (!built[e]) continue;
            int from = first[e];
            first[e] = at;
            for (int k = 0; k < count[e]; k++, at++) {
                p[at] = pipelines[from + k];
                m[at] = materials[from + k];
                me[at] = meshes[from + k];
                s[at] = submeshes[from + k];
            }
        }
        pipelines = p;
        materials = m;
        meshes = me;
        submeshes = s;
        size = live = at;
    }
}
