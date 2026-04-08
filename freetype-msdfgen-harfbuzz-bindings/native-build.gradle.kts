// =============================================================================
// native-build.gradle.kts — Zig-based cross-platform native build system
// Mirrors tree-sitter-ng-v0.26.6: uses `zig c++` as a cross-compiler
// Applied via: apply(from = "native-build.gradle.kts") in build.gradle.kts
//
// All configurable values are in gradle.properties with `nativeBuild.*` prefix.
// =============================================================================
@file:Suppress("DEPRECATION")
import java.net.URL
import java.util.zip.ZipInputStream

// =============================================================================
// Read properties from gradle.properties (with command-line override support)
// =============================================================================

val zigVersion: String = findProperty("nativeBuild.zig.version") as? String ?: "0.11.0"
val zigDownloadUrl: String = findProperty("nativeBuild.zig.downloadUrl") as? String ?: "https://ziglang.org/download"
val freetypeVersion: String = findProperty("nativeBuild.freetype.version") as? String ?: "2.13.2"
val freetypeDownloadUrl: String = findProperty("nativeBuild.freetype.downloadUrl") as? String ?: "https://download.savannah.gnu.org/releases/freetype"
val harfbuzzVersion: String = findProperty("nativeBuild.harfbuzz.version") as? String ?: "8.3.0"
val harfbuzzDownloadUrl: String = findProperty("nativeBuild.harfbuzz.downloadUrl") as? String ?: "https://github.com/harfbuzz/harfbuzz/releases/download"
val libraryName: String = findProperty("nativeBuild.libraryName") as? String ?: "freetype_msdfgen_harfbuzz_jni"
val cOptLevel: String = findProperty("nativeBuild.cOptLevel") as? String ?: "-O2"
val cDebugLevel: String = findProperty("nativeBuild.cDebugLevel") as? String ?: "-g0"
val cppStandard: String = findProperty("nativeBuild.cppStandard") as? String ?: "-std=c++11"

// Cross-compilation targets — override with -PnativeBuild.targets=x86_64-windows,...
// Also supports legacy -PnativeTargets=... (command-line only, takes priority)
val nativeTargets: List<String> = run {
    val fromLegacy = findProperty("nativeTargets") as? String
    val fromNew = findProperty("nativeBuild.targets") as? String
    (fromLegacy ?: fromNew)?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        ?: listOf("x86_64-windows", "x86_64-linux-gnu", "x86_64-macos", "aarch64-macos", "aarch64-linux-gnu")
}

// =============================================================================
// Directory layout (derived from project, not hardcoded paths)
// =============================================================================

val nativeBuildDir = layout.buildDirectory.dir("native-build").get().asFile
val zigDir = File(nativeBuildDir, "zig")
val depsDir = File(nativeBuildDir, "deps")
val objDir = File(nativeBuildDir, "obj")
val jniIncludeDir = file("include/jni")
val nativeSourceDir = file("native/freetype-msdfgen-harfbuzz-jni/src/cpp")
val msdfgenSourceDir = file("native/msdfgen-jni/msdfgen")
val msdfgenOverrideDir = file("native/freetype-msdfgen-harfbuzz-jni/overrides/msdfgen")
val nativesOutputDir = file("src/main/resources/natives")

// =============================================================================
// Helper functions — pure utilities, no Gradle API dependencies
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

/** Execute a command via ProcessBuilder. Throws on non-zero exit. */
fun runCommand(cmd: List<String>, workDir: File? = null, logger: org.gradle.api.logging.Logger? = null) {
    val displayCmd = cmd.joinToString(" ") { if (it.contains(" ")) "\"$it\"" else it }
    logger?.debug("Running: $displayCmd")
    val pb = ProcessBuilder(cmd)
    if (workDir != null) pb.directory(workDir)
    pb.redirectErrorStream(true)
    val process = pb.start()
    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()
    if (exitCode != 0) {
        throw GradleException("Command failed (exit $exitCode): $displayCmd\n$output")
    }
}

/** Download a file, following HTTP redirects. */
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

/**
 * Extract a .tar.xz archive.
 * Runs `tar` from the archive's own directory using a relative filename
 * to avoid Windows bsdtar misinterpreting drive-letter prefixes as
 * remote host identifiers (e.g. `X:` → `X` host).
 */
