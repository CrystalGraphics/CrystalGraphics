package io.github.somehussar.crystalgraphics.harness.camera;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.util.logging.Logger;

/**
 * First-person 3D camera with mouse-driven yaw/pitch rotation and WASD movement.
 *
 * <p>Camera controls:</p>
 * <ul>
 *   <li><b>Mouse</b>: yaw (horizontal) and pitch (vertical) rotation</li>
 *   <li><b>W/S</b>: forward/backward movement along the look direction</li>
 *   <li><b>A/D</b>: strafe left/right perpendicular to look direction</li>
 *   <li><b>SPACE</b>: move up (positive Y)</li>
 *   <li><b>SHIFT</b>: move down (negative Y), clamped to floor level (Y=0)</li>
 * </ul>
 *
 * <p>The floor plane is at Y=0. When SHIFT is held, the camera Y position is
 * clamped so it never goes below {@link #FLOOR_Y}.</p>
 *
 * <p>This class also exposes programmatic control methods for LLM debug tools:
 * {@link #rotateCamera(float, float)} and {@link #moveCamera(float, float, float)}.</p>
 */
public class Camera3D {

    private static final Logger LOGGER = Logger.getLogger(Camera3D.class.getName());

    /** The Y coordinate of the floor plane. Camera Y is clamped above this. */
    public static final float FLOOR_Y = 0.0f;

    /** Minimum camera Y position (slightly above floor to avoid z-fighting). */
    private static final float MIN_CAMERA_Y = FLOOR_Y + 0.1f;

    /** Default mouse sensitivity (degrees per pixel of mouse movement). */
    private static final float DEFAULT_SENSITIVITY = 0.05f;

    /** Default movement speed (units per second). */
    private static final float DEFAULT_MOVE_SPEED = 0.25f;

    /** Pitch limits to prevent gimbal lock (in degrees). */
    private static final float MAX_PITCH = 89.0f;
    private static final float MIN_PITCH = -89.0f;

    // ── Position and orientation ──
    private float posX;
    private float posY;
    private float posZ;
    private float yaw;   // degrees, 0 = looking along -Z (OpenGL convention)
    private float pitch; // degrees, positive = looking up

    // ── Settings ──
    private float sensitivity;
    private float moveSpeed;

    // ── Cached view matrix ──
    private final Matrix4f viewMatrix = new Matrix4f();
    private boolean viewDirty = true;

    // ── Mouse grab state ──
    private boolean mouseGrabbed = false;

    /**
     * Creates a camera at the given position looking along -Z.
     *
     * @param startX initial X position
     * @param startY initial Y position (clamped above floor)
     * @param startZ initial Z position
     */
    public Camera3D(float startX, float startY, float startZ) {
        this.posX = startX;
        this.posY = Math.max(startY, MIN_CAMERA_Y);
        this.posZ = startZ;
        this.yaw = 0.0f;
        this.pitch = 0.0f;
        this.sensitivity = DEFAULT_SENSITIVITY;
        this.moveSpeed = DEFAULT_MOVE_SPEED;
        LOGGER.info("[Camera3D] Created at (" + posX + ", " + posY + ", " + posZ + ")");
    }

    /**
     * Creates a camera at the default position (0, 2, 5), looking along -Z.
     */
    public Camera3D() {
        this(0.0f, 2.0f, 5.0f);
    }

    /**
     * Updates camera position and orientation based on keyboard and mouse input.
     *
     * <p>Call this once per frame before retrieving the view matrix. The deltaTime
     * parameter scales movement speed so behavior is framerate-independent.</p>
     *
     * @param deltaTime time elapsed since last frame, in seconds
     */
    public void update(float deltaTime) {
        handleMouseInput();
        handleKeyboardInput(deltaTime);
    }

