package dev.vibecraft.core.ctx;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Bounded in-memory log of a module (logger output + engine/runtime messages). Thread-safe. */
public final class LogRing {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final int capacity;
    private final ArrayDeque<String> lines;

    public LogRing(int capacity) {
        this.capacity = capacity;
        this.lines = new ArrayDeque<>(capacity);
    }

    public void add(String level, String message) {
        String time = LocalTime.now().format(TIME);
        String[] parts = (message == null ? "null" : message).split("\r?\n", -1);
        synchronized (lines) {
            for (String p : parts) {
                if (lines.size() >= capacity) {
                    lines.pollFirst();
                }
                lines.addLast(time + " " + level + " " + p);
            }
        }
    }

    /** At most {@code limit} most recent lines, oldest first. */
    public List<String> tail(int limit) {
        synchronized (lines) {
            int n = Math.max(0, Math.min(limit, lines.size()));
            List<String> out = new ArrayList<>(n);
            int skip = lines.size() - n;
            int i = 0;
            for (String s : lines) {
                if (i++ >= skip) {
                    out.add(s);
                }
            }
            return out;
        }
    }
}
