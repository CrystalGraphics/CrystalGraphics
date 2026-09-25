package cgbuildlogic

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.signature.SignatureReader
import org.objectweb.asm.signature.SignatureVisitor
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.MethodNode
import java.io.Closeable
import java.io.File
import java.util.zip.ZipFile

/**
 * What a node's compile needs from its [targets] — the jars a stub replaces — given what javac resolved
 * ([SigRecorder]'s records), as the pruned classes [StubSignatures] writes.
 *
 * ```kotlin
 * StubClosure(compileClasspath, targets = externalJars).use { closure ->
 *     StubSignatures.write(closure.keep(recordFiles), emptyList(), file("stub.sig"))
 * }
 * ```
 *
 * The records are not enough alone; the closure adds what javac also reads:
 * - every supertype of a kept class, and the types in a kept member's descriptor, signature and throws;
 * - every constructor of a class a node calls one of or extends;
 * - every abstract method of an interface, an annotation's elements, an enum's `values`/`valueOf`;
 * - for each class a node declares: every abstract method in its supertypes AND its implementation
 *   elsewhere in them (else javac reports the class as not implementing it), and every method it
 *   overrides;
 * - through a classpath entry that is NOT a target (our own projects, kept whole on the classpath):
 *   its supertypes, and for one a node names, every member's types — javac weighs every overload of a
 *   method it calls there, and completes each parameter type to do it.
 */
class StubClosure(classpath: List<File>, private val targets: Set<File>) : Closeable {

    private class Source(val bytes: () -> ByteArray, val target: Boolean)

    private val zips = mutableListOf<ZipFile>()
    private val sources = HashMap<String, Source>()
    private val nodes = HashMap<String, ClassNode>()

    private val kept = sortedSetOf<String>()
    private val keptFields = HashMap<String, MutableSet<String>>()
    private val keptMethods = HashMap<String, MutableSet<String>>()
    private val allConstructors = HashSet<String>()
    private val targetWork = ArrayDeque<String>()

    private val throughHeaders = HashSet<String>()
    private val throughMembers = HashSet<String>()
    private val throughWork = ArrayDeque<String>()

    init {
        // First on the classpath wins, as it does for javac.
        for (entry in classpath) {
            val target = entry in targets
            if (entry.isDirectory) {
                entry.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.forEach { file ->
                    val name = file.relativeTo(entry).invariantSeparatorsPath.removeSuffix(".class")
                    if (!name.startsWith("META-INF/")) sources.putIfAbsent(name, Source(file::readBytes, target))
                }
            } else if (entry.isFile && entry.extension == "jar") {
                val zip = ZipFile(entry).also { zips += it }
                for (e in zip.entries()) {
                    if (e.isDirectory || !e.name.endsWith(".class") || e.name.startsWith("META-INF/")) continue
                    sources.putIfAbsent(e.name.removeSuffix(".class"), Source({ zip.getInputStream(e).use { it.readBytes() } }, target))
                }
            }
        }
    }

    override fun close() = zips.forEach(ZipFile::close)

    /** The kept classes of [targets], pruned, for [records] — what [SigRecorder] wrote. */
    fun keep(records: Collection<File>): List<ClassNode> {
        val supers = mutableListOf<Pair<String, String>>()
        val declared = mutableListOf<Triple<String, String, String>>()
        for (line in records.flatMap { it.readLines() }) {
            val p = line.split(' ')
            when (p[0]) {
                "C" -> root(p[1])
                "F" -> { root(p[1]); keptFields.getOrPut(p[1]) { HashSet() } += p[2] }
                "M" -> {
                    root(p[1])
                    if (p[2] == "<init>") allConstructors += p[1] else keptMethods.getOrPut(p[1]) { HashSet() } += p[2] + p[3]
                }
                "X" -> { supers += p[1] to p[2]; root(p[2]); allConstructors += p[2] }
                "O" -> declared += Triple(p[1], p[2], p[3])
            }
        }
        drain()
        for ((ours, superNames) in supers.groupBy({ it.first }, { it.second })) {
            val closure = LinkedHashSet<String>()
            superNames.forEach { superClosure(it, closure) }
            val abstracts = closure.mapNotNull(::node).flatMap { c ->
                c.methods.filter { it.access and Opcodes.ACC_ABSTRACT != 0 }.map { it.name + it.desc }
            }.toSet()
            val overrides = declared.filter { it.first == ours }
            for (s in closure) {
                val c = node(s) ?: continue
                for (m in c.methods) {
                    if (m.access and Opcodes.ACC_PRIVATE != 0) continue
                    if (m.name + m.desc in abstracts) keepMethod(s, m)
                    for ((_, name, desc) in overrides) {
                        if (m.name == name && m.desc.startsWith(desc.substring(0, desc.indexOf(')') + 1))) keepMethod(s, m)
                    }
                }
            }
        }
        drain()
        return kept.map(::prune)
    }

    private fun isTarget(c: String) = sources[c]?.target == true

    private fun node(c: String): ClassNode? {
        val source = sources[c] ?: return null
        return nodes.getOrPut(c) { ClassNode().also { ClassReader(source.bytes()).accept(it, ClassReader.SKIP_CODE) } }
    }

    /** Named by a node: a target is kept; one of ours is walked member by member. */
    private fun root(c: String) {
        need(c)
        if (!isTarget(c) && sources.containsKey(c) && throughMembers.add(c)) {
            val n = node(c)!!
            n.fields.filter { it.access and Opcodes.ACC_PRIVATE == 0 }.forEach { desc(it.desc); signature(it.signature) }
            n.methods.filter { it.access and Opcodes.ACC_PRIVATE == 0 }.forEach {
                desc(it.desc); signature(it.signature); it.exceptions?.forEach(::need)
            }
        }
    }

