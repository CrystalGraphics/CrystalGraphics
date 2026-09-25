package cgbuildlogic;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Plugin;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.ModuleElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Set;
import java.util.TreeSet;

/**
 * Records every symbol a compile resolved on its classpath, for {@link StubClosure} to keep.
 *
 * <pre>{@code
 * javac -processorpath <singlejar-logic> -Xplugin:"CgSigRecorder build/stubs/records/compileJava.txt" ...
 * }</pre>
 *
 * One line per symbol, sorted, written when the compile finishes:
 *
 * <pre>
 * C net/minecraft/client/Minecraft                         a class named
 * F net/minecraft/client/Minecraft options                 a field read or written
 * M net/minecraft/client/Minecraft getInstance ()L...;     a method or constructor called
 * X com/example/MyScreen net/minecraft/client/gui/screens/Screen   our class extends or implements
 * O com/example/MyScreen render (L...;IIF)V                a method our class declares (an override candidate)
 * </pre>
 *
 * - Read from the attributed trees, never from bytecode: javac inlines constants, so a referenced
 *   constant is in no class file.
 * - JDK classes are not recorded; a class is the JDK's when it sits in a named module.
 * - Runs inside javac, so it is compiled at the lowest release a node compiles with (17).
 */
public final class SigRecorder implements Plugin {

    @Override
    public String getName() {
        return "CgSigRecorder";
    }

    @Override
    public void init(JavacTask task, String... args) {
        String out = args[0];
        Trees trees = Trees.instance(task);
        Elements elements = task.getElements();
        Types types = task.getTypes();
        Set<String> lines = new TreeSet<>();
        task.addTaskListener(new TaskListener() {
            @Override
            public void finished(TaskEvent e) {
                if (e.getKind() == TaskEvent.Kind.ANALYZE) {
                    new Scanner(trees, elements, types, lines).scan(e.getCompilationUnit(), null);
                } else if (e.getKind() == TaskEvent.Kind.COMPILATION) {
                    try {
                        Files.write(Paths.get(out), lines, StandardCharsets.UTF_8);
                    } catch (IOException x) {
                        throw new UncheckedIOException(x);
                    }
                }
            }
        });
    }

    private static final class Scanner extends TreePathScanner<Void, Void> {
        private final Trees trees;
        private final Elements elements;
        private final Types types;
        private final Set<String> lines;

        Scanner(Trees trees, Elements elements, Types types, Set<String> lines) {
            this.trees = trees;
            this.elements = elements;
            this.types = types;
            this.lines = lines;
        }

        @Override
        public Void scan(Tree tree, Void v) {
            if (tree != null && getCurrentPath() != null) record(new TreePath(getCurrentPath(), tree));
            return super.scan(tree, v);
        }

        @Override
        public Void visitClass(ClassTree node, Void v) {
            if (trees.getElement(getCurrentPath()) instanceof TypeElement type) {
                String self = binaryName(type);
                if (type.getSuperclass().getKind() == TypeKind.DECLARED) {
                    lines.add("X " + self + " " + binaryName((TypeElement) types.asElement(type.getSuperclass())));
                }
                for (TypeMirror i : type.getInterfaces()) {
                    lines.add("X " + self + " " + binaryName((TypeElement) types.asElement(i)));
                }
                for (Element member : type.getEnclosedElements()) {
                    if (member instanceof ExecutableElement method && member.getKind() == ElementKind.METHOD) {
                        lines.add("O " + self + " " + method.getSimpleName() + " " + descriptor(method));
                    }
                }
            }
            return super.visitClass(node, v);
        }

        private void record(TreePath path) {
            Element e;
            try {
                e = trees.getElement(path);
            } catch (RuntimeException unresolvable) {
                return;
            }
            if (e instanceof TypeElement type) {
                if (onClasspath(type)) lines.add("C " + binaryName(type));
            } else if (e instanceof ExecutableElement method && method.getEnclosingElement() instanceof TypeElement owner) {
                if (onClasspath(owner)) {
                    String name = method.getKind() == ElementKind.CONSTRUCTOR ? "<init>" : method.getSimpleName().toString();
                    lines.add("M " + binaryName(owner) + " " + name + " " + descriptor(method));
                }
            } else if (e instanceof VariableElement field && field.getEnclosingElement() instanceof TypeElement owner
                    && (field.getKind() == ElementKind.FIELD || field.getKind() == ElementKind.ENUM_CONSTANT)) {
                if (onClasspath(owner)) lines.add("F " + binaryName(owner) + " " + field.getSimpleName());
            }
        }

        /** Not among the sources being compiled, and not the JDK's. */
        private boolean onClasspath(TypeElement type) {
            TypeElement top = type;
            while (top.getEnclosingElement() instanceof TypeElement outer) top = outer;
            if (trees.getTree(top) != null) return false;
            ModuleElement module = elements.getModuleOf(top);
            return module == null || module.isUnnamed();
        }

        private String binaryName(TypeElement type) {
            return elements.getBinaryName(type).toString().replace('.', '/');
        }

        /** Erased; an inner class constructor's outer instance is javac's to add and is not in it. */
        private String descriptor(ExecutableElement method) {
            ExecutableType type = (ExecutableType) types.erasure(method.asType());
            StringBuilder sb = new StringBuilder("(");
            for (TypeMirror p : type.getParameterTypes()) sb.append(descriptor(p));
            return sb.append(')').append(descriptor(type.getReturnType())).toString();
        }

        private String descriptor(TypeMirror t) {
            t = types.erasure(t);
            return switch (t.getKind()) {
                case BOOLEAN -> "Z";
                case BYTE -> "B";
                case CHAR -> "C";
                case SHORT -> "S";
                case INT -> "I";
                case LONG -> "J";
                case FLOAT -> "F";
                case DOUBLE -> "D";
                case VOID -> "V";
                case ARRAY -> "[" + descriptor(((ArrayType) t).getComponentType());
                case DECLARED -> "L" + binaryName((TypeElement) ((DeclaredType) t).asElement()) + ";";
                default -> "Ljava/lang/Object;";
            };
        }
    }
}
