package cgbuildlogic

import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register
import java.io.File

/** The tasks [registerDownloadTasks] made. */
class DownloadTasks(val check: TaskProvider<JavaExec>, val verify: TaskProvider<JavaExec>, val stage: TaskProvider<JavaExec>)

/**
 * Registers a product's checks of its `download/locations.json`: `checkDownloadLocations`, `verifyDownloadLocations`
 * (online) and `stageDownloadMirror`. All run `CgDownloadTool` over the code the runtime reads the file with, so there
 * is one reader of the format.
 *
 * ```kotlin
 * // a build whose [toolClasspath] holds com.crystalgraphics:core, platform, gson and log4j-core
 * val tasks = registerDownloadTasks(file("download/locations.json"), downloadTool)
 * tasks.check.configure { dependsOn("checkMyPins") }   // the product's own checks, beside
 * ```
 *
 * The file's `mirror` block says where the mirror is and what each mirrored file is licensed under; `stage` prints the
 * upload commands and never runs them. [localFiles] are jars already on this machine, so `stage` fetches only the rest.
 */
fun Project.registerDownloadTasks(
    locations: File,
    toolClasspath: FileCollection,
    localFiles: () -> Collection<File> = { emptyList() },
): DownloadTasks {
    val tool = "com.crystalgraphics.storage.download.CgDownloadTool"
    val staged = layout.buildDirectory.dir("download-mirror")
    val check = tasks.register<JavaExec>("checkDownloadLocations") {
        group = "verification"
        description = "Fails if ${locations.name} is malformed, or a mirrored file is unpinned or unlicensed."
        classpath = toolClasspath
        mainClass.set(tool)
        args("check", locations.absolutePath)
    }
    val verify = tasks.register<JavaExec>("verifyDownloadLocations") {
        group = "distribution"
        description = "Fetches every address in ${locations.name} and checks what it serves. Needs the network."
        classpath = toolClasspath
        mainClass.set(tool)
        args("verify", locations.absolutePath)
    }
    val stage = tasks.register<JavaExec>("stageDownloadMirror") {
        group = "distribution"
        description = "Collects what the download mirror holds into build/download-mirror, verified."
        classpath = toolClasspath
        mainClass.set(tool)
        doFirst { args("stage", locations.absolutePath, staged.get().asFile.absolutePath, *localFiles().map { it.absolutePath }.toTypedArray()) }
    }
    return DownloadTasks(check, verify, stage)
}
