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
            "How much each effect draws. Low drops distortion and optional layers, draws a cheaper bloom, and halves optional"
                    + " particles. Minecraft's Particles caps it: Medium on Decreased, Low on Minimal.",
            CgQuality.ULTRA);

    public static final CgSetting.Choice<CgQuality> ANTI_ALIASING = FILE.choice("rendering", "anti_aliasing", "Anti-aliasing",
            "How much temporal super resolution does where the picture changes. Low lets a newly seen edge smooth over a few"
                    + " frames; Medium and High smooth it at once; Ultra follows each edge farther.",
            CgQuality.LOW);

    // Motion blur, as Unreal's: r.DefaultFeature.MotionBlur, r.MotionBlurQuality, r.MotionBlur.Scale, .Amount, .Max, .TargetFPS

    public static final CgSetting.Toggle MOTION_BLUR = FILE.toggle("rendering", "motion_blur", "Motion blur",
            "Blurs what moves, and the world while the camera turns, along its motion as a film camera's shutter would."
                    + " Off, nothing blurs unless a camera or an area asks for it.",
            false);

    public static final CgSetting.Number MOTION_BLUR_QUALITY = FILE.number("rendering", "motion_blur_quality", "Motion blur quality",
            "Samples per pixel: 0 off, 1 to 4 for 4, 8, 12 or 16. -1 follows Quality (Low off, Medium and High 12, Ultra 16).",
            -1f, -1f, 4f, 1f);

    public static final CgSetting.Number MOTION_BLUR_SCALE = FILE.number("rendering", "motion_blur_scale", "Motion blur scale",
            "Scales every camera's and area's motion blur amount.", 1f, 0f, 50f, 0.05f);

    public static final CgSetting.Number MOTION_BLUR_AMOUNT = FILE.number("rendering", "motion_blur_amount", "Motion blur amount",
            "Replaces every camera's and area's amount, 0 to 1 of a frame's motion; -1 keeps theirs.", -1f, -1f, 1f, 0.05f);

    public static final CgSetting.Number MOTION_BLUR_MAX = FILE.number("rendering", "motion_blur_max", "Motion blur max",
            "Replaces the longest blur, in percent of the screen's width; -1 keeps theirs.", -1f, -1f, 100f, 0.5f);

    public static final CgSetting.Number MOTION_BLUR_TARGET_FPS = FILE.number("rendering", "motion_blur_target_fps",
            "Motion blur target FPS", "Replaces the frame rate whose shutter the blur is; 0 follows the game's, -1 keeps theirs.",
            -1f, -1f, 120f, 1f);

    public static final CgSetting.Number SHAKE = FILE.number("comfort", "shake", "Camera shake",
            "Scales camera shake, on top of Minecraft's Screen Effects.", 1f, 0f, 1f, 0.05f);

    public static final CgSetting.Number FOV_KICK = FILE.number("comfort", "fov_kick", "Field of view kick",
            "Scales the field-of-view punch of big impacts, on top of Minecraft's FOV Effects.", 1f, 0f, 1f, 0.05f);

    public static final CgSetting.Number FLASHES = FILE.number("comfort", "flashes", "Screen flashes",
            "Scales full-screen flashes and impact frames; 0 for none, for sensitivity to flashing light.", 1f, 0f, 1f, 0.05f);

    public static final CgSetting.Toggle SUBTITLES = FILE.toggle("comfort", "subtitles", "Subtitles",
            "Shows the lines moves and mods give their sounds at the bottom of the screen.",
            true);

    private CgGraphicsSettings() {
    }
}
