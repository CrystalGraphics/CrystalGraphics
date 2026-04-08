// =============================================================================
// native-build.gradle.kts — Generic Zig-based cross-platform native build system
// =============================================================================
// Library-agnostic: all library definitions live in gradle.properties.
// The build iterates nativeBuild.libraries, compiles each, and links into one shared lib.
//
// Applied via: apply(from = "native-build.gradle.kts") in build.gradle.kts
// =============================================================================
@file:Suppress("DEPRECATION")
import java.net.URL
import java.util.zip.ZipInputStream

// =============================================================================
// Global properties from gradle.properties
// =============================================================================

val zigVersion: String = findProperty("nativeBuild.zig.version") as? String ?: "0.11.0"
val zigDownloadUrl: String = findProperty("nativeBuild.zig.downloadUrl") as? String ?: "https://ziglang.org/download"
val libraryName: String = findProperty("nativeBuild.libraryName") as? String ?: "native_jni"
val cOptLevel: String = findProperty("nativeBuild.cOptLevel") as? String ?: "-O2"
val cDebugLevel: String = findProperty("nativeBuild.cDebugLevel") as? String ?: "-g0"
val cppStandard: String = findProperty("nativeBuild.cppStandard") as? String ?: "-std=c++11"

val nativeTargets: List<String> = run {
    val fromLegacy = findProperty("nativeTargets") as? String
    val fromNew = findProperty("nativeBuild.targets") as? String
    (fromLegacy ?: fromNew)?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        ?: listOf("x86_64-windows", "x86_64-linux-gnu", "x86_64-macos", "aarch64-macos", "aarch64-linux-gnu")
}

val libraryNames: List<String> = (findProperty("nativeBuild.libraries") as? String)
    ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
    ?: emptyList()

// =============================================================================
// Directory layout
// =============================================================================

val nativeBuildDir = layout.buildDirectory.dir("native-build").get().asFile
val zigDir = File(nativeBuildDir, "zig")
val depsDir = File(nativeBuildDir, "deps")
val objDir = File(nativeBuildDir, "obj")
val jniIncludeDir = file("include/jni")
val nativesOutputDir = file(findProperty("nativeBuild.outputDir") as? String?: "src/main/resources/natives")

// =============================================================================
// Data class: parsed library config
// =============================================================================

data class NativeLibConfig(
    val name: String,
    val type: String,           // "c" or "cpp"
    val sourceType: String,     // "download", "git", "local"
    val sourceUrl: String,      // URL or path
    val sourceTag: String,      // git tag (for git: sources)
    val sourceRoot: String,     // extracted directory name (for download:)
    val srcDirs: List<String>,
    val includes: List<String>,
    val defines: List<String>,
    val files: List<String>,    // explicit file list (empty = glob)
    val includesFrom: List<String>,
    val overrides: String,      // local override path
    val objPrefix: String,
    val jniHeaders: Boolean
)

fun parseLibConfig(name: String): NativeLibConfig {
    fun prop(key: String): String? = findProperty("nativeBuild.lib.$name.$key") as? String

    val type = prop("type") ?: "cpp"
    val sourceRaw = prop("source") ?: throw GradleException("nativeBuild.lib.$name.source is required")

    val sourceType: String
    val sourceUrl: String
    val sourceTag: String
    when {
        sourceRaw.startsWith("download:") -> {
            sourceType = "download"
            sourceUrl = sourceRaw.removePrefix("download:")
            sourceTag = ""
        }
        sourceRaw.startsWith("git:") -> {
            sourceType = "git"
            val gitPart = sourceRaw.removePrefix("git:")
            if ("@" in gitPart) {
                sourceUrl = gitPart.substringBefore("@")
                sourceTag = gitPart.substringAfter("@")
            } else {
                sourceUrl = gitPart
                sourceTag = ""
            }
        }
        sourceRaw.startsWith("local:") -> {
            sourceType = "local"
            sourceUrl = sourceRaw.removePrefix("local:")
            sourceTag = ""
        }
        else -> throw GradleException("nativeBuild.lib.$name.source must start with download:, git:, or local:")
    }

    return NativeLibConfig(
        name = name,
        type = type,
        sourceType = sourceType,
        sourceUrl = sourceUrl,
        sourceTag = sourceTag,
        sourceRoot = prop("sourceRoot") ?: "",
        srcDirs = prop("srcDirs")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: listOf("."),
        includes = prop("includes")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
        defines = prop("defines")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
        files = prop("files")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
        includesFrom = prop("includesFrom")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
        overrides = prop("overrides") ?: "",
        objPrefix = prop("objPrefix") ?: "${name}_",
        jniHeaders = prop("jniHeaders")?.toBoolean() ?: false
    )
}

