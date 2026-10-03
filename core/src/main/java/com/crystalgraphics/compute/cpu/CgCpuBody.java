package com.crystalgraphics.compute.cpu;

/**
 * A kernel's Java body: what the CPU tier runs where no GPU tier can run the kernel, or where the CPU tier is forced
 * (gpu-compute §6.4). It is handed a range of elements and reaches the dispatch's buffers, images and properties
 * through {@link CgCpuDispatch}, by the names the {@code .compute} declares.
 *
 * <pre>{@code
 * particles.kernel("Simulate").cpu(d -> {
 *     CgCpuBuffer state = d.buffer("STATE");
 *     int pos = state.field("positionLife").word(), vel = state.field("velocitySeed").word();
 *     float dt = d.time();
 *     for (int e = d.first(); e < d.end(); e++) {
 *         for (int c = 0; c < 3; c++) {
 *             state.setFloat(e, pos + c, state.getFloat(e, pos + c) + state.getFloat(e, vel + c) * dt);
 *         }
 *     }
 * });
 * }</pre>
 *
 * <ul>
 *   <li>A map, gather, append or image kernel's body runs on several threads at once, each over its own range: it
 *       writes its own elements only, as the shape says. Appends land in element order whatever thread made them.</li>
 *   <li>A scatter or general kernel's body runs once, on one thread, over every element: its writes at any index stay
 *       deterministic.</li>
 *   <li>The body runs in the frame, at the dispatch's place in it: keep it to the work the kernel does.</li>
 * </ul>
 */
@FunctionalInterface
public interface CgCpuBody {

    void run(CgCpuDispatch dispatch);
}
