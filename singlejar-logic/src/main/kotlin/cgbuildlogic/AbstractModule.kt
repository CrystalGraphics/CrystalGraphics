package cgbuildlogic

import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.attributes.java.TargetJvmEnvironment
import org.gradle.api.attributes.java.TargetJvmVersion
import org.gradle.api.file.FileCollection
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.Sync
import org.gradle.jvm.tasks.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import xyz.wagyourtail.jvmdg.gradle.JVMDowngraderExtension
import xyz.wagyourtail.jvmdg.gradle.JVMDowngraderPlugin

/** The Java every consumer below the abstract modules' own can load. */
const val DOWNGRADED_JAVA = 8

/** What [abstractModule] names the downgraded copy's source set. */
const val DOWNGRADED_SOURCE_SET = "downgraded"

/**
 * Makes this an ABSTRACT module: authored and compiled at the build's one compiler Java
 * (`dep.jdk.compiler`), and published twice -- the classes as compiled, and a copy downgraded to Java 8.
 * Gradle hands each consumer the one its JVM can load, by `TargetJvmVersion`, so a Minecraft node, the
 * harness and a test worker each name `project(":core")` and nothing else.
 *
 * ```kotlin
 * // core/build.gradle.kts
 * plugins { `java-library` }
 * abstractModule("com/crystalgui/core/jvmdg")   // where jvmdg's stubs land in the downgraded copy
 * ```
 *
 * ModDevGradle reads source sets rather than variants, so a Forge-family dev run names the copy's:
 *
 * ```kotlin
 * mods { create("mymod") { sourceSet(devRunSourceSet(project(":core"))) } }   // from the consumer's script
 * ```
 *
 * - The shade path must be a package no other module owns: dev runs put several of these copies in
 *   one module layer, and two copies of one stub package there is a split package.
 * - A configuration that requests no version at all gets the highest -- the Java 25 classes. Gradle's
 *   own requests one; one a plugin creates may not, and [requestJvm] is the fix.
 * - The shipped jars never read the copy: every merge takes `jar` and downgrades it in one pass.
 */
fun Project.abstractModule(shadePackage: String) {
    val java = providers.gradleProperty("dep.jdk.compiler").get().toInt()
    extensions.configure(JavaPluginExtension::class.java) {
        toolchain.languageVersion.set(JavaLanguageVersion.of(java))
        sourceCompatibility = JavaVersion.toVersion(java)
        targetCompatibility = JavaVersion.toVersion(java)
    }

    pluginManager.apply(JVMDowngraderPlugin::class.java)
    // jvmdg's own `downgradeJar` would sit beside `downgradedJar` and build a second, unused copy.
    extensions.getByType<JVMDowngraderExtension>().run {
        defaultTask.configure { enabled = false }
        defaultShadeTask.configure { enabled = false }
    }
    val downgradedJar = tasks.register<DowngradeShadeJar>("downgradedJar") {
        group = "build"
        description = "This module's jar, downgraded to Java $DOWNGRADED_JAVA for consumers below Java $java."
        val jar = tasks.named<Jar>("jar")
        inputFile.set(jar.flatMap { it.archiveFile })
        classpath.from(configurations["compileClasspath"])
        downgradeTo.set(JavaVersion.toVersion(DOWNGRADED_JAVA))
        shadePath.set { shadePackage }
        destinationDirectory.set(layout.buildDirectory.dir("libs"))
        archiveBaseName.set(jar.flatMap { it.archiveBaseName })
        archiveVersion.set(jar.flatMap { it.archiveVersion })
        archiveClassifier.set("java$DOWNGRADED_JAVA")
    }

    fun downgradedVariant(name: String, like: String, usage: String) = configurations.create(name) {
        isCanBeConsumed = true
        isCanBeResolved = false
        setExtendsFrom(configurations[like].extendsFrom)
        attributes {
            attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, usage))
            attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.LIBRARY))
            attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling::class.java, Bundling.EXTERNAL))
            attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements::class.java, LibraryElements.JAR))
            attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named(TargetJvmEnvironment::class.java, TargetJvmEnvironment.STANDARD_JVM))
            attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, DOWNGRADED_JAVA)
        }
        outgoing.artifact(downgradedJar)
    }
    downgradedVariant("downgradedApiElements", "apiElements", Usage.JAVA_API)
    downgradedVariant("downgradedRuntimeElements", "runtimeElements", Usage.JAVA_RUNTIME)

    // The copy's CLASSES as a directory, beside main's resources: a dev run stages resources from
    // `main`, and a second root offering the same paths is a collision there.
    val unpack = tasks.register<Sync>(UNPACK_TASK) {
        from(downgradedJar.map { zipTree(it.archiveFile) }) { include("**/*.class") }
        into(layout.buildDirectory.dir(DOWNGRADED_CLASSES))
    }
    val sourceSets = extensions.getByType<SourceSetContainer>()
    val main = sourceSets["main"]
    sourceSets.create(DOWNGRADED_SOURCE_SET) {
        output.dir(mapOf("builtBy" to unpack), layout.buildDirectory.dir(DOWNGRADED_CLASSES))
        output.dir(mapOf("builtBy" to main.processResourcesTaskName), main.output.resourcesDir!!)
    }
}

private const val UNPACK_TASK = "unpackDowngraded"
private const val DOWNGRADED_CLASSES = "downgraded/classes"

/** What this project's dev run loads of [module]: the downgraded copy for an [abstractModule], else `main`. */
fun Project.devRunSourceSet(module: Project): SourceSet {
    evaluationDependsOn(module.path)
    val sourceSets = module.extensions.getByType<SourceSetContainer>()
    return sourceSets.findByName(DOWNGRADED_SOURCE_SET) ?: sourceSets["main"]
}

/** [devRunSourceSet]'s classes alone, built by whatever writes them -- a dev run's class roots. */
fun Project.devRunClasses(module: Project): FileCollection {
    evaluationDependsOn(module.path)
    return if (module.tasks.names.contains(UNPACK_TASK)) {
        module.files(module.layout.buildDirectory.dir(DOWNGRADED_CLASSES)).builtBy(module.tasks.named(UNPACK_TASK))
    } else {
        module.extensions.getByType<SourceSetContainer>()["main"].output.classesDirs
    }
}

/**
 * Asks [configuration] for what a JVM of [java] can load. For a configuration a plugin creates with no
 * request at all -- ModDevGradle's `additionalRuntimeClasspath` -- which would otherwise resolve an
 * [abstractModule] to its Java 25 classes.
 */
fun requestJvm(configuration: Configuration, java: Int) {
    configuration.attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, java)
}
