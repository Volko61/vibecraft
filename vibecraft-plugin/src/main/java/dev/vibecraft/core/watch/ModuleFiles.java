package dev.vibecraft.core.watch;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Fingerprinting and reading of a module directory ({@code *.java}, config.yml, module.yml; .vibe/ ignored). */
public final class ModuleFiles {

    private ModuleFiles() {
    }

    private static final int MAX_FILES = 2000;

    /**
     * @param fingerprint (relative path, size, mtime) of every relevant file
     * @param hasJava     at least one .java file
     * @param hasContent  at least one file outside .vibe/ (any kind)
     */
    public record Snapshot(String fingerprint, boolean hasJava, boolean hasContent) {
    }

    /** Sources of a module, read for compilation. */
    public record Sources(String fingerprint, Map<String, String> java, String configYml, String moduleYml) {
    }

    private record Entry(String rel, Path path, long size, long mtime) {
    }

    private static List<Entry> walk(File dir, boolean[] anyContent) throws IOException {
        List<Entry> out = new ArrayList<>();
        Path root = dir.toPath();
        if (!Files.isDirectory(root)) {
            return out;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                if (!d.equals(root) && d.getFileName().toString().startsWith(".")) {
                    return FileVisitResult.SKIP_SUBTREE; // .vibe, .git, .idea...
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) {
                if (!attrs.isRegularFile()) {
                    return FileVisitResult.CONTINUE;
                }
                anyContent[0] = true;
                String rel = root.relativize(f).toString().replace('\\', '/');
                boolean top = !rel.contains("/");
                if (rel.endsWith(".java") || (top && (rel.equals("config.yml") || rel.equals("module.yml")))) {
                    out.add(new Entry(rel, f, attrs.size(), attrs.lastModifiedTime().toMillis()));
                    if (out.size() > MAX_FILES) {
                        return FileVisitResult.TERMINATE;
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path f, IOException e) {
                return FileVisitResult.CONTINUE; // file vanished while walking
            }
        });
        out.sort((a, b) -> a.rel.compareTo(b.rel));
        return out;
    }

    private static String fingerprint(List<Entry> entries) {
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries) {
            sb.append(e.rel).append('|').append(e.size).append('|').append(e.mtime).append('\n');
        }
        return sb.toString();
    }

    public static Snapshot snapshot(File dir) {
        boolean[] any = new boolean[1];
        try {
            List<Entry> entries = walk(dir, any);
            boolean java = false;
            for (Entry e : entries) {
                if (e.rel.endsWith(".java")) {
                    java = true;
                    break;
                }
            }
            return new Snapshot(fingerprint(entries), java, any[0]);
        } catch (IOException e) {
            return new Snapshot("error:" + e.getMessage(), false, dir.isDirectory());
        }
    }

    public static Sources read(File dir) throws IOException {
        List<Entry> entries = walk(dir, new boolean[1]);
        String fp = fingerprint(entries);
        Map<String, String> java = new LinkedHashMap<>();
        String config = null;
        String module = null;
        for (Entry e : entries) {
            String text;
            try {
                text = decode(Files.readAllBytes(e.path));
            } catch (IOException ex) {
                continue; // deleted meanwhile: the next poll will notice
            }
            if (!text.isEmpty() && text.charAt(0) == '﻿') {
                text = text.substring(1); // BOM
            }
            switch (e.rel) {
                case "config.yml" -> config = text;
                case "module.yml" -> module = text;
                default -> java.put(e.rel, text);
            }
        }
        return new Sources(fp, Collections.unmodifiableMap(java), config, module);
    }

    private static String decode(byte[] b) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString();
        } catch (CharacterCodingException e) {
            // not UTF-8 (e.g. saved as ANSI by an editor): fall back to Windows-1252 / Latin-1
            return new String(b, StandardCharsets.ISO_8859_1);
        }
    }
}