// Parse all libraries at configuration time
val libConfigs: List<NativeLibConfig> = libraryNames.map { parseLibConfig(it) }

// =============================================================================
// Helper functions
// =============================================================================

fun libExt(target: String): String = when {
    target.contains("windows") -> "dll"
    target.contains("linux") -> "so"
    target.contains("macos") -> "dylib"
    else -> throw GradleException("Unsupported target: $target")
}

fun jniMdIncludeDir(target: String): File = when {
    target.contains("windows") -> File(jniIncludeDir, "win32")
    target.contains("linux") -> File(jniIncludeDir, "linux")
    target.contains("macos") -> File(jniIncludeDir, "darwin")
    else -> throw GradleException("Unsupported target for JNI: $target")
}

fun targetToOutputDir(target: String): String {
    val os = when {
        target.contains("windows") -> "windows"
        target.contains("linux") -> "linux"
        target.contains("macos") -> "macos"
        else -> throw GradleException("Unsupported target: $target")
    }
    val arch = when {
        target.startsWith("x86_64") -> "x64"
        target.startsWith("aarch64") -> "aarch64"
        target.startsWith("i686") || target.startsWith("x86-") -> "x86"
        else -> throw GradleException("Unsupported arch in target: $target")
    }
    return "$os-$arch"
}

fun getZigExeName(): String =
    if (System.getProperty("os.name", "").lowercase().contains("win")) "zig.exe" else "zig"

fun getZigPlatformDir(): String {
    val osName = System.getProperty("os.name", "").lowercase()
    val archName = System.getProperty("os.arch", "").lowercase()
    val os = when {
        osName.contains("win") -> "windows"
        osName.contains("mac") -> "macos"
        osName.contains("linux") -> "linux"
        else -> throw GradleException("Unsupported build OS: $osName")
    }
    val arch = when (archName) {
        "amd64", "x86_64" -> "x86_64"
        "aarch64", "arm64" -> "aarch64"
        else -> throw GradleException("Unsupported build arch: $archName")
    }
    return "zig-$os-$arch-$zigVersion"
}

fun getZigArchiveUrl(): String {
    val ext = if (System.getProperty("os.name", "").lowercase().contains("win")) "zip" else "tar.xz"
    return "$zigDownloadUrl/$zigVersion/${getZigPlatformDir()}.$ext"
}

fun resolveZigExe(): File {
    val userZig = findProperty("zigExe") as? String
    if (userZig != null) {
        val f = File(userZig)
        if (f.exists()) return f
        throw GradleException("Specified zigExe does not exist: $userZig")
    }
    val pathZig = try {
        val cmd = if (System.getProperty("os.name", "").lowercase().contains("win"))
            listOf("where", "zig") else listOf("which", "zig")
        val result = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val output = result.inputStream.bufferedReader().readText().trim()
        result.waitFor()
        if (result.exitValue() == 0 && output.isNotBlank()) File(output.lines().first().trim()) else null
    } catch (_: Exception) { null }
    if (pathZig != null && pathZig.exists()) return pathZig
    val downloaded = File(zigDir, "${getZigPlatformDir()}/${getZigExeName()}")
    if (downloaded.exists()) return downloaded
    throw GradleException(
        "Zig compiler not found. Either:\n" +
        "  1. Install Zig and add to PATH\n" +
        "  2. Run: ./gradlew downloadZig\n" +
        "  3. Pass: -PzigExe=/path/to/zig"
    )
}

/**
 * Resolve git executable. On Windows, Gradle's ProcessBuilder may not inherit
 * the full PATH, so we look for Git for Windows at its standard location.
 */
