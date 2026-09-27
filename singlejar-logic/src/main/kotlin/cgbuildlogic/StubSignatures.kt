package cgbuildlogic

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.InnerClassNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.MethodNode

/**
 * The text a class's signature is stored as in the stub database ([StubDatabase]), and the class file a
 * stub build compiles against, synthesized from it.
 *
 * ```kotlin
 * val text = StringBuilder().also { StubSignatures.format(classNode, it) }       // one block
 * val classes = StubSignatures.parse(blockLines)                                // back again
 * jar.put(classes[0].name + ".class", StubSignatures.classBytes(classes[0]))
 * ```
 *
 * One block per class; `-` is an absent value and lists are comma-separated:
 *
 * ```
 * class net/minecraft/client/gui/screens/Screen 61 public,super,abstract net/minecraft/client/gui/components/events/AbstractContainerEventHandler net/minecraft/client/gui/components/Renderable -
 *  inner net/minecraft/client/gui/screens/Screen$NarratableSearchResult net/minecraft/client/gui/screens/Screen NarratableSearchResult public,static
 *  field protected title Lnet/minecraft/network/chat/Component; -
 *  field public,static,final WIDTH I - I320
 *  method protected <init> (Lnet/minecraft/network/chat/Component;)V - -
 *  anno visible @Ljava/lang/annotation/Retention;(value=Ejava/lang/annotation/RetentionPolicy;#RUNTIME)
 *  method public,abstract value ()Ljava/lang/String; - - "default"
 * ```
 *
 * A trailing value is a constant (`I`, `J`, `F`, `D`, `"..."`) on a field, and an annotation default on a
 * method, which may also be `Z B C S`, `T<desc>` (a class), `E<desc>#<name>` (an enum), `[..]` or
 * `@<desc>(name=..)`.
 *
 * - Bodies are `aconst_null; athrow`: nothing runs a stub, and javac reads no Code attribute.
 * - Only what javac reads is carried: no record components, nest members, source files or annotations
 *   other than an annotation type's own meta-annotations.
 */
object StubSignatures {

    /** One class's block. */
    fun format(c: ClassNode, text: StringBuilder) {
        text.append("class ").append(c.name).append(' ').append(c.version).append(' ')
            .append(Access.CLASS.format(c.access)).append(' ').append(c.superName ?: "-").append(' ')
            .append(list(c.interfaces)).append(' ').append(c.signature ?: "-").append('\n')
        if (c.outerClass != null) {
            text.append(" outer ").append(c.outerClass).append(' ').append(c.outerMethod ?: "-").append(' ')
                .append(c.outerMethodDesc ?: "-").append('\n')
        }
        for (i in c.innerClasses) {
            text.append(" inner ").append(i.name).append(' ').append(i.outerName ?: "-").append(' ')
                .append(i.innerName ?: "-").append(' ').append(Access.INNER.format(i.access)).append('\n')
        }
        c.visibleAnnotations.orEmpty().forEach { text.append(" anno visible ").append(Values.write(it)).append('\n') }
        c.invisibleAnnotations.orEmpty().forEach { text.append(" anno invisible ").append(Values.write(it)).append('\n') }
        for (f in c.fields) {
            text.append(" field ").append(Access.FIELD.format(f.access)).append(' ').append(f.name).append(' ')
                .append(f.desc).append(' ').append(f.signature ?: "-")
            f.value?.let { text.append(' ').append(Values.write(it)) }
            text.append('\n')
        }
        for (m in c.methods) {
            text.append(" method ").append(Access.METHOD.format(m.access)).append(' ').append(m.name).append(' ')
                .append(m.desc).append(' ').append(m.signature ?: "-").append(' ').append(list(m.exceptions))
            m.annotationDefault?.let { text.append(' ').append(Values.write(it)) }
            text.append('\n')
        }
    }

