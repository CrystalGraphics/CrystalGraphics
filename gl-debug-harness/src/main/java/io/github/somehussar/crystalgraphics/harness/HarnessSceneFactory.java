package io.github.somehussar.crystalgraphics.harness;

/**
 * Factory for creating {@link HarnessSceneLifecycle} instances.
 * Used by the registry to defer scene instantiation until needed.
 */
public interface HarnessSceneFactory {
    HarnessSceneLifecycle create();
}
