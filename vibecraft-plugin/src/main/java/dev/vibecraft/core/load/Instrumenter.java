package dev.vibecraft.core.load;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.vibecraft.core.Diagnostic;
import dev.vibecraft.core.guard.Guard;
import dev.vibecraft.core.guard.Policy;
import dev.vibecraft.core.guard.Redirects;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * Bytecode policy scanner and guard instrumentation for module classes.
 *
 * <p>Instrumentation inserts {@code Guard.check(id)} at every method entry and before every loop
 * back-edge, and rewrites a set of calls into {@link Redirects} (sleep, raw Bukkit registrations,
 * scheduler, blocking calls, reflection...). Inserted code is stack-neutral and adds no branch
 * target, so the existing stack map frames stay valid (ClassWriter COMPUTE_MAXS only).
 */
public final class Instrumenter {

    private Instrumenter() {
    }

    static final String GUARD = Type.getInternalName(Guard.class);
    static final String REDIRECTS = Type.getInternalName(Redirects.class);
    static final String VIBE_MODULE = "dev/vibecraft/api/VibeModule";
    private static final String PAPER_SCHED = "io/papermc/paper/threadedregions/scheduler/";

    // ================================================================== redirect rules

    private enum Kind { REPLACE, POST }

    /**
     * @param owner     declaring type (matched exactly for static calls, by subtype otherwise)
     * @param name      method name, or a prefix ending with '*'
     * @param desc      exact descriptor or null for any
     * @param isStatic  static call?
     * @param target    Redirects method name (for '*' names: prefix + original name)
     */
    private record Rule(Kind kind, String owner, String name, String desc, boolean isStatic, String target) {
        boolean nameMatches(String n) {
            return name.endsWith("*") ? n.startsWith(name.substring(0, name.length() - 1)) : name.equals(n);
        }

        String targetName(String n) {
            return target.endsWith("*") ? target.substring(0, target.length() - 1) + n : target;
        }
    }

