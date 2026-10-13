package dev.vibecraft.core.compile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import dev.vibecraft.core.Diagnostic;

/**
 * In-memory javac: sources in, class bytes out, no files on disk. One standard file manager per
 * compiler thread is reused across compilations (the classpath jars are indexed only once).
 */
public final class ModuleCompiler {

    /** Options from the spec (§4.2). */
    private static final List<String> OPTIONS = List.of("-g", "-parameters", "--release", "25", "-encoding", "UTF-8",
            "-proc:none", "-Xlint:none", "-implicit:none", "-Xmaxerrs", "100", "-Xmaxwarns", "50");

    /**
     * @param classes     internal name ({@code a/b/C}) to bytecode (empty when !ok)
     * @param sourceOf    internal name to module-relative source path
     * @param diagnostics converted diagnostics (errors first in source order)
     */
    public record Result(boolean ok, Map<String, byte[]> classes, Map<String, String> sourceOf,
                         List<Diagnostic> diagnostics, int errors, int warnings, long millis) {
    }

    /** Limits that keep one module from monopolizing the shared compile threads (and the heap). */
    static final int MAX_SOURCE_CHARS = 1_000_000;
    static final int MAX_SOURCE_FILES = 200;
    static final int MAX_CLASSES = 1000;
    static final long MAX_COMPILE_NANOS = 60_000_000_000L;

    /** Thrown from the task listener when a compile runs past its deadline. */
    static final class CompileTimeout extends RuntimeException {
        private static final long serialVersionUID = 1L;

        CompileTimeout() {
            super("compile timeout", null, false, false);
        }
    }

    private final JavaCompiler javac;
    private final List<File> classpath;
    private final ThreadLocal<StandardJavaFileManager> fileManagers;

    public ModuleCompiler(List<File> classpath) {
        this.javac = ToolProvider.getSystemJavaCompiler();
        this.classpath = List.copyOf(classpath);
        this.fileManagers = ThreadLocal.withInitial(this::newFileManager);
    }

    public boolean available() {
        return javac != null;
    }

    private StandardJavaFileManager newFileManager() {
        StandardJavaFileManager fm = javac.getStandardFileManager(null, Locale.ENGLISH, StandardCharsets.UTF_8);
        try {
            fm.setLocation(StandardLocation.CLASS_PATH, classpath);
            fm.setLocation(StandardLocation.SOURCE_PATH, List.of());
        } catch (IOException e) {
            throw new IllegalStateException("cannot set the module classpath: " + e, e);
        }
        return fm;
    }

    /** Compile a tiny class to load javac and index the classpath (call once per compiler thread). */
    public void warmUp() {
        compile(Map.of("Warmup.java", "public class Warmup { org.bukkit.Server s; dev.vibecraft.api.ModuleContext c; "
                + "java.util.List<String> l = java.util.List.of(); }"));
    }

