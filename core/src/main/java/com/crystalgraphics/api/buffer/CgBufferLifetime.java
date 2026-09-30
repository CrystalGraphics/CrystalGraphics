package com.crystalgraphics.api.buffer;

/**
 * How long a shader buffer's contents must stay readable -- which decides where they live. Pass it when
 * creating any SSBO or UBO; the factories without it mean {@link #RETAINED}.
 *
 * <pre>{@code
 * // Instance data, written before the draws that read it, every frame:
 * CgShaderBuffer particles = CgShaderBufferRegistry.get()
 *         .getOrCreate("Particles", PARTICLE_FORMAT, 0, CgBufferLifetime.FRAME);
 * particles.beginWrite(n);
 * // ... n records ...
 * particles.endWrite();          // lands in this frame's region, and re-binds there
 * mesh.drawInstanced(n);
 *
 * // A block uploaded before every draw that reads it -- in frames where nothing changed too:
 * CgUniformBuffer light = CgShaderBufferRegistry.get()
 *         .getOrCreateUbo(LIGHT_FORMAT, "LightBlock", 0, CgBufferLifetime.FRAME);
 * light.writer().reset().beginRecord().vec4("color", r, g, b, 1f);
 * light.endRecord();
 * light.upload();                // an unchanged block already uploaded this frame costs a compare
 * light.bind();
 *
 * // Written once, then read by draws that never upload it:
 * CgUniformBuffer config = CgShaderBufferRegistry.get()
 *         .getOrCreateUbo(CONFIG_FORMAT, "ConfigBlock", 1);   // RETAINED
 * }</pre>
 *
 * <p>Easy to get wrong:</p>
 * <ul>
 *   <li>A {@link #FRAME} buffer read in a frame that did not upload it reads another frame's bytes: the ring
 *       reuses a region three frames on, while a draw may still read it. Nothing reports it -- the values are
 *       simply wrong. Upload before the draw, every frame; a {@code CgUniformBuffer} makes that cheap.</li>
 *   <li>{@link #FRAME} costs nothing extra to ask for where it cannot apply: a TBO (the path below GL 4.3, where
 *       {@code glTexBuffer} reads from offset 0) and a stream tier forced to {@code orphan} or {@code subdata}
 *       both fall back to {@link #RETAINED}'s storage, which is correct for every use.</li>
 * </ul>
 */
public enum CgBufferLifetime {

    /**
     * Uploaded in every frame that reads it. The frame ring: each upload a new offset in this frame's region,
     * bound by range, with no orphan and no driver rename -- one plain copy on the persistent tier.
     */
    FRAME,

    /**
     * Readable until the next upload, however many frames apart. Orphaning storage at offset 0: each upload
     * costs the driver a rename, which is the price of keeping the bytes for draws that never upload.
     */
    RETAINED
}