fun resolveGitExe(): String {
    if (!System.getProperty("os.name", "").lowercase().contains("win")) return "git"
    // Try PATH first
    try {
        val p = ProcessBuilder("git", "--version").redirectErrorStream(true).start()
        if (p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0) return "git"
    } catch (_: Exception) { /* not on PATH */ }
    // Common Git for Windows locations
    for (path in listOf("C:/Program Files/Git/cmd/git.exe", "C:/Program Files (x86)/Git/cmd/git.exe")) {
        if (File(path).exists()) return path
    }
    throw GradleException("git not found. Install Git for Windows or add git to PATH.")
}

fun runCommand(
    cmd: List<String>,
    workDir: File? = null,
    logger: org.gradle.api.logging.Logger? = null,
    timeoutSeconds: Long = 120
) {
    val displayCmd = cmd.joinToString(" ") { if (it.contains(" ")) "\"$it\"" else it }
    logger?.debug("Running: $displayCmd")
    val pb = ProcessBuilder(cmd)
    if (workDir != null) pb.directory(workDir)
    pb.redirectErrorStream(true)
    val process = pb.start()

    // Consume stdout+stderr in a daemon thread to prevent pipe-buffer deadlock.
    // readText() on the main thread blocks forever if the process fills the OS
    // pipe buffer (~64 KB) and stalls waiting for the reader.
    val outputBuilder = StringBuilder()
    val gobbler = Thread {
        try {
            process.inputStream.bufferedReader().use { reader ->
                val buf = CharArray(8192)
                var n: Int
                while (reader.read(buf).also { n = it } != -1) {
                    outputBuilder.append(buf, 0, n)
                }
            }
        } catch (_: Exception) { /* stream closed — expected on timeout kill */ }
    }
    gobbler.isDaemon = true
    gobbler.start()

    val finished = process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
    if (!finished) {
        process.destroyForcibly()
        gobbler.join(2000)
        throw GradleException(
            "Command timed out after ${timeoutSeconds}s: $displayCmd\n" +
            "Partial output:\n${outputBuilder.toString().takeLast(4000)}"
        )
    }
    gobbler.join(5000) // let gobbler finish draining
    val exitCode = process.exitValue()
    if (exitCode != 0) {
        throw GradleException("Command failed (exit $exitCode): $displayCmd\n${outputBuilder}")
    }
}

fun downloadFile(url: String, dest: File) {
    dest.parentFile?.mkdirs()
    var currentUrl = url
    var redirectCount = 0
    while (redirectCount < 10) {
        val conn = URL(currentUrl).openConnection() as java.net.HttpURLConnection
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        conn.connectTimeout = 30000
        conn.readTimeout = 120000
        conn.instanceFollowRedirects = true
        val code = conn.responseCode
        if (code in 300..399) {
            currentUrl = conn.getHeaderField("Location") ?: throw GradleException("Redirect without Location header")
            redirectCount++
            conn.disconnect()
            continue
        }
        if (code != 200) throw GradleException("HTTP $code downloading $url")
        conn.inputStream.use { input ->
            dest.outputStream().use { input.copyTo(it) }
        }
        conn.disconnect()
        if (dest.length() == 0L) throw GradleException("Downloaded file is empty: $dest")
        return
    }
    throw GradleException("Too many redirects downloading $url")
}