    /** The classes in [lines] — blocks as [format] writes them. */
    fun parse(lines: List<String>): List<ClassNode> {
        val classes = mutableListOf<ClassNode>()
        var current: ClassNode? = null
        for ((index, line) in lines.withIndex()) {
            if (line.isBlank()) continue
            val tokens = Tokens(line)
            fun owner() = current ?: throw IllegalArgumentException("line ${index + 1}: a member before any class")
            try {
                when (tokens.next()) {
                    "class" -> current = ClassNode().apply {
                        name = tokens.next(); version = tokens.next().toInt(); access = Access.CLASS.parse(tokens.next())
                        superName = tokens.optional(); interfaces = tokens.list(); signature = tokens.optional()
                        classes += this
                    }
                    "outer" -> owner().apply { outerClass = tokens.next(); outerMethod = tokens.optional(); outerMethodDesc = tokens.optional() }
                    "inner" -> owner().innerClasses.add(InnerClassNode(tokens.next(), tokens.optional(), tokens.optional(), Access.INNER.parse(tokens.next())))
                    "anno" -> {
                        val visible = tokens.next() == "visible"
                        val annotation = Values.read(tokens.rest()) as AnnotationNode
                        owner().apply {
                            if (visible) visibleAnnotations = (visibleAnnotations ?: mutableListOf()).also { it += annotation }
                            else invisibleAnnotations = (invisibleAnnotations ?: mutableListOf()).also { it += annotation }
                        }
                    }
                    "field" -> {
                        val access = Access.FIELD.parse(tokens.next())
                        val name = tokens.next(); val desc = tokens.next(); val signature = tokens.optional()
                        val value = tokens.rest().takeIf { it.isNotEmpty() }?.let(Values::read)
                        owner().fields.add(FieldNode(access, name, desc, signature, value))
                    }
                    "method" -> {
                        val access = Access.METHOD.parse(tokens.next())
                        val name = tokens.next(); val desc = tokens.next(); val signature = tokens.optional()
                        val exceptions = tokens.list()
                        val method = MethodNode(access, name, desc, signature, exceptions.toTypedArray())
                        tokens.rest().takeIf { it.isNotEmpty() }?.let { method.annotationDefault = Values.read(it) }
                        owner().methods.add(method)
                    }
                    else -> throw IllegalArgumentException("unknown line")
                }
            } catch (e: RuntimeException) {
                throw IllegalArgumentException("line ${index + 1}: ${e.message}: $line", e)
            }
        }
        return classes
    }

    /** [node] as a class file whose every body throws. */
    fun classBytes(node: ClassNode): ByteArray {
        for (m in node.methods) {
            m.instructions = InsnList()
            if (m.access and (Opcodes.ACC_ABSTRACT or Opcodes.ACC_NATIVE) == 0) {
                m.instructions.add(InsnNode(Opcodes.ACONST_NULL))
                m.instructions.add(InsnNode(Opcodes.ATHROW))
            }
        }
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        node.accept(writer)
        return writer.toByteArray()
    }

    private fun list(values: List<String>?): String = if (values.isNullOrEmpty()) "-" else values.joinToString(",")

    /** Space-separated tokens, then whatever is left of the line (a value may hold spaces). */
    private class Tokens(private val line: String) {
        private var at = 0

        fun next(): String {
            while (at < line.length && line[at] == ' ') at++
            val start = at
            while (at < line.length && line[at] != ' ') at++
            require(at > start) { "the line ends early" }
            return line.substring(start, at)
        }

        fun optional(): String? = next().takeIf { it != "-" }
        fun list(): List<String> = optional()?.split(',') ?: emptyList()
        fun rest(): String = line.substring(at).trim()
    }

    /** Access flags as keywords, per kind: the same bit means `super` on a class and `synchronized` on a method. */
    private class Access(vararg flags: Pair<String, Int>) {
        private val flags = flags.toList()

        fun format(access: Int): String {
            var rest = access
            val words = flags.filter { (_, bit) -> access and bit != 0 }.map { (word, bit) -> rest = rest and bit.inv(); word }
            val all = if (rest != 0) words + "0x" + Integer.toHexString(rest) else words
            return if (all.isEmpty()) "-" else all.joinToString(",")
        }

        fun parse(text: String): Int = if (text == "-") 0 else text.split(',').sumOf { word ->
            if (word.startsWith("0x")) word.substring(2).toInt(16)
            else flags.firstOrNull { it.first == word }?.second ?: throw IllegalArgumentException("unknown access flag '$word'")
        }

        companion object {
            private val COMMON = arrayOf("public" to Opcodes.ACC_PUBLIC, "private" to Opcodes.ACC_PRIVATE,
                "protected" to Opcodes.ACC_PROTECTED, "static" to Opcodes.ACC_STATIC, "final" to Opcodes.ACC_FINAL)
            private val TAIL = arrayOf("synthetic" to Opcodes.ACC_SYNTHETIC, "deprecated" to Opcodes.ACC_DEPRECATED)
            val CLASS = Access(*COMMON, "super" to Opcodes.ACC_SUPER, "interface" to Opcodes.ACC_INTERFACE,
                "abstract" to Opcodes.ACC_ABSTRACT, "annotation" to Opcodes.ACC_ANNOTATION, "enum" to Opcodes.ACC_ENUM, *TAIL)
            val INNER = Access(*COMMON, "interface" to Opcodes.ACC_INTERFACE, "abstract" to Opcodes.ACC_ABSTRACT,
                "annotation" to Opcodes.ACC_ANNOTATION, "enum" to Opcodes.ACC_ENUM, *TAIL)
            val FIELD = Access(*COMMON, "volatile" to Opcodes.ACC_VOLATILE, "transient" to Opcodes.ACC_TRANSIENT,
                "enum" to Opcodes.ACC_ENUM, *TAIL)
            val METHOD = Access(*COMMON, "synchronized" to Opcodes.ACC_SYNCHRONIZED, "bridge" to Opcodes.ACC_BRIDGE,
                "varargs" to Opcodes.ACC_VARARGS, "native" to Opcodes.ACC_NATIVE, "abstract" to Opcodes.ACC_ABSTRACT,
                "strict" to Opcodes.ACC_STRICT, *TAIL)
        }
    }

