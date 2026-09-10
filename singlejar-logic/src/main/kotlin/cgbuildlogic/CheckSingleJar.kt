package cgbuildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction
import java.util.jar.JarFile
import java.util.zip.ZipFile

/**
 * Everything the single jar must be true about itself, asserted on the finished file.
 *
 * <p>The merged jar is the product: one artifact for four loaders, and every way it can be wrong is
 * silent. A class above major 52 makes FML 1.7.10 call the whole jar "probably a corrupt zip". An
 * unrelocated `org.joml` is a split package that kills Forge before a mod class loads. A second copy
 * of the engine is 20 MB nobody notices. A host class left at its source name is a
 * `NoClassDefFoundError` on three loaders at once. None of those fails a build; all of them fail
 * here.</p>
 *
 * <pre>
 * tasks.register&lt;CheckSingleJar&gt;("checkSingleJar") {
 *     jar.set(singleJar.flatMap { it.archiveFile })
 *     expectSingle.set(listOf("com/crystalgui/ui/"))
 *     expectPerTarget.set(mapOf("com/crystalgui/mc/" to 4))
 *     requiredManifest.set(mapOf("TweakClass" to "org.spongepowered.asm.launch.MixinTweaker"))
 * }
 * </pre>
 */
abstract class CheckSingleJar : DefaultTask() {

    /** The finished single jar. */
    @get:InputFile
    abstract val jar: RegularFileProperty

    /** Highest class-file major version permitted. 52 is Java 8 — FML 1.7.10's ASM 5 ceiling. */
    @get:Input
    abstract val classMajorCeiling: Property<Int>

    /** Entry prefixes that must not appear at all. */
    @get:Input
    abstract val forbiddenPrefixes: ListProperty<String>

    /** Class prefixes each of whose classes must appear exactly once. */
    @get:Input
    abstract val expectSingle: ListProperty<String>

    /**
     * A host class by its SOURCE path → how many relocated copies the jar must hold.
     *
     * <p>By exact class rather than by package prefix, because the 1.7.10 loader legitimately owns
     * classes in the very packages the 1.20.x host is relocated out of — `com.crystalgui.mc.client`
     * holds both `CgUiScreen` (1.7.10's own, and staying) and `CgUiScreen1201` (relocated three
     * times). A prefix cannot tell those apart; a class name can.</p>
     */
    @get:Input
    abstract val relocatedClasses: MapProperty<String, Int>

    /** Manifest attributes the jar must carry, and the values they must have. */
    @get:Input
    abstract val requiredManifest: MapProperty<String, String>

    /** Entries that must exist, by exact path. */
    @get:Input
    abstract val requiredEntries: ListProperty<String>

    /**
     * Service file → a provider it must list.
     *
     * <p>Not `requiredServices`: `TaskInternal` already declares `getRequiredServices()`, and Gradle
     * refuses to decorate a class whose managed property collides with one of its own.</p>
     */
    @get:Input
    abstract val expectServices: MapProperty<String, String>

    init {
        group = "verification"
        description = "Fails unless the single jar is what four loaders each need it to be."
        classMajorCeiling.convention(52)
        forbiddenPrefixes.convention(listOf("META-INF/versions/"))
    }

    @TaskAction
    fun check() {
        val file = jar.get().asFile
        val problems = mutableListOf<String>()
        val ceiling = classMajorCeiling.get()

        val names = mutableListOf<String>()
        val classCounts = HashMap<String, Int>()
        var classes = 0
        var tooNew = 0
        var firstTooNew: String? = null

        ZipFile(file).use { zip ->
            for (entry in zip.entries()) {
                if (entry.isDirectory) continue
                val name = entry.name
                names += name
                if (!name.endsWith(".class")) continue
                classes++
                classCounts[name] = (classCounts[name] ?: 0) + 1
                val head = ByteArray(8)
                zip.getInputStream(entry).use { it.read(head) }
                val major = ((head[6].toInt() and 0xFF) shl 8) or (head[7].toInt() and 0xFF)
                if (major > ceiling) {
                    tooNew++
                    if (firstTooNew == null) firstTooNew = "$name (major $major)"
                }
            }
        }

        if (tooNew > 0) {
            problems += "$tooNew classes are above major $ceiling; the first is $firstTooNew. " +
                "FML 1.7.10 reads every entry with ASM 5 and calls the whole jar corrupt."
        }

        forbiddenPrefixes.get().forEach { prefix ->
            val hits = names.filter { it.startsWith(prefix) }
            if (hits.isNotEmpty()) {
                problems += "${hits.size} entries under $prefix, the first ${hits.first()}"
            }
        }

        // A class the engine owns must exist once. A zip CAN hold two entries of one name; the merge's
        // EXCLUDE strategy is what prevents it, and this is what proves the strategy held.
        expectSingle.get().forEach { prefix ->
            val duplicated = classCounts.filterKeys { it.startsWith(prefix) }.filterValues { it > 1 }
            if (duplicated.isNotEmpty()) {
                problems += "${duplicated.size} classes under $prefix appear more than once, " +
                    "the first ${duplicated.keys.first()}"
            }
            if (classCounts.keys.none { it.startsWith(prefix) }) {
                problems += "no classes under $prefix at all — the engine did not reach the jar"
            }
        }

        // The host: one relocated copy per loader, and nothing left at the source name.
        relocatedClasses.get().forEach { (source, expected) ->
            if (source in names) {
                problems += "$source is still at its source name — relocation did not happen, and " +
                    "three loaders would share one class"
            }
            val leaf = "/" + source.substringAfterLast('/')
            val copies = names.filter { it.endsWith(leaf) }
            if (copies.size != expected) {
                problems += "expected $expected relocated copies of " +
                    source.substringAfterLast('/') + ", found ${copies.size}: $copies"
            }
        }

        requiredEntries.get().forEach { required ->
            if (required !in names) problems += "the jar has no $required"
        }

        val manifest = JarFile(file).use { it.manifest }
        requiredManifest.get().forEach { (key, value) ->
            val actual = manifest?.mainAttributes?.getValue(key)
            if (actual == null) {
                problems += "the manifest has no $key"
            } else if (value.isNotEmpty() && actual != value) {
                problems += "the manifest says $key = $actual, expected $value"
            }
        }
        if (manifest?.mainAttributes?.getValue("Multi-Release") != null) {
            problems += "the manifest says Multi-Release, which puts modern classes under " +
                "META-INF/versions where FML 1.7.10's scanner reads them and fails"
        }

        if (expectServices.get().isNotEmpty()) {
            ZipFile(file).use { zip ->
                expectServices.get().forEach { (service, provider) ->
                    val entry = zip.getEntry("META-INF/services/$service")
                    if (entry == null) {
                        problems += "no META-INF/services/$service"
                    } else {
                        val text = zip.getInputStream(entry).bufferedReader().readText()
                        if (!text.contains(provider)) {
                            problems += "META-INF/services/$service does not list $provider — the " +
                                "service files were copied rather than unioned, so one jar's " +
                                "providers replaced another's"
                        }
                    }
                }
            }
        }

        if (problems.isNotEmpty()) {
            // DELETED, not left on disk: a jar that fails this is one somebody would otherwise install.
            file.delete()
            throw GradleException(
                "${file.name} is not shippable, and has been deleted:\n"
                    + problems.joinToString("\n") { "  - $it" })
        }
        logger.lifecycle("[single-jar] {}: {} entries, {} classes, all at or below major {}",
                file.name, names.size, classes, ceiling)
    }
}
