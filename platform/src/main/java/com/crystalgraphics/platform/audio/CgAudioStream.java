package com.crystalgraphics.platform.audio;

/**
 * An open output stream ({@link CgAudioOutput#open}): it takes whole buffers of interleaved float samples and plays
 * them in order. Only the thread that opened it uses it.
 *
 * <pre>{@code
 * for (int n = stream.free(); n > 0; n--) {
 *     mixer.render(buffer);            // frames × channels floats in [-1, 1]
 *     if (!stream.write(buffer)) break;
 * }
 * }</pre>
 *
 * <ul>
 *   <li>A buffer is exactly its format's {@code frames × channels} floats.</li>
 *   <li>{@link #write} answers false once the device is gone or the host tore its sound down: close and reopen.</li>
 *   <li>An underrun is the device running out of queued buffers; it plays silence and is counted.</li>
 * </ul>
 */
public interface CgAudioStream extends AutoCloseable {

    /** How many buffers it takes now, without waiting. */
    int free();

    /** Queues one buffer; false where the stream can no longer play. */
    boolean write(float[] interleaved);

    /** Underruns since it opened. */
    int underruns();

    /** Seconds from a buffer's write to its first sample reaching the speaker, as well as the device tells it. */
    double latency();

    @Override
    void close();
}
