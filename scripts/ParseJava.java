import com.sun.source.util.JavacTask;
import java.nio.file.*;
import java.util.*;
import javax.tools.*;

/** Syntax-only parser. Does not replace a Java 21 build. */
public class ParseJava {
  public static void main(String[] args) throws Exception {
    Path root = args.length == 0 ? Path.of(".") : Path.of(args[0]);
    List<String> files;
    try (var paths = Files.walk(root)) {
      files =
          paths
              .filter(p -> p.toString().endsWith(".java"))
              .filter(p -> !p.toString().contains("/target/"))
              .map(Path::toString)
              .toList();
    }
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) throw new IllegalStateException("JDK compiler module required");
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    try (var manager = compiler.getStandardFileManager(diagnostics, null, null)) {
      JavacTask task =
          (JavacTask)
              compiler.getTask(
                  null,
                  manager,
                  diagnostics,
                  List.of("-proc:none"),
                  null,
                  manager.getJavaFileObjectsFromStrings(files));
      int parsed = 0;
      for (var ignored : task.parse()) parsed++;
      long errors =
          diagnostics.getDiagnostics().stream()
              .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
              .count();
      diagnostics.getDiagnostics().forEach(System.out::println);
      System.out.println("Parsed " + parsed + " Java files; syntax errors: " + errors);
      System.out.println(
          "No dependency resolution, type checking, Java 21 build or runtime tests were"
              + " performed.");
      if (errors != 0) System.exit(1);
    }
  }
}
