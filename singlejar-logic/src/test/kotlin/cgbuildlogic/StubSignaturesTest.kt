package cgbuildlogic

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.InnerClassNode
import org.objectweb.asm.tree.MethodNode

class StubSignaturesTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `text survives a read and a write unchanged`() {
        val first = temp.newFile("a.sig")
        StubSignatures.write(listOf(annotationType(), holder()), listOf("Fabric-Jar-Type" to "classes"), first)
        val stub = StubSignatures.read(first)
        val second = temp.newFile("b.sig")
        StubSignatures.write(stub.classes, stub.manifest, second)

        assertEquals(first.readText(), second.readText())
        assertEquals(listOf("Fabric-Jar-Type" to "classes"), StubSignatures.manifest(first))
    }

    @Test
    fun `synthesized classes carry what javac reads`() {
        val file = temp.newFile("c.sig")
        StubSignatures.write(listOf(annotationType(), holder()), emptyList(), file)
        val read = StubSignatures.read(file).classes.associateBy { it.name }

        val holder = ClassNode().also { ClassReader(StubSignatures.classBytes(read.getValue("p/Holder"))).accept(it, 0) }
        assertEquals(listOf("p/Holder\$Inner"), holder.innerClasses.map { it.name })
        assertEquals(listOf(42, "tab\there \"q\" é", 1.5f, Long.MIN_VALUE), holder.fields.map { it.value })
        assertEquals(listOf("java/io/IOException"), holder.methods.single { it.name == "run" }.exceptions)

        val annotation = ClassNode().also { ClassReader(StubSignatures.classBytes(read.getValue("p/Anno"))).accept(it, 0) }
        assertEquals("Ljava/lang/annotation/Retention;", annotation.visibleAnnotations.single().desc)
        val default = annotation.methods.single().annotationDefault as List<*>
        assertEquals(Type.getType("Lp/Holder;"), default[0])
        assertEquals((default[1] as AnnotationNode).values, listOf("value", listOf("x,y]", "")))
    }

    private fun annotationType() = ClassNode().apply {
        version = Opcodes.V17; name = "p/Anno"; superName = "java/lang/Object"
        access = Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT or Opcodes.ACC_ANNOTATION
        interfaces = listOf("java/lang/annotation/Annotation")
        visibleAnnotations = listOf(AnnotationNode("Ljava/lang/annotation/Retention;").apply {
            visitEnum("value", "Ljava/lang/annotation/RetentionPolicy;", "RUNTIME")
        })
        methods.add(MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, "value", "()[Ljava/lang/Object;", null, null).apply {
            annotationDefault = listOf(Type.getType("Lp/Holder;"),
                AnnotationNode("Lp/Nested;").apply { visit("value", listOf("x,y]", "")) }, 'c', true, 7.toShort())
        })
    }

    private fun holder() = ClassNode().apply {
        version = Opcodes.V17; name = "p/Holder"; superName = "java/lang/Object"; access = Opcodes.ACC_PUBLIC
        signature = "<T:Ljava/lang/Object;>Ljava/lang/Object;"
        innerClasses.add(InnerClassNode("p/Holder\$Inner", "p/Holder", "Inner", Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC))
        fields.add(FieldNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL, "I", "I", null, 42))
        fields.add(FieldNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL, "S", "Ljava/lang/String;", null, "tab\there \"q\" é"))
        fields.add(FieldNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL, "F", "F", null, 1.5f))
        fields.add(FieldNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL, "J", "J", null, Long.MIN_VALUE))
        methods.add(MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_VARARGS, "run", "([Ljava/lang/String;)Ljava/lang/Object;",
            "([Ljava/lang/String;)TT;", arrayOf("java/io/IOException")))
        methods.add(MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null))
    }
}
