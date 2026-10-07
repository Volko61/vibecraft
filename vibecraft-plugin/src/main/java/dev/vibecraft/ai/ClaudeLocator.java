package dev.vibecraft.ai;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Finds the Claude Code CLI binary. Pure JDK (no Bukkit), testable.
 *
 * <p>Search order for {@code claude-path: auto}:
 * <ol>
 *   <li>{@code PATH}: {@code claude.exe} / {@code claude} (then {@code claude.cmd} on Windows, last resort);</li>
 *   <li>native installer locations: {@code ~/.local/bin/claude(.exe)}, {@code ~/.claude/local/claude(.exe)};</li>
 *   <li>the CLI bundled with the Claude desktop app, newest version first:
 *       {@code %APPDATA%/Claude/claude-code/<version>/<hash>/claude.exe} and, for the Microsoft Store
 *       (MSIX) build whose AppData is virtualised, {@code %LOCALAPPDATA%/Packages/Claude_*}
 *       {@code /LocalCache/Roaming/Claude/claude-code/<version>/<hash>/claude.exe};
 *       macOS: {@code ~/Library/Application Support/Claude/claude-code/<version>/<hash>/claude}.</li>
 * </ol>
 */
public final class ClaudeLocator {

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private final Map<String, String> env;

    public ClaudeLocator() {
        this(System.getenv());
    }

    public ClaudeLocator(Map<String, String> env) {
        this.env = env;
    }

    /** Result of a search: the binary (if found) and a human-readable list of what was searched. */
    public record Located(Optional<File> binary, String searched) {
    }

    /**
     * @param configured value of {@code ai.claude-path} ("auto", blank or an explicit path)
     */
    public Located locate(String configured) {
        if (configured != null && !configured.isBlank() && !configured.trim().equalsIgnoreCase("auto")) {
            File f = new File(configured.trim());
            return new Located(f.isFile() ? Optional.of(f.getAbsoluteFile()) : Optional.empty(), "ai.claude-path=" + configured);
        }
        List<String> searched = new ArrayList<>();

        // 1. PATH
        String path = env.getOrDefault("PATH", env.getOrDefault("Path", ""));
        List<String> names = WINDOWS ? List.of("claude.exe") : List.of("claude");
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            for (String n : names) {
                File f = new File(dir.trim(), n);
                if (f.isFile()) {
                    return new Located(Optional.of(f.getAbsoluteFile()), "PATH");
                }
            }
        }
        searched.add("PATH");

        // 2. native installer
        String home = env.getOrDefault("USERPROFILE", env.getOrDefault("HOME", System.getProperty("user.home", "")));
        String exe = WINDOWS ? "claude.exe" : "claude";
        for (File f : List.of(new File(home, ".local/bin/" + exe), new File(home, ".claude/local/" + exe))) {
            if (f.isFile()) {
                return new Located(Optional.of(f.getAbsoluteFile()), "native install");
            }
        }
        searched.add("~/.local/bin, ~/.claude/local");

        // 3. Claude desktop bundled CLI (newest version across all candidate roots)
        List<File> roots = new ArrayList<>();
        String appData = env.get("APPDATA");
        if (appData != null && !appData.isBlank()) {
            roots.add(new File(appData, "Claude/claude-code"));
        }
        String localAppData = env.get("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            File[] pkgs = new File(localAppData, "Packages").listFiles(
                    f -> f.isDirectory() && f.getName().startsWith("Claude_"));
            if (pkgs != null) {
                for (File p : pkgs) {
                    roots.add(new File(p, "LocalCache/Roaming/Claude/claude-code"));
                }
            }
        }
        roots.add(new File(home, "Library/Application Support/Claude/claude-code"));
        Optional<File> bundled = newestBundled(roots, exe);
        if (bundled.isPresent()) {
            return new Located(bundled, "Claude desktop");
        }
        for (File r : roots) {
            searched.add(r.getPath());
        }

        // 4. npm shim on Windows (needs cmd.exe to run)
        if (WINDOWS) {
            for (String dir : path.split(File.pathSeparator)) {
                if (dir.isBlank()) {
                    continue;
                }
                File f = new File(dir.trim(), "claude.cmd");
                if (f.isFile()) {
                    return new Located(Optional.of(f.getAbsoluteFile()), "PATH (npm)");
                }
            }
        }
        return new Located(Optional.empty(), String.join("; ", searched));
    }

    /** The command prefix used to run a binary (a .cmd/.bat shim needs cmd.exe; a .js/.mjs file needs node). */
    public static List<String> commandFor(File binary) {
        String n = binary.getName().toLowerCase(Locale.ROOT);
        if (WINDOWS && (n.endsWith(".cmd") || n.endsWith(".bat"))) {
            return List.of("cmd.exe", "/d", "/c", binary.getPath());
        }
        if (n.endsWith(".js") || n.endsWith(".mjs")) {
            return List.of("node", binary.getPath());
        }
        return List.of(binary.getPath());
    }

    static Optional<File> newestBundled(List<File> roots, String exe) {
        File best = null;
        int[] bestVer = null;
        long bestMtime = 0;
        for (File root : roots) {
            File[] versions = root.listFiles(File::isDirectory);
            if (versions == null) {
                continue;
            }
            for (File vDir : versions) {
                int[] ver = parseVersion(vDir.getName());
                if (ver == null) {
                    continue;
                }
                File[] hashes = vDir.listFiles(File::isDirectory);
                if (hashes == null) {
                    continue;
                }
                for (File h : hashes) {
                    File f = new File(h, exe);
                    if (!f.isFile()) {
                        continue;
                    }
                    int cmp = bestVer == null ? 1 : compareVersions(ver, bestVer);
                    if (cmp > 0 || (cmp == 0 && f.lastModified() > bestMtime)) {
                        best = f;
                        bestVer = ver;
                        bestMtime = f.lastModified();
                    }
                }
            }
        }
        return Optional.ofNullable(best).map(File::getAbsoluteFile);
    }

    /** "2.1.289" -> [2,1,289]; null if not a version. */
    static int[] parseVersion(String s) {
        if (!s.matches("\\d+(\\.\\d+)*")) {
            return null;
        }
        try {
            return Arrays.stream(s.split("\\.")).mapToInt(Integer::parseInt).toArray();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static int compareVersions(int[] a, int[] b) {
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }
}
