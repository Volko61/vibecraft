package dev.vibecraft.core.load;

import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Class loader of one module generation. Defines the (instrumented) module classes from memory;
 * everything else is delegated to the parent (the VibeCraft plugin class loader). Module classes
 * are looked up child-first so a module can never be shadowed by a parent class of the same name.
 */
public final class ModuleClassLoader extends ClassLoader {

    static {
        registerAsParallelCapable();
    }

    private final String moduleId;
    private final Map<String, byte[]> classes;
    private final ProtectionDomain domain;

    /**
     * @param classes binary class name ({@code a.b.C$D}) to bytecode
     */
    public ModuleClassLoader(String name, String moduleId, Map<String, byte[]> classes, ClassLoader parent) {
        super(name, parent);
        this.moduleId = moduleId;
        this.classes = new ConcurrentHashMap<>(classes);
        this.domain = new ProtectionDomain(new CodeSource(null, (Certificate[]) null), null, this, null);
    }

    public String moduleId() {
        return moduleId;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                byte[] b = classes.get(name);
                if (b != null) {
                    c = defineClass(name, b, 0, b.length, domain);
                    classes.remove(name); // bytes no longer needed
                } else {
                    c = getParent().loadClass(name);
                }
            }
            if (resolve) {
                resolveClass(c);
            }
            return c;
        }
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        byte[] b = classes.get(name);
        if (b == null) {
            throw new ClassNotFoundException(name);
        }
        return defineClass(name, b, 0, b.length, domain);
    }
}