    private static final List<Rule> RULES = List.of(
            // sleeping / blocking
            new Rule(Kind.REPLACE, "java/lang/Thread", "sleep", null, true, "sleep"),
            new Rule(Kind.REPLACE, "java/util/concurrent/TimeUnit", "sleep", "(J)V", false, "sleep"),
            new Rule(Kind.REPLACE, "java/lang/Object", "wait", null, false, "waitFor"),
            new Rule(Kind.REPLACE, "java/lang/Thread", "join", null, false, "join"),
            new Rule(Kind.REPLACE, "java/util/concurrent/Future", "get", "()Ljava/lang/Object;", false, "futureGet"),
            new Rule(Kind.REPLACE, "java/util/concurrent/CompletableFuture", "join", "()Ljava/lang/Object;", false, "futureJoin"),
            new Rule(Kind.REPLACE, "java/util/concurrent/CountDownLatch", "await", "()V", false, "latchAwait"),
            new Rule(Kind.REPLACE, "java/util/concurrent/BlockingQueue", "take", "()Ljava/lang/Object;", false, "queueTake"),
            new Rule(Kind.REPLACE, "java/util/concurrent/Semaphore", "acquire", "()V", false, "semaphoreAcquire"),
            // threads & executors
            new Rule(Kind.REPLACE, "java/lang/Thread", "start", "()V", false, "startThread"),
            new Rule(Kind.POST, "java/lang/Thread", "startVirtualThread", null, true, "trackThread"),
            new Rule(Kind.POST, "java/lang/Thread$Builder", "start", "(Ljava/lang/Runnable;)Ljava/lang/Thread;", false, "trackThread"),
            new Rule(Kind.POST, "java/util/concurrent/Executors", "new*", null, true, "track*"),
            // Bukkit registrations
            new Rule(Kind.REPLACE, "org/bukkit/plugin/PluginManager", "registerEvents", null, false, "registerEvents"),
            new Rule(Kind.REPLACE, "org/bukkit/plugin/PluginManager", "registerEvent", null, false, "registerEvent"),
            new Rule(Kind.REPLACE, "org/bukkit/event/HandlerList", "unregisterAll", "(Lorg/bukkit/event/Listener;)V", true, "unregisterAll"),
            new Rule(Kind.REPLACE, "org/bukkit/scheduler/BukkitScheduler", "runTask*", null, false, "sched_*"),
            new Rule(Kind.REPLACE, "org/bukkit/scheduler/BukkitScheduler", "schedule*", null, false, "sched_*"),
            new Rule(Kind.REPLACE, "org/bukkit/scheduler/BukkitScheduler", "getMainThreadExecutor", null, false, "sched_getMainThreadExecutor"),
            new Rule(Kind.REPLACE, "org/bukkit/scheduler/BukkitScheduler", "callSyncMethod", null, false, "sched_callSyncMethod"),
            new Rule(Kind.REPLACE, "org/bukkit/scheduler/BukkitRunnable", "runTask*", null, false, "br_*"),
            new Rule(Kind.REPLACE, PAPER_SCHED + "GlobalRegionScheduler", "execute", null, false, "paper_execute"),
            new Rule(Kind.REPLACE, PAPER_SCHED + "GlobalRegionScheduler", "run*", null, false, "paper_*"),
            new Rule(Kind.REPLACE, PAPER_SCHED + "RegionScheduler", "execute", null, false, "paper_execute"),
            new Rule(Kind.REPLACE, PAPER_SCHED + "RegionScheduler", "run*", null, false, "paper_*"),
            new Rule(Kind.REPLACE, PAPER_SCHED + "EntityScheduler", "execute", null, false, "paper_execute"),
            new Rule(Kind.REPLACE, PAPER_SCHED + "EntityScheduler", "run*", null, false, "paper_*"),
            new Rule(Kind.REPLACE, PAPER_SCHED + "AsyncScheduler", "run*", null, false, "paper_*"),
            // visual leftovers: scoreboard objectives/teams, boss bars, per-player scoreboards
            new Rule(Kind.POST, "org/bukkit/scoreboard/Scoreboard", "registerNewObjective", null, false, "trackObjective"),
            new Rule(Kind.POST, "org/bukkit/scoreboard/Scoreboard", "registerNewTeam", null, false, "trackTeam"),
            new Rule(Kind.POST, "org/bukkit/Bukkit", "createBossBar", null, true, "track*"),
            new Rule(Kind.POST, "org/bukkit/Server", "createBossBar", null, false, "track*"),
            new Rule(Kind.REPLACE, "net/kyori/adventure/audience/Audience", "showBossBar",
                    "(Lnet/kyori/adventure/bossbar/BossBar;)V", false, "showBossBar"),
            new Rule(Kind.REPLACE, "org/bukkit/entity/Player", "setScoreboard", "(Lorg/bukkit/scoreboard/Scoreboard;)V",
                    false, "setScoreboard"),
            new Rule(Kind.REPLACE, "org/bukkit/command/CommandMap", "register", null, false, "registerCommand"),
            new Rule(Kind.REPLACE, "org/bukkit/command/CommandMap", "registerAll", null, false, "registerAllCommands"),
            // dangerous command lines
            new Rule(Kind.REPLACE, "org/bukkit/Bukkit", "dispatchCommand", null, true, "dispatchCommand"),
            new Rule(Kind.REPLACE, "org/bukkit/Server", "dispatchCommand", null, false, "dispatchCommand"),
            new Rule(Kind.REPLACE, "org/bukkit/entity/Player", "performCommand", null, false, "performCommand"),
            // reflection (policy at run time + module class loader semantics)
            new Rule(Kind.REPLACE, "java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;", true, "forName"),
            new Rule(Kind.REPLACE, "java/lang/Class", "forName", "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;", true, "forName"),
            new Rule(Kind.REPLACE, "java/lang/ClassLoader", "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;", false, "loadClass"),
            new Rule(Kind.REPLACE, "java/lang/reflect/Method", "invoke", null, false, "invoke"),
            new Rule(Kind.REPLACE, "java/lang/reflect/Constructor", "newInstance", null, false, "newInstance"),
            new Rule(Kind.REPLACE, "java/lang/invoke/MethodHandles$Lookup", "find*", null, false, "lookup_*"),
            new Rule(Kind.REPLACE, "java/lang/invoke/MethodHandles$Lookup", "unreflect", null, false, "lookup_unreflect"),
            new Rule(Kind.REPLACE, "org/bukkit/plugin/java/JavaPlugin", "getProvidingPlugin", null, true, "getProvidingPlugin"),
            // network / locks that would block the main thread (refused or bounded there; real call elsewhere)
            new Rule(Kind.REPLACE, "java/net/http/HttpClient", "send", null, false, "httpSend"),
            new Rule(Kind.REPLACE, "java/net/URL", "openStream", "()Ljava/io/InputStream;", false, "urlOpenStream"),
            new Rule(Kind.REPLACE, "java/net/URL", "openConnection", null, false, "urlOpenConnection"),
            new Rule(Kind.REPLACE, "java/net/URL", "getContent", null, false, "urlGetContent"),
            new Rule(Kind.REPLACE, "java/net/InetAddress", "getByName", null, true, "inetGetByName"),
            new Rule(Kind.REPLACE, "java/net/InetAddress", "getAllByName", null, true, "inetGetAllByName"),
            new Rule(Kind.REPLACE, "java/net/InetAddress", "getLocalHost", null, true, "inetGetLocalHost"),
            new Rule(Kind.REPLACE, "com/destroystokyo/paper/profile/PlayerProfile", "complete", null, false, "profileComplete"),
            new Rule(Kind.REPLACE, "java/util/concurrent/locks/Lock", "lock", "()V", false, "lockLock"),
            new Rule(Kind.REPLACE, "java/util/concurrent/locks/Lock", "lockInterruptibly", "()V", false, "lockLock"),
            new Rule(Kind.REPLACE, "java/util/concurrent/locks/Lock", "tryLock", "(JLjava/util/concurrent/TimeUnit;)Z", false, "lockTryLockTimed"),
            new Rule(Kind.REPLACE, "java/util/concurrent/locks/Condition", "await", "()V", false, "conditionAwait"),
            new Rule(Kind.REPLACE, "java/util/concurrent/locks/LockSupport", "park", null, true, "park"),
            new Rule(Kind.REPLACE, "java/util/concurrent/Future", "get", "(JLjava/util/concurrent/TimeUnit;)Ljava/lang/Object;", false, "futureGetTimed"),
            new Rule(Kind.REPLACE, "java/util/concurrent/CountDownLatch", "await", "(JLjava/util/concurrent/TimeUnit;)Z", false, "latchAwaitTimed"),
            new Rule(Kind.REPLACE, "java/util/concurrent/BlockingQueue", "put", "(Ljava/lang/Object;)V", false, "queuePut"),
            new Rule(Kind.REPLACE, "java/util/concurrent/BlockingQueue", "poll", "(JLjava/util/concurrent/TimeUnit;)Ljava/lang/Object;", false, "queuePollTimed"),
            // values that freeze or disconnect: extreme velocities, oversized item / name NBT
            new Rule(Kind.REPLACE, "org/bukkit/entity/Entity", "setVelocity", "(Lorg/bukkit/util/Vector;)V", false, "setVelocity"),
            new Rule(Kind.POST, "org/bukkit/projectiles/ProjectileSource", "launchProjectile", null, false, "clampLaunched"),
            new Rule(Kind.POST, "org/bukkit/World", "spawnArrow", null, false, "clampLaunched"),
            new Rule(Kind.REPLACE, "org/bukkit/entity/Fireball", "setDirection", "(Lorg/bukkit/util/Vector;)V", false, "fireballSetDirection"),
            new Rule(Kind.REPLACE, "org/bukkit/entity/Fireball", "setAcceleration", "(Lorg/bukkit/util/Vector;)V", false, "fireballSetAcceleration"),
            new Rule(Kind.REPLACE, "org/bukkit/entity/Fireball", "setPower", "(Lorg/bukkit/util/Vector;)V", false, "fireballSetPower"),
            new Rule(Kind.REPLACE, "org/bukkit/inventory/ItemStack", "setItemMeta", "(Lorg/bukkit/inventory/meta/ItemMeta;)Z", false, "itemSetItemMeta"),
            new Rule(Kind.REPLACE, "org/bukkit/inventory/ItemStack", "editMeta", null, false, "itemEditMeta"),
            new Rule(Kind.REPLACE, "org/bukkit/inventory/ItemStack", "lore", "(Ljava/util/List;)V", false, "itemLore"),
            new Rule(Kind.REPLACE, "org/bukkit/inventory/ItemStack", "setData", null, false, "itemSetData"),
            new Rule(Kind.REPLACE, "org/bukkit/Nameable", "customName", "(Lnet/kyori/adventure/text/Component;)V", false, "customName"),
            new Rule(Kind.REPLACE, "org/bukkit/Nameable", "setCustomName", "(Ljava/lang/String;)V", false, "setCustomName"),
            new Rule(Kind.REPLACE, "org/bukkit/entity/TextDisplay", "text", "(Lnet/kyori/adventure/text/Component;)V", false, "textDisplayText"),
            // raw recipes: same as ctx.recipe (replaces an existing key, removed on unload)
            new Rule(Kind.REPLACE, "org/bukkit/Bukkit", "addRecipe", null, true, "addRecipe"),
            new Rule(Kind.REPLACE, "org/bukkit/Server", "addRecipe", null, false, "addRecipe"),
            // registrations outside ctx that would keep old generations alive / call killed code: undone on unload
            new Rule(Kind.REPLACE, "net/kyori/adventure/text/event/ClickEvent", "callback", null, true, "clickCallback"),
            new Rule(Kind.REPLACE, "net/kyori/adventure/bossbar/BossBar", "addViewer",
                    "(Lnet/kyori/adventure/audience/Audience;)Lnet/kyori/adventure/bossbar/BossBar;", false, "bossBarAddViewer"),
            new Rule(Kind.REPLACE, "org/bukkit/plugin/ServicesManager", "register", null, false, "servicesRegister"),
            new Rule(Kind.REPLACE, "org/bukkit/plugin/messaging/Messenger", "registerIncomingPluginChannel", null, false,
                    "registerIncomingPluginChannel"),
            new Rule(Kind.REPLACE, "org/bukkit/map/MapView", "addRenderer", null, false, "mapAddRenderer"),
            new Rule(Kind.REPLACE, "org/bukkit/conversations/Conversation", "begin", "()V", false, "conversationBegin"),
            new Rule(Kind.REPLACE, "org/bukkit/conversations/Conversable", "beginConversation", null, false, "beginConversation"),
            new Rule(Kind.REPLACE, "com/destroystokyo/paper/entity/ai/MobGoals", "addGoal", null, false, "addGoal"),
            new Rule(Kind.POST, "org/bukkit/permissions/Permissible", "addAttachment", null, false, "trackPermissionAttachment"),
            new Rule(Kind.REPLACE, "java/util/logging/Logger", "addHandler", "(Ljava/util/logging/Handler;)V", false, "loggerAddHandler"),
            new Rule(Kind.REPLACE, "io/papermc/paper/event/player/AbstractChatEvent", "renderer",
                    "(Lio/papermc/paper/chat/ChatRenderer;)V", false, "chatRenderer"),
            new Rule(Kind.POST, "org/bukkit/Bukkit", "createInventory", null, true, "trackInventory"),
            new Rule(Kind.POST, "org/bukkit/Server", "createInventory", null, false, "trackInventory"),
            // other modules' tasks / listeners are off limits
            new Rule(Kind.REPLACE, "org/bukkit/scheduler/BukkitScheduler", "cancelTask", "(I)V", false, "sched_cancelTask"),
            new Rule(Kind.REPLACE, "org/bukkit/event/HandlerList", "unregister", "(Lorg/bukkit/event/Listener;)V", false,
                    "handlerListUnregister")
    );

