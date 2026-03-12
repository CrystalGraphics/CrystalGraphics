package com.msdfgen;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Platform-specific leak detection tool integration.
 * Provides hooks for Valgrind (Linux), leaks (macOS), and Dr.Memory (Windows).
 * <p>
 * These are optional external tools - tests run without them but produce
 * richer reports when available.
 */
public final class PlatformTools {

    public static final String OS = NativeLoader.getOsName();
    public static final String ARCH = NativeLoader.getArchName();
    public static final boolean IS_WINDOWS = "windows".equals(OS);
    public static final boolean IS_MACOS = "macos".equals(OS);
    public static final boolean IS_LINUX = "linux".equals(OS);
    public static final boolean IS_ARM64 = "aarch64".equals(ARCH);

    private PlatformTools() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static boolean isValgrindAvailable() {
        if (!IS_LINUX) return false;
        return isCommandAvailable("valgrind", "--version");
    }

    public static boolean isMacOsLeaksAvailable() {
        if (!IS_MACOS) return false;
        return isCommandAvailable("leaks", "--help");
    }

    public static boolean isDrMemoryAvailable() {
        if (!IS_WINDOWS) return false;
        return isCommandAvailable("drmemory", "-version");
    }

    public static String getPlatformDescription() {
        return OS + "-" + ARCH + " (Java " + System.getProperty("java.version") + ", "
            + System.getProperty("java.vm.name") + ")";
    }

    public static String getNativeLibraryInfo() {
        String libName = NativeLoader.mapLibraryName("msdfgen-jni");
        String resourcePath = "/natives/" + OS + "-" + ARCH + "/" + libName;
        boolean available = NativeLoader.class.getResourceAsStream(resourcePath) != null;
        return "Library: " + libName + " at " + resourcePath + " (available=" + available + ")";
    }

    /**
     * Checks file type of native library (useful for verifying ARM64 vs x64 on macOS).
     * Returns the output of 'file' command on Unix, or 'dumpbin /headers' on Windows.
     */
    public static String getNativeLibraryFileType(String libraryPath) {
        if (IS_WINDOWS) {
            return executeCommand("dumpbin", "/headers", libraryPath);
        } else {
            return executeCommand("file", libraryPath);
        }
    }

    /**
     * Generates a test result report in CSV format.
     */
    public static String generateCsvReport(List<TestResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("test_name,status,baseline_kb,peak_kb,final_kb,leak_kb,duration_ms,platform\n");
        for (int i = 0; i < results.size(); i++) {
            TestResult r = results.get(i);
            sb.append(r.testName).append(',');
            sb.append(r.passed ? "PASS" : "FAIL").append(',');
            sb.append(r.baselineKb).append(',');
            sb.append(r.peakKb).append(',');
            sb.append(r.finalKb).append(',');
            sb.append(r.leakKb).append(',');
            sb.append(r.durationMs).append(',');
            sb.append(getPlatformDescription()).append('\n');
        }
        return sb.toString();
    }

    /**
     * Generates a human-readable test report.
     */
    public static String generateTextReport(List<TestResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("==========================================\n");
        sb.append("MSDFgen Memory Leak Test Report\n");
        sb.append("Platform: ").append(getPlatformDescription()).append('\n');
        sb.append("Native: ").append(getNativeLibraryInfo()).append('\n');
        sb.append("==========================================\n\n");

        int passed = 0;
        int failed = 0;
        for (int i = 0; i < results.size(); i++) {
            TestResult r = results.get(i);
            if (r.passed) passed++;
            else failed++;
            sb.append(String.format("[%s] %s\n", r.passed ? "PASS" : "FAIL", r.testName));
            sb.append(String.format("  Baseline: %dKB | Peak: %dKB | Final: %dKB | Leak: %dKB\n",
                r.baselineKb, r.peakKb, r.finalKb, r.leakKb));
            sb.append(String.format("  Duration: %dms\n", r.durationMs));
            if (r.notes != null && !r.notes.isEmpty()) {
                sb.append(String.format("  Notes: %s\n", r.notes));
            }
            sb.append('\n');
        }

        sb.append("==========================================\n");
        sb.append(String.format("TOTAL: %d passed, %d failed, %d total\n", passed, failed, results.size()));
        sb.append("==========================================\n");
        return sb.toString();
    }

    private static boolean isCommandAvailable(String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            proc.waitFor();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String executeCommand(String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
            proc.waitFor();
            return output.toString();
        } catch (Exception e) {
            return "Error executing command: " + e.getMessage();
        }
    }

    public static final class TestResult {
        public final String testName;
        public final boolean passed;
        public final long baselineKb;
        public final long peakKb;
        public final long finalKb;
        public final long leakKb;
        public final long durationMs;
        public final String notes;

        public TestResult(String testName, boolean passed,
                          long baselineKb, long peakKb, long finalKb, long leakKb,
                          long durationMs, String notes) {
            this.testName = testName;
            this.passed = passed;
            this.baselineKb = baselineKb;
            this.peakKb = peakKb;
            this.finalKb = finalKb;
            this.leakKb = leakKb;
            this.durationMs = durationMs;
            this.notes = notes;
        }
    }
}