    /** Constants and annotation values in ASM's own shapes, as one-line text. */
    private object Values {

        fun write(value: Any): String = StringBuilder().also { write(value, it) }.toString()

        private fun write(value: Any, out: StringBuilder) {
            when (value) {
                is Boolean -> out.append('Z').append(if (value) 1 else 0)
                is Byte -> out.append('B').append(value.toInt())
                is Char -> out.append('C').append(value.code)
                is Short -> out.append('S').append(value.toInt())
                is Int -> out.append('I').append(value)
                is Long -> out.append('J').append(value)
                is Float -> out.append('F').append(exact(value.toString(), value) { it.toFloat() })
                is Double -> out.append('D').append(exact(value.toString(), value) { it.toDouble() })
                is String -> quote(value, out)
                is Type -> out.append('T').append(value.descriptor)
                is Array<*> -> out.append('E').append(value[0]).append('#').append(value[1])
                is AnnotationNode -> {
                    out.append('@').append(value.desc).append('(')
                    value.values.orEmpty().chunked(2).forEachIndexed { i, (name, v) ->
                        if (i > 0) out.append(',')
                        out.append(name).append('=')
                        write(v!!, out)
                    }
                    out.append(')')
                }
                is List<*> -> {
                    out.append('[')
                    value.forEachIndexed { i, v -> if (i > 0) out.append(','); write(v!!, out) }
                    out.append(']')
                }
                else -> throw IllegalArgumentException("no text form for a ${value.javaClass.name}")
            }
        }

        /** Java's shortest decimal round-trips; asserted rather than assumed, since a wrong constant is silent. */
        private fun <T> exact(text: String, value: T, parse: (String) -> T): String {
            check(parse(text) == value || text == "NaN") { "$value does not survive as '$text'" }
            return text
        }

        private fun quote(s: String, out: StringBuilder) {
            out.append('"')
            for (ch in s) when {
                ch == '"' || ch == '\\' -> out.append('\\').append(ch)
                ch.code < 0x20 || ch.code > 0x7e -> out.append("\\u").append(String.format("%04x", ch.code))
                else -> out.append(ch)
            }
            out.append('"')
        }

        fun read(text: String): Any {
            val parser = Parser(text)
            val value = parser.value()
            require(parser.at == text.length) { "trailing text after a value" }
            return value
        }

        private class Parser(val s: String) {
            var at = 0

            fun value(): Any = when (val kind = s[at++]) {
                'Z' -> token() == "1"
                'B' -> token().toByte()
                'C' -> token().toInt().toChar()
                'S' -> token().toShort()
                'I' -> token().toInt()
                'J' -> token().toLong()
                'F' -> token().toFloat()
                'D' -> token().toDouble()
                'T' -> Type.getType(token())
                'E' -> token().split('#', limit = 2).let { (desc, name) -> arrayOf(desc, name) }
                '"' -> string()
                '[' -> mutableListOf<Any>().also { list ->
                    while (s[at] != ']') { list += value(); if (s[at] == ',') at++ }
                    at++
                }
                '@' -> {
                    val open = s.indexOf('(', at)
                    AnnotationNode(s.substring(at, open)).also { node ->
                        at = open + 1
                        while (s[at] != ')') {
                            val eq = s.indexOf('=', at)
                            val name = s.substring(at, eq)
                            at = eq + 1
                            node.visit(name, value())
                            if (s[at] == ',') at++
                        }
                        at++
                    }
                }
                else -> throw IllegalArgumentException("no value starts with '$kind'")
            }

            /** Up to the next delimiter of an enclosing list or annotation. */
            private fun token(): String {
                val start = at
                while (at < s.length && s[at] != ',' && s[at] != ')' && s[at] != ']') at++
                return s.substring(start, at)
            }

            private fun string(): String {
                val out = StringBuilder()
                while (true) {
                    when (val ch = s[at++]) {
                        '"' -> return out.toString()
                        '\\' -> {
                            val esc = s[at++]
                            if (esc == 'u') { out.append(s.substring(at, at + 4).toInt(16).toChar()); at += 4 }
                            else out.append(esc)
                        }
                        else -> out.append(ch)
                    }
                }
            }
        }
    }
}
