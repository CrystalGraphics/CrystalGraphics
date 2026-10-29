package com.crystalgraphics.audio;

import java.util.Objects;

import javax.annotation.Nullable;

import com.crystalgraphics.platform.audio.CgHostVolume;

/**
 * A category of sounds in a tree: Unreal's {@code USoundClass}. Its volume and pitch multiply down the tree, a sound
 * mix adjusts it, and it names the host's slider that rules it, the submix its sounds go to and whether they hold
 * while the game is paused.
 *
 * <pre>{@code
 * audio.play(boom, CgSoundClass.SFX, 1f);
 * CgSoundClass.MUSIC.volume(0.5f);                                       // every music sound, halved
 * CgSoundClass hits = new CgSoundClass("mymod:hits", CgSoundClass.SFX); // a mod's, under sfx
 * }</pre>
 *
 * <ul>
 *   <li>A class made under another takes its slider, submix and pause unless given its own.</li>
 *   <li>Frame thread only for its volume and pitch.</li>
 * </ul>
 */
public final class CgSoundClass {

    public static final CgSoundClass MASTER = new CgSoundClass("crystalgraphics:master", null).submix("main");
    /** Effects and moves: the host's players slider. */
    public static final CgSoundClass SFX = new CgSoundClass("crystalgraphics:sfx", MASTER)
            .host(CgHostVolume.Category.PLAYERS).submix("world");
    public static final CgSoundClass VOICE = new CgSoundClass("crystalgraphics:voice", MASTER)
            .host(CgHostVolume.Category.VOICE).submix("world");
    public static final CgSoundClass MUSIC = new CgSoundClass("crystalgraphics:music", MASTER)
            .host(CgHostVolume.Category.MUSIC).submix("music");
    public static final CgSoundClass AMBIENT = new CgSoundClass("crystalgraphics:ambient", MASTER)
            .host(CgHostVolume.Category.AMBIENT).submix("world");
    /** Menus: plays on while the game is paused. */
    public static final CgSoundClass UI = new CgSoundClass("crystalgraphics:ui", MASTER)
            .host(CgHostVolume.Category.UI).submix("ui").pausesWithGame(false);

    private final String id;
    @Nullable
    private final CgSoundClass parent;
    @Nullable
    private CgHostVolume.Category host;
    @Nullable
    private String submix;
    private boolean pauses = true;
    private float volume = 1f, pitch = 1f;

    public CgSoundClass(String id, @Nullable CgSoundClass parent) {
        this.id = Objects.requireNonNull(id, "id");
        this.parent = parent;
        if (parent != null) {
            host = parent.host;
            submix = parent.submix;
            pauses = parent.pauses;
        }
    }

    public String id() {
        return id;
    }

    @Nullable
    public CgSoundClass parent() {
        return parent;
    }

    /** The host slider ruling it, or null for none. */
    public CgSoundClass host(@Nullable CgHostVolume.Category category) {
        host = category;
        return this;
    }

    @Nullable
    public CgHostVolume.Category host() {
        return host;
    }

    /** The id of the submix its sounds go to. */
    public CgSoundClass submix(String id) {
        submix = id;
        return this;
    }

    public String submix() {
        return submix == null ? "main" : submix;
    }

    public CgSoundClass pausesWithGame(boolean pauses) {
        this.pauses = pauses;
        return this;
    }

    public boolean pausesWithGame() {
        return pauses;
    }

    public CgSoundClass volume(float volume) {
        this.volume = volume;
        return this;
    }

    public float volume() {
        return volume;
    }

    public CgSoundClass pitch(float pitch) {
        this.pitch = pitch;
        return this;
    }

    public float pitch() {
        return pitch;
    }

    /** Whether it is {@code other} or under it. */
    public boolean within(CgSoundClass other) {
        for (CgSoundClass c = this; c != null; c = c.parent) if (c == other) return true;
        return false;
    }

    @Override
    public String toString() {
        return "CgSoundClass[" + id + "]";
    }
}
