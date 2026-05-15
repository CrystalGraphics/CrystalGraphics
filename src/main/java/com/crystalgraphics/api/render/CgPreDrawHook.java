package com.crystalgraphics.api.render;

import com.crystalgraphics.gl.buffer.shader.CgShaderBuffer;

import java.util.function.Consumer;

/**
 * User-defined logic fired once per merged draw batch, after material bind, before the
 * instanced draw call. Primary use case: writing per-instance data to a secondary
 * {@link CgShaderBuffer} (SSBO/UBO)
 * that the material's vertex shader reads indexed by {@code gl_InstanceID}.
 *
 * <h3>Batch ordering contract</h3>
 * <p>{@code batch[offset + k]} corresponds to {@code gl_InstanceID = k}. The slice is in
 * sorted draw order (opaque: front-to-back; transparent: back-to-front) — NOT submission
 * order. Use {@link CgRenderCommand#tag} to identify which command occupies each slot.</p>
 *
 * <h3>Pass firing</h3>
 * <p>The hook fires in <em>every</em> renderer pass that draws this batch: depth prepass
 * and forward pass (two invocations per batch per frame for opaque geometry). The hook
 * must be idempotent — overwrite deterministic SSBO slots, never append or increment.</p>
 *
 * <h3>Merge rule</h3>
 * <p>Commands merge into one instanced draw only when they share the same
 * {@code CgPreDrawHook} <em>reference</em> ({@code ==}). Functionally identical lambdas
 * that are distinct objects will never merge. Store the hook as a shared field, not
 * constructed inline per command.</p>
 *
 * <h3>Constraints</h3>
 * <ul>
 *   <li>Do NOT mutate any field of the batch commands (material, mesh, matrices, custom0-3).</li>
 *   <li>Do NOT override draw-state GL calls (blend, depth func, cull) — fires after
 *       {@code doBind()}; those overrides will not be restored by {@code drawChain()}.</li>
 *   <li>The hook runs on the GL thread. No blocking operations.</li>
 * </ul>
 *
 * @see CgRenderCommand#tag
 * @see CgRenderCommand#preDrawHook
 */
@FunctionalInterface
public interface CgPreDrawHook {

    /**
     * Called once per merged batch, after material bind, before draw.
     *
     * @param batch  the renderer's sorted command array
     * @param offset index of the first command in this batch ({@code batch[offset]} = {@code gl_InstanceID 0})
     * @param count  number of commands in this batch (= the {@code drawInstanced} instance count)
     */
    void apply(CgRenderCommand[] batch, int offset, int count);

    /**
     * Convenience wrapper for hooks that assert they will never be called on a merged batch.
     * Throws {@link IllegalStateException} if {@code count > 1} — catches the misuse at draw
     * time rather than silently corrupting SSBO data or silently disabling instancing.
     *
     * <p><strong>Safe only when the command's material or mesh is structurally unique in the
     * scene</strong> (a different {@code CgMaterial} reference → different sort key → never
     * merges, regardless of hook). Do NOT assign the same {@code only()} instance to multiple
     * commands with the same material+mesh — that will merge and throw.</p>
     *
     * @param fn consumer receiving the single command for this draw
     * @return a hook that asserts single-instance draw and delegates to {@code fn}
     */
    static CgPreDrawHook only(Consumer<CgRenderCommand> fn) {
        return (batch, offset, count) -> {
            if (count != 1) throw new IllegalStateException(
                "CgPreDrawHook.only() called with count=" + count
                + ". Use a batch-aware hook or ensure structural no-merge (unique CgMaterial).");
            fn.accept(batch[offset]);
        };
    }
}
