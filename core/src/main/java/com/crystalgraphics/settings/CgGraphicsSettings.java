package com.crystalgraphics.settings;

/**
 * CrystalGraphics' own settings, in {@code config/crystalgraphics.toml}: what a player changes about effects and the
 * camera. The engine applies them; an effect never reads one.
 *
 * <pre>{@code
 * float share = CgGraphicsSettings.DENSITY.get();
 * if (CgGraphicsSettings.QUALITY.get().atLeast(CgQuality.MEDIUM)) ...
 * for (CgSetting s : CgGraphicsSettings.FILE.settings()) ...   // a settings screen
 * }</pre>
 *
 * <p>Performance settings are ours, never Minecraft's: its Particles and Graphics options price its own renderer, and
 * players lower them for frames ours do not cost. Comfort settings multiply Minecraft's own accessibility scales, so
 * they can lower a shake and never raise one.</p>
 */
public final class CgGraphicsSettings {

    public static final CgSettings FILE = CgSettings.file("crystalgraphics");

    public static final CgSetting.Number DENSITY = FILE.number("effects", "density", "Particle density",
            "Share of each effect's particles to spawn, thinned evenly so an effect keeps its shape.",
            1f, 0f, 1f, 0.05f);

    public static final CgSetting.Toggle FOLLOW_MINECRAFT_PARTICLES = FILE.toggle("effects", "follow_minecraft_particles",
            "Follow Minecraft's Particles", "Also spawn Minecraft's Particles share: 50% on Decreased, 15% on Minimal.",
            false);

    public static final CgSetting.Choice<CgQuality> QUALITY = FILE.choice("effects", "quality", "Quality",
            "How much each effect draws. Low drops distortion, bloom and optional layers, and halves optional particles.",
            CgQuality.HIGH);

    public static final CgSetting.Number SHAKE = FILE.number("comfort", "shake", "Camera shake",
            "Scales camera shake, on top of Minecraft's Screen Effects.", 1f, 0f, 1f, 0.05f);

    public static final CgSetting.Number FOV_KICK = FILE.number("comfort", "fov_kick", "Field of view kick",
            "Scales the field-of-view punch of big impacts, on top of Minecraft's FOV Effects.", 1f, 0f, 1f, 0.05f);

    private CgGraphicsSettings() {
    }
}
