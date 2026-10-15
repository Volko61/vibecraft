package dev.vibecraft.ai;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Per-job options chosen by the requester (conversation UI / API), on top of the config defaults.
 *
 * @param imageProvider "auto" | "openrouter" | "meshy": provider the texture / 3D endpoints use by default for this job
 * @param model         Claude model for this job ("" = ai.model of config.yml)
 */
public record JobOptions(String imageProvider, String model) {

    public static final JobOptions DEFAULT = new JobOptions("auto", "");

    private static final Pattern MODEL = Pattern.compile("[A-Za-z0-9._\\[\\]-]{1,80}");

    public JobOptions {
        imageProvider = normProvider(imageProvider);
        model = model == null ? "" : model.strip();
        if (!model.isEmpty() && !MODEL.matcher(model).matches()) {
            throw new IllegalArgumentException("modèle invalide : " + StreamTranslator.oneLine(model, 40)
                    + " (lettres, chiffres, . _ - [ ] uniquement)");
        }
    }

    /** "auto" for null/blank; throws IllegalArgumentException for an unknown provider. */
    public static String normProvider(String p) {
        if (p == null || p.isBlank()) {
            return "auto";
        }
        String s = p.strip().toLowerCase(Locale.ROOT);
        if (!s.equals("auto") && !s.equals("openrouter") && !s.equals("meshy")) {
            throw new IllegalArgumentException("imageProvider invalide : " + StreamTranslator.oneLine(p, 30)
                    + " (attendu : auto, openrouter ou meshy)");
        }
        return s;
    }
}
