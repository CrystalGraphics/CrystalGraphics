package io.github.somehussar.crystalgraphics.harness.validation;

import io.github.somehussar.crystalgraphics.harness.camera.Camera3D;
import io.github.somehussar.crystalgraphics.harness.capture.ArtifactService;
import io.github.somehussar.crystalgraphics.harness.config.RuntimeServices;
import io.github.somehussar.crystalgraphics.harness.scheduler.TaskScheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Reusable choreographer for scripted camera-pose / pause / capture sequences.
 *
 * <p>Validation scenes typically need to:</p>
 * <ol>
 *   <li>Position the camera at a specific pose</li>
 *   <li>Wait one frame for the pose to take effect</li>
 *   <li>Capture a screenshot via {@link ArtifactService}</li>
 *   <li>Optionally pause/unpause for pause-overlay captures</li>
 *   <li>Repeat for multiple angles/states</li>
 *   <li>Shut down after all captures are complete</li>
 * </ol>
 *
 * <p>Without this choreographer, each validation scene duplicated 50-100 lines
 * of boilerplate scheduling code that differed only in camera positions, suffixes,
 * and timing offsets. The choreographer encapsulates the recurring
 * setup → delay → capture → cleanup pattern, letting scenes describe
 * <em>intent</em> via {@link ValidationCaptureStep} rather than manually
 * composing raw {@link TaskScheduler} callbacks.</p>
 *
 * <h3>Timing model</h3>
 * <p>For each {@link ValidationCaptureStep}:</p>
 * <ul>
 *   <li><b>{@code t = baseTime}</b>: Camera is positioned, optional pause is set</li>
 *   <li><b>{@code t = baseTime + captureDelay}</b>: Screenshot is captured via
 *       {@link ArtifactService#requestCapture(String)}</li>
 *   <li><b>{@code t = baseTime + captureDelay + 0.1}</b>: If paused, unpause</li>
 * </ul>
 *
 * <p>The default {@code captureDelay} is 0.1 seconds, sufficient for the pose
 * to render at least one full frame before capture.</p>
 *
 * <h3>Shutdown</h3>
 * <p>An optional shutdown callback can be provided via {@link #onShutdown(Runnable)}.
 * It is scheduled at {@code lastStepBaseTime + shutdownDelay} (default 0.5s after
 * the last capture).</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * ValidationChoreographer choreographer = new ValidationChoreographer(
 *     ctx.getCamera3D(),
 *     ctx.getTaskScheduler(),
 *     ctx.getArtifactService(),
 *     ctx.getRuntimeServices()
 * );
 *
 * choreographer.addStep(
 *     ValidationCaptureStep.builder("front-view", 0.5)
 *         .cameraPosition(0f, 2f, 8f)
 *         .cameraOrientation(0f, -10f)
 *         .build()
 * );
 * choreographer.addStep(
 *     ValidationCaptureStep.builder("paused", 1.0)
 *         .cameraPosition(0f, 3f, 8f)
 *         .cameraOrientation(0f, -15f)
 *         .paused()
 *         .build()
 * );
 *
 * choreographer.onShutdown(new Runnable() {
 *     public void run() { running = false; }
 * });
 *
 * choreographer.scheduleAll();
 * }</pre>
 *
 * @see ValidationCaptureStep
 * @see ArtifactService
 * @see TaskScheduler
 */
public final class ValidationChoreographer {

    private static final Logger LOGGER = Logger.getLogger(ValidationChoreographer.class.getName());

    /**
     * Default delay (in seconds) between the camera-pose setup task and the
     * capture task. One frame at 60fps is ~0.017s; 0.1s gives a comfortable
     * margin for the pose to render before capture.
     */
    private static final double DEFAULT_CAPTURE_DELAY = 0.1;

    /**
     * Default delay (in seconds) after the last capture step before the
     * shutdown callback fires.
     */
    private static final double DEFAULT_SHUTDOWN_DELAY = 0.5;

    /**
     * Delay (in seconds) after a paused-capture before the scene is unpaused.
     * Gives one frame for the paused screenshot to be written before resuming.
     */
    private static final double UNPAUSE_DELAY = 0.1;

    private final Camera3D camera;
    private final TaskScheduler scheduler;
    private final ArtifactService artifacts;
    private final RuntimeServices runtime; // nullable — only needed for pause steps

    private final List<ValidationCaptureStep> steps = new ArrayList<ValidationCaptureStep>();
    private Runnable shutdownCallback;
    private double captureDelay = DEFAULT_CAPTURE_DELAY;
    private double shutdownDelay = DEFAULT_SHUTDOWN_DELAY;
    private String logPrefix = "";

    /**
     * Creates a new choreographer bound to the given harness services.
     *
     * @param camera    the camera to position for each step
     * @param scheduler the scheduler to register timed callbacks on
     * @param artifacts the artifact service for capture requests
     * @param runtime   runtime services for pause control (may be null if no
     *                  steps require pausing)
     */
    public ValidationChoreographer(Camera3D camera, TaskScheduler scheduler,
                                   ArtifactService artifacts, RuntimeServices runtime) {
        if (camera == null) throw new IllegalArgumentException("camera must not be null");
        if (scheduler == null) throw new IllegalArgumentException("scheduler must not be null");
        if (artifacts == null) throw new IllegalArgumentException("artifacts must not be null");
        this.camera = camera;
        this.scheduler = scheduler;
        this.artifacts = artifacts;
        this.runtime = runtime;
    }

    /**
     * Adds a capture step to the sequence.
     *
     * <p>Steps are scheduled in the order they are added. Their timing is
     * determined by {@link ValidationCaptureStep#getBaseTimeSeconds()}.</p>
     *
     * @param step the step to add
     * @return this choreographer for chaining
     */
    public ValidationChoreographer addStep(ValidationCaptureStep step) {
        if (step == null) throw new IllegalArgumentException("step must not be null");
        steps.add(step);
        return this;
    }

    /**
     * Sets the shutdown callback to fire after all captures complete.
     *
     * @param callback the callback (typically {@code running = false})
     * @return this choreographer for chaining
     */
    public ValidationChoreographer onShutdown(Runnable callback) {
        this.shutdownCallback = callback;
        return this;
    }

    /**
     * Overrides the default capture delay (seconds between pose setup and capture).
     *
     * @param delaySeconds the delay in seconds (must be > 0)
     * @return this choreographer for chaining
     */
    public ValidationChoreographer captureDelay(double delaySeconds) {
        if (delaySeconds <= 0) throw new IllegalArgumentException("captureDelay must be > 0");
        this.captureDelay = delaySeconds;
        return this;
    }

    /**
     * Overrides the default shutdown delay (seconds after the last capture
     * before the shutdown callback fires).
     *
     * @param delaySeconds the delay in seconds (must be > 0)
     * @return this choreographer for chaining
     */
    public ValidationChoreographer shutdownDelay(double delaySeconds) {
        if (delaySeconds <= 0) throw new IllegalArgumentException("shutdownDelay must be > 0");
        this.shutdownDelay = delaySeconds;
        return this;
    }

    /**
     * Sets a log prefix for all scheduled tasks (e.g. scene class name).
     *
     * @param prefix the prefix string
     * @return this choreographer for chaining
     */
    public ValidationChoreographer logPrefix(String prefix) {
        this.logPrefix = prefix != null ? prefix : "";
        return this;
    }

    /**
     * Schedules all added steps onto the {@link TaskScheduler}.
     *
     * <p>For each step, this method registers:</p>
     * <ol>
     *   <li>A <b>setup task</b> at {@code step.baseTime}: positions the camera and
     *       optionally sets the paused state</li>
     *   <li>A <b>capture task</b> at {@code step.baseTime + captureDelay}: requests
     *       a screenshot via {@link ArtifactService#requestCapture(String)}</li>
     *   <li>An <b>unpause task</b> at {@code step.baseTime + captureDelay + 0.1}
     *       (only if the step is paused): restores the unpaused state</li>
     * </ol>
     *
     * <p>If a shutdown callback was set via {@link #onShutdown(Runnable)}, it is
     * scheduled at {@code latestStepEnd + shutdownDelay}.</p>
     *
     * @throws IllegalStateException if no steps have been added
     * @throws IllegalStateException if a paused step is added but runtime is null
     */
    public void scheduleAll() {
        if (steps.isEmpty()) {
            throw new IllegalStateException("No validation steps added to choreographer");
        }

        double latestEndTime = 0.0;

        for (final ValidationCaptureStep step : steps) {
            final double baseTime = step.getBaseTimeSeconds();
            final String suffix = step.getSuffix();
            final String stepLogPrefix = resolveLogPrefix(step);

            // ── Setup task: position camera + optional pause ──
            scheduler.schedule(baseTime, suffix + "-setup", new Runnable() {
                @Override
                public void run() {
                    camera.moveCamera(step.getCamX(), step.getCamY(), step.getCamZ());
                    camera.setYaw(step.getYaw());
                    camera.setPitch(step.getPitch());

                    if (step.isPaused()) {
                        if (runtime == null) {
                            throw new IllegalStateException(
                                    "Paused step '" + suffix + "' requires RuntimeServices but none provided");
                        }
                        runtime.setPaused(true);
                    }

                    LOGGER.info(stepLogPrefix + "Camera positioned for '" + suffix + "' capture");
                }
            });

            // ── Capture task: fire one frame later ──
            double captureTime = baseTime + captureDelay;
            scheduler.schedule(captureTime, suffix + "-capture", new Runnable() {
                @Override
                public void run() {
                    artifacts.requestCapture(suffix);
                }
            });

            // ── Unpause task (only for paused steps): restore state after capture ──
            double endTime = captureTime;
            if (step.isPaused()) {
                double unpauseTime = captureTime + UNPAUSE_DELAY;
                scheduler.schedule(unpauseTime, suffix + "-unpause", new Runnable() {
                    @Override
                    public void run() {
                        runtime.setPaused(false);
                        LOGGER.fine(stepLogPrefix + "Unpaused after '" + suffix + "' capture");
                    }
                });
                endTime = unpauseTime;
            }

            if (endTime > latestEndTime) {
                latestEndTime = endTime;
            }
        }

        // ── Shutdown task ──
        if (shutdownCallback != null) {
            double shutdownTime = latestEndTime + shutdownDelay;
            scheduler.schedule(shutdownTime, logPrefix + "shutdown", new Runnable() {
                @Override
                public void run() {
                    LOGGER.info(logPrefix + "All validation captures complete. Stopping.");
                    shutdownCallback.run();
                }
            });
        }

        LOGGER.info(logPrefix + "Scheduled " + steps.size() + " validation capture steps");
    }

    /**
     * Resolves the effective log prefix for a step: step-level prefix takes
     * priority over the choreographer-level prefix.
     */
    private String resolveLogPrefix(ValidationCaptureStep step) {
        String stepPrefix = step.getLogPrefix();
        if (stepPrefix != null && !stepPrefix.isEmpty()) {
            return stepPrefix;
        }
        return logPrefix;
    }

    /**
     * Returns the number of steps currently queued (before {@link #scheduleAll()}).
     *
     * @return the number of added steps
     */
    public int stepCount() {
        return steps.size();
    }
}
