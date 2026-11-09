package com.crystalgraphics.audio;

import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.world.CgAttachment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Sound, one call each: play one by name, at the listener or in the world; load a file and play or loop it; stop one,
 * preload one, push a mix. Every call runs on the client world's engine ({@link #engine()}), made at its first use,
 * as Unreal's {@code UGameplayStatics} plays on the world's audio device.
 *
 * <pre>{@code
 * CgSounds.play2D("minecraft:ui.button.click");                         // a click, at the listener
 * long boom = CgSounds.playAt("minecraft:entity.generic.explode", x, y, z);
 * CgSounds.stop(boom);
 * CgSounds.playAttached("minecraft:entity.blaze.ambient", mob);          // following a CgAttachment as it moves
 *
 * static final String SHOUT = "kakarot:sound/V_PL_SKL_01";              // a kit's cue: what an asset names
 * CgSounds.preload(SHOUT);                                              // at load, off the frame
 *
 * CgSound hum = CgSounds.load("mymod:sounds/engine_hum.ogg");           // a file of your own
 * long id = CgSounds.loop(hum, CgSoundClass.AMBIENT, 0.6f);
 * CgSounds.engine().activeSounds().at(id, x, y, z);                     // what the engine does past these
 *
 * CgSounds.push(UNDERWATER);   // a mix: classes adjusted while it is pushed
 * CgSounds.pop(UNDERWATER);
 * }</pre>
 *
 * <ul>
 *   <li>A name is a {@link CgSoundRef}'s: a host event ({@code namespace:path}, read from the packs' {@code sounds.json}),
 *       a file ({@code .ogg}), or a kit's cue. One with no namespace, or that the host lacks, plays nothing and is logged
 *       once; each is compiled at its first use and kept.</li>
 *   <li>{@link #play2D} is unplaced and unattenuated, in {@link CgSoundClass#UI} unless told, which plays on behind a
 *       pause; {@link #playAt} is placed and attenuated as its sound says, in {@link CgSoundClass#SFX} unless told.</li>
 *   <li>A play answers the sound's id, what {@link #stop} and the engine's {@code activeSounds()} take; 0 where nothing
 *       played (every voice taken, no such sound, no output).</li>
 *   <li>Where the host has no audio output (a dedicated server, the harness, a test) nothing plays, {@link #load}
 *       answers null, and no engine is made.</li>
 *   <li>Frame thread, but {@link #preload}, which a worker runs.</li>
 * </ul>
 */
public final class CgSounds {

    private static final Logger LOGGER = LogManager.getLogger("CgAudio");
    private static final Map<String, CgSoundRef> REFS = new HashMap<>();
    private static final Set<String> MISSING = new HashSet<>();
    private static final double[] POINT = new double[3];

    private CgSounds() {
    }

    /**
     * The client world's engine ({@link CgAudioEngine#active}), made at the first ask; null where the host has no audio
     * output. For what these calls do not cover: placing a playing sound, its attenuation, priority and concurrency,
     * submixes, subtitles.
     *
     * <pre>{@code
     * CgAudioEngine audio = CgSounds.engine();
     * if (audio != null) audio.activeSounds().at(id, x, y, z).priority(id, 2f);
     * }</pre>
     */
    @Nullable
    public static CgAudioEngine engine() {
        return CgAudioEngine.active(CgWorld.client());
    }

    /**
     * Plays {@code sound} at the listener, in the UI class: Unreal's {@code PlaySound2D}.
     *
     * <pre>{@code
     * CgSounds.play2D("minecraft:ui.button.click");
     * }</pre>
     */
    public static long play2D(String sound) {
        return play2D(sound, CgSoundClass.UI, 1f, 1f);
    }

    /**
     * Plays {@code sound} at the listener in {@code soundClass}, at {@code volume} and {@code pitch} times its own.
     *
     * <pre>{@code
     * CgSounds.play2D("mymod:sounds/ui/open.ogg", CgSoundClass.UI, 0.8f, 1.2f);   // quieter and higher
     * CgSounds.play2D("mymod:sounds/stinger.ogg", CgSoundClass.MUSIC, 1f, 1f);    // under the music slider
     * }</pre>
     */
    public static long play2D(String sound, CgSoundClass soundClass, float volume, float pitch) {
        return play(sound, soundClass, Double.NaN, Double.NaN, Double.NaN, volume, pitch);
    }

    /**
     * Plays {@code sound} at a point in the world, in the SFX class: Unreal's {@code PlaySoundAtLocation}.
     *
     * <pre>{@code
     * CgSounds.playAt("minecraft:entity.generic.explode", x, y, z);
     * }</pre>
     */
    public static long playAt(String sound, double x, double y, double z) {
        return playAt(sound, CgSoundClass.SFX, x, y, z, 1f, 1f);
    }

    /**
     * Plays {@code sound} at a point in the world in {@code soundClass}, at {@code volume} and {@code pitch} times its own.
     *
     * <pre>{@code
     * CgSounds.playAt("kakarot:sound/S_battle_punch_sw", CgSoundClass.SFX, x, y, z, 1f, 0.9f);
     * }</pre>
     */
    public static long playAt(String sound, CgSoundClass soundClass, double x, double y, double z, float volume, float pitch) {
        return play(sound, soundClass, x, y, z, volume, pitch);
    }

    /**
     * Plays {@code sound} on {@code at}, following it as it moves, in the SFX class: Unreal's {@code SpawnSoundAttached}.
     * Once {@code at} is gone the sound plays on where it last was; {@link #stop} it to cut it there.
     *
     * <pre>{@code
     * CgSounds.playAttached("minecraft:entity.blaze.ambient", CgEntityAttachments.of(mobId));
     * CgSounds.playAttached("mymod:sounds/charge.ogg", CgEntityAttachments.of(playerId, CgEntityAttachments.RIGHT_HAND));
     * }</pre>
     */
    public static long playAttached(String sound, CgAttachment at) {
        return playAttached(sound, at, CgSoundClass.SFX, 1f, 1f, false);
    }

    /**
     * {@link #playAttached(String, CgAttachment)} in {@code soundClass}, at {@code volume} and {@code pitch} times its
     * own; stopped with its parent where {@code stopWhenGone}.
     *
     * <pre>{@code
     * CgSounds.playAttached("mymod:sounds/engine.ogg", cart, CgSoundClass.SFX, 0.7f, 1f, true);   // silent once the cart is
     * }</pre>
     */
    public static long playAttached(String sound, CgAttachment at, CgSoundClass soundClass, float volume, float pitch,
            boolean stopWhenGone) {
        CgAudioEngine audio = engine();
        double[] p = POINT;
        if (audio == null || !at.place(p, null)) return 0L;
        long id = play(sound, soundClass, p[0], p[1], p[2], volume, pitch);
        audio.activeSounds().follow(id, at, stopWhenGone);
        return id;
    }

    /**
     * Plays a loaded {@code sound} looping on {@code at}, following it, until {@link #stop}, or until {@code at} is gone.
     * Heard as {@link CgSoundAttenuation#DEFAULT} from it.
     *
     * <pre>{@code
     * long hum = CgSounds.loopAttached(BEAM_HUM, beam.attachment("tip"), CgSoundClass.SFX, 0.8f);
     * }</pre>
     */
    public static long loopAttached(@Nullable CgSound sound, CgAttachment at, CgSoundClass soundClass, float volume) {
        CgAudioEngine audio = engine();
        double[] p = POINT;
        if (audio == null || sound == null || !at.place(p, null)) return 0L;
        long id = audio.play(sound, soundClass, volume, 1f, true, Double.NaN);
        audio.activeSounds().at(id, p[0], p[1], p[2]).attenuation(id, CgSoundAttenuation.DEFAULT).follow(id, at, true);
        return id;
    }

    /**
     * A sound file, read and decoded once and kept: Ogg Vorbis, by path. Null where the host has no audio output.
     *
     * <pre>{@code
     * CgSound click = CgSounds.load("mymod:sounds/click.ogg");
     * }</pre>
     *
     * Loading reads and decodes on the calling thread: load ahead of the play.
     */
    @Nullable
    public static CgSound load(String path) {
        CgAudioEngine audio = engine();
        return audio == null ? null : audio.sounds().load(path);
    }

    /**
     * {@link #load(String)}, streamed where {@code stream}: its compressed bytes kept and decoded as it plays, for
     * music and long ambience. A sound past ten seconds streams either way.
     *
     * <pre>{@code
     * CgSound theme = CgSounds.load("mymod:sounds/theme.ogg", true);
     * }</pre>
     */
    @Nullable
    public static CgSound load(String path, boolean stream) {
        CgAudioEngine audio = engine();
        return audio == null ? null : audio.sounds().load(path, stream);
    }

    /**
     * Plays a loaded {@code sound} once at the listener in {@code soundClass}, at {@code volume}.
     *
     * <pre>{@code
     * long id = CgSounds.play(CLICK, CgSoundClass.UI, 1f);
     * }</pre>
     */
    public static long play(@Nullable CgSound sound, CgSoundClass soundClass, float volume) {
        CgAudioEngine audio = engine();
        return audio == null || sound == null ? 0L : audio.play(sound, soundClass, volume);
    }

    /**
     * Plays a loaded {@code sound} looping at the listener in {@code soundClass}, at {@code volume}, until {@link #stop}.
     *
     * <pre>{@code
     * long hum = CgSounds.loop(HUM, CgSoundClass.AMBIENT, 0.6f);
     * CgSounds.stop(hum);
     * }</pre>
     */
    public static long loop(@Nullable CgSound sound, CgSoundClass soundClass, float volume) {
        CgAudioEngine audio = engine();
        return audio == null || sound == null ? 0L : audio.play(sound, soundClass, volume, 1f, true, Double.NaN);
    }

    /** Stops the sound of id {@code id}; nothing for 0 or one that ended. */
    public static void stop(long id) {
        CgAudioEngine audio = engine();
        if (audio != null && id != 0L) audio.activeSounds().stop(id);
    }

    /**
     * Reads and decodes what {@code sound} may play, so its first play decodes nothing on the frame. Any thread: a
     * worker at load. A host event's variants are left to their first pick.
     *
     * <pre>{@code
     * CgJob.executor(CgJobLane.LATENCY).execute(() -> CgSounds.preload("mymod:sounds/beam_loop.ogg"));
     * }</pre>
     */
    public static void preload(String sound) {
        CgAudioEngine audio = engine();
        CgSoundRef ref = audio == null ? null : ref(sound);
        if (ref != null) audio.preload(ref);
    }

    /** {@code sound} compiled, as an asset names one; null, logged once, where it names nothing. */
    @Nullable
    public static synchronized CgSoundRef ref(String sound) {
        CgSoundRef ref = REFS.get(sound);
        if (ref != null || MISSING.contains(sound)) return ref;
        try {
            REFS.put(sound, ref = CgSoundRef.of(sound, "sound"));
            return ref;
        } catch (IllegalArgumentException | IllegalStateException unknown) {
            MISSING.add(sound);
            LOGGER.warn("Sound {} plays nothing: {}", sound, unknown.getMessage());
            return null;
        }
    }

    /**
     * Pushes {@code mix}: its class adjustments fade in, and hold until it is popped.
     *
     * <pre>{@code
     * CgSounds.push(UNDERWATER);
     * }</pre>
     */
    public static void push(CgSoundMix mix) {
        CgAudioEngine audio = engine();
        if (audio != null) audio.push(mix);
    }

    /** Pops {@code mix}: its adjustments fade out. */
    public static void pop(CgSoundMix mix) {
        CgAudioEngine audio = engine();
        if (audio != null) audio.pop(mix);
    }

    private static long play(String sound, CgSoundClass soundClass, double x, double y, double z, float volume, float pitch) {
        if (sound == null || sound.isEmpty()) return 0L;
        CgAudioEngine audio = engine();
        CgSoundRef ref = audio == null ? null : ref(sound);
        if (ref == null) return 0L;
        try {
            return audio.play(ref, soundClass, x, y, z, volume, pitch, false, Double.NaN, 0L);
        } catch (IllegalArgumentException | IllegalStateException unknown) {
            if (MISSING.add(sound)) LOGGER.warn("Sound {} plays nothing: {}", sound, unknown.getMessage());
            return 0L;
        }
    }
}
