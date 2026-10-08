package dev.vibecraft.core;

import java.util.Set;

/** Pure-JDK helpers deriving Java names from module ids (shared by the engine scaffold and the AI prompt). */
public final class ModuleIds {

    private static final Set<String> JAVA_KEYWORDS = Set.of("abstract", "assert", "boolean", "break", "byte", "case",
            "catch", "char", "class", "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long",
            "native", "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void", "volatile", "while",
            "true", "false", "null", "var", "record", "yield", "sealed", "permits", "non", "_");

    private ModuleIds() {
    }

    /** Java package of a module: {@code vibe.<id with '-' replaced by '_'>} ({@code _} prefix for digits/keywords). */
    public static String packageOf(String id) {
        String p = id.replace('-', '_');
        if (Character.isDigit(p.charAt(0)) || JAVA_KEYWORDS.contains(p)) {
            p = "_" + p;
        }
        return "vibe." + p;
    }
}
