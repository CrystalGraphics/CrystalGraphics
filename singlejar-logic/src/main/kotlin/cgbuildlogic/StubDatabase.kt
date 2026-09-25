package cgbuildlogic

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.MethodNode
import java.io.File
import java.security.MessageDigest
import java.util.BitSet
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Every node's compile-time platform — Minecraft, its loader and their libraries — as ONE database:
 * each class's public API stored once, tagged with the nodes it is identical on, as javac's `ct.sym`
 * stores every JDK release; plus the rename tables the same way. What a stub build compiles against.
 * Shipped as `singlejar-logic/stubs.zip`, and read zipped.
 *
 * ```
 * ./gradlew listStubInputs; ./gradlew -p CrystalGraphics listStubInputs   # each repo's real nodes
 * ./gradlew -p CrystalGraphics/singlejar-logic generateStubDatabase        # -PcgStubText also writes stubs/ as text
 * ```
 *
 * ```kotlin
 * StubStore.jarsFor(zip, "forge:1.20.1", gradleUserHome) // the node's classes, unpacked once per machine
 * StubDatabase.names(zip, "forge:1.20.1")                // its rename table, for one rename task
 * StubDatabase.manifest(zip, "fabric:1.20.1")            // the Fabric-* attributes Loom would write
 * ```
 *
 * Inside the zip:
 * ```
 * nodes.txt                  one node per line: branch:version, the order sets are numbered against
 * sets.txt                   <id> forge:1.17.1-1.20.1,fabric:1.20.1   runs of consecutive nodes
 * manifests.txt              <node> <attribute> <value>
 * api/<a>.<b>.sig            the classes under package a/b, each [StubSignatures] block headed `in <set>`
 * names/srg.tsrg             official -> SRG, per class, headed `in <set>`
 * names/intermediary.tiny    named -> intermediary, the same way
 * ```
 *
 * - The API is every public and protected member of every class but anonymous and local ones, so a
 *   change to our code never needs this regenerated — only a node added or re-pinned.
 */
object StubDatabase {

    const val HEADER = "cg-stub-db 1"

    /** Which rename table a node's thin jar takes. */
    enum class Names { TSRG, TINY }

    /** One node's inputs: its toolchain's jars, first on the classpath first; its full rename table; Loom's manifest. */
    class Node(val key: String, val targets: List<File>, val names: Pair<Names, File>?, val manifest: List<Pair<String, String>>)

    private class Block(val owner: String, val text: ByteArray) {
        val nodes = BitSet()
    }

    private val BRANCHES = listOf("common") + MODERN_LOADERS

    @JvmStatic
    fun main(args: Array<String>) {
        val zip = File(args[0])
        val text = args[1].takeIf { it != "-" }?.let(::File)
        build(readNodes(args.drop(2).map(::File)), zip, text) { println(it) }
    }

    // ── Reading ───────────────────────────────────────────────────────────────────────────────────

    private val nodeLists = ConcurrentHashMap<String, List<String>>()

    /** The nodes [zip] has an entry for. */
    fun nodes(zip: File): List<String> = nodeLists.computeIfAbsent("${zip.absolutePath}@${zip.lastModified()}") {
        ZipFile(zip).use { z -> text(z, "nodes.txt").lines().filter { it.isNotBlank() } }
    }

    /** The `Fabric-*` manifest attributes Loom writes on [node]'s renamed jar. */
    fun manifest(zip: File, node: String): List<Pair<String, String>> = ZipFile(zip).use { z ->
        text(z, "manifests.txt").lines().map { it.split(' ', limit = 3) }.filter { it.size == 3 && it[0] == node }.map { it[1] to it[2] }
    }

