package com.crystalgraphics.platform.audio;

import com.crystalgraphics.platform.CgService;

/**
 * Where the engine's mixed sound goes: an output stream on the device the host plays to. Unreal's
 * {@code IAudioMixerPlatformInterface}: the platform only opens the stream, and the engine renders every buffer.
 * Client only; a dedicated server has none.
 *
 * <pre>{@code
 * // the audio thread, which alone uses what it opens
 * CgAudioStream stream = CgPlatform.get(CgAudioOutput.SERVICE).open(new CgAudioOutput.Format(48000, 2, 512, 8));
 * while (running) {
 *     for (int n = stream.free(); n > 0; n--) {
 *         mixer.render(buffer);
 *         if (!stream.write(buffer)) { stream.close(); stream = null; break; }   // the device went: open again later
 *     }
 * }
 * }</pre>
 *
 * <pre>{@code
 * CgPlatform.provide(CgAudioOutput.SERVICE, new Lwjgl3AudioOutput(() -> deviceName()));   // a host, on a client
 * }</pre>
 *
 * <ul>
 *   <li>{@link #open} runs on the thread that will write the stream, and only that thread uses it.</li>
 *   <li>Null where no stream can open now (no device, the host's sound engine not up yet): ask again later.</li>
 *   <li>{@link #NONE} opens nothing: the server's, the harness's without a device, a test's.</li>
 * </ul>
 */
@FunctionalInterface
public interface CgAudioOutput {

    /** A stream's shape: interleaved float frames, {@code frames} a buffer, at most {@code buffers} queued. */
    record Format(int sampleRate, int channels, int frames, int buffers) {
        /** The seconds one buffer plays. */
        public double bufferSeconds() {
            return (double) frames / sampleRate;
        }
    }

    /** A stream on the output, or null where none can open now. */
    CgAudioStream open(Format format);

    CgAudioOutput NONE = format -> null;

    CgService<CgAudioOutput> SERVICE = CgService.of("crystalgraphics:audio_output", NONE);
}
