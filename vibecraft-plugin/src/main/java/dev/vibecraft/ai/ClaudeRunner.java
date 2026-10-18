package dev.vibecraft.ai;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Runs ONE headless Claude Code process ({@code claude -p --output-format stream-json --verbose ...})
 * and blocks until it ends. No shell: the command is launched directly, the prompt is written to stdin
 * (UTF-8) and stdin is closed. stdout is parsed by a {@link StreamTranslator} on a reader thread,
 * stderr is kept (last lines). {@link #stop()} and the two time limits (inactivity, total duration) kill the
 * whole process tree.
 * Pure JDK + Gson (no Bukkit): usable from the dev harness.
 */
public final class ClaudeRunner {

    /**
     * Everything needed to launch one process.
     *
     * @param idleTimeout the process is killed after that long without any stdout line (hung CLI); null / zero = never
     * @param maxDuration the process is killed after that long in total; null / zero = never
     */
    public record Request(List<String> commandPrefix, File workDir, String prompt, String model, String permissionMode,
                          List<String> allowedTools, List<String> tools, String resumeSessionId, Duration idleTimeout,
                          Duration maxDuration, Map<String, String> env, List<String> extraArgs, String moduleId) {
    }

    /** {@code TIMED_OUT}: {@code maxDuration} reached; {@code STALLED}: nothing printed for {@code idleTimeout}. */
    public enum End { COMPLETED, STOPPED, TIMED_OUT, STALLED, LAUNCH_FAILED }

    /** What happened. {@code result} is null if the process never printed a result line. */
    public record Outcome(End end, int exitCode, StreamTranslator.Final result, String sessionId, boolean realActivity,
                          boolean authFailed, String apiError, boolean sessionNotFound, List<String> stderrTail,
                          String launchError, long durationMs, int reloadOk, int reloadFailed) {
        public boolean succeeded() {
            return end == End.COMPLETED && result != null && !result.isError();
        }
    }

    private static final ScheduledExecutorService TICKER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "VibeCraft-AI-ticker");
        t.setDaemon(true);
        return t;
    });

    private static final int STDERR_LINES = 40;

    private final Request req;
    private final Consumer<String> progress;
    private final Deque<String> stderr = new ArrayDeque<>();
    private volatile Process process;
    private volatile boolean stopRequested;
    private volatile boolean timedOut;
    private volatile boolean stalled;
    /** Time of the last stdout line (= the conversation moved on). */
    private volatile long lastActivity;
    private final CountDownLatch killDone = new CountDownLatch(1);
    private volatile Consumer<String> rawSink;

    public ClaudeRunner(Request req, Consumer<String> progress) {
        this.req = req;
        this.progress = progress;
    }

    /** Also receives every raw stdout line (stream-json), after the translator (transcript capture). */
    public void rawSink(Consumer<String> sink) {
        this.rawSink = sink;
    }

    /** The exact argv (without the prompt, which goes to stdin). */
    public static List<String> buildCommand(Request r) {
        List<String> cmd = new ArrayList<>(r.commandPrefix());
        cmd.add("-p");
        cmd.add("--output-format");
        cmd.add("stream-json");
        cmd.add("--verbose");
        if (r.permissionMode() != null && !r.permissionMode().isBlank()) {
            cmd.add("--permission-mode");
            cmd.add(r.permissionMode());
        }
        if (r.model() != null && !r.model().isBlank()) {
            cmd.add("--model");
            cmd.add(r.model());
        }
        if (r.resumeSessionId() != null && !r.resumeSessionId().isBlank()) {
            cmd.add("--resume");
            cmd.add(r.resumeSessionId());
        }
        if (r.extraArgs() != null) {
            cmd.addAll(r.extraArgs());
        }
        // Variadic options last-but-flagged: each list is followed by another flag or the end of argv.
        if (r.tools() != null && !r.tools().isEmpty()) {
            cmd.add("--tools");
            cmd.add(String.join(",", r.tools()));
        }
        if (r.allowedTools() != null && !r.allowedTools().isEmpty()) {
            cmd.add("--allowedTools");
            cmd.addAll(r.allowedTools());
        }
        return cmd;
    }

    /** Runs the process to completion (or stop / timeout). Never throws. */
    public Outcome run() {
        long t0 = System.currentTimeMillis();
        StreamTranslator tr = new StreamTranslator(req.moduleId(), progress);
        if (stopRequested) {
            return outcome(End.STOPPED, -1, tr, null, t0);
        }
        List<String> cmd = buildCommand(req);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(req.workDir());
        pb.environment().clear();
        pb.environment().putAll(req.env());
        Process p;
        try {
            p = pb.start();
        } catch (IOException | RuntimeException e) {
            return outcome(End.LAUNCH_FAILED, -1, tr, String.valueOf(e.getMessage()), t0);
        }
        process = p;
        lastActivity = System.currentTimeMillis();
        if (stopRequested) { // stop() raced with start()
            killTree(p.toHandle());
        }

        Thread out = new Thread(() -> readStdout(p, tr), "VibeCraft-AI-out-" + req.moduleId());
        Thread err = new Thread(() -> readStderr(p), "VibeCraft-AI-err-" + req.moduleId());
        out.setDaemon(true);
        err.setDaemon(true);
        out.start();
        err.start();
        ScheduledFuture<?> tick = TICKER.scheduleAtFixedRate(() -> tr.tick(System.currentTimeMillis()), 500, 500, TimeUnit.MILLISECONDS);

        try (OutputStream stdin = p.getOutputStream()) {
            stdin.write(req.prompt().getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        } catch (IOException e) {
            // process died early: stdout/stderr tell why
        }

        int exit = -1;
        try {
            long idleMs = millis(req.idleTimeout());
            long maxMs = millis(req.maxDuration());
            while (!p.waitFor(250, TimeUnit.MILLISECONDS)) {
                long now = System.currentTimeMillis();
                if (now - t0 >= maxMs) {
                    timedOut = true;
                } else if (now - lastActivity >= idleMs) {
                    stalled = true;
                } else {
                    continue;
                }
                killTree(p.toHandle());
                p.waitFor(10, TimeUnit.SECONDS);
                break;
            }
            if (!p.isAlive()) {
                exit = p.exitValue();
            }
            if (stopRequested) {
                killDone.await(5, TimeUnit.SECONDS); // report STOPPED only once the whole tree is dead
            }
            out.join(10_000);
            err.join(2_000);
        } catch (InterruptedException e) {
            killTree(p.toHandle());
            Thread.currentThread().interrupt();
        } finally {
            tick.cancel(false);
        }
        tr.finish();
        End end = stopRequested ? End.STOPPED : timedOut ? End.TIMED_OUT : stalled ? End.STALLED : End.COMPLETED;
        return outcome(end, exit, tr, null, t0);
    }

    private static long millis(Duration d) {
        return d == null || d.isZero() || d.isNegative() ? Long.MAX_VALUE : d.toMillis();
    }

    /** Kill the process tree (idempotent; safe from any thread). Returns once the tree is gone (max ~3 s). */
    public void stop() {
        stopRequested = true;
        Process p = process;
        try {
            if (p != null) {
                killTree(p.toHandle());
            }
        } finally {
            killDone.countDown();
        }
    }

    public boolean stopRequested() {
        return stopRequested;
    }

    /** PID of the running process, or -1. */
    public long pid() {
        Process p = process;
        return p == null ? -1 : p.pid();
    }

    /**
     * Forcibly kills a process and all its descendants, and waits (up to 3 s) until they are gone.
     * Descendants are snapshotted and killed while the root is still alive (once the root is dead,
     * Windows no longer links orphans to it), deepest first, then a second pass catches processes
     * spawned in between, then the root itself is killed.
     */
    public static void killTree(ProcessHandle root) {
        List<ProcessHandle> all = new ArrayList<>();
        for (int pass = 0; pass < 2; pass++) {
            List<ProcessHandle> snap = new ArrayList<>();
            try {
                root.descendants().forEach(snap::add);
            } catch (RuntimeException ignored) {
                // access denied on some system processes: kill what we can
            }
            for (int i = snap.size() - 1; i >= 0; i--) { // descendants() is breadth-first: reverse = deepest first
                ProcessHandle h = snap.get(i);
                h.destroyForcibly();
                if (!all.contains(h)) {
                    all.add(h);
                }
            }
        }
        root.destroyForcibly();
        all.add(root);
        long deadline = System.currentTimeMillis() + 3000;
        for (ProcessHandle h : all) {
            while (h.isAlive() && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------

    private void readStdout(Process p, StreamTranslator tr) {
        boolean reported = false;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                lastActivity = System.currentTimeMillis();
                try {
                    tr.accept(line);
                    Consumer<String> raw = rawSink;
                    if (raw != null) {
                        raw.accept(line);
                    }
                } catch (Throwable t) {
                    // keep draining stdout whatever happens, or the CLI would block on a full pipe
                    if (!reported) {
                        reported = true;
                        synchronized (stderr) {
                            stderr.addLast("[VibeCraft] stream parsing failed: " + t);
                        }
                    }
                }
            }
        } catch (IOException ignored) {
            // killed
        }
    }

    private void readStderr(Process p) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                synchronized (stderr) {
                    stderr.addLast(line);
                    while (stderr.size() > STDERR_LINES) {
                        stderr.removeFirst();
                    }
                }
            }
        } catch (IOException ignored) {
            // killed
        }
    }

    private Outcome outcome(End end, int exit, StreamTranslator tr, String launchError, long t0) {
        List<String> errTail;
        synchronized (stderr) {
            errTail = List.copyOf(stderr);
        }
        StreamTranslator.Final fin = tr.result();
        boolean notFound = false;
        if (req.resumeSessionId() != null) {
            for (String s : errTail) {
                notFound |= s.contains("No conversation found");
            }
            if (fin != null) {
                for (String e : fin.errors()) {
                    notFound |= e.contains("No conversation found");
                }
            }
            // a resumed run that produced no conversation at all is also treated as "not found"
            notFound &= !tr.realActivity();
        }
        String sid = tr.sessionId();
        if (sid == null && fin != null) {
            sid = fin.sessionId();
        }
        return new Outcome(end, exit, fin, sid, tr.realActivity(), tr.authFailed(), tr.apiError(), notFound, errTail,
                launchError, System.currentTimeMillis() - t0, tr.reloadOk(), tr.reloadFailed());
    }
}
