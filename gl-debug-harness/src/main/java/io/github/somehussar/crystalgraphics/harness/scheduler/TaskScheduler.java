package io.github.somehussar.crystalgraphics.harness.scheduler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Logger;

/**
 * Time-based event scheduler for the harness render loop.
 *
 * <p>Actions are scheduled at specific timestamps (in seconds from scene start).
 * During each frame, {@link #tick(double)} is called with the current elapsed time,
 * and all actions whose scheduled time has passed are fired in chronological order.</p>
 *
 * <p>Each action fires exactly once. After firing, it is removed from the queue.</p>
 *
 * <p>This scheduler is <b>not</b> thread-safe. All calls must happen on the
 * render thread.</p>
 */
public class TaskScheduler {

    private static final Logger LOGGER = Logger.getLogger(TaskScheduler.class.getName());

    /**
     * A scheduled action: a callback paired with a target timestamp.
     */
    public static final class ScheduledTask implements Comparable<ScheduledTask> {
        private final double timeSeconds;
        private final String name;
        private final Runnable action;

        public ScheduledTask(double timeSeconds, String name, Runnable action) {
            if (action == null) {
                throw new IllegalArgumentException("ScheduledTask action must not be null");
            }
            if (timeSeconds < 0.0) {
                throw new IllegalArgumentException("ScheduledTask time must be >= 0: " + timeSeconds);
            }
            this.timeSeconds = timeSeconds;
            this.name = name != null ? name : "unnamed";
            this.action = action;
        }

        public double getTimeSeconds() { return timeSeconds; }
        public String getName() { return name; }
        public Runnable getAction() { return action; }

        @Override
        public int compareTo(ScheduledTask other) {
            return Double.compare(this.timeSeconds, other.timeSeconds);
        }

        @Override
        public String toString() {
            return "ScheduledTask{" + name + " @ " + timeSeconds + "s}";
        }
    }

    private final List<ScheduledTask> pendingTasks = new ArrayList<ScheduledTask>();
    private boolean sorted = true;

    /**
     * Schedules an action to fire at the given time (seconds from scene start).
     *
     * @param timeSeconds when to fire (in seconds)
     * @param name        human-readable name for logging
     * @param action      the action to execute
     */
    public void schedule(double timeSeconds, String name, Runnable action) {
        pendingTasks.add(new ScheduledTask(timeSeconds, name, action));
        sorted = false;
        LOGGER.fine("[TaskScheduler] Scheduled: " + name + " @ " + timeSeconds + "s");
    }

    /**
     * Fires all pending tasks whose scheduled time is <= currentTime.
     *
     * <p>Tasks are fired in chronological order. Each task fires exactly once
     * and is removed from the queue after firing.</p>
     *
     * @param currentTimeSeconds elapsed time since scene start, in seconds
     */
    public void tick(double currentTimeSeconds) {
        if (pendingTasks.isEmpty()) {
            return;
        }

        // Sort if new tasks were added since last tick
        if (!sorted) {
            Collections.sort(pendingTasks);
            sorted = true;
        }

        // Fire tasks whose time has come, iterating from earliest
        // Use index-based removal to avoid ConcurrentModificationException
        int removeCount = 0;
        for (int i = 0; i < pendingTasks.size(); i++) {
            ScheduledTask task = pendingTasks.get(i);
            if (task.getTimeSeconds() <= currentTimeSeconds) {
                LOGGER.info("[TaskScheduler] Firing: " + task.getName()
                        + " (scheduled=" + task.getTimeSeconds()
                        + "s, actual=" + currentTimeSeconds + "s)");
                try {
                    task.getAction().run();
                } catch (Exception e) {
                    LOGGER.severe("[TaskScheduler] Task '" + task.getName() + "' threw: " + e.getMessage());
                }
                removeCount++;
            } else {
                // Tasks are sorted; once we hit a future task, stop
                break;
            }
        }

        // Remove fired tasks from the front of the list
        if (removeCount > 0) {
            pendingTasks.subList(0, removeCount).clear();
        }
    }

    /**
     * Returns the number of pending (not yet fired) tasks.
     */
    public int pendingCount() {
        return pendingTasks.size();
    }

    /**
     * Returns true if there are no pending tasks remaining.
     */
    public boolean isEmpty() {
        return pendingTasks.isEmpty();
    }

    /**
     * Removes all pending tasks without firing them.
     */
    public void clear() {
        pendingTasks.clear();
        sorted = true;
    }
}
