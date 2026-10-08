package dev.vibecraft.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Files written by the host into {@code modules/<id>/.vibe/} for AI agents: status.json,
 * build.log, errors.log (rotated at ~200 KB), and the {@code disabled} marker.
 */
final class StatusFiles {

    private StatusFiles() {
    }

    static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeNulls().disableHtmlEscaping().create();
    static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final long ERRORS_LOG_MAX = 200 * 1024;

    static File vibeDir(File moduleDir) {
        return new File(moduleDir, ".vibe");
    }

    static File marker(File moduleDir) {
        return new File(vibeDir(moduleDir), "disabled");
    }

    /** Create .vibe/ only if the module directory still exists (never resurrect a deleted module). */
    private static Path ensureVibeDir(File moduleDir) throws IOException {
        if (!moduleDir.isDirectory()) {
            return null;
        }
        Path p = vibeDir(moduleDir).toPath();
        Files.createDirectories(p);
        return p;
    }

    static void writeAtomic(Path target, String text) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // Windows: target briefly locked by a reader -> plain overwrite
            Files.writeString(target, text, StandardCharsets.UTF_8);
            Files.deleteIfExists(tmp);
        }
    }

    static void writeStatus(File moduleDir, ModuleInfo info) throws IOException {
        Path d = ensureVibeDir(moduleDir);
        if (d != null) {
            writeAtomic(d.resolve("status.json"), GSON.toJson(info) + "\n");
        }
    }

    static void writeBuildLog(File moduleDir, String text) throws IOException {
        Path d = ensureVibeDir(moduleDir);
        if (d != null) {
            writeAtomic(d.resolve("build.log"), text);
        }
    }

    static void appendError(File moduleDir, String text) throws IOException {
        Path d = ensureVibeDir(moduleDir);
        if (d == null) {
            return;
        }
        Path log = d.resolve("errors.log");
        if (Files.exists(log) && Files.size(log) > ERRORS_LOG_MAX) {
            Files.move(log, d.resolve("errors.log.1"), StandardCopyOption.REPLACE_EXISTING);
        }
        Files.writeString(log, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    // ================================================================== build.log rendering

    /**
     * Human-readable diagnostics, javac style: {@code File.java:LINE:COL: error: message}, the
     * source line and a caret.
     */
    static String renderBuildLog(String id, String headline, List<Diagnostic> diags, Map<String, String> sources,
                                 long compileMs) {
        StringBuilder sb = new StringBuilder();
        sb.append("VibeCraft build log - module '").append(id).append("' - ")
                .append(LocalDateTime.now().format(STAMP)).append('\n');
        sb.append("Result: ").append(headline).append('\n');
        if (compileMs >= 0) {
            sb.append("Compile time: ").append(compileMs).append(" ms\n");
        }
        sb.append('\n');
        int errors = 0;
        int warnings = 0;
        for (Diagnostic d : diags) {
            String kind = switch (d.kind()) {
                case "ERROR" -> "error";
                case "WARNING" -> "warning";
                default -> "note";
            };
            if (kind.equals("error")) {
                errors++;
            } else if (kind.equals("warning")) {
                warnings++;
            }
            String[] msg = d.message().split("\r?\n", -1);
            StringBuilder loc = new StringBuilder();
            if (!d.file().isEmpty()) {
                loc.append(d.file());
                if (d.line() > 0) {
                    loc.append(':').append(d.line());
                    if (d.column() > 0) {
                        loc.append(':').append(d.column());
                    }
                }
                loc.append(": ");
            }
            sb.append(loc).append(kind).append(": ").append(msg[0]).append('\n');
            String raw = rawLine(sources, d.file(), d.line());
            if (raw != null) {
                String expanded = expandTabs(raw);
                sb.append(expanded).append('\n');
                if (d.column() > 0) {
                    sb.append(" ".repeat((int) Math.min(d.column() - 1, 500))).append("^\n");
                }
            } else if (!d.sourceLine().isEmpty()) {
                sb.append("    ").append(d.sourceLine()).append('\n');
            }
            for (int i = 1; i < msg.length; i++) {
                sb.append(msg[i]).append('\n');
            }
        }
        if (!diags.isEmpty()) {
            sb.append('\n');
        }
        sb.append(errors).append(" error(s), ").append(warnings).append(" warning(s)\n");
        return sb.toString();
    }

    private static String rawLine(Map<String, String> sources, String file, long line) {
        if (sources == null || file == null || file.isEmpty() || line < 1) {
            return null;
        }
        String src = sources.get(file);
        if (src == null) {
            return null;
        }
        String[] lines = src.split("\r?\n", -1);
        return line <= lines.length ? lines[(int) line - 1] : null;
    }

    /** javac reports columns with tabs expanded to multiples of 8. */
    private static String expandTabs(String s) {
        if (s.indexOf('\t') < 0) {
            return s;
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\t') {
                do {
                    b.append(' ');
                } while (b.length() % 8 != 0);
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }
}
