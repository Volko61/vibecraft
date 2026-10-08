package dev.vibecraft.core.guard;

import java.util.Set;

/**
 * Forbidden types and calls for module code. Used at load time on bytecode (internal names) and at
 * run time by the reflection redirects.
 */
public final class Policy {

    private Policy() {
    }

    /** Type hierarchy oracle ("is {@code type} a subtype of {@code superType}?"), internal names. */
    @FunctionalInterface
    public interface Hierarchy {
        boolean isSubtype(String type, String superType);
    }

    private static final Set<String> SERVER_LIFECYCLE = Set.of("shutdown", "restart", "reload", "reloadData");
    private static final Set<String> PLUGIN_LIFECYCLE = Set.of("disablePlugin", "disablePlugins", "clearPlugins",
            "enablePlugin", "loadPlugin", "loadPlugins");
    private static final Set<String> NMS_LIFECYCLE = Set.of("halt", "stopServer", "close", "safeShutdown", "reload",
            "reloadResources");

    /** Reason why referencing {@code internalName} (or an array of it) is forbidden, or null. */
    public static String forbiddenType(String internalName) {
        if (internalName == null) {
            return null;
        }
        String n = internalName;
        while (n.startsWith("[")) {
            n = n.substring(1);
        }
        if (n.startsWith("L") && n.endsWith(";")) {
            n = n.substring(1, n.length() - 1);
        }
        if (n.equals("java/lang/ProcessBuilder") || n.startsWith("java/lang/ProcessBuilder$")) {
            return "ProcessBuilder is not allowed: modules cannot start external processes";
        }
        if (n.equals("java/lang/ProcessHandle") || n.startsWith("java/lang/ProcessHandle$")) {
            return "ProcessHandle is not allowed: modules cannot control processes";
        }
        if (n.equals("sun/misc/Unsafe") || n.startsWith("jdk/internal/")) {
            return n.replace('/', '.') + " is not allowed: internal JDK APIs can crash the JVM";
        }
        if (n.equals("java/lang/foreign/Linker") || n.equals("java/lang/foreign/SymbolLookup")) {
            return "native code (java.lang.foreign) is not allowed in modules";
        }
        if (n.startsWith("dev/vibecraft/") && !n.startsWith("dev/vibecraft/api/")) {
            return n.replace('/', '.') + " is a VibeCraft internal class: modules may only use dev.vibecraft.api";
        }
        return null;
    }

    /** Reason why calling {@code owner.name desc} is forbidden, or null. */
    public static String forbiddenCall(String owner, String name, String desc, Hierarchy h) {
        if (owner == null || name == null) {
            return null;
        }
        if (owner.startsWith("[")) {
            return null;
        }
        String t = forbiddenType(owner);
        if (t != null) {
            return t;
        }
        switch (owner) {
            case "java/lang/System":
                switch (name) {
                    case "exit": return "System.exit() would stop the whole server";
                    case "setSecurityManager": return "System.setSecurityManager() is not allowed";
                    case "load", "loadLibrary": return "loading native libraries is not allowed in modules";
                    case "setOut", "setErr", "setIn": return "System." + name + "() would break the server console";
                    default: return null;
                }
            case "java/lang/Runtime":
                switch (name) {
                    case "exit", "halt": return "Runtime." + name + "() would stop the whole server";
                    case "exec": return "Runtime.exec() is not allowed: modules cannot start external processes";
                    case "load", "loadLibrary": return "loading native libraries is not allowed in modules";
                    default: return null;
                }
            case "org/bukkit/Bukkit":
                if (SERVER_LIFECYCLE.contains(name)) {
                    return "Bukkit." + name + "() is not allowed: it would stop/reload the whole server";
                }
                return null;
            case "org/bukkit/event/HandlerList":
                if (name.equals("unregisterAll") && ("()V".equals(desc) || "(Lorg/bukkit/plugin/Plugin;)V".equals(desc))) {
                    return "HandlerList.unregisterAll(" + ("()V".equals(desc) ? "" : "plugin")
                            + ") would remove the listeners of every module (and of the server); "
                            + "listeners are unregistered automatically on reload";
                }
                return null;
            case "java/lang/invoke/MethodHandles$Lookup":
                if (name.equals("defineClass") || name.startsWith("defineHiddenClass")) {
                    return "defining classes at run time is not allowed in modules";
                }
                return null;
            default:
                break;
        }
        if (h == null) {
            return null;
        }
        if (SERVER_LIFECYCLE.contains(name) && h.isSubtype(owner, "org/bukkit/Server")) {
            return "Server." + name + "() is not allowed: it would stop/reload the whole server";
        }
        if (PLUGIN_LIFECYCLE.contains(name) && h.isSubtype(owner, "org/bukkit/plugin/PluginManager")) {
            return "PluginManager." + name + "() is not allowed: it would break the server's plugins";
        }
        if (name.equals("cancelTasks") && (h.isSubtype(owner, "org/bukkit/scheduler/BukkitScheduler")
                || h.isSubtype(owner, "io/papermc/paper/threadedregions/scheduler/GlobalRegionScheduler")
                || h.isSubtype(owner, "io/papermc/paper/threadedregions/scheduler/AsyncScheduler"))) {
            return "BukkitScheduler.cancelTasks(plugin) would cancel the tasks of every module; "
                    + "tasks are cancelled automatically on reload";
        }
        if (name.equals("clearCommands") && h.isSubtype(owner, "org/bukkit/command/CommandMap")) {
            return "CommandMap.clearCommands() would remove every command of the server";
        }
        if ((name.equals("stop") || name.equals("suspend") || name.equals("resume")) && "()V".equals(desc)
                && h.isSubtype(owner, "java/lang/Thread")) {
            return "Thread." + name + "() is not allowed";
        }
        if (name.equals("defineClass") && h.isSubtype(owner, "java/lang/ClassLoader")) {
            return "defining classes at run time is not allowed in modules";
        }
        if (NMS_LIFECYCLE.contains(name) && owner.startsWith("net/minecraft/")
                && h.isSubtype(owner, "net/minecraft/server/MinecraftServer")) {
            return "MinecraftServer." + name + "() is not allowed: it would stop the whole server";
        }
        return null;
    }

    /** Reason why a console/player command line must not be dispatched by a module, or null. */
    public static String forbiddenCommandLine(String commandLine) {
        if (commandLine == null) {
            return null;
        }
        String s = commandLine.trim();
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        int sp = s.indexOf(' ');
        String label = (sp < 0 ? s : s.substring(0, sp)).toLowerCase(java.util.Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) {
            label = label.substring(colon + 1);
        }
        switch (label) {
            case "stop", "restart", "reload", "rl":
                return "modules may not run /" + label + ": it would stop or reload the whole server";
            default:
                return null;
        }
    }
}