    /**
     * Constructors whose instance is tracked right after construction ({@code new X(...)}: DUP + tracking
     * call inserted after the INVOKESPECIAL). These classes start threads inside JDK code, where the
     * Thread.start redirect cannot see them.
     */
    private static final Map<String, String[]> CTOR_TRACK = Map.of(
            "java/util/Timer", new String[] {"trackTimer", "(Ljava/util/Timer;I)V"},
            "java/util/concurrent/ThreadPoolExecutor", new String[] {"trackNewExecutor", "(Ljava/util/concurrent/ExecutorService;I)V"},
            "java/util/concurrent/ScheduledThreadPoolExecutor", new String[] {"trackNewExecutor", "(Ljava/util/concurrent/ExecutorService;I)V"},
            "java/util/concurrent/ForkJoinPool", new String[] {"trackNewExecutor", "(Ljava/util/concurrent/ExecutorService;I)V"});

    /** name+desc of every public static method of Redirects (only those redirects are applied). */
    private static final Set<String> REDIRECT_METHODS = new HashSet<>();

    static {
        for (Method m : Redirects.class.getDeclaredMethods()) {
            if (Modifier.isPublic(m.getModifiers()) && Modifier.isStatic(m.getModifiers())) {
                REDIRECT_METHODS.add(m.getName() + Type.getMethodDescriptor(m));
            }
        }
    }

