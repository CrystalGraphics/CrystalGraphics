package cgbuildlogic

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * `stubs.zip` unpacked ONCE per machine into class files, grouped as the database groups them: one jar per
 * node set, each distinct class in exactly one. A node compiles against the set jars that include it, so
 * no node holds a copy of its own and every clone and both repos share the one store.
 *
 * ```kotlin
 * val jars = StubStore.jarsFor(zip, "forge:1.20.4", gradle.gradleUserHomeDir)
 * dependencies { "compileOnly"(files(provider { jars })) }
 * ```
 *
 * - Lives in `~/.gradle/caches/cg-stubs/<zip digest>/`, beside the libraries Gradle already caches. A new
 *   zip is a new directory, and building it removes the previous one; any can be deleted at any time.
 * - Built under a file lock and renamed into place, so two builds starting together build it once.
 */
object StubStore {

    fun jarsFor(zip: File, node: String, gradleUserHome: File): List<File> {
        val store = ensure(zip, gradleUserHome)
        val nodes = File(store, "nodes.txt").readLines().filter { it.isNotBlank() }
        val index = nodes.indexOf(node).also { require(it >= 0) { "$zip has no node $node" } }
        return File(store, "sets.txt").readLines().filter { it.isNotBlank() }.map { it.split(' ', limit = 2) }
            .filter { (_, runs) -> index in StubDatabase.expand(runs, nodes) }
            .map { (id, _) -> File(store, "sets/$id.jar") }.filter { it.isFile }
    }

    private val stores = ConcurrentHashMap<String, File>()

    /** The store for [zip], built if absent. */
    fun ensure(zip: File, gradleUserHome: File): File =
        stores.computeIfAbsent("${zip.absolutePath}@${zip.lastModified()}@$gradleUserHome") { build(zip, gradleUserHome) }

    private fun build(zip: File, gradleUserHome: File): File {
        val root = File(gradleUserHome, "caches/cg-stubs").apply { mkdirs() }
        val store = File(root, digest(zip))
        if (File(store, "complete").isFile) return store
        RandomAccessFile(File(root, "${store.name}.lock"), "rw").use { lock ->
            lock.channel.lock().use {
                if (File(store, "complete").isFile) return store
                val building = File(root, "${store.name}.building").apply { deleteRecursively(); mkdirs() }
                unpack(zip, building)
                File(building, "complete").writeText("")
                store.deleteRecursively()
                Files.move(building.toPath(), store.toPath(), StandardCopyOption.ATOMIC_MOVE)
                // A store for any other zip is a previous database: a checkout still on it rebuilds its own.
                root.listFiles().orEmpty().filter { it.isDirectory && it.name != store.name && !it.name.endsWith(".building") }
                    .forEach { it.deleteRecursively() }
            }
        }
        return store
    }

    private fun unpack(zip: File, out: File) {
        val perSet = HashMap<String, MutableList<Pair<String, ByteArray>>>()
        ZipFile(zip).use { z ->
            for (name in listOf("nodes.txt", "sets.txt")) z.getInputStream(z.getEntry(name)).use { File(out, name).writeBytes(it.readBytes()) }
            val entries = z.entries().toList().filter { it.name.startsWith("api/") }
            entries.parallelStream().forEach { entry ->
                val local = HashMap<String, MutableList<Pair<String, ByteArray>>>()
                var set: String? = null
                val block = mutableListOf<String>()
                fun flush() {
                    val id = set ?: return
                    for (c in StubSignatures.parse(block)) local.getOrPut(id) { mutableListOf() } += "${c.name}.class" to StubSignatures.classBytes(c)
                    block.clear()
                }
                val text = synchronized(z) { z.getInputStream(entry).use { it.readBytes() } }.decodeToString()
                for (line in text.lineSequence().drop(1)) {
                    if (line.startsWith("in ")) { flush(); set = line.substring(3) }
                    else if (line.isNotEmpty()) block += line
                }
                flush()
                synchronized(perSet) { local.forEach { (id, classes) -> perSet.getOrPut(id) { mutableListOf() } += classes } }
            }
        }
        File(out, "sets").mkdirs()
        perSet.entries.parallelStream().forEach { (id, classes) ->
            ZipOutputStream(File(out, "sets/$id.jar").outputStream().buffered()).use { jar ->
                for ((name, bytes) in classes.sortedBy { it.first }) {
                    jar.putNextEntry(ZipEntry(name).apply { time = StubDatabase.FIXED_TIME })
                    jar.write(bytes)
                    jar.closeEntry()
                }
            }
        }
    }

    private fun digest(zip: File): String = zip.inputStream().use { input ->
        val md = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1 shl 16)
        while (true) { val n = input.read(buffer); if (n < 0) break; md.update(buffer, 0, n) }
        md.digest().take(8).joinToString("") { "%02x".format(it) }
    }
}
