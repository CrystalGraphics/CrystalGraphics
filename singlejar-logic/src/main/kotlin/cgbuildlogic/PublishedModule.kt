package cgbuildlogic

import javax.inject.Inject
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.attributes.java.TargetJvmVersion
import org.gradle.api.component.AdhocComponentWithVariants
import org.gradle.api.component.SoftwareComponentFactory
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPom
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.external.javadoc.StandardJavadocDocletOptions
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.withType

/** What [publishedModule] names the bucket of dependencies a consumer compiles against. */
const val CONSUMER_API = "consumerApi"

/**
 * Set on a build's `gradle` when a consumer included it as a checkout (`com.crystalgui.settings`): its
 * projects then stand in for the published modules, and a consumer resolving one must get what the
 * published one declares. Our own builds never set it, so their classpaths never change.
 */
const val CONSUMER_CHECKOUT = "cgConsumerCheckout"

/** Declares [notation] in [CONSUMER_API]; `import cgbuildlogic.consumerApi`, as for any build-logic function. */
fun DependencyHandler.consumerApi(notation: Any): Dependency? = add(CONSUMER_API, notation)

/** A licence as a POM states it. */
data class Licence(val name: String, val url: String) {
    companion object {
        val LGPL3 = Licence("LGPL-3.0-or-later", "https://www.gnu.org/licenses/lgpl-3.0.html")
        val MIT = Licence("MIT", "https://opensource.org/licenses/MIT")
    }
}

/**
 * Publishes this module as a library a mod compiles against: the jar, its sources and javadoc (so an
 * IDE shows the real declarations and their documentation), and -- for an [abstractModule] -- the Java 8
 * copy, which Gradle hands any consumer below Java 25.
 *
 * ```kotlin
 * // after abstractModule(...)
 * publishedModule("CrystalGUI Core", "The retained-mode UI engine.")
 * dependencies {
 *     consumerApi(project(":taffy"))              // compiled against here, AND by every consumer
 *     consumerApi("org.joml:joml:1.10.5")
 *     compileOnly("org.projectlombok:lombok:1.18.44")   // ours alone
 * }
 * ```
 *
 * - Declare a dependency in `consumerApi` when a type of it appears in this module's public API. It is
 *   on this module's `compileOnly` too, so declare it once.
 * - What is published is NOT what the build's own projects see: they keep resolving `apiElements`,
 *   which carries none of it. So a host's compile classpath -- and the overload javac picks against it
 *   -- is unchanged by publishing. A consumer's checkout is the exception, [CONSUMER_CHECKOUT].
 * - Name the OLDEST version any target ships of a library Minecraft supplies; a consumer's resolution
 *   raises it, never lowers it.
 * - Call it after [abstractModule], or the Java 8 copy is not published.
 * - The artifact is the project's name unless [artifactId] says otherwise:
 *   `publishedModule("…", "…", artifactId = "mc-shared")` on `:runtime:mc:shared`.
 */
fun Project.publishedModule(title: String, description: String, licence: Licence = Licence.LGPL3,
                            artifactId: String = name) {
    pluginManager.apply("maven-publish")
    val java = extensions.getByType<JavaPluginExtension>()
    java.withSourcesJar()
    java.withJavadocJar()
    // A directory that is both a java and a resources root lists each resource twice; it is one file.
    tasks.named<Jar>("sourcesJar") { duplicatesStrategy = DuplicatesStrategy.EXCLUDE }
    tasks.withType<Javadoc>().configureEach {
        (options as StandardJavadocDocletOptions).run {
            encoding = "UTF-8"
            docEncoding = "UTF-8"
            charSet = "UTF-8"
            // Lombok's members are not in the sources javadoc reads; a link to one is not a broken build.
            addStringOption("Xdoclint:none", "-quiet")
        }
    }

    val consumerApi = configurations.create(CONSUMER_API) {
        isCanBeConsumed = false
        isCanBeResolved = false
    }
    configurations["compileOnly"].extendsFrom(consumerApi)
    if (gradle.extensions.extraProperties.has(CONSUMER_CHECKOUT)) {
        listOf("apiElements", "runtimeElements", "downgradedApiElements", "downgradedRuntimeElements")
            .mapNotNull { configurations.findByName(it) }.forEach { it.extendsFrom(consumerApi) }
    }

    val component = objects.newInstance(ComponentFactory::class.java).factory.adhoc("published")
    fun variant(name: String, like: String, jar: TaskProvider<*>, jvm: Int?) =
        publishedVariant(name, configurations[like], consumerApi, jar, jvm)
    val jar = tasks.named("jar")
    component.add(variant("publishedApiElements", "apiElements", jar, null), "compile")
    component.add(variant("publishedRuntimeElements", "runtimeElements", jar, null), "runtime")
    if (tasks.names.contains("downgradedJar")) {
        val copy = tasks.named("downgradedJar")
        component.add(variant("publishedJava8ApiElements", "apiElements", copy, DOWNGRADED_JAVA), null)
        component.add(variant("publishedJava8RuntimeElements", "runtimeElements", copy, DOWNGRADED_JAVA), null)
    }
    component.add(configurations["sourcesElements"], null)
    component.add(configurations["javadocElements"], null)
    components.add(component)

    extensions.getByType<PublishingExtension>().publications.create<MavenPublication>("maven") {
        from(component)
        this.artifactId = artifactId
        pom { describe(title, description, licence) }
    }
    apiCompatibility(artifactId)
}