    /** A matched redirect for a call site. */
    private record Redirect(Kind kind, String name, String desc) {
    }

    private static Redirect findRedirect(MethodInsnNode m, ClassHierarchy h) {
        if (m.getOpcode() == Opcodes.INVOKESPECIAL) {
            return null;
        }
        boolean isStatic = m.getOpcode() == Opcodes.INVOKESTATIC;
        for (Rule r : RULES) {
            if (r.isStatic() != isStatic || !r.nameMatches(m.name)) {
                continue;
            }
            if (r.desc() != null && !r.desc().equals(m.desc)) {
                continue;
            }
            if (isStatic ? !r.owner().equals(m.owner) : !(m.owner.equals(r.owner()) || h.isSubtype(m.owner, r.owner()))) {
                continue;
            }
            Type mt = Type.getMethodType(m.desc);
            String name = r.targetName(m.name);
            String desc;
            if (r.kind() == Kind.POST) {
                Type ret = mt.getReturnType();
                if (ret.getSort() != Type.OBJECT) {
                    continue;
                }
                if (r.target().endsWith("*")) {
                    // Executors.newXxx(...) -> trackExecutorService / trackScheduledExecutorService
                    String simple = ret.getInternalName().substring(ret.getInternalName().lastIndexOf('/') + 1);
                    name = r.target().substring(0, r.target().length() - 1) + simple;
                }
                desc = Type.getMethodDescriptor(ret, ret, Type.INT_TYPE);
            } else {
                List<Type> args = new ArrayList<>();
                if (!isStatic) {
                    args.add(Type.getObjectType(r.owner()));
                }
                args.addAll(List.of(mt.getArgumentTypes()));
                args.add(Type.INT_TYPE);
                desc = Type.getMethodDescriptor(mt.getReturnType(), args.toArray(new Type[0]));
            }
            if (REDIRECT_METHODS.contains(name + desc)) {
                return new Redirect(r.kind(), name, desc);
            }
        }
        return null;
    }

