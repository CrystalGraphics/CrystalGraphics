package io.github.somehussar.crystalgraphics.harness.util;

import java.io.File;
import java.util.logging.Logger;

public  class HarnessOutputDir {

    private static final Logger LOGGER = Logger.getLogger(HarnessOutputDir.class.getName());

    public static void ensureExists(String path) {
        File dir = new File(path);
        if (!dir.exists()) {
            boolean created = dir.mkdirs();
            if (!created) {
                throw new RuntimeException("Failed to create output directory: " + path);
            }
            LOGGER.info("[Harness] Created output directory: " + dir.getAbsolutePath());
        }
    }

    private HarnessOutputDir() { }
}
