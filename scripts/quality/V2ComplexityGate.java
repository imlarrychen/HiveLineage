import com.sun.source.tree.*;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.TreeScanner;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import javax.tools.*;

/** Offline, syntax-aware cyclomatic baseline. No external analyzer dependency. */
public final class V2ComplexityGate {
    private static final int NEW_METHOD_LIMIT = 20;

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]).toAbsolutePath().normalize();
        Path baseline = Paths.get(args[1]);
        boolean write = args.length > 2 && args[2].equals("--write-baseline");
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("A JDK is required");
        Map<String, Integer> counts = new TreeMap<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8);
             Stream<Path> sourcePaths = Files.walk(root)) {
            List<File> sources = sourcePaths.filter(p -> p.toString().endsWith(".java")).map(Path::toFile).collect(Collectors.toList());
            JavacTask task = (JavacTask) compiler.getTask(null, files, null, Arrays.asList("-proc:none"), null, files.getJavaFileObjectsFromFiles(sources));
            for (CompilationUnitTree unit : task.parse()) {
                String path = root.relativize(Paths.get(unit.getSourceFile().toUri())).toString().replace(File.separatorChar, '/');
                new TreePathScanner<Void, Void>() {
                    @Override public Void visitMethod(MethodTree method, Void unused) {
                        if (method.getBody() != null) {
                            Counter counter = new Counter();
                            counter.scan(method.getBody(), null);
                            String owner = owner(getCurrentPath().getParentPath());
                            String parameters = method.getParameters().stream().map(p -> p.getType().toString()).collect(Collectors.joining(","));
                            counts.put(path + "#" + owner + "." + method.getName() + "(" + parameters + ")", counter.value);
                        }
                        return super.visitMethod(method, unused);
                    }
                }.scan(unit, null);
            }
        }
        if (write) {
            List<String> lines = new ArrayList<>();
            lines.add("# AST cyclomatic count: 1 + if/loops/catch/case/ternary/&&/||; new method limit=" + NEW_METHOD_LIMIT);
            counts.forEach((key, count) -> { if (count > NEW_METHOD_LIMIT) lines.add(key + "\t" + count); });
            Files.write(baseline, lines, StandardCharsets.UTF_8);
            System.out.println("Wrote " + (lines.size() - 1) + " existing complexity allowances");
            return;
        }
        Map<String, Integer> allowed = new HashMap<>();
        for (String line : Files.readAllLines(baseline, StandardCharsets.UTF_8)) {
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.split("\t");
            allowed.put(parts[0], Integer.parseInt(parts[1]));
        }
        List<String> violations = new ArrayList<>();
        counts.forEach((key, count) -> {
            int limit = allowed.getOrDefault(key, NEW_METHOD_LIMIT);
            if (count > limit) violations.add(key + ": " + count + " > " + limit);
        });
        if (!violations.isEmpty()) throw new IllegalStateException(String.join("\n", violations));
        System.out.println("Complexity gate passed: " + counts.size() + " methods; existing hotspots cannot grow, new methods <= " + NEW_METHOD_LIMIT);
    }

    private static String owner(com.sun.source.util.TreePath path) {
        List<String> names = new ArrayList<>();
        for (; path != null; path = path.getParentPath()) {
            if (path.getLeaf() instanceof ClassTree) names.add(((ClassTree) path.getLeaf()).getSimpleName().toString());
        }
        Collections.reverse(names);
        return String.join(".", names);
    }

    private static final class Counter extends TreeScanner<Void, Void> {
        int value = 1;
        @Override public Void visitClass(ClassTree tree, Void unused) { return null; }
        @Override public Void visitIf(IfTree tree, Void unused) { value++; return super.visitIf(tree, unused); }
        @Override public Void visitForLoop(ForLoopTree tree, Void unused) { value++; return super.visitForLoop(tree, unused); }
        @Override public Void visitEnhancedForLoop(EnhancedForLoopTree tree, Void unused) { value++; return super.visitEnhancedForLoop(tree, unused); }
        @Override public Void visitWhileLoop(WhileLoopTree tree, Void unused) { value++; return super.visitWhileLoop(tree, unused); }
        @Override public Void visitDoWhileLoop(DoWhileLoopTree tree, Void unused) { value++; return super.visitDoWhileLoop(tree, unused); }
        @Override public Void visitCatch(CatchTree tree, Void unused) { value++; return super.visitCatch(tree, unused); }
        @SuppressWarnings("deprecation")
        @Override public Void visitCase(CaseTree tree, Void unused) { if (tree.getExpression() != null) value++; return super.visitCase(tree, unused); }
        @Override public Void visitConditionalExpression(ConditionalExpressionTree tree, Void unused) { value++; return super.visitConditionalExpression(tree, unused); }
        @Override public Void visitBinary(BinaryTree tree, Void unused) {
            if (tree.getKind() == Tree.Kind.CONDITIONAL_AND || tree.getKind() == Tree.Kind.CONDITIONAL_OR) value++;
            return super.visitBinary(tree, unused);
        }
    }
}
