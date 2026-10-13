# leaks-log4j: a Log4j appender of an old generation stays on the root logger (low)

Module: "console in chat" = `((org.apache.logging.log4j.core.Logger) LogManager.getRootLogger()).addAppender(relay)`.

Steps: load, `say relayme one` (relayed to the bot), reload, `say relayme two`.

Expected: only v2's appender.
Observed: `root appenders: [Async, leaks-relay-v2, leaks-relay-v1]`; Log4j catches the stale appender's error
(`ERROR An exception occurred processing Appender leaks-relay-v1 ... AppenderLoggingException:
ModuleKilledError`, 40-line stack traces in the console); no crash (unlike the JUL handler, leaks-jul), but
the old generation stays pinned and the console gets stack traces.

Suggested fix: same as leaks-jul (track `addAppender` -> `removeAppender` + `stop()` on unload; scan root
appenders for classes of the dead loader).

## After the fix (re-verified 2026-10-14 on a private sandbox, MC 25640 / API 8840)

After the reload: `root appenders: [Async, leaks-relay-v2]` and the module log says "removed a Log4j appender left by the unloaded version (leaks-relay-v1)"; no AppenderLoggingException.
