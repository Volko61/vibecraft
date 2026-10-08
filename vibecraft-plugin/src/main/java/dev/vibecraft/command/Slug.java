package dev.vibecraft.command;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Turns free text (an audience idea) into a valid module id, e.g. "Faire pleuvoir des cochons !" -> "pleuvoir-cochons". */
public final class Slug {

    /** French (and a few English) filler words that make poor module names. */
    private static final Set<String> STOP_WORDS = Set.copyOf(List.of(
            "a", "au", "aux", "avec", "ce", "ces", "cette", "dans", "de", "des", "du", "en", "et", "il", "ils",
            "je", "j", "l", "la", "le", "les", "leur", "ma", "mes", "mon", "ne", "nous", "on", "ou", "par", "pas",
            "plus", "pour", "qu", "que", "qui", "quand", "sa", "se", "ses", "son", "sur", "ta", "te", "tes", "ton",
            "tu", "un", "une", "vous", "y", "d", "s", "c", "n", "m", "t",
            "faire", "fais", "fait", "faites", "ajouter", "ajoute", "ajoutez", "mettre", "met", "mets", "rajouter",
            "rajoute", "creer", "cree", "veux", "voudrais", "voulons", "peut", "peux", "pourrait", "faudrait",
            "serait", "etre", "est", "sont", "avoir", "a", "ont", "svp", "stp", "please", "the", "an", "of",
            "to", "add", "make", "and", "with", "for", "on", "in", "is", "it", "that", "when", "chaque", "tout",
            "tous", "toutes", "toute"));

    private static final int MAX_WORDS = 3;
    private static final int MAX_LEN = 32;

    private Slug() {
    }

    /**
     * Slug of {@code text}: lower case, accents removed, filler words dropped, at most 3 words joined
     * by '-', at most 32 chars; always matches {@code [a-z0-9][a-z0-9_-]{0,39}}. Falls back to
     * {@code fallback} (or "module") when nothing usable remains.
     */
    public static String of(String text, String fallback) {
        String norm = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replace('œ', 'o').replace('æ', 'a').replace('ß', 's');
        String[] words = norm.split("[^a-z0-9]+");
        List<String> kept = new ArrayList<>();
        List<String> all = new ArrayList<>();
        for (String w : words) {
            if (w.isEmpty()) {
                continue;
            }
            all.add(w);
            if (!STOP_WORDS.contains(w) && kept.size() < MAX_WORDS) {
                kept.add(w);
            }
        }
        if (kept.isEmpty()) {
            kept = all.subList(0, Math.min(MAX_WORDS, all.size()));
        }
        StringBuilder sb = new StringBuilder();
        for (String w : kept) {
            if (sb.length() + w.length() + (sb.isEmpty() ? 0 : 1) > MAX_LEN) {
                if (sb.isEmpty()) {
                    sb.append(w, 0, MAX_LEN);
                }
                break;
            }
            if (!sb.isEmpty()) {
                sb.append('-');
            }
            sb.append(w);
        }
        String slug = sb.toString();
        if (slug.isEmpty() || !slug.matches("[a-z0-9][a-z0-9_-]{0,39}")) {
            String fb = fallback == null ? "" : fallback;
            return fb.matches("[a-z0-9][a-z0-9_-]{0,39}") ? fb : "module";
        }
        return slug;
    }
}