/**
 * A shipped mod jar as a Maven artifact. A mod depends on it to RUN it in a dev client; it compiles
 * against the [publishedModule]s. No dependencies: the jar carries everything it needs.
 */
data class ShippedJar(
    val group: String,
    val artifactId: String,
    val title: String,
    val description: String,
    val licence: Licence = Licence.LGPL3,
)

/**
 * Publishes [jar] as [shipped], at this project's version. [checkedBy] runs before any publish of it,
 * so an artifact that fails its own checks never reaches a repository.
 *
 * ```kotlin
 * publishShippedJar(tasks.named<Jar>("jomlJar"), ShippedJar("com.example", "example-joml", "JOML", "...", Licence.MIT))
 * ```
 * A [SingleJarSpec] names its jar's publication with `publication = ShippedJar(...)` instead.
 */
fun Project.publishShippedJar(
    jar: TaskProvider<out AbstractArchiveTask>, shipped: ShippedJar, checkedBy: TaskProvider<*>? = null,
) {
    pluginManager.apply("maven-publish")
    val name = shipped.artifactId.split('-', '_').joinToString("") { it.replaceFirstChar(Char::uppercase) }
    extensions.getByType<PublishingExtension>().publications.create<MavenPublication>(name.replaceFirstChar(Char::lowercase)) {
        groupId = shipped.group
        artifactId = shipped.artifactId
        version = project.version.toString()
        artifact(jar)
        pom { describe(shipped.title, shipped.description, shipped.licence) }
    }
    if (checkedBy != null) {
        tasks.matching { it.name.startsWith("publish${name}PublicationTo") }.configureEach { dependsOn(checkedBy) }
    }

    // The same jar to a build that includes this one, which substitutes the coordinate with it:
    // `com.crystalgui.settings`' checkout. The capability tells two jars of one project apart.
    configurations.create(name.replaceFirstChar(Char::lowercase) + "Elements") {
        isCanBeConsumed = true
        isCanBeResolved = false
        attributes {
            attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
            attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.LIBRARY))
            attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements::class.java, LibraryElements.JAR))
            attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling::class.java, Bundling.EXTERNAL))
            attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, DOWNGRADED_JAVA)
        }
        outgoing.capability("${shipped.group}:${shipped.artifactId}:${project.version}")
        outgoing.artifact(jar)
    }
}

/** The fields every POM of ours carries. */
fun MavenPom.describe(title: String, description: String, licence: Licence) {
    name.set(title)
    this.description.set(description)
    licenses { license { name.set(licence.name); url.set(licence.url) } }
}

internal interface ComponentFactory {
    @get:Inject val factory: SoftwareComponentFactory
}

/**
 * A variant for the published metadata only. Resolvable rather than consumable, so it is never a
 * candidate when a project of the build depends on this one -- a consumer requesting no attributes at
 * all (RetroFuturaGradle's `shadowImplementation`) would otherwise find it ambiguous with `apiElements`.
 */
private fun Project.publishedVariant(
    name: String, like: Configuration, deps: Configuration, jar: TaskProvider<*>, jvm: Int?,
): Configuration = configurations.create(name) {
    isCanBeConsumed = false
    isCanBeResolved = true
    extendsFrom(deps)
    attributes {
        // Read when resolved: a script configures its Java after whatever applied this.
        like.attributes.keySet().forEach { key ->
            @Suppress("UNCHECKED_CAST")
            attributeProvider(key as Attribute<Any>, provider { like.attributes.getAttribute(key)!! })
        }
        if (jvm != null) attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, jvm)
    }
    outgoing.artifact(jar)
}

/** A POM has one scope per dependency; the Java 8 copies and the documentation ride as classifiers. */
private fun AdhocComponentWithVariants.add(configuration: Configuration, mavenScope: String?) =
    addVariantsFromConfiguration(configuration) {
        if (mavenScope != null) mapToMavenScope(mavenScope) else mapToOptional()
    }
