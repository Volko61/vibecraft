package dev.vibecraft.ai;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.vibecraft.core.ModuleInfo;
import dev.vibecraft.core.ModuleManager;
import dev.vibecraft.core.ModuleState;

/**
 * One conversation per module (= its Claude Code session): the {@link ConversationStore} transcript joined
 * with the module's state and its AI job. Shared by the HTTP API ({@code /api/conversations}) and the in-game
 * dialogs. No Bukkit calls (ModuleManager reads are thread-safe snapshots).
 */
public final class Conversations {

    public static final int MAX_TEXT = 20_000;

    /** A conversation as listed. {@code state}: idle | running | queued | failed. */
    public record Entry(String id, String title, String state, long updatedAt, String lastLine, ModuleState moduleState,
                        int generation, String imageProvider, String model, int messageCount) {
        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("title", title);
            m.put("state", state);
            m.put("updatedAt", updatedAt);
            m.put("lastLine", lastLine);
            m.put("moduleState", moduleState);
            m.put("generation", generation);
            return m;
        }
    }

    private final ClaudeJobService ai;
    private final ModuleManager modules;

    public Conversations(ClaudeJobService ai, ModuleManager modules) {
        this.ai = ai;
        this.modules = modules;
    }

    public ConversationStore store() {
        return ai.conversations();
    }

    public AiJobService ai() {
        return ai;
    }

    public boolean validId(String id) {
        return id != null && modules.isValidId(id);
    }

    /**
     * {@code base} (a valid id, e.g. a slug of the request), or {@code base-2}, {@code base-3}... so that no
     * conversation, module or module folder already uses it. Always a valid id.
     */
    public String freeId(String base) {
        String b = validId(base) ? base : "demande";
        for (int i = 1; i < 1000; i++) {
            String suffix = i == 1 ? "" : "-" + i;
            String id = (b.length() + suffix.length() > 40 ? b.substring(0, 40 - suffix.length()) : b) + suffix;
            if (validId(id) && entry(id).isEmpty()) {
                return id;
            }
        }
        return b + "-" + Long.toString(System.currentTimeMillis() % 1_000_000L, 36);
    }

    /** Every conversation and every module (a module without conversation yet is an empty one), newest first. */
    public List<Entry> list() {
        Map<String, Entry> out = new LinkedHashMap<>();
        Map<String, ModuleInfo> infos = new LinkedHashMap<>();
        for (ModuleInfo m : safeModules()) {
            infos.put(m.id(), m);
        }
        for (ConversationStore.Summary s : store().list()) {
            out.put(s.id(), entry(s, infos.get(s.id())));
        }
        for (ModuleInfo m : infos.values()) {
            out.computeIfAbsent(m.id(), id -> entry(null, m));
        }
        List<Entry> list = new ArrayList<>(out.values());
        list.sort(Comparator.comparingLong(Entry::updatedAt).reversed().thenComparing(Entry::id));
        return list;
    }

    /** Empty if neither a conversation nor a module (nor its folder) exists. */
    public Optional<Entry> entry(String id) {
        Optional<ConversationStore.Summary> s = store().summary(id);
        ModuleInfo info = modules.info(id).orElse(null);
        if (s.isEmpty() && info == null && !folderExists(id)) {
            return Optional.empty();
        }
        return Optional.of(entry(s.orElse(null), info));
    }

    /** idle | running | queued | failed */
    public String state(String id) {
        Optional<AiJob> j = ai.job(id);
        if (j.isPresent()) {
            switch (j.get().state()) {
                case RUNNING -> {
                    return "running";
                }
                case QUEUED -> {
                    return "queued";
                }
                default -> {
                }
            }
        }
        String last = store().summary(id).map(ConversationStore.Summary::lastState).orElse("");
        if (last.isEmpty() && j.isPresent()) {
            last = j.get().state().name();
        }
        return AiJobState.FAILED.name().equals(last) ? "failed" : "idle";
    }

    /**
     * Sends a new request in the module's conversation (creates the module if needed).
     *
     * @throws IllegalArgumentException invalid id / provider / model, empty or too long text (message says which)
     * @throws IllegalStateException    AI unavailable, or a job is already queued / running for this module
     */
    public AiJob send(String id, String text, String imageProvider, String model, String by) {
        if (!validId(id)) {
            throw new IllegalArgumentException("identifiant de module invalide : doit respecter [a-z0-9][a-z0-9_-]{0,39}");
        }
        String t = text == null ? "" : text.strip();
        if (t.isEmpty()) {
            throw new IllegalArgumentException("message vide");
        }
        if (t.length() > MAX_TEXT) {
            throw new TooLong("message trop long (max " + MAX_TEXT + " caractères)");
        }
        JobOptions opts = new JobOptions(imageProvider, model);
        if (!ai.available()) {
            throw new IllegalStateException("IA indisponible : " + ai.unavailableReason());
        }
        return ai.start(id, t, by == null || by.isBlank() ? "dashboard" : by, opts);
    }

    /** Thrown by {@link #send} for a text over {@link #MAX_TEXT} (HTTP 413). */
    public static final class TooLong extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        TooLong(String m) {
            super(m);
        }
    }

    public boolean stop(String id) {
        return ai.stop(id);
    }

    /**
     * Forgets the conversation and the Claude session (module files are kept).
     *
     * @throws IllegalStateException while a job runs on it
     */
    public boolean delete(String id) {
        String st = state(id);
        if (st.equals("running") || st.equals("queued")) {
            throw new IllegalStateException("une IA travaille encore sur " + id + " : arrêtez-la d'abord");
        }
        ai.resetConversation(id);
        return store().delete(id);
    }

    /** Image provider of the conversation's last request ("auto" if none). */
    public String imageProvider(String id) {
        return store().summary(id).map(ConversationStore.Summary::imageProvider).orElse("auto");
    }

    // ------------------------------------------------------------------

    private Entry entry(ConversationStore.Summary s, ModuleInfo info) {
        String id = s != null ? s.id() : info.id();
        String title = s != null ? s.title() : id;
        long updated = s != null ? s.updatedAt() : info == null ? 0 : Math.max(info.lastReloadAt(), info.lastAttemptAt());
        String last = s != null ? s.lastLine() : info == null || info.description() == null ? "" : info.description();
        return new Entry(id, title, state(id), updated, last == null ? "" : last, info == null ? null : info.state(),
                info == null ? 0 : info.generation(), s == null ? "auto" : s.imageProvider(), s == null ? "" : s.model(),
                s == null ? 0 : s.messageCount());
    }

    private List<ModuleInfo> safeModules() {
        try {
            return modules.list();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private boolean folderExists(String id) {
        try {
            return validId(id) && new java.io.File(modules.modulesDir(), id).isDirectory();
        } catch (RuntimeException e) {
            return false;
        }
    }
}