    /**
     * Handles mouse-driven rotation.
     *
     * <p>Mouse is always grabbed for free-look camera control. Mouse delta
     * drives yaw/pitch rotation continuously without requiring a button press.</p>
     */
    private void handleMouseInput() {
        // Grab mouse on first frame for free-look mode
        if (!mouseGrabbed) {
            mouseGrabbed = true;
            Mouse.setGrabbed(true);
            // Drain any accumulated mouse delta from before grab
            Mouse.getDX();
            Mouse.getDY();
            return;
        }

        // When mouse is not grabbed (paused state), skip rotation
        if (!Mouse.isGrabbed()) {
            return;
        }

        int dx = Mouse.getDX();
        int dy = Mouse.getDY();
        if (dx != 0 || dy != 0) {
            // Negate dx so moving mouse RIGHT rotates camera RIGHT (positive yaw).
            // LWJGL2 getDX() returns positive for rightward mouse movement, but
            // the look direction uses -sin(yaw), so yaw must decrease for a
            // rightward camera turn.
            yaw -= dx * sensitivity;
            // Vertical mouse movement rotates pitch (inverted: moving mouse up looks up)
            pitch += dy * sensitivity;

            // Clamp pitch to prevent gimbal lock
            if (pitch > MAX_PITCH) {
                pitch = MAX_PITCH;
            }
            if (pitch < MIN_PITCH) {
                pitch = MIN_PITCH;
            }

            // Normalize yaw to [0, 360) range
            yaw = yaw % 360.0f;
            if (yaw < 0.0f) {
                yaw += 360.0f;
            }

            viewDirty = true;
        }
    }

    /**
     * Handles WASD + SPACE + SHIFT keyboard-driven movement.
     *
     * @param deltaTime frame delta in seconds for framerate-independent movement
     */
    private void handleKeyboardInput(float deltaTime) {
        float speed = moveSpeed * deltaTime;

        // Calculate forward/right vectors from yaw (ignore pitch for movement)
        float yawRad = (float) Math.toRadians(yaw);
        float forwardX = -(float) Math.sin(yawRad);
        float forwardZ = -(float) Math.cos(yawRad);
        float rightX = (float) Math.cos(yawRad);
        float rightZ = -(float) Math.sin(yawRad);

        float dx = 0.0f;
        float dy = 0.0f;
        float dz = 0.0f;

        // CTRL: Movement speed
        if (Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL)) {
            speed *= 5;
        }
        
          // SPACE: move up
        if (Keyboard.isKeyDown(Keyboard.KEY_SPACE)) {
            dy += speed;
        }
        