    /**
     * @param sources module-relative path ({@code Foo.java}, {@code util/Bar.java}) to source text
     */
    public Result compile(Map<String, String> sources) {
        long t0 = System.nanoTime();
        if (javac == null) {
            return new Result(false, Map.of(), Map.of(), List.of(new Diagnostic("ERROR", "", -1, -1,
                    "the Java compiler is not available: run the server with a JDK (not a JRE)", "")), 1, 0, 0);
        }
        long chars = 0;
        for (String src : sources.values()) {
            chars += src.length();
        }
        if (chars > MAX_SOURCE_CHARS || sources.size() > MAX_SOURCE_FILES) {
            return new Result(false, Map.of(), Map.of(), List.of(new Diagnostic("ERROR", "", -1, -1,
                    "module sources too large (" + sources.size() + " files, " + chars / 1024 + " KB; max "
                            + MAX_SOURCE_FILES + " files, " + MAX_SOURCE_CHARS / 1024 + " KB): keep modules small", "")),
                    1, 0, 0);
        }
        List<MemSource> units = new ArrayList<>();
        for (Map.Entry<String, String> e : sources.entrySet()) {
            units.add(new MemSource(e.getKey(), e.getValue()));
        }
        long deadline = t0 + MAX_COMPILE_NANOS;
        StandardJavaFileManager std = fileManagers.get();
        MemFileManager fm = new MemFileManager(std);
        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
        StringWriter out = new StringWriter();
        boolean ok;
        try {
            JavaCompiler.CompilationTask task = javac.getTask(out, fm, collector, OPTIONS, null, units);
            if (task instanceof com.sun.source.util.JavacTask jt) {
                // javac cannot be interrupted, but it reports its progress: abort at the next step once too slow
                jt.addTaskListener(new com.sun.source.util.TaskListener() {
                    @Override
                    public void started(com.sun.source.util.TaskEvent e) {
                        if (System.nanoTime() - deadline > 0) {
                            throw new CompileTimeout();
                        }
                    }

                    @Override
                    public void finished(com.sun.source.util.TaskEvent e) {
                        started(e);
                    }
                });
            }
            ok = Boolean.TRUE.equals(task.call());
        } catch (RuntimeException | Error e) {
            // javac crashed (very rare): report and reset this thread's file manager
            fileManagers.remove();
            try {
                std.close();
            } catch (IOException ignored) {
                // best effort
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            boolean timeout = System.nanoTime() - deadline > 0;
            for (Throwable c = e; c != null && !timeout; c = c.getCause()) {
                timeout = c instanceof CompileTimeout;
            }
            return new Result(false, Map.of(), Map.of(), List.of(new Diagnostic("ERROR", "", -1, -1,
                    timeout ? "compilation took longer than " + MAX_COMPILE_NANOS / 1_000_000_000L + " s: module too big or "
                            + "too complex, split or simplify it" : "internal compiler error: " + e, "")), 1, 0, ms);
        } finally {
            try {
                std.flush();
            } catch (IOException ignored) {
                // nothing
            }
        }
        List<Diagnostic> diags = new ArrayList<>();
        int errors = 0;
        int warnings = 0;
        for (javax.tools.Diagnostic<? extends JavaFileObject> d : collector.getDiagnostics()) {
            String kind = switch (d.getKind()) {
                case ERROR -> "ERROR";
                case WARNING, MANDATORY_WARNING -> "WARNING";
                default -> "NOTE";
            };
            if (kind.equals("ERROR")) {
                errors++;
            } else if (kind.equals("WARNING")) {
                warnings++;
            }
            String file = "";
            String line = "";
            if (d.getSource() instanceof MemSource s) {
                file = s.rel;
                line = s.line(d.getLineNumber());
            }
            String msg = d.getMessage(Locale.ENGLISH);
            if (kind.equals("NOTE") && msg != null && (msg.startsWith("Recompile with") || msg.contains("uses or overrides a deprecated API")
                    || msg.contains("uses unchecked or unsafe operations") || msg.contains("deprecated and marked for removal"))) {
                continue; // file-level javac notes without any line: noise for AI coders
            }
            diags.add(new Diagnostic(kind, file, d.getLineNumber() < 0 ? -1 : d.getLineNumber(),
                    d.getColumnNumber() < 0 ? -1 : d.getColumnNumber(), msg == null ? "" : msg, line.trim()));
        }
        String extra = out.toString().trim();
        if (!ok && errors == 0) {
            errors = 1;
            diags.add(new Diagnostic("ERROR", "", -1, -1, extra.isEmpty() ? "compilation failed" : extra, ""));
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        if (!ok) {
            return new Result(false, Map.of(), Map.of(), diags, errors, warnings, ms);
        }
        if (fm.outputs.size() > MAX_CLASSES) {
            return new Result(false, Map.of(), Map.of(), List.of(new Diagnostic("ERROR", "", -1, -1,
                    "too many classes (" + fm.outputs.size() + ", max " + MAX_CLASSES + "): keep modules small", "")),
                    1, warnings, ms);
        }
        Map<String, byte[]> classes = new LinkedHashMap<>();
        for (Map.Entry<String, MemClass> e : fm.outputs.entrySet()) {
            classes.put(e.getKey().replace('.', '/'), e.getValue().bytes());
        }
        return new Result(true, classes, Map.copyOf(fm.sourceOf), diags, errors, warnings, ms);
    }

    // ================================================================== file objects

    static final class MemSource extends SimpleJavaFileObject {
        final String rel;
        final String content;
        private String[] lines;

        MemSource(String rel, String content) {
            super(uri(rel), Kind.SOURCE);
            this.rel = rel;
            this.content = content;
        }

        private static URI uri(String rel) {
            try {
                return new URI("mem", null, "/" + rel.replace('\\', '/'), null);
            } catch (URISyntaxException e) {
                return URI.create("mem:///Invalid.java");
            }
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return content;
        }

        String line(long n) {
            if (n < 1) {
                return "";
            }
            if (lines == null) {
                lines = content.split("\r?\n", -1);
            }
            return n <= lines.length ? lines[(int) n - 1] : "";
        }
    }

    static final class MemClass extends SimpleJavaFileObject {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        MemClass(String className) {
            super(URI.create("mem:///" + className.replace('.', '/') + ".class"), Kind.CLASS);
        }

        @Override
        public OutputStream openOutputStream() {
            return out;
        }

        byte[] bytes() {
            return out.toByteArray();
        }
    }

    static final class MemFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {
        final Map<String, MemClass> outputs = new LinkedHashMap<>();
        final Map<String, String> sourceOf = new LinkedHashMap<>();

        MemFileManager(StandardJavaFileManager fm) {
            super(fm);
        }

        @Override
        public JavaFileObject getJavaFileForOutput(Location location, String className, JavaFileObject.Kind kind,
                                                   FileObject sibling) {
            MemClass c = new MemClass(className);
            outputs.put(className, c);
            if (sibling instanceof MemSource s) {
                sourceOf.put(className.replace('.', '/'), s.rel);
            }
            return c;
        }

        @Override
        public boolean isSameFile(FileObject a, FileObject b) {
            return a == b || a.toUri().equals(b.toUri());
        }

        @Override
        public void close() {
            // the underlying standard file manager is shared by this thread: never close it here
        }
    }
}