    private fun need(c: String?) {
        if (c == null) return
        val source = sources[c] ?: return
        if (source.target) { if (kept.add(c)) targetWork += c }
        else if (throughHeaders.add(c)) throughWork += c
    }

    private fun keepMethod(owner: String, m: MethodNode) {
        keptMethods.getOrPut(owner) { HashSet() } += m.name + m.desc
        need(owner)
    }

    private fun superClosure(c: String, out: MutableSet<String>) {
        val n = node(c) ?: return
        if (!out.add(c)) return
        n.superName?.let { superClosure(it, out) }
        n.interfaces.forEach { superClosure(it, out) }
    }

    /** Headers first, then the types kept members name, until nothing new is needed. */
    private fun drain() {
        do {
            while (throughWork.isNotEmpty()) header(throughWork.removeFirst())
            while (targetWork.isNotEmpty()) {
                val c = targetWork.removeFirst()
                header(c)
                val n = node(c)!!
                val annotation = n.access and Opcodes.ACC_ANNOTATION != 0
                val iface = n.access and Opcodes.ACC_INTERFACE != 0
                val enum = n.access and Opcodes.ACC_ENUM != 0
                // What a supertype leaves abstract and this class implements: dropping the implementation
                // leaves the method abstract here, and a lambda for an interface with two is refused.
                val inherited = LinkedHashSet<String>().also { closure ->
                    n.superName?.let { superClosure(it, closure) }
                    n.interfaces.forEach { superClosure(it, closure) }
                }.mapNotNull(::node).flatMap { s ->
                    s.methods.filter { it.access and Opcodes.ACC_ABSTRACT != 0 }.map { it.name + it.desc }
                }.toSet()
                for (m in n.methods) {
                    if (annotation || (iface && m.access and Opcodes.ACC_ABSTRACT != 0) || (enum && (m.name == "values" || m.name == "valueOf"))
                        || (m.access and (Opcodes.ACC_ABSTRACT or Opcodes.ACC_PRIVATE) == 0 && m.name + m.desc in inherited)) {
                        keptMethods.getOrPut(c) { HashSet() } += m.name + m.desc
                    }
                }
            }
            for (c in kept.toList()) {
                val n = node(c)!!
                n.fields.filter { keepsField(c, it) }.forEach { desc(it.desc); signature(it.signature) }
                n.methods.filter { keepsMethod(c, it) }.forEach { desc(it.desc); signature(it.signature); it.exceptions?.forEach(::need) }
            }
        } while (targetWork.isNotEmpty() || throughWork.isNotEmpty())
    }

    private fun header(c: String) {
        val n = node(c) ?: return
        need(n.superName)
        n.interfaces.forEach(::need)
        need(n.outerClass)
        n.innerClasses.filter { it.name == c }.forEach { need(it.outerName) }
        signature(n.signature)
    }

    private fun keepsField(c: String, f: FieldNode) = keptFields[c]?.contains(f.name) == true

    private fun keepsMethod(c: String, m: MethodNode) =
        (m.name == "<init>" && c in allConstructors) || keptMethods[c]?.contains(m.name + m.desc) == true

    private fun desc(d: String) {
        val t = Type.getType(d)
        if (t.sort == Type.METHOD) { t.argumentTypes.forEach(::type); type(t.returnType) } else type(t)
    }

    private fun type(t: Type) {
        val element = if (t.sort == Type.ARRAY) t.elementType else t
        if (element.sort == Type.OBJECT) need(element.internalName)
    }

    private fun signature(s: String?) {
        if (s == null) return
        SignatureReader(s).accept(object : SignatureVisitor(Opcodes.ASM9) {
            override fun visitClassType(name: String) = need(name)
            override fun visitInnerClassType(name: String) {}
        })
    }

    /** [c] as the stub carries it: kept members only, and nothing javac does not read. */
    private fun prune(c: String): ClassNode {
        val n = node(c)!!
        val out = ClassNode()
        out.version = n.version
        // A record's components are dropped, so the stub is a plain class.
        out.access = n.access and Opcodes.ACC_RECORD.inv()
        out.name = n.name
        out.signature = n.signature
        out.superName = n.superName
        out.interfaces = n.interfaces
        out.outerClass = n.outerClass
        out.outerMethod = n.outerMethod
        out.outerMethodDesc = n.outerMethodDesc
        out.innerClasses = n.innerClasses.filter { it.name in kept }
        // An annotation type keeps its JDK meta-annotations: Retention decides what javac emits for a use.
        if (n.access and Opcodes.ACC_ANNOTATION != 0) {
            out.visibleAnnotations = n.visibleAnnotations?.filter { it.desc.startsWith("Ljava/lang/annotation/") }?.ifEmpty { null }
            out.invisibleAnnotations = n.invisibleAnnotations?.filter { it.desc.startsWith("Ljava/lang/annotation/") }?.ifEmpty { null }
        }
        out.fields = n.fields.filter { keepsField(c, it) }
            .map { FieldNode(it.access, it.name, it.desc, it.signature, it.value) }
        out.methods = n.methods.filter { keepsMethod(c, it) && it.access and Opcodes.ACC_SYNTHETIC == 0 }
            .map { m -> MethodNode(m.access, m.name, m.desc, m.signature, m.exceptions?.toTypedArray()).also { it.annotationDefault = m.annotationDefault } }
        return out
    }
}
