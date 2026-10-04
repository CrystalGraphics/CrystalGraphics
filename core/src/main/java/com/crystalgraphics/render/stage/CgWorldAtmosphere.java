package com.crystalgraphics.render.stage;

import com.crystalgraphics.render.draw.CgPassConstants;

/**
 * The world's sun and fog, as a pass's {@code cg_SunDirection}, {@code cg_FogColor} and {@code cg_FogParams}: what
 * {@link CgStageFrame#defaults} fills every stage's constants with, so a material lit by default fogs as Minecraft's
 * own things do.
 *
 * <pre>{@code
 * CgPassConstants constants = frame.defaults(new CgPassConstants());   // already applied
 * CgWorldAtmosphere.apply(environment, constants);                      // a pass with constants of its own
 * }</pre>
 *
 * <ul>
 *   <li>Where the host hands its fog over (Minecraft 1.17.1 to 1.21.1) it is used as given. Elsewhere it is
 *       Minecraft's rule for terrain fog, from the render distance, coloured from the sky; in water, lava and powder
 *       snow, and while blind, the fog of each.</li>
 *   <li>No level, no render distance: no fog, and the sun {@link CgPassConstants}' default.</li>
 * </ul>
 */
public final class CgWorldAtmosphere {

    // Render thread: one stage fills at a time.
    private static final float[] SUN = new float[3];

    private CgWorldAtmosphere() {
    }

    public static CgPassConstants apply(CgHostEnvironment world, CgPassConstants constants) {
        sun(world, constants);
        fog(world, constants);
        return constants;
    }

    private static void sun(CgHostEnvironment world, CgPassConstants constants) {
        if (Float.isNaN(world.celestialAngle())) {
            constants.defaultSun();
            return;
        }
        world.sunDirection(SUN);
        float sign = SUN[1] < 0f ? -1f : 1f;   // the moon, opposite, while the sun is down
        float daylight = world.skyBrightness();
        if (Float.isNaN(daylight)) daylight = clamp(SUN[1] * 4f + 0.5f);
        constants.sun(SUN[0] * sign, SUN[1] * sign, SUN[2] * sign, daylight);
    }

    private static void fog(CgHostEnvironment world, CgPassConstants constants) {
        float distance = world.renderDistance();
        boolean given = !Float.isNaN(world.fogEnd());
        if (Float.isNaN(distance) && !given) {
            constants.noFog();
            return;
        }
        float blind = Math.max(nan0(world.blindness()), nan0(world.darkness()));
        switch (world.cameraFluid()) {
            case CgHostEnvironment.FLUID_WATER -> fog(world, constants, 0.05f, 0.16f, 0.32f, -8f, 64f, blind);
            case CgHostEnvironment.FLUID_LAVA -> constants.fog(0.6f, 0.1f, 0f, 0.25f, 1f);
            case CgHostEnvironment.FLUID_POWDER_SNOW -> constants.fog(0.623f, 0.734f, 0.785f, 0f, 2f);
            default -> {
                if (given) {
                    fog(world, constants, world.fogRed(), world.fogGreen(), world.fogBlue(), world.fogStart(),
                            world.fogEnd(), blind);
                } else {
                    float end = distance, start = end - Math.min(Math.max(end / 10f, 4f), 64f);
                    float r, g, b;
                    if (!Float.isNaN(world.skyRed())) {
                        // The horizon is paler than the sky overhead.
                        float day = Float.isNaN(world.skyBrightness()) ? 1f : world.skyBrightness();
                        r = mix(world.skyRed(), 0.75f * day, 0.6f);
                        g = mix(world.skyGreen(), 0.85f * day, 0.6f);
                        b = mix(world.skyBlue(), 1f * day, 0.6f);
                    } else if (world.ultraWarm()) {
                        r = 0.2f;
                        g = 0.03f;
                        b = 0.03f;
                    } else {
                        r = g = b = 0.02f;
                    }
                    fog(world, constants, r, g, b, start, end, blind);
                }
            }
        }
    }

    /** {@code (r, g, b, start, end)}, drawn in to five blocks and toward black as the player is blinded. */
    private static void fog(CgHostEnvironment world, CgPassConstants constants, float r, float g, float b, float start,
                            float end, float blind) {
        float k = 1f - blind;
        constants.fog(r * k, g * k, b * k, mix(start, 0f, blind), mix(end, 5f, blind));
    }

    private static float nan0(float v) {
        return Float.isNaN(v) ? 0f : v;
    }

    private static float mix(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