    /** [node]'s rename table as the renamer reads it, or "" where its loader runs Mojang's names. */
    fun names(zip: File, node: String): String = ZipFile(zip).use { z ->
        val nodes = text(z, "nodes.txt").lines().filter { it.isNotBlank() }
        val index = nodes.indexOf(node).also { require(it >= 0) { "$zip has no node $node" } }
        val sets = text(z, "sets.txt").lines().filter { it.isNotBlank() }.map { it.split(' ', limit = 2) }
            .filter { (_, runs) -> index in expand(runs, nodes) }.map { it[0] }.toSet()
        fun cut(entry: String): String {
            val out = StringBuilder()
            var keep = false
            z.getInputStream(z.getEntry(entry)).bufferedReader().useLines { lines ->
                for (line in lines.drop(1)) {
                    if (line.startsWith("in ")) keep = line.substring(3) in sets
                    else if (keep) out.append(line).append('\n')
                }
            }
            return out.toString()
        }
        val srg = cut("names/srg.tsrg")
        val tiny = if (srg.isEmpty()) cut("names/intermediary.tiny") else ""
        when {
            srg.isNotEmpty() -> "tsrg2 left right\n$srg"
            tiny.isNotEmpty() -> "tiny\t2\t0\tnamed\tintermediary\n$tiny"
            else -> ""
        }
    }

    private fun text(zip: ZipFile, name: String): String =
        zip.getInputStream(zip.getEntry(name) ?: throw IllegalArgumentException("${zip.name} has no $name")).use { it.readBytes().decodeToString() }

    /** `forge:1.17.1-1.20.1,fabric:1.20.1` -> the node indices it names. */
    fun expand(runs: String, nodes: List<String>): Set<Int> = runs.split(',').flatMap { run ->
        val branch = run.substringBefore(':')
        val (from, to) = run.substringAfter(':').split('-').let { bounds -> bounds[0] to bounds.getOrElse(1) { bounds[0] } }
        (nodes.indexOf("$branch:$from")..nodes.indexOf("$branch:$to")).toList()
    }.toSet()

    // ── Writing ───────────────────────────────────────────────────────────────────────────────────

    /** Every `build/stubs/inputs.txt` under [roots]' 1.20.x trees, merged per node across roots. */
    fun readNodes(roots: List<File>): List<Node> {
        val targets = LinkedHashMap<String, LinkedHashSet<File>>()
        val names = HashMap<String, Pair<Names, File>>()
        val manifests = HashMap<String, LinkedHashMap<String, String>>()
        for (root in roots) for (branch in BRANCHES) {
            for (version in File(root, "runtime/mc/modern/$branch/versions").listFiles().orEmpty()) {
                val inputs = File(version, "build/stubs/inputs.txt").takeIf { it.isFile } ?: continue
                val key = "$branch:${version.name}"
                for (line in inputs.readLines()) {
                    val parts = line.split(' ', limit = 3)
                    when (parts[0]) {
                        "target" -> targets.getOrPut(key) { LinkedHashSet() } += File(line.substringAfter(' '))
                        "names" -> names.putIfAbsent(key, Names.valueOf(parts[1]) to File(parts[2]))
                        "manifest" -> manifests.getOrPut(key) { LinkedHashMap() }.putIfAbsent(parts[1], parts[2])
                    }
                }
            }
        }
        return targets.keys.sortedWith(compareBy<String> { BRANCHES.indexOf(it.substringBefore(':')) }
            .then(compareBy(MinecraftVersionOrder) { it.substringAfter(':') }))
            .map { Node(it, targets.getValue(it).toList(), names[it], manifests[it]?.toList().orEmpty()) }
    }