        // SHIFT: move down, clamped to floor level
        if (Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT)) {
            dy -= speed;
            speed *= 0.1f;
        }
        
        // W/S: forward/backward along look direction (XZ plane only)
        if (Keyboard.isKeyDown(Keyboard.KEY_W)) {
            dx += forwardX * speed;
            dz += forwardZ * speed;
        }
        if (Keyboard.isKeyDown(Keyboard.KEY_S)) {
            dx -= forwardX * speed;
            dz -= forwardZ * speed;
        }

        // A/D: strafe left/right
        if (Keyboard.isKeyDown(Keyboard.KEY_A)) {
            dx -= rightX * speed;
            dz -= rightZ * speed;
        }
        if (Keyboard.isKeyDown(Keyboard.KEY_D)) {
            dx += rightX * speed;
            dz += rightZ * speed;
        }
        
        if (dx != 0.0f || dy != 0.0f || dz != 0.0f) {
            posX += dx;
            posY += dy;
            posZ += dz;

            // Clamp Y position to stay above floor
            if (posY < MIN_CAMERA_Y) {
                posY = MIN_CAMERA_Y;
            }

            viewDirty = true;
        }

        // O: Go to origin
        if (Keyboard.isKeyDown(Keyboard.KEY_O)) {
            posX = posZ = yaw = pitch = 0;
            posY = MIN_CAMERA_Y;

            viewDirty = true;
        }
    }

    /**
     * Returns the view matrix for this camera.
     *
     * <p>The matrix is cached and only recalculated when position or orientation changes.
     * Uses lookAt construction from position toward the look direction derived from
     * yaw and pitch.</p>
     *
     * @return the 4x4 view matrix
     */
    public Matrix4f getViewMatrix() {
        if (viewDirty) {
            recalculateViewMatrix();
            viewDirty = false;
        }
        return viewMatrix;
    }

    /**
     * Recalculates the view matrix from current position, yaw, and pitch.
     */
    private void recalculateViewMatrix() {
        float yawRad = (float) Math.toRadians(yaw);
        float pitchRad = (float) Math.toRadians(pitch);

        // Calculate look direction from yaw and pitch
        float lookX = -(float) (Math.sin(yawRad) * Math.cos(pitchRad));
        float lookY = (float) Math.sin(pitchRad);
        float lookZ = -(float) (Math.cos(yawRad) * Math.cos(pitchRad));

        float targetX = posX + lookX;
        float targetY = posY + lookY;
        float targetZ = posZ + lookZ;

        viewMatrix.identity();
        viewMatrix.lookAt(
                posX, posY, posZ,
                targetX, targetY, targetZ,
                0.0f, 1.0f, 0.0f
        );
    }

    // ── LLM Debug Tool Methods ──

    /**
     * Programmatically rotates the camera by the given yaw and pitch deltas.
     *
     * <p>Used by LLM debug tools to position the camera for validation screenshots.</p>
     *
     * @param yawDelta   horizontal rotation delta in degrees (positive = turn right)
     * @param pitchDelta vertical rotation delta in degrees (positive = look up)
     */
    public void rotateCamera(float yawDelta, float pitchDelta) {
        yaw += yawDelta;
        pitch += pitchDelta;

        // Clamp pitch
        if (pitch > MAX_PITCH) {
            pitch = MAX_PITCH;
        }
        if (pitch < MIN_PITCH) {
            pitch = MIN_PITCH;
        }

        // Normalize yaw
        yaw = yaw % 360.0f;
        if (yaw < 0.0f) {
            yaw += 360.0f;
        }

        viewDirty = true;
        LOGGER.fine("[Camera3D] rotateCamera: yaw=" + yaw + " pitch=" + pitch);
    }

    /**
     * Programmatically sets the camera's absolute position.
     *
     * <p>Used by LLM debug tools. The Y coordinate is clamped above the floor.</p>
     *
     * @param x new X position
     * @param y new Y position (clamped above floor)
     * @param z new Z position
     */
    public void moveCamera(float x, float y, float z) {
        posX = x;
        posY = Math.max(y, MIN_CAMERA_Y);
        posZ = z;
        viewDirty = true;
        // Drain accumulated mouse delta to prevent the next update() from
        // overwriting this programmatic position with stale mouse movement.
        Mouse.getDX();
        Mouse.getDY();
        LOGGER.fine("[Camera3D] moveCamera: (" + posX + ", " + posY + ", " + posZ + ")");
    }

    // ── Accessors ──

    public float getPosX() {
        return posX;
    }

    public float getPosY() {
        return posY;
    }

    public float getPosZ() {
        return posZ;
    }

    public float getYaw() {
        return yaw;
    }

    public float getPitch() {
        return pitch;
    }

    public void setSensitivity(float sensitivity) {
        this.sensitivity = sensitivity;
    }

    public float getSensitivity() {
        return sensitivity;
    }

    public void setMoveSpeed(float moveSpeed) {
        this.moveSpeed = moveSpeed;
    }

    public float getMoveSpeed() {
        return moveSpeed;
    }

    /**
     * Sets yaw directly (in degrees). Normalizes to [0, 360).
     * Drains accumulated mouse delta to prevent overwrite by next update().
     */
    public void setYaw(float yaw) {
        this.yaw = yaw % 360.0f;
        if (this.yaw < 0.0f) {
            this.yaw += 360.0f;
        }
        viewDirty = true;
        Mouse.getDX();
        Mouse.getDY();
    }

    /**
     * Sets pitch directly (in degrees). Clamped to [-89, 89].
     * Drains accumulated mouse delta to prevent overwrite by next update().
     */
    public void setPitch(float pitch) {
        this.pitch = pitch;
        if (this.pitch > MAX_PITCH) {
            this.pitch = MAX_PITCH;
        }
        if (this.pitch < MIN_PITCH) {
            this.pitch = MIN_PITCH;
        }
        viewDirty = true;
        Mouse.getDX();
        Mouse.getDY();
    }

    @Override
    public String toString() {
        return "Camera3D{pos=(" + posX + ", " + posY + ", " + posZ
                + "), yaw=" + yaw + ", pitch=" + pitch + "}";
    }
}