fun extractTar(archive: File, destDir: File, logger: org.gradle.api.logging.Logger? = null) {
    destDir.mkdirs()
    val localArchive = File(destDir, archive.name)
    if (localArchive.absolutePath != archive.absolutePath) {
        // Chunked copy with progress — avoids blocking on large files and gives
        // visibility so debugger step-overs don't appear frozen.
        val totalBytes = archive.length()
        val totalMB = totalBytes / (1024 * 1024)
        logger?.lifecycle("  Copying archive to extraction dir: ${archive.name} (${totalMB} MB)")
        val chunkSize = 1024 * 1024 // 1 MB chunks
        var copied = 0L
        archive.inputStream().buffered().use { input ->
            localArchive.outputStream().buffered().use { output ->
                val buf = ByteArray(chunkSize)
                var n: Int
                while (input.read(buf).also { n = it } != -1) {
                    output.write(buf, 0, n)
                    copied += n
                    // Log every 5 MB
                    if (copied % (5 * 1024 * 1024) < chunkSize) {
                        val copiedMB = copied / (1024 * 1024)
                        logger?.lifecycle("  Copied $copiedMB / $totalMB MB")
                    }
                }
            }
        }
        logger?.lifecycle("  Copy complete: ${copied / (1024 * 1024)} MB")
    }

    // Resolve tar executable: prefer GNU tar from Git for Windows over
    // Windows bsdtar (System32\tar.exe) which hangs on .tar.xz files
    // due to child-process pipe inheritance issues.
    val tarExe: String = if (System.getProperty("os.name", "").lowercase().contains("win")) {
        val gitTar = File("C:/Program Files/Git/usr/bin/tar.exe")
        if (gitTar.exists()) gitTar.absolutePath else "tar"
    } else {
        "tar"
    }

    // Redirect output to file to avoid pipe-buffer deadlocks entirely.
    // Even GNU tar on Windows can inherit pipe handles to child processes.
    val logFile = File(destDir, ".tar-output.log")
    val cmd = listOf(tarExe, "xf", localArchive.name)
    val displayCmd = cmd.joinToString(" ")
    logger?.lifecycle("  Extracting: $displayCmd")
    val extractStart = System.currentTimeMillis()
    val pb = ProcessBuilder(cmd)
    pb.directory(destDir)
    pb.redirectErrorStream(true)
    pb.redirectOutput(logFile)
    val process = pb.start()
    // Close stdin immediately — tar doesn't read from it
    process.outputStream.close()
    val timeoutSeconds = 300L
    val finished = process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
    if (!finished) {
        process.destroyForcibly()
        val partial = if (logFile.exists()) logFile.readText().takeLast(2000) else "(no output)"
        logFile.delete()
        throw GradleException("tar timed out after ${timeoutSeconds}s: $displayCmd\n$partial")
    }
    val exitCode = process.exitValue()
    val output = if (logFile.exists()) logFile.readText() else ""
    logFile.delete()
    if (exitCode != 0) {
        throw GradleException("tar failed (exit $exitCode): $displayCmd\n$output")
    }
    val elapsedMs = System.currentTimeMillis() - extractStart
    logger?.lifecycle("  Extraction complete in ${elapsedMs / 1000}.${(elapsedMs % 1000) / 100}s")
    if (localArchive.absolutePath != archive.absolutePath) {
        localArchive.delete()
    }
}

/**
 * Resolve the source root directory for a library.
 * - download: depsDir/<sourceRoot>  (sourceRoot is required for download sources)
 * - local: project-relative path
 * - git: depsDir/<repo-name>  (derived from URL, or sourceRoot if specified)
 */
fun resolveSourceRoot(lib: NativeLibConfig): File = when (lib.sourceType) {
    "download" -> {
        if (lib.sourceRoot.isBlank()) throw GradleException(
            "nativeBuild.lib.${lib.name}.sourceRoot is required for download: sources"
        )
        File(depsDir, lib.sourceRoot)
    }
    "local" -> file(lib.sourceUrl)
    "git" -> {
        // Use sourceRoot if specified, otherwise derive from URL (e.g. "msdfgen.git" -> "msdfgen")
        val dirName = if (lib.sourceRoot.isNotBlank()) lib.sourceRoot
            else lib.sourceUrl.substringAfterLast("/").removeSuffix(".git")
        File(depsDir, dirName)
    }
    else -> throw GradleException("Unknown source type: ${lib.sourceType}")
}

/**
 * Collect source files for a library.
 * If `files` is specified, use the explicit list. Otherwise glob by extension in srcDirs.
 */
fun collectSourceFiles(lib: NativeLibConfig, srcRoot: File): List<File> {
    if (lib.files.isNotEmpty()) {
        return lib.files.map { File(srcRoot, it) }.filter { it.exists() }
    }
    val extensions = when (lib.type) {
        "c" -> setOf("c")
        "cpp" -> setOf("cpp", "cc")
        else -> setOf("c", "cpp", "cc")
    }
    return lib.srcDirs.flatMap { dirPath ->
        val dir = if (dirPath == ".") srcRoot else File(srcRoot, dirPath)
        dir.listFiles()?.filter { it.extension in extensions }?.toList() ?: emptyList()
    }
}

