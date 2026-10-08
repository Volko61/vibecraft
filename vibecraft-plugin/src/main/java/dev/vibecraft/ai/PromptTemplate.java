package dev.vibecraft.ai;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import dev.vibecraft.core.ModuleIds;

/**
 * The job prompt template ({@code ai/job-prompt.md} resource) with {@code {{PLACEHOLDER}}} substitution.
 * Placeholders: MODULE_ID, MODULE_DIR, MODULE_DIR_ABS, PACKAGE, API_BASE, AUTH_HEADER, REQUEST,
 * REQUESTED_BY, CONVERSATION_NOTE. Pure JDK.
 */
public final class PromptTemplate {

    public static final String RESOURCE = "ai/job-prompt.md";

    static final String NOTE_FIRST = "Nouvelle conversation pour ce module. Son dossier existe peut-être déjà "
            + "(squelette généré ou ancienne version) : lis ses fichiers .java avant d'écrire.";
    static final String NOTE_RESUMED = "Nouvelle demande dans notre conversation sur ce module. Le code a pu changer "
            + "depuis : relis les fichiers du module avant de modifier, garde ce qui marche déjà.";

    private static final String FALLBACK = """
            Tu es l'IA de VibeCraft (serveur Minecraft Paper avec rechargement à chaud), en direct devant un public.
            {{CONVERSATION_NOTE}}
            Module : {{MODULE_ID}} (dossier {{MODULE_DIR}}/), demandé par {{REQUESTED_BY}}.
            <demande>
            {{REQUEST}}
            </demande>
            Travaille UNIQUEMENT dans {{MODULE_DIR}}/, suis CLAUDE.md, package {{PACKAGE}}.
            Recharge avec : curl -s -X POST {{API_BASE}}/api/modules/{{MODULE_ID}}/reload{{AUTH_HEADER}}
            et corrige jusqu'à "ok":true. Sois rapide. Termine par 1 à 2 phrases en français pour le public.
            """;

    private final String template;

    public PromptTemplate(String template) {
        this.template = template;
    }

    /** Loads the template from the class path of {@code loader} (built-in fallback if missing). */
    public static PromptTemplate load(ClassLoader loader) {
        try (InputStream in = loader.getResourceAsStream(RESOURCE)) {
            if (in != null) {
                return new PromptTemplate(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
            // fall back
        }
        return new PromptTemplate(FALLBACK);
    }

    public String render(Map<String, String> values) {
        String out = template;
        for (Map.Entry<String, String> e : values.entrySet()) {
            out = out.replace("{{" + e.getKey() + "}}", e.getValue() == null ? "" : e.getValue());
        }
        return out;
    }

    /** Java package for a module id, identical to the engine's scaffold ("ma-fusee" -> "vibe.ma_fusee", "3d" -> "vibe._3d", "new" -> "vibe._new"). */
    public static String packageFor(String moduleId) {
        return ModuleIds.packageOf(moduleId);
    }
}
