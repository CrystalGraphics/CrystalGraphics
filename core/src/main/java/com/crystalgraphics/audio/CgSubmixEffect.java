package com.crystalgraphics.audio;

/**
 * One effect in a submix's chain: Unreal's {@code FSoundEffectSubmix}. It processes the submix's whole buffer in place
 * on the audio thread, after every voice and child submix is in it.
 *
 * <pre>{@code
 * CgSubmixEffect halve = (buffer, frames, channels, sampleRate) -> {
 *     for (int i = 0; i < frames * channels; i++) buffer[i] *= 0.5f;
 * };
 * audio.submix("world").effect(halve);
 * }</pre>
 *
 * <ul>
 *   <li>Audio thread only, and allocate nothing.</li>
 *   <li>Its state (a filter's memory) is its own: one instance, one submix.</li>
 * </ul>
 */
@FunctionalInterface
public interface CgSubmixEffect {

    void process(float[] buffer, int frames, int channels, int sampleRate);
}