// =============================================================================
// Task: downloadZig
// =============================================================================
tasks.register("downloadZig") {
    group = "native build"
    description = "Download Zig $zigVersion compiler for cross-platform native builds"
    notCompatibleWithConfigurationCache("Executes external commands and downloads files")
    doNotTrackState("External tool download")

    onlyIf {
        val zigExeFile = File(zigDir, "${getZigPlatformDir()}/${getZigExeName()}")
        val onPath = try {
            val cmd = if (System.getProperty("os.name", "").lowercase().contains("win"))
                listOf("where", "zig") else listOf("which", "zig")
            ProcessBuilder(cmd).redirectErrorStream(true).start().waitFor() == 0
        } catch (_: Exception) { false }
        !onPath && !zigExeFile.exists()
    }

    doLast {
        val zigExeFile = File(zigDir, "${getZigPlatformDir()}/${getZigExeName()}")
        zigDir.mkdirs()
        val url = getZigArchiveUrl()
        val archiveName = url.substringAfterLast("/")
        val archiveFile = File(zigDir, archiveName)

        logger.lifecycle("Downloading Zig $zigVersion from $url ...")
        downloadFile(url, archiveFile)
        logger.lifecycle("Downloaded: ${archiveFile.name} (${archiveFile.length() / 1024 / 1024} MB)")

        if (archiveName.endsWith(".zip")) {
            ZipInputStream(archiveFile.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val outFile = File(zigDir, entry.name)
                    if (entry.isDirectory) { outFile.mkdirs() }
                    else { outFile.parentFile?.mkdirs(); outFile.outputStream().use { zis.copyTo(it) } }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } else {
            extractTar(archiveFile, zigDir, logger)
        }

        if (!System.getProperty("os.name", "").lowercase().contains("win")) {
            zigExeFile.setExecutable(true, false)
        }
        logger.lifecycle("Zig compiler ready: ${zigExeFile.absolutePath}")
    }
}

// =============================================================================
// Task: downloadNativeDeps — download/clone all remote library sources
// =============================================================================
tasks.register("downloadNativeDeps") {
    group = "native build"
    description = "Download native library source archives and clone git repos"
    notCompatibleWithConfigurationCache("Downloads and extracts archive files")
    doNotTrackState("External dependency download")

    onlyIf {
        libConfigs.any { lib ->
            (lib.sourceType == "download" || lib.sourceType == "git") && run {
                val srcRoot = resolveSourceRoot(lib)
                !srcRoot.exists() || (srcRoot.listFiles()?.isEmpty() != false)
            }
        }
    }

    doLast {
        depsDir.mkdirs()
        for (lib in libConfigs) {
            val srcRoot = resolveSourceRoot(lib)

            when (lib.sourceType) {
                "download" -> {
                    if (srcRoot.exists() && (srcRoot.listFiles()?.isNotEmpty() == true)) {
                        logger.lifecycle("${lib.name}: source already present at $srcRoot")
                        continue
                    }
                    if (srcRoot.exists()) srcRoot.deleteRecursively()

                    val url = lib.sourceUrl
                    val archiveName = url.substringAfterLast("/")
                    val archiveFile = File(depsDir, archiveName)

                    logger.lifecycle("Downloading ${lib.name} from $url ...")
                    downloadFile(url, archiveFile)
                    logger.lifecycle("  Downloaded ${archiveFile.length() / 1024} KB")
                    extractTar(archiveFile, depsDir, logger)

                    if (!srcRoot.exists() || srcRoot.listFiles()?.isEmpty() != false) {
                        throw GradleException("${lib.name} extraction failed — $srcRoot is empty")
                    }
                    logger.lifecycle("  Source ready: $srcRoot")
                }
                "git" -> {
                    if (srcRoot.exists() && (srcRoot.listFiles()?.isNotEmpty() == true)) {
                        logger.lifecycle("${lib.name}: source already present at $srcRoot")
                        continue
                    }
                    if (srcRoot.exists()) srcRoot.deleteRecursively()

                    val gitExe = resolveGitExe()
                    val url = lib.sourceUrl
                    val tag = lib.sourceTag
                    val cloneCmd = mutableListOf(gitExe, "clone", "--depth", "1")
                    if (tag.isNotBlank()) { cloneCmd.add("--branch"); cloneCmd.add(tag) }
                    cloneCmd.add(url)
                    cloneCmd.add(srcRoot.absolutePath)

                    logger.lifecycle("Cloning ${lib.name} from $url" + if (tag.isNotBlank()) " @ $tag" else "" + " ...")
                    runCommand(cloneCmd, logger = logger, timeoutSeconds = 120)

                    if (!srcRoot.exists() || srcRoot.listFiles()?.isEmpty() != false) {
                        throw GradleException("${lib.name} git clone failed — $srcRoot is empty")
                    }
                    logger.lifecycle("  Source ready: $srcRoot")
                }
                // "local" — nothing to download
            }
        }
    }
}

// =============================================================================
// Task: buildNatives — Generic cross-compile all libraries using Zig
// =============================================================================
tasks.register("buildNatives") {
    group = "native build"
    description = "Cross-compile $libraryName for all targets using Zig"
    dependsOn("downloadZig", "downloadNativeDeps")
    notCompatibleWithConfigurationCache("Executes Zig compiler via ProcessBuilder")
    doNotTrackState("Native cross-compilation via external toolchain")

    doLast {
        val zigExe = resolveZigExe()
        logger.lifecycle("Using Zig: ${zigExe.absolutePath}")

        // Resolve source roots and validate
        val sourceRoots = mutableMapOf<String, File>()
        for (lib in libConfigs) {
            val srcRoot = resolveSourceRoot(lib)
            if (!srcRoot.exists()) {
                throw GradleException("${lib.name}: source root not found at $srcRoot" +
                    if (lib.sourceType in listOf("download", "git")) " — run downloadNativeDeps" else "")
            }
            sourceRoots[lib.name] = srcRoot
        }

        // Apply overrides before compiling
        for (lib in libConfigs) {
            if (lib.overrides.isBlank()) continue
            val overrideDir = file(lib.overrides)
            if (!overrideDir.exists()) continue
            val srcRoot = sourceRoots[lib.name]!!
            logger.lifecycle("Applying overrides for ${lib.name} from $overrideDir")
            overrideDir.walkTopDown().filter { it.isFile }.forEach { overrideFile ->
                val relativePath = overrideFile.relativeTo(overrideDir).path
                val target = File(srcRoot, relativePath)
                target.parentFile?.mkdirs()
                overrideFile.copyTo(target, overwrite = true)
                logger.lifecycle("  override: $relativePath")
            }
        }

        // Resolve include directories per library (absolute paths)
        // Map: libName -> list of absolute include dirs
        val resolvedIncludes = mutableMapOf<String, List<File>>()
        for (lib in libConfigs) {
            val srcRoot = sourceRoots[lib.name]!!
            val ownIncludes = lib.includes.map { relPath ->
                if (relPath == ".") srcRoot else File(srcRoot, relPath)
            }
            resolvedIncludes[lib.name] = ownIncludes
        }

        // Collect source files per library
        val sourceFiles = mutableMapOf<String, List<File>>()
        for (lib in libConfigs) {
            sourceFiles[lib.name] = collectSourceFiles(lib, sourceRoots[lib.name]!!)
        }

        // Print configuration
        logger.lifecycle("=== Native build configuration ===")
        for (lib in libConfigs) {
            val count = sourceFiles[lib.name]!!.size
            val ext = if (lib.type == "c") "C" else "C++"
            logger.lifecycle("${lib.name}: $count $ext files")
        }
        logger.lifecycle("Targets:  $nativeTargets")
        logger.lifecycle("Flags:    $cOptLevel $cDebugLevel $cppStandard")
        logger.lifecycle("Output:   $libraryName")

        for (zigTarget in nativeTargets) {
            logger.lifecycle("")
            logger.lifecycle("==== Building: $zigTarget ====")

            val platformDir = targetToOutputDir(zigTarget)
            val outDir = File(nativesOutputDir, platformDir)
            outDir.mkdirs()

            val ext = libExt(zigTarget)
            val pfx = if (zigTarget.contains("windows")) "" else "lib"
            val outputFile = File(outDir, "$pfx$libraryName.$ext")

            val tgtObjDir = File(objDir, zigTarget)
            tgtObjDir.mkdirs()

            val allObjects = mutableListOf<File>()

            for ((libIdx, lib) in libConfigs.withIndex()) {
                val files = sourceFiles[lib.name]!!
                if (files.isEmpty()) {
                    logger.lifecycle("  [${libIdx + 1}/${libConfigs.size}] ${lib.name} — no source files, skipping")
                    continue
                }
                logger.lifecycle("  [${libIdx + 1}/${libConfigs.size}] ${lib.name} ...")

                // Build include path: own includes + includesFrom dependencies
                val includeDirs = mutableListOf<File>()
                includeDirs.addAll(resolvedIncludes[lib.name] ?: emptyList())
                for (depName in lib.includesFrom) {
                    includeDirs.addAll(resolvedIncludes[depName]
                        ?: throw GradleException("${lib.name}.includesFrom references unknown library: $depName"))
                }
                // Add JNI headers if flagged
                if (lib.jniHeaders) {
                    includeDirs.add(jniIncludeDir)
                    includeDirs.add(jniMdIncludeDir(zigTarget))
                }

                // Build defines
                val defineFlags = lib.defines.map { d -> "-D$d" }

                // Compile each source file
                val objects = files.map { srcFile ->
                    // Generate unique obj name: prefix + parent-dir + filename
                    val parentHint = srcFile.parentFile?.name?.let { if (it == "." || it == "cpp" || it == "src") "" else "${it}_" } ?: ""
                    val obj = File(tgtObjDir, "${lib.objPrefix}${parentHint}${srcFile.nameWithoutExtension}.o")

                    val compiler = if (lib.type == "c") "cc" else "c++"
                    val cmd = mutableListOf(
                        zigExe.absolutePath, compiler, "-c",
                        "-target", zigTarget, cOptLevel, cDebugLevel,
                        "-fno-sanitize=undefined", "-fPIC"
                    )
                    if (lib.type == "cpp") cmd.add(cppStandard)
                    cmd.addAll(defineFlags)
                    cmd.addAll(listOf("-o", obj.absolutePath, srcFile.absolutePath))
                    for (inc in includeDirs) { cmd.add("-I"); cmd.add(inc.absolutePath) }

                    runCommand(cmd, logger = logger)
                    obj
                }
                allObjects.addAll(objects)
                logger.lifecycle("    ${objects.size} ${if (lib.type == "c") "C" else "C++"} files")
            }

            // Link all objects into shared library
            logger.lifecycle("  [link] Linking ${allObjects.size} objects ...")
            val linkCmd = mutableListOf(
                zigExe.absolutePath, "c++", "-shared",
                "-target", zigTarget, cDebugLevel, "-fno-sanitize=undefined",
                "-o", outputFile.absolutePath
            )
            for (obj in allObjects) linkCmd.add(obj.absolutePath)
            runCommand(linkCmd, logger = logger)

            logger.lifecycle("${project.relativePath(nativesOutputDir)}\\${zigTarget}\\ => ${outputFile.name} (${outputFile.length() / 1024} KB)")
        }

        // Clean Windows debug artifacts
        nativesOutputDir.walk().filter { it.extension in listOf("pdb", "lib") }.forEach { it.delete() }

        logger.lifecycle("")
        logger.lifecycle("=== buildNatives complete ===")
        nativesOutputDir.listFiles()?.filter { it.isDirectory }?.sorted()?.forEach { dir ->
            logger.lifecycle("  ${dir.name}/")
            dir.listFiles()?.forEach { f -> logger.lifecycle("    ${f.name} (${f.length() / 1024} KB)") }
        }
    }
}

// =============================================================================
// Task: buildNativesLocal
// =============================================================================
tasks.register("buildNativesLocal") {
    group = "native build"
    description = "Build native library for current platform only"

    doLast {
        val osName = System.getProperty("os.name", "").lowercase()
        val archName = System.getProperty("os.arch", "").lowercase()
        val os = when {
            osName.contains("win") -> "windows"
            osName.contains("mac") -> "macos"
            osName.contains("linux") -> "linux"
            else -> throw GradleException("Unsupported OS: $osName")
        }
        val arch = when (archName) {
            "amd64", "x86_64" -> "x86_64"
            "aarch64", "arm64" -> "aarch64"
            else -> throw GradleException("Unsupported arch: $archName")
        }
        val suffix = if (os == "linux") "-gnu" else ""
        logger.lifecycle("To build for local platform only, run:")
        logger.lifecycle("  ./gradlew buildNatives -PnativeBuild.targets=$arch-$os$suffix")
    }
}

// =============================================================================
// Task: cleanNatives
// =============================================================================
tasks.register<Delete>("cleanNatives") {
    group = "native build"
    description = "Clean all native build artifacts and output libraries"
    delete(nativeBuildDir)
    delete(nativesOutputDir)
}
