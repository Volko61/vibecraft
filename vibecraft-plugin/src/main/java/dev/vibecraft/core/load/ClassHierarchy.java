package dev.vibecraft.core.load;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import dev.vibecraft.core.guard.Policy;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;

/**
 * Type hierarchy over a module's own (not yet defined) classes plus everything visible from the
 * parent class loader. Used for policy checks, redirect matching and ASM frame computation.
 */
public final class ClassHierarchy implements Policy.Hierarchy {

    /** Header of a class: super class, interfaces, interface flag (internal names). */
    public record Info(String name, String superName, List<String> interfaces, boolean isInterface) {
    }

    private static final Info MISSING = new Info("", null, List.of(), false);

    private final Map<String, byte[]> moduleClasses; // internal name -> bytes
    private final ClassLoader parent;
    private final Map<String, Info> cache = new ConcurrentHashMap<>();

    /**
     * @param moduleClasses internal name ({@code a/b/C}) to bytecode
     */
    public ClassHierarchy(Map<String, byte[]> moduleClasses, ClassLoader parent) {
        this.moduleClasses = moduleClasses;
        this.parent = parent;
    }

    public boolean isModuleClass(String internalName) {
        return moduleClasses.containsKey(internalName);
    }

    /** Header info, or null if the class cannot be resolved. */
    public Info info(String internalName) {
        if (internalName == null) {
            return null;
        }
        Info i = cache.computeIfAbsent(internalName, this::resolve);
        return i == MISSING ? null : i;
    }

    private Info resolve(String n) {
        byte[] b = moduleClasses.get(n);
        if (b != null) {
            ClassReader r = new ClassReader(b);
            return new Info(n, r.getSuperName(), List.of(r.getInterfaces()), (r.getAccess() & Opcodes.ACC_INTERFACE) != 0);
        }
        try {
            Class<?> c = Class.forName(n.replace('/', '.'), false, parent);
            List<String> itfs = new ArrayList<>();
            for (Class<?> i : c.getInterfaces()) {
                itfs.add(i.getName().replace('.', '/'));
            }
            String sup = c.getSuperclass() == null ? null : c.getSuperclass().getName().replace('.', '/');
            return new Info(n, sup, itfs, c.isInterface());
        } catch (Throwable t) {
            return MISSING;
        }
    }

    /** Is {@code type} equal to or a subtype of {@code superType}? (internal names) */
    @Override
    public boolean isSubtype(String type, String superType) {
        if (type == null || superType == null) {
            return false;
        }
        if (type.equals(superType)) {
            return true;
        }
        if (superType.equals("java/lang/Object")) {
            return true;
        }
        Deque<String> todo = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        todo.add(type);
        while (!todo.isEmpty()) {
            String t = todo.poll();
            if (!seen.add(t)) {
                continue;
            }
            if (t.equals(superType)) {
                return true;
            }
            Info i = info(t);
            if (i == null) {
                continue;
            }
            if (i.superName() != null) {
                todo.add(i.superName());
            }
            todo.addAll(i.interfaces());
        }
        return false;
    }

    /** For ASM COMPUTE_FRAMES: closest common super class of two types. */
    public String commonSuperClass(String a, String b) {
        if (isSubtype(a, b)) {
            return b;
        }
        if (isSubtype(b, a)) {
            return a;
        }
        Info ia = info(a);
        Info ib = info(b);
        if (ia == null || ib == null || ia.isInterface() || ib.isInterface()) {
            return "java/lang/Object";
        }
        String t = a;
        while (t != null) {
            Info i = info(t);
            if (i == null) {
                return "java/lang/Object";
            }
            t = i.superName();
            if (t != null && isSubtype(b, t)) {
                return t;
            }
        }
        return "java/lang/Object";
    }
}
