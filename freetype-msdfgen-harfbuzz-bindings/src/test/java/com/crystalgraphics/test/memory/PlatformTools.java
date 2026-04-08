package com.crystalgraphics.test.memory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;

public final class PlatformTools {

    private PlatformTools() {
    }

    public enum Platform {
        WINDOWS, LINUX, MACOS, UNKNOWN
    }

    public static Platform detectPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) return Platform.WINDOWS;
        if (os.contains("mac") || os.contains("darwin")) return Platform.MACOS;
        if (os.contains("linux") || os.contains("nux")) return Platform.LINUX;
        return Platform.UNKNOWN;
    }

    public static String detectArch() {
        String arch = System.getProperty("os.arch", "").toLowerCase();
        if (arch.equals("amd64") || arch.equals("x86_64")) return "x64";
        if (arch.equals("aarch64") || arch.equals("arm64")) return "aarch64";
        return arch;
    }

    public static boolean isValgrindAvailable() {
        return isCommandAvailable("valgrind", "--version");
    }

    public static boolean isDrMemoryAvailable() {
        return isCommandAvailable("drmemory", "-version");
    }

    public static boolean isMacOSLeaksAvailable() {
        return detectPlatform() == Platform.MACOS && isCommandAvailable("leaks", "--help");
    }

    public static long getProcessRSS() {
        Platform platform = detectPlatform();
        switch (platform) {
            case LINUX:
                return getLinuxRSS();
            case MACOS:
                return getMacOSRSS();
            case WINDOWS:
                return getWindowsWorkingSet();
            default:
                return -1;
        }
    }

    private static long getLinuxRSS() {
        try {
            // /proc/self/status has VmRSS in KB
            java.io.File status = new java.io.File("/proc/self/status");
            if (!status.exists()) return -1;
            BufferedReader reader = new BufferedReader(
                    new java.io.FileReader(status));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("VmRSS:")) {
                    reader.close();
                    String value = line.substring(6).trim();
                    // value is like "12345 kB"
                    String[] parts = value.split("\\s+");
                    return Long.parseLong(parts[0]) * 1024;
                }
            }
            reader.close();
        } catch (Exception e) {
            // Ignore
        }
        return -1;
    }

    private static long getMacOSRSS() {
        try {
            long pid = getProcessId();
            if (pid < 0) return -1;
            Process proc = Runtime.getRuntime().exec(
                    new String[]{"ps", "-o", "rss=", "-p", String.valueOf(pid)});
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream()));
            String line = reader.readLine();
            reader.close();
            proc.waitFor();
            if (line != null) {
                return Long.parseLong(line.trim()) * 1024;
            }
        } catch (Exception e) {
            // Ignore
        }
        return -1;
    }

    private static long getWindowsWorkingSet() {
        try {
            long pid = getProcessId();
            if (pid < 0) return -1;
            Process proc = Runtime.getRuntime().exec(
                    new String[]{"wmic", "process", "where",
                            "ProcessId=" + pid, "get", "WorkingSetSize"});
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.equals("WorkingSetSize")) {
                    reader.close();
                    proc.waitFor();
                    return Long.parseLong(line);
                }
            }
            reader.close();
            proc.waitFor();
        } catch (Exception e) {
            // Ignore
        }
        return -1;
    }

    private static long getProcessId() {
        // Java 8 compatible PID extraction
        String name = ManagementFactoryHelper.getRuntimeMXBeanName();
        if (name != null && name.contains("@")) {
            try {
                return Long.parseLong(name.substring(0, name.indexOf('@')));
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }

    private static boolean isCommandAvailable(String... command) {
        try {
            Process proc = Runtime.getRuntime().exec(command);
            proc.waitFor();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static final class ManagementFactoryHelper {
        static String getRuntimeMXBeanName() {
            try {
                return java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
            } catch (Exception e) {
                return null;
            }
        }
    }
}