    // ================================================================== instrumentation

    /**
     * Instrument every class.
     *
     * @param classes internal name to original bytecode
     * @return binary name ({@code a.b.C}) to instrumented bytecode
     */
    public static Map<String, byte[]> instrument(Map<String, byte[]> classes, int genId, ClassHierarchy h) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : classes.entrySet()) {
            out.put(e.getKey().replace('/', '.'), instrumentClass(e.getValue(), genId, h));
        }
        return out;
    }

    static byte[] instrumentClass(byte[] bytes, int genId, ClassHierarchy h) {
        ClassReader cr = new ClassReader(bytes);
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);
        for (MethodNode mn : cn.methods) {
            if (mn.instructions == null || mn.instructions.size() == 0) {
                continue; // abstract / native
            }
            instrumentMethod(mn, genId, h, cn.superName);
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                return h.commonSuperClass(type1, type2);
            }
        };
        cn.accept(cw);
        return cw.toByteArray();
    }

    private static AbstractInsnNode idConst(int id) {
        return new LdcInsnNode(id);
    }

    private static InsnList checkCall(int id) {
        InsnList l = new InsnList();
        l.add(idConst(id));
        l.add(new MethodInsnNode(Opcodes.INVOKESTATIC, GUARD, "check", "(I)V", false));
        return l;
    }

    private static void instrumentMethod(MethodNode mn, int id, ClassHierarchy h, String superName) {
        InsnList insns = mn.instructions;
        // index of every instruction (before modification) for back-edge detection
        Map<AbstractInsnNode, Integer> index = new HashMap<>();
        int i = 0;
        for (AbstractInsnNode n = insns.getFirst(); n != null; n = n.getNext()) {
            index.put(n, i++);
        }
        List<AbstractInsnNode> backEdges = new ArrayList<>();
        List<MethodInsnNode> calls = new ArrayList<>();
        for (AbstractInsnNode n = insns.getFirst(); n != null; n = n.getNext()) {
            int at = index.get(n);
            if (n instanceof JumpInsnNode j) {
                if (index.get(j.label) <= at) {
                    backEdges.add(n);
                }
            } else if (n instanceof TableSwitchInsnNode t) {
                if (anyBackward(t.dflt, t.labels, index, at)) {
                    backEdges.add(n);
                }
            } else if (n instanceof LookupSwitchInsnNode t) {
                if (anyBackward(t.dflt, t.labels, index, at)) {
                    backEdges.add(n);
                }
            } else if (n instanceof MethodInsnNode m) {
                calls.add(m);
            }
        }
        for (AbstractInsnNode n : backEdges) {
            insns.insertBefore(n, checkCall(id));
        }
        for (MethodInsnNode m : calls) {
            if (m.getOpcode() == Opcodes.INVOKESPECIAL && m.name.equals("<init>")) {
                String[] t = CTOR_TRACK.get(m.owner);
                // in a constructor of a subclass, X.<init> may be the super() call (no reference left on the stack)
                if (t != null && !(mn.name.equals("<init>") && m.owner.equals(superName))) {
                    InsnList post = new InsnList();
                    post.add(new InsnNode(Opcodes.DUP));
                    post.add(idConst(id));
                    post.add(new MethodInsnNode(Opcodes.INVOKESTATIC, REDIRECTS, t[0], t[1], false));
                    insns.insert(m, post);
                }
                continue;
            }
            Redirect r = findRedirect(m, h);
            if (r == null) {
                continue;
            }
            if (r.kind() == Kind.REPLACE) {
                insns.insertBefore(m, idConst(id));
                insns.set(m, new MethodInsnNode(Opcodes.INVOKESTATIC, REDIRECTS, r.name(), r.desc(), false));
            } else {
                InsnList post = new InsnList();
                post.add(idConst(id));
                post.add(new MethodInsnNode(Opcodes.INVOKESTATIC, REDIRECTS, r.name(), r.desc(), false));
                insns.insert(m, post);
            }
        }
        // constructors keep the throwing entry check (returning before super() would not verify) and so do
        // static initializers; every other method gets "if (Guard.enter(id)) return <default>;"
        if (mn.name.equals("<init>") || mn.name.equals("<clinit>")) {
            AbstractInsnNode first = insns.getFirst();
            while (first != null && first.getOpcode() < 0) {
                first = first.getNext();
            }
            if (first != null) {
                insns.insertBefore(first, checkCall(id));
            } else {
                insns.insert(checkCall(id));
            }
        } else {
            insertEntry(mn, id);
        }
    }

    /**
     * Method entry: {@code if (Guard.enter(id)) return <default value>;} inserted at the very start.
     * The jump target is the original offset 0: it reuses the method's own first stack map frame if
     * it has one there (a loop starting the method), otherwise an F_SAME frame (= the implicit initial
     * frame, so later delta-encoded frames stay valid) is added.
     */
    private static void insertEntry(MethodNode mn, int id) {
        InsnList insns = mn.instructions;
        LabelNode existing = null;
        boolean hasFrame = false;
        int firstLine = -1;
        for (AbstractInsnNode n = insns.getFirst(); n != null && n.getOpcode() < 0; n = n.getNext()) {
            if (n instanceof LabelNode l) {
                existing = l;
            } else if (n instanceof FrameNode) {
                hasFrame = true;
            } else if (n instanceof LineNumberNode ln && firstLine < 0) {
                firstLine = ln.line;
            }
        }
        if (firstLine < 0) {
            for (AbstractInsnNode n = insns.getFirst(); n != null; n = n.getNext()) {
                if (n instanceof LineNumberNode ln) {
                    firstLine = ln.line;
                    break;
                }
            }
        }
        InsnList pre = new InsnList();
        if (firstLine > 0) {
            LabelNode start = new LabelNode();
            pre.add(start);
            pre.add(new LineNumberNode(firstLine, start));
        }
        pre.add(idConst(id));
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, GUARD, "enter", "(I)Z", false));
        LabelNode cont = hasFrame && existing != null ? existing : new LabelNode();
        pre.add(new JumpInsnNode(Opcodes.IFEQ, cont));
        Type ret = Type.getReturnType(mn.desc);
        switch (ret.getSort()) {
            case Type.VOID -> pre.add(new InsnNode(Opcodes.RETURN));
            case Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> {
                pre.add(new InsnNode(Opcodes.ICONST_0));
                pre.add(new InsnNode(Opcodes.IRETURN));
            }
            case Type.LONG -> {
                pre.add(new InsnNode(Opcodes.LCONST_0));
                pre.add(new InsnNode(Opcodes.LRETURN));
            }
            case Type.FLOAT -> {
                pre.add(new InsnNode(Opcodes.FCONST_0));
                pre.add(new InsnNode(Opcodes.FRETURN));
            }
            case Type.DOUBLE -> {
                pre.add(new InsnNode(Opcodes.DCONST_0));
                pre.add(new InsnNode(Opcodes.DRETURN));
            }
            default -> {
                pre.add(new InsnNode(Opcodes.ACONST_NULL));
                pre.add(new InsnNode(Opcodes.ARETURN));
            }
        }
        if (cont != existing) {
            pre.add(cont);
            pre.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        }
        insns.insert(pre);
    }

    private static boolean anyBackward(LabelNode dflt, List<LabelNode> labels, Map<AbstractInsnNode, Integer> index, int at) {
        if (index.get(dflt) <= at) {
            return true;
        }
        for (LabelNode l : labels) {
            if (index.get(l) <= at) {
                return true;
            }
        }
        return false;
    }

    // ================================================================== policy scan

    /**
     * Scan original module bytecode for forbidden calls/types.
     *
     * @param classes  internal name to bytecode
     * @param sourceOf internal name to module-relative source path (may miss entries)
     */
    public static List<Diagnostic> scan(Map<String, byte[]> classes, Map<String, String> sourceOf, ClassHierarchy h) {
        List<Diagnostic> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Map.Entry<String, byte[]> e : classes.entrySet()) {
            ClassNode cn = new ClassNode();
            new ClassReader(e.getValue()).accept(cn, ClassReader.SKIP_FRAMES);
            String file = sourceOf.getOrDefault(cn.name, cn.sourceFile == null ? "" : cn.sourceFile);
            Scan s = new Scan(file, out, seen, h);
            s.type(cn.superName, -1);
            String sup = Policy.forbiddenSupertype(cn.name, h);
            if (sup != null) {
                s.report(sup, -1);
            }
            if (cn.interfaces != null) {
                for (String itf : cn.interfaces) {
                    s.type(itf, -1);
                }
            }
            if (cn.fields != null) {
                for (FieldNode f : cn.fields) {
                    s.desc(f.desc, -1);
                }
            }
            for (MethodNode mn : cn.methods) {
                s.method(mn);
            }
        }
        return out;
    }

    private static final class Scan {
        final String file;
        final List<Diagnostic> out;
        final Set<String> seen;
        final ClassHierarchy h;

        Scan(String file, List<Diagnostic> out, Set<String> seen, ClassHierarchy h) {
            this.file = file;
            this.out = out;
            this.seen = seen;
            this.h = h;
        }

        void report(String reason, int line) {
            if (out.size() >= 50) {
                return;
            }
            if (seen.add(file + ":" + line + ":" + reason)) {
                out.add(new Diagnostic("ERROR", file, line, -1, "forbidden: " + reason, ""));
            }
        }

        void type(String internalName, int line) {
            if (internalName == null) {
                return;
            }
            String r = Policy.forbiddenType(internalName);
            if (r != null) {
                report(r, line);
            }
        }

        void desc(String desc, int line) {
            if (desc == null) {
                return;
            }
            Type t = Type.getType(desc);
            if (t.getSort() == Type.METHOD) {
                for (Type a : t.getArgumentTypes()) {
                    descType(a, line);
                }
                descType(t.getReturnType(), line);
            } else {
                descType(t, line);
            }
        }

        void descType(Type t, int line) {
            if (t.getSort() == Type.ARRAY) {
                t = t.getElementType();
            }
            if (t.getSort() == Type.OBJECT) {
                type(t.getInternalName(), line);
            }
        }

        void call(String owner, String name, String desc, int line) {
            String r = Policy.forbiddenCall(owner, name, desc, h);
            if (r != null) {
                report(r, line);
            }
            desc(desc, line);
        }

        void handle(Handle hd, int line) {
            call(hd.getOwner(), hd.getName(), hd.getDesc(), line);
        }

        void constant(Object c, int line) {
            if (c instanceof Type t) {
                if (t.getSort() == Type.METHOD) {
                    desc(t.getDescriptor(), line);
                } else {
                    descType(t, line);
                }
            } else if (c instanceof Handle hd) {
                handle(hd, line);
            } else if (c instanceof ConstantDynamic cd) {
                handle(cd.getBootstrapMethod(), line);
                desc(cd.getDescriptor(), line);
                for (int i = 0; i < cd.getBootstrapMethodArgumentCount(); i++) {
                    constant(cd.getBootstrapMethodArgument(i), line);
                }
            }
        }

        void method(MethodNode mn) {
            int firstLine = -1;
            for (AbstractInsnNode n = mn.instructions.getFirst(); n != null; n = n.getNext()) {
                if (n instanceof LineNumberNode ln) {
                    firstLine = ln.line;
                    break;
                }
            }
            desc(mn.desc, firstLine);
            if (mn.exceptions != null) {
                for (String ex : mn.exceptions) {
                    type(ex, firstLine);
                }
            }
            int line = firstLine;
            for (AbstractInsnNode n = mn.instructions.getFirst(); n != null; n = n.getNext()) {
                if (n instanceof LineNumberNode ln) {
                    line = ln.line;
                } else if (n instanceof MethodInsnNode m) {
                    if (!m.owner.startsWith("[")) {
                        type(m.owner, line);
                    }
                    call(m.owner, m.name, m.desc, line);
                } else if (n instanceof FieldInsnNode f) {
                    String fr = Policy.forbiddenField(f.owner, f.name);
                    if (fr != null) {
                        report(fr, line);
                    }
                    type(f.owner, line);
                    desc(f.desc, line);
                } else if (n instanceof TypeInsnNode t) {
                    if (t.desc.startsWith("[")) {
                        desc(t.desc, line);
                    } else {
                        type(t.desc, line);
                    }
                } else if (n instanceof MultiANewArrayInsnNode m) {
                    desc(m.desc, line);
                } else if (n instanceof LdcInsnNode l) {
                    constant(l.cst, line);
                } else if (n instanceof InvokeDynamicInsnNode d) {
                    desc(d.desc, line);
                    handle(d.bsm, line);
                    if (d.bsmArgs != null) {
                        for (Object a : d.bsmArgs) {
                            constant(a, line);
                        }
                    }
                }
            }
            if (mn.tryCatchBlocks != null) {
                for (TryCatchBlockNode t : mn.tryCatchBlocks) {
                    type(t.type, firstLine);
                }
            }
            if (mn.localVariables != null) {
                for (LocalVariableNode lv : mn.localVariables) {
                    desc(lv.desc, firstLine);
                }
            }
        }
    }

    // ================================================================== module class discovery

    /** Result of looking for the VibeModule implementation. */
    public record EntryPoint(String className, List<Diagnostic> problems) {
    }

    /**
     * Find exactly one concrete public class implementing VibeModule with a public no-arg
     * constructor.
     *
     * @return the binary class name, or null with problems filled in
     */
    public static EntryPoint findEntryPoint(Map<String, byte[]> classes, Map<String, String> sourceOf, ClassHierarchy h) {
        List<String> good = new ArrayList<>();
        List<Diagnostic> problems = new ArrayList<>();
        for (Map.Entry<String, byte[]> e : classes.entrySet()) {
            ClassReader r = new ClassReader(e.getValue());
            int acc = r.getAccess();
            String name = r.getClassName();
            if ((acc & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT)) != 0 || !h.isSubtype(name, VIBE_MODULE)) {
                continue;
            }
            String file = sourceOf.getOrDefault(name, "");
            String simple = name.substring(name.lastIndexOf('/') + 1);
            if ((acc & Opcodes.ACC_PUBLIC) == 0) {
                problems.add(new Diagnostic("ERROR", file, -1, -1,
                        "class " + simple + " implements VibeModule but is not public", ""));
                continue;
            }
            ClassNode cn = new ClassNode();
            r.accept(cn, ClassReader.SKIP_CODE);
            boolean ctor = false;
            for (MethodNode m : cn.methods) {
                if (m.name.equals("<init>") && m.desc.equals("()V") && (m.access & Opcodes.ACC_PUBLIC) != 0) {
                    ctor = true;
                }
            }
            if (!ctor) {
                problems.add(new Diagnostic("ERROR", file, -1, -1,
                        "class " + simple + " implements VibeModule but has no public no-arg constructor", ""));
                continue;
            }
            good.add(name);
        }
        if (good.size() == 1) {
            return new EntryPoint(good.get(0).replace('/', '.'), List.of());
        }
        if (good.isEmpty()) {
            if (problems.isEmpty()) {
                problems.add(new Diagnostic("ERROR", "", -1, -1,
                        "no module class: exactly one public class must implement dev.vibecraft.api.VibeModule "
                                + "(with a public no-arg constructor)", ""));
            }
            return new EntryPoint(null, problems);
        }
        List<String> names = new ArrayList<>();
        for (String g : good) {
            names.add(g.replace('/', '.'));
        }
        problems.add(new Diagnostic("ERROR", sourceOf.getOrDefault(good.get(1), ""), -1, -1,
                "several classes implement VibeModule " + names + ": a module must have exactly one", ""));
        return new EntryPoint(null, problems);
    }
}
