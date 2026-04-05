package io.github.somehussar.crystalgraphics.harness;

/**
 * Factory for creating {@link HarnessScene} instances.
 * Used by the registry to defer scene instantiation until needed.
 */
public interface HarnessSceneFactory {
    HarnessScene create();
}
