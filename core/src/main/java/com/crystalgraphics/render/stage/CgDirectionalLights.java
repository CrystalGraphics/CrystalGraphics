package com.crystalgraphics.render.stage;

import java.util.Arrays;

import javax.annotation.Nullable;

import com.crystalgraphics.api.material.CgParameterCollection;

/**
 * The directional lights besides the host's sun, and which one lights the world: Unreal's forward-shading directional
 * light, the highest {@code ForwardShadingPriority}, a tie to the brighter. The host's sun is priority 0 at its
 * daylight. Every pass's {@code cg_SunDirection} points toward the one chosen ({@link CgWorldAtmosphere}), and
 * {@link #DIRECTION} and {@link #COLOUR} hold it for a material that reads them.
 *
 * <pre>{@code
 * CgDirectionalLights.Light cut = CgDirectionalLights.get().add(1).colour(0xFFFFFFFF, 10f).travel(0f, -1f, 0.2f);
 * cut.travel(dx, dy, dz);   // each frame, as the node it rides turns
 * cut.close();              // the sun lights the world again
 * }</pre>
 *
 * <ul>
 *   <li>{@code travel} is where the light goes, Unreal's light direction; the collection's {@code direction} is the
 *       other way, toward the light, as a material's light vector is.</li>
 *   <li>Brightness, for a tie, is the intensity times the colour's luma; the sun's is its daylight, 0 to 1.</li>
 *   <li>Adding and closing replace the list whole, so the frame's reads allocate nothing.</li>
 * </ul>
 */
public final class CgDirectionalLights {

    /** The chosen light for materials: {@code CG_GLOBAL_directional_light_direction}, {@code ..._colour}. */
    public static final CgParameterCollection COLLECTION = CgParameterCollection.of("directional_light");
    /** Toward the chosen light, unit length. */
    public static final CgParameterCollection.Vector DIRECTION = COLLECTION.vector("direction", 0f, 1f, 0f, 0f);
    /** Its linear colour times its intensity; the sun's is white times its daylight. */
    public static final CgParameterCollection.Vector COLOUR = COLLECTION.vector("colour", 1f, 1f, 1f, 1f);

    private static final Light[] NONE = new Light[0];
    private static final CgDirectionalLights INSTANCE = new CgDirectionalLights();

    private volatile Light[] lights = NONE;

    private CgDirectionalLights() {
    }

    public static CgDirectionalLights get() {
        return INSTANCE;
    }

    /** A new light at {@code priority} (0 and up): white, Unreal's intensity of 10, travelling straight down. */
    public synchronized Light add(int priority) {
        if (priority < 0) throw new IllegalArgumentException("a directional light's priority is 0 or more, not " + priority);
        Light l = new Light(priority);
        Light[] next = Arrays.copyOf(lights, lights.length + 1);
        next[next.length - 1] = l;
        lights = next;
        return l;
    }

    private synchronized void remove(Light l) {
        Light[] current = lights;
        for (int i = 0; i < current.length; i++) {
            if (current[i] != l) continue;
            Light[] next = new Light[current.length - 1];
            System.arraycopy(current, 0, next, 0, i);
            System.arraycopy(current, i + 1, next, i, next.length - i);
            lights = next.length == 0 ? NONE : next;
            return;
        }
    }

    /** The light chosen over a sun as bright as {@code sun}; null where the sun lights the world. */
    @Nullable
    public Light active(float sun) {
        Light best = null;
        for (Light l : lights) {
            if (best == null || l.priority > best.priority || l.priority == best.priority && l.brightness() > best.brightness()) best = l;
        }
        if (best == null || best.priority == 0 && best.brightness() <= sun) return null;
        return best;
    }

    /** One directional light: its priority, where it travels and its colour. Written on the frame thread. */
    public final class Light implements AutoCloseable {
        private final int priority;
        private volatile float dx, dy = -1f, dz, r = 1f, g = 1f, b = 1f, intensity = 10f;

        private Light(int priority) {
            this.priority = priority;
        }

        /** Where it travels, any length; a zero vector leaves it as it was. */
        public Light travel(float x, float y, float z) {
            float length = (float) Math.sqrt(x * x + y * y + z * z);
            if (length > 0f) {
                dx = x / length;
                dy = y / length;
                dz = z / length;
            }
            return this;
        }

        /** Its linear colour and intensity. */
        public Light colour(float r, float g, float b, float intensity) {
            this.r = r;
            this.g = g;
            this.b = b;
            this.intensity = intensity;
            return this;
        }

        /** {@code 0xAARRGGBB}, its alpha unused, times {@code intensity}. */
        public Light colour(int argb, float intensity) {
            return colour((argb >> 16 & 0xFF) / 255f, (argb >> 8 & 0xFF) / 255f, (argb & 0xFF) / 255f, intensity);
        }

        public int priority() {
            return priority;
        }

        public float dx() {
            return dx;
        }

        public float dy() {
            return dy;
        }

        public float dz() {
            return dz;
        }

        public float red() {
            return r * intensity;
        }

        public float green() {
            return g * intensity;
        }

        public float blue() {
            return b * intensity;
        }

        /** Its intensity times its colour's Rec. 601 luma: what breaks a tie. */
        public float brightness() {
            return intensity * (0.3f * r + 0.59f * g + 0.11f * b);
        }

        /** Takes it away: the next brightest, or the sun, lights the world. */
        @Override
        public void close() {
            remove(this);
        }
    }
}
