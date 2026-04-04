package io.github.somehussar.crystalgraphics.harness;

/**
 * Scene interface for the harness. Each mode implements this.
 */
interface HarnessScene {
    void run(HarnessContext ctx, String outputDir) throws Exception;
}
