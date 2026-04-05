package io.github.somehussar.crystalgraphics.harness;

import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;

/**
 * Scene interface for the harness. Each mode implements this.
 */
public interface HarnessScene {
    void run(HarnessContext ctx, String outputDir) throws Exception;
}