    /** Writes [zip], and the same files as text under [textDir] when one is given. */
    fun build(nodes: List<Node>, zip: File, textDir: File?, log: (String) -> Unit) {
        val api = ConcurrentHashMap<String, Block>()
        nodes.withIndex().toList().parallelStream().forEach { (index, node) ->
            val seen = HashSet<String>()
            for (jar in node.targets.filter { it.isFile && it.extension == "jar" }) ZipFile(jar).use { z ->
                for (entry in z.entries()) {
                    val name = entry.name
                    if (!name.endsWith(".class") || name.startsWith("META-INF/") || !seen.add(name)) continue
                    val c = api(z.getInputStream(entry).use { it.readBytes() }) ?: continue
                    val text = StringBuilder().also { StubSignatures.format(c, it) }.toString().toByteArray()
                    val block = api.computeIfAbsent(digest(text)) { Block(c.name, text) }
                    synchronized(block) { block.nodes.set(index) }
                }
            }
            log("  ${node.key}: ${seen.size} classes")
        }

        val sets = LinkedHashMap<BitSet, Int>()
        fun set(nodesOf: BitSet) = sets.getOrPut(nodesOf) { sets.size }
        val files = LinkedHashMap<String, ByteArray>()
        api.values.groupBy { group(it.owner) }.toSortedMap().forEach { (group, blocks) -> files["api/$group.sig"] = blocks(blocks, ::set) }
        files["names/srg.tsrg"] = blocks(tables(nodes, Names.TSRG), ::set)
        files["names/intermediary.tiny"] = blocks(tables(nodes, Names.TINY), ::set)
        files["nodes.txt"] = nodes.joinToString("\n", postfix = "\n") { it.key }.toByteArray()
        files["sets.txt"] = sets.entries.joinToString("\n", postfix = "\n") { (bits, id) -> "$id ${runs(bits, nodes)}" }.toByteArray()
        files["manifests.txt"] = nodes.flatMap { n -> n.manifest.map { (k, v) -> "${n.key} $k $v" } }.joinToString("\n", postfix = "\n").toByteArray()

        zip.parentFile.mkdirs()
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            out.setLevel(Deflater.BEST_COMPRESSION)
            for ((name, bytes) in files) {
                out.putNextEntry(ZipEntry(name).apply { time = FIXED_TIME })
                out.write(bytes)
                out.closeEntry()
            }
        }
        textDir?.let { dir ->
            dir.deleteRecursively()
            files.forEach { (name, bytes) -> File(dir, name).apply { parentFile.mkdirs() }.writeBytes(bytes) }
        }
        log("stub database: ${nodes.size} nodes, ${api.size} distinct classes, ${sets.size} node sets")
        log("  ${files.values.sumOf { it.size.toLong() } / 1_048_576} MB of text in ${files.size} entries -> $zip, ${zip.length() / 1_048_576} MB")
    }

    /** A class's public face, or null for one nothing outside its own file can name. */
    private fun api(bytes: ByteArray): ClassNode? {
        val n = ClassNode()
        ClassReader(bytes).accept(n, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        if (n.name == "module-info" || n.name.endsWith("/package-info") || n.access and Opcodes.ACC_SYNTHETIC != 0) return null
        val self = n.innerClasses.firstOrNull { it.name == n.name }
        if (self != null && (self.innerName == null || self.outerName == null)) return null
        val visible = Opcodes.ACC_PUBLIC or Opcodes.ACC_PROTECTED
        val out = ClassNode()
        out.version = n.version
        // A record's components are dropped, so the stub is a plain class.
        out.access = n.access and Opcodes.ACC_RECORD.inv()
        out.name = n.name
        out.signature = n.signature
        out.superName = n.superName
        out.interfaces = n.interfaces
        out.innerClasses = n.innerClasses.filter { it.innerName != null && it.outerName != null }
        // An annotation type keeps its JDK meta-annotations: Retention decides what javac emits for a use.
        if (n.access and Opcodes.ACC_ANNOTATION != 0) {
            out.visibleAnnotations = n.visibleAnnotations?.filter { it.desc.startsWith("Ljava/lang/annotation/") }?.ifEmpty { null }
            out.invisibleAnnotations = n.invisibleAnnotations?.filter { it.desc.startsWith("Ljava/lang/annotation/") }?.ifEmpty { null }
        }
        out.fields = n.fields.filter { it.access and visible != 0 && it.access and Opcodes.ACC_SYNTHETIC == 0 }
            .map { FieldNode(it.access, it.name, it.desc, it.signature, it.value) }
        out.methods = n.methods.filter { it.access and visible != 0 && it.access and Opcodes.ACC_SYNTHETIC == 0 }
            .map { m -> MethodNode(m.access, m.name, m.desc, m.signature, m.exceptions?.toTypedArray()).also { it.annotationDefault = m.annotationDefault } }
        return out
    }

    /** Every node's [format] table, split into per-class blocks and deduplicated the same way. */
    private fun tables(nodes: List<Node>, format: Names): List<Block> {
        val blocks = LinkedHashMap<String, Block>()
        nodes.forEachIndexed { index, node ->
            val (f, file) = node.names ?: return@forEachIndexed
            if (f != format) return@forEachIndexed
            val lines = file.readLines()
            val perClass = if (format == Names.TSRG) tsrgClasses(lines) else tinyClasses(lines)
            for ((owner, text) in perClass) {
                val bytes = text.toByteArray()
                blocks.getOrPut(digest(bytes)) { Block(owner, bytes) }.nodes.set(index)
            }
        }
        return blocks.values.toList()
    }

    /** TSRG v1 or v2, without parameter and `static` lines: nothing of ours is in the table to take them. */
    private fun tsrgClasses(lines: List<String>): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        var owner: String? = null
        val text = StringBuilder()
        fun flush() { owner?.let { out += it to text.toString() }; text.clear() }
        for (line in lines) when {
            line.startsWith("tsrg2 ") -> {}
            !line.startsWith("\t") -> { flush(); owner = line.substringBefore(' '); text.append(line).append('\n') }
            !line.startsWith("\t\t") -> text.append(line).append('\n')
        }
        flush()
        return out
    }

    /** Tiny v2 cut to `named -> intermediary`, descriptors rewritten into the named namespace. */
    private fun tinyClasses(lines: List<String>): List<Pair<String, String>> {
        val namespaces = lines.first().split('\t').drop(3)
        val named = namespaces.indexOf("named")
        val inter = namespaces.indexOf("intermediary")
        fun name(parts: List<String>, first: Int, ns: Int) = parts.getOrNull(first + ns)?.ifEmpty { null } ?: parts[first]
        val classes = lines.filter { it.startsWith("c\t") }.map { it.split('\t') }.associate { it[1] to name(it, 1, named) }
        val reference = Regex("L([^;]+);")
        fun remap(desc: String) = reference.replace(desc) { "L${classes[it.groupValues[1]] ?: it.groupValues[1]};" }
        val out = mutableListOf<Pair<String, String>>()
        var owner: String? = null
        val text = StringBuilder()
        fun flush() { owner?.let { out += it to text.toString() }; text.clear() }
        for (line in lines.drop(1)) {
            val p = line.split('\t')
            when {
                line.startsWith("c\t") -> {
                    flush(); owner = name(p, 1, named)
                    text.append("c\t").append(owner).append('\t').append(name(p, 1, inter)).append('\n')
                }
                owner != null && p.size > 3 && p[0].isEmpty() && (p[1] == "m" || p[1] == "f") ->
                    text.append('\t').append(p[1]).append('\t').append(remap(p[2])).append('\t')
                        .append(name(p, 3, named)).append('\t').append(name(p, 3, inter)).append('\n')
            }
        }
        flush()
        return out
    }

    private fun blocks(blocks: List<Block>, set: (BitSet) -> Int): ByteArray {
        val out = StringBuilder(HEADER).append('\n')
        for (b in blocks.sortedWith(compareBy<Block> { it.owner }.thenBy { it.nodes.nextSetBit(0) })) {
            out.append("in ").append(set(b.nodes)).append('\n').append(String(b.text))
        }
        return out.toString().toByteArray()
    }

    /** `a/b/C` -> `a.b`, the entry a class's block lives in; Minecraft one level deeper. */
    private fun group(owner: String): String {
        val packages = owner.split('/').dropLast(1)
        return packages.take(if (owner.startsWith("net/minecraft/")) 3 else 2).joinToString(".").ifEmpty { "default" }
    }

    /** A node set as runs of consecutive nodes of one branch: `forge:1.17.1-1.20.1,fabric:1.20.1`. */
    private fun runs(bits: BitSet, nodes: List<Node>): String {
        val parts = mutableListOf<String>()
        var i = bits.nextSetBit(0)
        while (i >= 0) {
            var j = i
            val branch = nodes[i].key.substringBefore(':')
            while (j + 1 < nodes.size && bits.get(j + 1) && nodes[j + 1].key.substringBefore(':') == branch) j++
            parts += if (i == j) nodes[i].key else "${nodes[i].key}-${nodes[j].key.substringAfter(':')}"
            i = bits.nextSetBit(j + 1)
        }
        return parts.joinToString(",")
    }

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** 1980-02-01: a reproducible entry time, the one Gradle's own reproducible archives use. */
    const val FIXED_TIME = 315_532_800_000L + 31L * 86_400_000L
}