fun extractTarXz(archive: File, destDir: File, logger: org.gradle.api.logging.Logger? = null) {
    destDir.mkdirs()
    val localArchive = File(destDir, archive.name)
    if (localArchive.absolutePath != archive.absolutePath) {
        archive.copyTo(localArchive, overwrite = true)
    }
    runCommand(listOf("tar", "xf", localArchive.name), workDir = destDir, logger = logger)
    if (localArchive.absolutePath != archive.absolutePath) {
        localArchive.delete()
    }
}

// =============================================================================
// Task: downloadZig
// =============================================================================
tasks.register("downloadZig") {
    group = "native build"
    description = "Download Zig $zigVersion compiler for cross-platform native builds"
    notCompatibleWithConfigurationCache("Executes external commands and downloads files")
    doNotTrackState("External tool download — not cacheable")

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
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { zis.copyTo(it) }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } else {
            extractTarXz(archiveFile, zigDir, logger)
        }

        if (!System.getProperty("os.name", "").lowercase().contains("win")) {
            zigExeFile.setExecutable(true, false)
        }
        logger.lifecycle("Zig compiler ready: ${zigExeFile.absolutePath}")
    }
}

// =============================================================================
// Task: downloadNativeDeps
// =============================================================================
tasks.register("downloadNativeDeps") {
    group = "native build"
    description = "Download FreeType $freetypeVersion and HarfBuzz $harfbuzzVersion source archives"
    notCompatibleWithConfigurationCache("Downloads and extracts archive files")
    doNotTrackState("External dependency download — not cacheable")

    val ftDir = File(depsDir, "freetype-$freetypeVersion")
    val hbDir = File(depsDir, "harfbuzz-$harfbuzzVersion")

    onlyIf {
        val ftOk = ftDir.exists() && (ftDir.listFiles()?.isNotEmpty() == true)
        val hbOk = hbDir.exists() && (hbDir.listFiles()?.isNotEmpty() == true)
        !ftOk || !hbOk
    }

    doLast {
        depsDir.mkdirs()

        val ftOk = ftDir.exists() && (ftDir.listFiles()?.isNotEmpty() == true)
        if (!ftOk) {
            if (ftDir.exists()) ftDir.deleteRecursively()
            val ftArchive = File(depsDir, "freetype.tar.xz")
            val ftUrl = "$freetypeDownloadUrl/freetype-$freetypeVersion.tar.xz"
            logger.lifecycle("Downloading FreeType $freetypeVersion from $ftUrl ...")
            downloadFile(ftUrl, ftArchive)
            logger.lifecycle("  Downloaded ${ftArchive.length() / 1024} KB")
            extractTarXz(ftArchive, depsDir, logger)
            if (!ftDir.exists() || ftDir.listFiles()?.isEmpty() != false)
                throw GradleException("FreeType extraction failed — $ftDir is empty")
            logger.lifecycle("FreeType source ready: $ftDir")
        }

        val hbOk = hbDir.exists() && (hbDir.listFiles()?.isNotEmpty() == true)
        if (!hbOk) {
            if (hbDir.exists()) hbDir.deleteRecursively()
            val hbArchive = File(depsDir, "harfbuzz.tar.xz")
            val hbUrl = "$harfbuzzDownloadUrl/$harfbuzzVersion/harfbuzz-$harfbuzzVersion.tar.xz"
            logger.lifecycle("Downloading HarfBuzz $harfbuzzVersion from $hbUrl ...")
            downloadFile(hbUrl, hbArchive)
            logger.lifecycle("  Downloaded ${hbArchive.length() / 1024} KB")
            extractTarXz(hbArchive, depsDir, logger)
            if (!hbDir.exists() || hbDir.listFiles()?.isEmpty() != false)
                throw GradleException("HarfBuzz extraction failed — $hbDir is empty")
            logger.lifecycle("HarfBuzz source ready: $hbDir")
        }
    }
}

