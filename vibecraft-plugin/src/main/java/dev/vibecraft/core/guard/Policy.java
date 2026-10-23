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
    private static final Set<String> SOCKET_TYPES = Set.of("java/net/Socket", "java/net/ServerSocket",
            "java/net/DatagramSocket", "java/net/MulticastSocket", "java/net/SocketImpl", "java/net/DatagramSocketImpl",
            "java/nio/channels/SocketChannel", "java/nio/channels/ServerSocketChannel", "java/nio/channels/DatagramChannel",
            "java/nio/channels/AsynchronousSocketChannel", "java/nio/channels/AsynchronousServerSocketChannel",
            "javax/net/SocketFactory", "javax/net/ServerSocketFactory");
    /** Superclasses whose subclasses the server calls from its chunk system (any error there crashes the server). */
    private static final Set<String> WORLD_GEN_TYPES = Set.of("org/bukkit/generator/ChunkGenerator",
            "org/bukkit/generator/BiomeProvider", "org/bukkit/generator/BlockPopulator");

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
        if (SOCKET_TYPES.contains(n) || n.startsWith("javax/net/ssl/SSLSocket") || n.startsWith("javax/net/ssl/SSLServerSocket")
                || n.startsWith("com/sun/net/httpserver/")) {
            return n.substring(n.lastIndexOf('/') + 1) + " is not allowed: modules may not open sockets or servers "
                    + "(a blocking socket call freezes the whole server); for web requests use java.net.http.HttpClient "
                    + "inside ctx.async(...)";
        }
        if (n.startsWith("net/minecraft/server/network/") || n.equals("net/minecraft/network/Connection")
                || n.equals("net/minecraft/server/players/PlayerList")) {
            return n.substring(n.lastIndexOf('/') + 1) + " is not allowed: server internals that manage player "
                    + "connections (a module must never disconnect players); use the Paper API";
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
                if (name.equals("banIP") || name.equals("unbanIP") || name.equals("setWhitelist")
                        || name.equals("setWhitelistEnforced") || name.equals("reloadWhitelist")
                        || name.equals("getPluginCommand")) {
                    return "Bukkit." + name + "() is not allowed in modules";
                }
                return null;
            case "org/bukkit/event/HandlerList":
                if (name.equals("unregisterAll") && ("()V".equals(desc) || "(Lorg/bukkit/plugin/Plugin;)V".equals(desc))) {
                    return "HandlerList.unregisterAll(" + ("()V".equals(desc) ? "" : "plugin")
                            + ") would remove the listeners of every module (and of the server); "
                            + "listeners are unregistered automatically on reload";
                }
                if (name.equals("getHandlerLists")) {
                    return "HandlerList.getHandlerLists() is not allowed: it exposes the listeners of every module "
                            + "(and of the server)";
                }
                break;
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
        if (PLUGIN_LIFECYCLE.contains(name) && (h.isSubtype(owner, "org/bukkit/plugin/PluginManager")
                || h.isSubtype(owner, "org/bukkit/plugin/PluginLoader"))) {
            return owner.substring(owner.lastIndexOf('/') + 1) + "." + name
                    + "() is not allowed: it would break the server's plugins (or VibeCraft itself)";
        }
        if ((name.equals("onEnable") || name.equals("onDisable") || name.equals("onLoad")) && "()V".equals(desc)
                && h.isSubtype(owner, "org/bukkit/plugin/Plugin")) {
            return "Plugin." + name + "() is not allowed: it would restart or stop VibeCraft itself";
        }
        if ((name.equals("setEnabled") || name.equals("setNaggable")) && h.isSubtype(owner, "org/bukkit/plugin/Plugin")) {
            return "Plugin." + name + "() is not allowed: it would break VibeCraft itself";
        }
        if (name.equals("getCommand") && h.isSubtype(owner, "org/bukkit/plugin/java/JavaPlugin")
                || name.equals("getPluginCommand") && h.isSubtype(owner, "org/bukkit/Server")) {
            return "plugin.getCommand(...) is not available to modules (no plugin.yml): register commands with "
                    + "ctx.command(name, handler)";
        }
        // disconnecting / banning / promoting players: never from module code
        if ((name.equals("kick") || name.equals("kickPlayer") || name.equals("transfer"))
                && h.isSubtype(owner, "org/bukkit/entity/Player")) {
            return "Player." + name + "() is not allowed in modules (players must never be disconnected by a module): "
                    + "teleport them, change their game mode or send them a message instead";
        }
        if ((name.equals("ban") || name.equals("banIp")) && (h.isSubtype(owner, "org/bukkit/OfflinePlayer")
                || h.isSubtype(owner, "org/bukkit/entity/Player"))) {
            return "banning players is not allowed in modules";
        }
        if ((name.equals("addBan") || name.equals("pardon")) && h.isSubtype(owner, "org/bukkit/BanList")) {
            return "BanList." + name + "() is not allowed in modules";
        }
        if (name.equals("setOp") && h.isSubtype(owner, "org/bukkit/permissions/ServerOperator")) {
            return "setOp() is not allowed in modules: give a feature-specific ability instead of operator rights";
        }
        if (name.equals("setWhitelisted") && h.isSubtype(owner, "org/bukkit/OfflinePlayer")) {
            return "changing the whitelist is not allowed in modules";
        }
        if ((name.equals("banIP") || name.equals("unbanIP") || name.equals("setWhitelist") || name.equals("setWhitelistEnforced")
                || name.equals("reloadWhitelist")) && h.isSubtype(owner, "org/bukkit/Server")) {
            return "Server." + name + "() is not allowed in modules";
        }
        // other modules' listeners and tasks
        if (owner.equals("org/bukkit/event/HandlerList") && (name.equals("register") || name.equals("registerAll")
                || (name.equals("unregister") && ("(Lorg/bukkit/plugin/Plugin;)V".equals(desc)
                || "(Lorg/bukkit/plugin/RegisteredListener;)V".equals(desc))))) {
            return "HandlerList." + name + "(" + ("(Lorg/bukkit/plugin/Plugin;)V".equals(desc) ? "plugin" : "...")
                    + ") is not allowed: it would touch the listeners of every module; use ctx.on / ctx.listen "
                    + "(unregistered automatically on reload)";
        }
        if ((name.equals("getPendingTasks") || name.equals("getActiveWorkers"))
                && h.isSubtype(owner, "org/bukkit/scheduler/BukkitScheduler")) {
            return "BukkitScheduler." + name + "() is not allowed: it exposes the tasks of every module; keep the "
                    + "BukkitTask returned by ctx.every / ctx.later and cancel() it";
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
        return forbiddenCommandLine(commandLine, true);
    }

    /** Commands that stop / reload / wipe the server, disconnect or promote players, or change who may join. */
    private static final Set<String> ADMIN_COMMANDS = Set.of("stop", "restart", "reload", "rl", "op", "deop", "kick",
            "ban", "ban-ip", "pardon", "pardon-ip", "whitelist", "transfer", "save-off", "setidletimeout", "tick", "debug",
            "jfr", "perf", "reset");
    private static final Set<String> HOST_COMMANDS = Set.of("vibe", "vc");

    /**
     * Reason why a command line must not be run, or null.
     *
     * @param fromModule true for module code (also refuses the /vibe host command), false for HTTP API callers
     */
    public static String forbiddenCommandLine(String commandLine, boolean fromModule) {
        if (commandLine == null) {
            return null;
        }
        String s = commandLine.strip();
        for (int depth = 0; depth < 16; depth++) {
            while (s.startsWith("/")) {
                s = s.substring(1).strip();
            }
            String[] parts = s.split("\\s+");
            String label = parts[0].toLowerCase(java.util.Locale.ROOT);
            int colon = label.indexOf(':');
            if (colon >= 0) {
                label = label.substring(colon + 1);
            }
            if (ADMIN_COMMANDS.contains(label)) {
                return (fromModule ? "modules" : "the API") + " may not run /" + label
                        + ": it would stop or reload the server, disconnect players or change who may join / who is op";
            }
            if (fromModule && HOST_COMMANDS.contains(label)) {
                return "modules may not run /" + label + " (VibeCraft's own admin command)";
            }
            if (!label.equals("execute") && !label.equals("return")) {
                return null;
            }
            // execute ... run <command> / return run <command>: check the wrapped command
            int run = -1;
            for (int i = 1; i < parts.length; i++) {
                if (parts[i].equalsIgnoreCase("run")) {
                    run = i;
                    break;
                }
            }
            if (run < 0 || run == parts.length - 1) {
                return null;
            }
            s = String.join(" ", java.util.Arrays.copyOfRange(parts, run + 1, parts.length));
        }
        return "command nesting too deep";
    }

    /** Reason why a module class may not extend {@code superInternalName} (checked on the whole hierarchy), or null. */
    public static String forbiddenSupertype(String className, Hierarchy h) {
        for (String t : WORLD_GEN_TYPES) {
            if (h.isSubtype(className, t)) {
                return "custom world generators (" + t.substring(t.lastIndexOf('/') + 1) + ") are not allowed in modules: "
                        + "the server calls them from its chunk system, where any error crashes the whole server. "
                        + "Use the existing world and change blocks with ctx.every(...)";
            }
        }
        return null;
    }

    /** Reason why reading field {@code owner.name} is forbidden, or null. */
    public static String forbiddenField(String owner, String name) {
        if ("java/lang/System".equals(owner) && "in".equals(name)) {
            return "System.in is the server console: reading it would freeze the server";
        }
        return null;
    }
}