// =============================================================================
// Task: buildNatives — Cross-compile all native libraries using Zig
// =============================================================================
tasks.register("buildNatives") {
    group = "native build"
    description = "Cross-compile $libraryName for all targets using Zig"
    dependsOn("downloadNativeDeps")
    notCompatibleWithConfigurationCache("Executes Zig compiler via ProcessBuilder")
    doNotTrackState("Native cross-compilation via external toolchain — not cacheable")

    doLast {
        val zigExe = resolveZigExe()
        logger.lifecycle("Using Zig: ${zigExe.absolutePath}")

        val ftSrcDir = File(depsDir, "freetype-$freetypeVersion")
        val hbSrcDir = File(depsDir, "harfbuzz-$harfbuzzVersion")

        if (!ftSrcDir.exists()) throw GradleException("FreeType not found at $ftSrcDir — run downloadNativeDeps")
        if (!hbSrcDir.exists()) throw GradleException("HarfBuzz not found at $hbSrcDir — run downloadNativeDeps")
        if (!msdfgenSourceDir.exists()) throw GradleException("msdfgen not found at $msdfgenSourceDir")

        // Apply msdfgen override
        val importFontOverride = File(msdfgenOverrideDir, "ext/import-font.cpp")
        if (importFontOverride.exists()) {
            logger.lifecycle("Applying msdfgen import-font.cpp override")
            importFontOverride.copyTo(File(msdfgenSourceDir, "ext/import-font.cpp"), overwrite = true)
        }

        // --- Source collection ---
        val ftIncludeDirs = listOf(
            File(ftSrcDir, "include"),
            File(ftSrcDir, "include/freetype"),
            File(ftSrcDir, "include/freetype/config"),
            File(ftSrcDir, "include/freetype/internal")
        )

        // FreeType source files — stable list for 2.13.x; update if upgrading major version
        val ftSourceFiles = listOf(
            "src/autofit/autofit.c",
            "src/base/ftbase.c", "src/base/ftbbox.c", "src/base/ftbdf.c",
            "src/base/ftbitmap.c", "src/base/ftcid.c", "src/base/ftdebug.c",
            "src/base/ftfstype.c",
            "src/base/ftgasp.c", "src/base/ftglyph.c", "src/base/ftgxval.c",
            "src/base/ftinit.c", "src/base/ftmm.c", "src/base/ftotval.c",
            "src/base/ftpatent.c", "src/base/ftpfr.c", "src/base/ftstroke.c",
            "src/base/ftsynth.c", "src/base/ftsystem.c", "src/base/fttype1.c",
            "src/base/ftwinfnt.c",
            "src/bdf/bdf.c", "src/bzip2/ftbzip2.c", "src/cache/ftcache.c",
            "src/cff/cff.c", "src/cid/type1cid.c",
            "src/gzip/ftgzip.c", "src/lzw/ftlzw.c",
            "src/pcf/pcf.c", "src/pfr/pfr.c",
            "src/psaux/psaux.c", "src/pshinter/pshinter.c", "src/psnames/psnames.c",
            "src/raster/raster.c", "src/sdf/sdf.c", "src/sfnt/sfnt.c",
            "src/smooth/smooth.c", "src/svg/svg.c",
            "src/truetype/truetype.c", "src/type1/type1.c", "src/type42/type42.c",
            "src/winfonts/winfnt.c"
        ).map { File(ftSrcDir, it) }.filter { it.exists() }

        val hbIncludeDirs = listOf(File(hbSrcDir, "src"))
        val hbSourceFiles = listOf(File(hbSrcDir, "src/harfbuzz.cc"))

        val msdfgenCoreDir = File(msdfgenSourceDir, "core")
        val msdfgenExtDir = File(msdfgenSourceDir, "ext")
        val msdfgenCoreSources = msdfgenCoreDir.listFiles()?.filter { it.extension == "cpp" } ?: emptyList()
        val msdfgenExtSources = msdfgenExtDir.listFiles()?.filter { it.extension == "cpp" } ?: emptyList()

        val jniSources = nativeSourceDir.listFiles()?.filter { it.extension == "cpp" } ?: emptyList()

        logger.lifecycle("=== Native build configuration ===")
        logger.lifecycle("FreeType: ${ftSourceFiles.size} C files (v$freetypeVersion)")
        logger.lifecycle("HarfBuzz: ${hbSourceFiles.size} C++ files (v$harfbuzzVersion)")
        logger.lifecycle("MSDFgen:  ${msdfgenCoreSources.size + msdfgenExtSources.size} C++ files")
        logger.lifecycle("JNI:      ${jniSources.size} C++ files")
        logger.lifecycle("Targets:  $nativeTargets")
        logger.lifecycle("Flags:    $cOptLevel $cDebugLevel $cppStandard")

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

            fun compileC(srcFile: File, objFile: File, defines: List<String>, includes: List<File>) {
                val cmd = mutableListOf(
                    zigExe.absolutePath, "cc", "-c",
                    "-target", zigTarget, cOptLevel, cDebugLevel,
                    "-fno-sanitize=undefined", "-fPIC"
                )
                for (d in defines) cmd.add(d)
                cmd.addAll(listOf("-o", objFile.absolutePath, srcFile.absolutePath))
                for (inc in includes) { cmd.add("-I"); cmd.add(inc.absolutePath) }
                runCommand(cmd, logger = logger)
            }

            fun compileCpp(srcFile: File, objFile: File, defines: List<String>, includes: List<File>) {
                val cmd = mutableListOf(
                    zigExe.absolutePath, "c++", "-c",
                    "-target", zigTarget, cOptLevel, cDebugLevel,
                    cppStandard, "-fno-sanitize=undefined", "-fPIC"
                )
                for (d in defines) cmd.add(d)
                cmd.addAll(listOf("-o", objFile.absolutePath, srcFile.absolutePath))
                for (inc in includes) { cmd.add("-I"); cmd.add(inc.absolutePath) }
                runCommand(cmd, logger = logger)
            }

            // Phase 1: FreeType
            logger.lifecycle("  [1/5] FreeType ...")
            val ftObjects = ftSourceFiles.map { src ->
                val obj = File(tgtObjDir, "ft_${src.nameWithoutExtension}.o")
                compileC(src, obj, listOf("-DFT2_BUILD_LIBRARY"), ftIncludeDirs)
                obj
            }
            logger.lifecycle("    ${ftObjects.size} objects")

            // Phase 2: HarfBuzz
            logger.lifecycle("  [2/5] HarfBuzz ...")
            val hbObjects = hbSourceFiles.map { src ->
                val obj = File(tgtObjDir, "hb_${src.nameWithoutExtension}.o")
                compileCpp(src, obj, listOf("-DHAVE_FREETYPE", "-DHB_NO_MT"), hbIncludeDirs + ftIncludeDirs)
                obj
            }
            logger.lifecycle("    ${hbObjects.size} objects")

            // Phase 3: MSDFgen
            logger.lifecycle("  [3/5] MSDFgen ...")
            val msdfgenDefines = listOf("-DMSDFGEN_PUBLIC=", "-DMSDFGEN_USE_CPP11", "-DMSDFGEN_USE_FREETYPE", "-DMSDFGEN_EXTENSIONS")
            val msdfgenIncludes = listOf(msdfgenSourceDir, msdfgenCoreDir, msdfgenExtDir) + ftIncludeDirs
            val msdfgenObjects = (msdfgenCoreSources + msdfgenExtSources).map { src ->
                val p = if (src.parentFile.name == "core") "msdfgen_core_" else "msdfgen_ext_"
                val obj = File(tgtObjDir, "$p${src.nameWithoutExtension}.o")
                compileCpp(src, obj, msdfgenDefines, msdfgenIncludes)
                obj
            }
            logger.lifecycle("    ${msdfgenObjects.size} objects")

            // Phase 4: JNI
            logger.lifecycle("  [4/5] JNI ...")
            val jniIncludes = listOf(jniIncludeDir, jniMdIncludeDir(zigTarget), nativeSourceDir,
                msdfgenSourceDir, msdfgenCoreDir, msdfgenExtDir) + ftIncludeDirs + hbIncludeDirs
            val jniObjects = jniSources.map { src ->
                val obj = File(tgtObjDir, "jni_${src.nameWithoutExtension}.o")
                compileCpp(src, obj, msdfgenDefines, jniIncludes)
                obj
            }
            logger.lifecycle("    ${jniObjects.size} objects")

            // Phase 5: Link
            logger.lifecycle("  [5/5] Linking ...")
            val allObjects = ftObjects + hbObjects + msdfgenObjects + jniObjects
            val linkCmd = mutableListOf(
                zigExe.absolutePath, "c++", "-shared",
                "-target", zigTarget, cDebugLevel, "-fno-sanitize=undefined",
                "-o", outputFile.absolutePath
            )
            for (obj in allObjects) linkCmd.add(obj.absolutePath)
            runCommand(linkCmd, logger = logger)

            logger.lifecycle("  => ${outputFile.name} (${outputFile.length() / 1024} KB)")
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
// Task: buildNativesLocal — prints the command for local-only build
// =============================================================================
tasks.register("buildNativesLocal") {
    group = "native build"
    description = "Build native library for current platform only (uses -PnativeBuild.targets)"

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
