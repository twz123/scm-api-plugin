/*
 * The MIT License
 *
 * Copyright (c) 2016 CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 */

package jenkins.scm.api;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.WeakHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import jenkins.scm.api.mixin.SCMHeadMixin;
import net.jcip.annotations.GuardedBy;
import org.apache.commons.lang.ClassUtils;
import org.kohsuke.asm5.ClassWriter;
import org.kohsuke.asm5.Label;
import org.kohsuke.asm5.MethodVisitor;
import org.kohsuke.asm5.Opcodes;
import org.kohsuke.asm5.Type;

import static org.kohsuke.asm5.Opcodes.ACC_PUBLIC;
import static org.kohsuke.asm5.Opcodes.ALOAD;
import static org.kohsuke.asm5.Opcodes.ASTORE;
import static org.kohsuke.asm5.Opcodes.CHECKCAST;
import static org.kohsuke.asm5.Opcodes.DCMPL;
import static org.kohsuke.asm5.Opcodes.DLOAD;
import static org.kohsuke.asm5.Opcodes.DSTORE;
import static org.kohsuke.asm5.Opcodes.FCMPL;
import static org.kohsuke.asm5.Opcodes.FLOAD;
import static org.kohsuke.asm5.Opcodes.FSTORE;
import static org.kohsuke.asm5.Opcodes.GOTO;
import static org.kohsuke.asm5.Opcodes.ICONST_0;
import static org.kohsuke.asm5.Opcodes.ICONST_1;
import static org.kohsuke.asm5.Opcodes.IFEQ;
import static org.kohsuke.asm5.Opcodes.IFNE;
import static org.kohsuke.asm5.Opcodes.IFNONNULL;
import static org.kohsuke.asm5.Opcodes.IFNULL;
import static org.kohsuke.asm5.Opcodes.IF_ICMPEQ;
import static org.kohsuke.asm5.Opcodes.ILOAD;
import static org.kohsuke.asm5.Opcodes.INVOKEINTERFACE;
import static org.kohsuke.asm5.Opcodes.INVOKESPECIAL;
import static org.kohsuke.asm5.Opcodes.INVOKEVIRTUAL;
import static org.kohsuke.asm5.Opcodes.IRETURN;
import static org.kohsuke.asm5.Opcodes.ISTORE;
import static org.kohsuke.asm5.Opcodes.LCMP;
import static org.kohsuke.asm5.Opcodes.LLOAD;
import static org.kohsuke.asm5.Opcodes.LSTORE;
import static org.kohsuke.asm5.Opcodes.RETURN;

/**
 * Generates {@link SCMHeadMixin.Equality} instances for concrete {@link SCMHead} instance types.
 * We need {@link SCMHead} instances to perform equality based on the {@link SCMHead#getName()} plus all the property
 * values declared on the {@link SCMHeadMixin} interfaces implemented by the {@link SCMHead} concrete type.
 * As {@link SCMHead#equals(Object)} is expected to be a hot method, we'd much rather avoid using reflection, so
 * instead we use bytecode generation to create our {@link SCMHeadMixin.Equality} subclass for us.
 *
 * @since 2.0
 */
class SCMHeadMixinEqualityGenerator extends ClassLoader {
    /**
     * Lock to guard access to the maps.
     */
    private static final ReadWriteLock lock = new ReentrantReadWriteLock();
    /**
     * Weak hashmap of equality generators for each classloader.
     */
    @GuardedBy("lock")
    private static final Map<ClassLoader, SCMHeadMixinEqualityGenerator> generators
            = new WeakHashMap<ClassLoader, SCMHeadMixinEqualityGenerator>();
    /**
     * Weak hashmap of the {@link SCMHeadMixin.Equality} instances keyed by the concrete type that requires them.
     */
    @GuardedBy("lock")
    private static final WeakHashMap<Class<? extends SCMHead>, SCMHeadMixin.Equality> mixinEqualities
            = new WeakHashMap<Class<? extends SCMHead>, SCMHeadMixin.Equality>();

    /**
     * Get the {@link SCMHeadMixin.Equality} instance to use.
     *
     * @param type the {@link SCMHead} type.
     * @return the {@link SCMHeadMixin.Equality} instance.
     */
    @NonNull
    static SCMHeadMixin.Equality getOrCreate(@NonNull Class<? extends SCMHead> type) {
        lock.readLock().lock();
        try {
            SCMHeadMixin.Equality result = mixinEqualities.get(type);
            if (result != null) {
                return result;
            }
        } finally {
            lock.readLock().unlock();
        }
        lock.writeLock().lock();
        try {
            SCMHeadMixin.Equality result = mixinEqualities.get(type);
            if (result != null) {
                // somebody else created it while we were waiting for the write lock
                return result;
            }
            final ClassLoader loader = type.getClassLoader();
            SCMHeadMixinEqualityGenerator generator;
            generator = generators.get(loader);
            if (generator == null) {
                generator = AccessController.doPrivileged(new PrivilegedAction<SCMHeadMixinEqualityGenerator>() {
                    @Override
                    public SCMHeadMixinEqualityGenerator run() {
                        return new SCMHeadMixinEqualityGenerator(loader);
                    }
                });
                generators.put(loader, generator);
            }
            result = generator.create(type);
            mixinEqualities.put(type, result);
            return result;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Constructor.
     *
     * @param parent the parent classloader.
     */
    private SCMHeadMixinEqualityGenerator(@NonNull ClassLoader parent) {
        super(parent);
    }

    /**
     * Creates the {@link SCMHeadMixin.Equality} instance.
     *
     * @param type the {@link SCMHead} type to create the instance for.
     * @return the {@link SCMHeadMixin.Equality} instance.
     */
    @NonNull
    private SCMHeadMixin.Equality create(@NonNull Class<? extends SCMHead> type) {
        Map<String, Method> properties = new TreeMap<String, Method>();
        for (Class clazz : (List<Class>) ClassUtils.getAllInterfaces(type)) {
            if (!SCMHeadMixin.class.isAssignableFrom(clazz)) {
                // not a mix-in
                continue;
            }
            if (SCMHeadMixin.class == clazz) {
                // no need to check this by reflection
                continue;
            }
            if (!Modifier.isPublic(clazz.getModifiers())) {
                // not public
                continue;
            }
            // this is a mixin interface, only look at declared properties;
            for (Method method : clazz.getDeclaredMethods()) {
                if (method.getReturnType() == Void.class) {
                    // nothing to do with us
                    continue;
                }
                if (!Modifier.isPublic(method.getModifiers())) {
                    // should never get here
                    continue;
                }
                if (Modifier.isStatic(method.getModifiers())) {
                    // might get here with Java 8
                    continue;
                }
                if (method.getParameterTypes().length != 0) {
                    // not a property
                    continue;
                }
                String name = method.getName();
                if (!name.matches("^((is[A-Z0-9_].*)|(get[A-Z0-9_].*))$")) {
                    // not a property
                    continue;
                }
                if (name.startsWith("is")) {
                    name = "" + Character.toLowerCase(name.charAt(2)) + (name.length() > 3 ? name.substring(3) : "");
                } else {
                    name = "" + Character.toLowerCase(name.charAt(3)) + (name.length() > 4 ? name.substring(4) : "");
                }
                if (properties.containsKey(name)) {
                    // a higher priority interface already defined the method
                    continue;
                }
                properties.put(name, method);
            }
        }
        if (properties.isEmpty()) {
            // no properties to consider
            return new ConstantEquality();
        }
        // now we define the class
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        String name = SCMHeadMixin.class.getPackage().getName() + ".internal." + type.getName();
        cw.visit(Opcodes.V1_7, ACC_PUBLIC, name.replace('.', '/'), null, Type
                .getInternalName(Object.class), new String[]{Type.getInternalName(SCMHeadMixin.Equality.class)});
        generateDefaultConstructor(cw);
        generateEquals(cw, properties.values());
        byte[] image = cw.toByteArray();

        Class<? extends SCMHeadMixin.Equality> c = defineClass(name, image, 0, image.length).asSubclass(
                SCMHeadMixin.Equality.class);

        try {
            return c.newInstance();
        } catch (InstantiationException e) {
            // fallback to reflection
        } catch (IllegalAccessException e) {
            // fallback to reflection
        }
        return new ReflectiveEquality(properties.values().toArray(new Method[properties.size()]));

    }

    /**
     * Generates {@link SCMHeadMixin.Equality#equals(SCMHeadMixin, SCMHeadMixin)}.
     *
     * @param cw      the {@link ClassWriter}
     * @param methods the property getters.
     */
    private void generateEquals(ClassWriter cw, Collection<Method> methods) {
        String scmHeadMixinDescriptor = Type.getDescriptor(SCMHeadMixin.class);
        MethodVisitor mv = cw.visitMethod(
                ACC_PUBLIC,
                "equals",
                "("+ scmHeadMixinDescriptor + scmHeadMixinDescriptor +")Z",
                null,
                null
        );
        mv.visitCode();
        boolean bigStack = false;
        for (Method m : methods) {
            String declClass = Type.getInternalName(m.getDeclaringClass());
            Class<?> returnType = m.getReturnType();
            String methodDesc = "()" + Type.getDescriptor(returnType);
            if (boolean.class.equals(returnType)
                    || byte.class.equals(returnType)
                    || char.class.equals(returnType)
                    || int.class.equals(returnType)
                    || short.class.equals(returnType)) {
                // all these primitive types are
                // int p1 = ((T)o1).get___();
                mv.visitVarInsn(ALOAD, 1);
                mv.visitTypeInsn(CHECKCAST, declClass);
                mv.visitMethodInsn(INVOKEINTERFACE, declClass, m.getName(), methodDesc, true);
                mv.visitVarInsn(ISTORE, 3);
                // int p2 = ((T)o2).get___();
                mv.visitVarInsn(ALOAD, 2);
                mv.visitTypeInsn(CHECKCAST, declClass);
                mv.visitMethodInsn(INVOKEINTERFACE, declClass, m.getName(), methodDesc, true);
                // if (p2 != p1) return false;
                mv.visitVarInsn(ILOAD, 3);
                Label l1 = new Label();
                mv.visitJumpInsn(IF_ICMPEQ, l1);
                mv.visitInsn(ICONST_0);
                mv.visitInsn(IRETURN);
                mv.visitLabel(l1);
            } else if (long.class.equals(returnType)) {
                bigStack = true;
                // long p1 = ((T)o1).get___();
                mv.visitVarInsn(ALOAD, 1);
                mv.visitTypeInsn(CHECKCAST, declClass);
                mv.visitMethodInsn(INVOKEINTERFACE, declClass, m.getName(), methodDesc, true);
                mv.visitVarInsn(LSTORE, 3);
                // long p2 = ((T)o2).get___();
                mv.visitVarInsn(ALOAD, 2);
                mv.visitTypeInsn(CHECKCAST, declClass);
                mv.visitMethodInsn(INVOKEINTERFACE, declClass, m.getName(), methodDesc, true);
                mv.visitVarInsn(LSTORE, 5);
                // if (p2 != p1) return false;
                mv.visitVarInsn(LLOAD, 3);
                mv.visitVarInsn(LLOAD, 5);
                mv.visitInsn(LCMP);
                Label l1 = new Label();
                mv.visitJumpInsn(IFEQ, l1);
                mv.visitInsn(ICONST_0);
                mv.visitInsn(IRETURN);
                mv.visitLabel(l1);
            } else if (double.class.equals(returnType)) {
                // not expecting people to return floating point types from SCMHeadMixin properties
                // here for completeness but will compare for strict equality so should blow up in peoples faces
                // if they are not persisting the floating points correctly
                bigStack = true;
                // double p1 = ((T)o1).get___();
                mv.visitVarInsn(ALOAD, 1);
                mv.visitTypeInsn(CHECKCAST, declClass);
                mv.visitMethodInsn(INVOKEINTERFACE, declClass, m.getName(), methodDesc, true);
                mv.visitVarInsn(DSTORE, 3);
                // double p2 = ((T)o2).get___();
                mv.visitVarInsn(ALOAD, 2);
                mv.visitTypeInsn(CHECKCAST, declClass);
                mv.visitMethodInsn(INVOKEINTERFACE, declClass, m.getName(), methodDesc, true);
                mv.visitVarInsn(DSTORE, 5);
                // if (p2 != p1) return false;
                mv.visitVarInsn(DLOAD, 3);
                mv.visitVarInsn(DLOAD, 5);
                mv.visitInsn(DCMPL); // HA HA HA this will likely not work for you
                Label l1 = new Label();
                mv.visitJumpInsn(IFEQ, l1);
                mv.visitInsn(ICONST_0);
                mv.visitInsn(IRETURN);
                mv.visitLabel(l1);
            } else if (float.class.equals(returnType)) {
                // not expecting people to return floating point types from SCMHeadMixin properties
                // here for completeness but will compare for strict equality so should blow up in peoples faces
                // if they are not persisting the floating points correctly

                // float p1 = ((T)o1).get___();
                mv.visitVarInsn(ALOAD, 1);
                mv.visitTypeInsn(CHECKCAST, declClass);
                mv.visitMethodInsn(INVOKEINTERFACE, declClass, m.getName(), methodDesc, true);
                mv.visitVarInsn(FSTORE, 3);
                // float p2 = ((T)o2).get___();
                mv.visitVarInsn(ALOAD, 2);
                mv.visitTypeInsn(CHECKCAST, declClass);
                mv.visitMethodInsn(INVOKEINTERFACE, declClass, m.getName(), methodDesc, true);
                mv.visitVarInsn(FSTORE, 5);
                // if (p2 != p1) return false;
                mv.visitVarInsn(FLOAD, 3);
                mv.visitVarInsn(FLOAD, 5);
                mv.visitInsn(FCMPL); // HA HA HA this will likely not work for you
                Label l1 = new Label();
                mv.visitJumpInsn(IFEQ, l1);
                mv.visitInsn(ICONST_0);
                mv.visitInsn(IRETURN);
                mv.visitLabel(l1);
            } else {
                // Object p1 = ((T)o1).get___();
                mv.visitVarInsn(ALOAD, 1);
                mv.visitTypeInsn(CHECKCAST, declClass);
                mv.visitMethodInsn(INVOKEINTERFACE, declClass, m.getName(), methodDesc, true);
                mv.visitVarInsn(ASTORE, 3);
                // Object p2 = ((T)o2).get___();
                mv.visitVarInsn(ALOAD, 2);
                mv.visitTypeInsn(CHECKCAST, declClass);
                mv.visitMethodInsn(INVOKEINTERFACE, declClass, m.getName(), methodDesc, true);
                mv.visitVarInsn(ASTORE, 4);
                // if (p1 == null ? p2 != null : !p1.equals(p2)) return false;
                mv.visitVarInsn(ALOAD, 3);
                Label l1 = new Label();
                Label l2 = new Label();
                Label l3 = new Label();
                mv.visitJumpInsn(IFNONNULL, l1);
                mv.visitVarInsn(ALOAD, 4);
                mv.visitJumpInsn(IFNULL, l3);
                mv.visitJumpInsn(GOTO, l2);
                mv.visitLabel(l1);
                mv.visitVarInsn(ALOAD, 4);
                mv.visitVarInsn(ALOAD, 3);
                mv.visitMethodInsn(INVOKEVIRTUAL, Type.getInternalName(Object.class), "equals", "(Ljava/lang/Object;)Z",
                        false);
                mv.visitJumpInsn(IFNE, l3);
                mv.visitLabel(l2);
                mv.visitInsn(ICONST_0);
                mv.visitInsn(IRETURN);
                mv.visitLabel(l3);
            }
        }
        // return true
        mv.visitInsn(ICONST_1);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(bigStack ? 4 : 2, bigStack ? 7 : 5);
        mv.visitEnd();
    }

    /**
     * Generates the default contstructor.
     *
     * @param cw the {@link ClassWriter}.
     */
    private void generateDefaultConstructor(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, Type.getInternalName(Object.class), "<init>", "()V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
    }

    /**
     * {@link SCMHeadMixin.Equality} to use when there are no properties to consider.
     */
    private static class ConstantEquality implements SCMHeadMixin.Equality {

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean equals(SCMHeadMixin o1, SCMHeadMixin o2) {
            return true;
        }
    }

    /**
     * {@link SCMHeadMixin.Equality} to use when bytecode generation fails.
     */
    private static class ReflectiveEquality implements SCMHeadMixin.Equality {
        /**
         * The getters to check.
         */
        private final Method[] props;

        /**
         * Constructor.
         *
         * @param props the getters to check.
         */
        private ReflectiveEquality(Method[] props) {
            this.props = props;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean equals(SCMHeadMixin o1, SCMHeadMixin o2) {
            for (Method p : props) {
                Object p1;
                try {
                    p1 = p.invoke(o1);
                } catch (IllegalAccessException e) {
                    try {
                        p.invoke(o2);
                        return false;
                    } catch (IllegalAccessException e1) {
                        // woot they both failed the same way
                        continue;
                    } catch (InvocationTargetException e1) {
                        return false;
                    }
                } catch (InvocationTargetException e) {
                    try {
                        p.invoke(o2);
                        return false;
                    } catch (IllegalAccessException e1) {
                        return false;
                    } catch (InvocationTargetException e1) {
                        // woot they both failed the same way
                        continue;
                    }
                }
                Object p2;
                try {
                    p2 = p.invoke(o2);
                } catch (IllegalAccessException e) {
                    return false;
                } catch (InvocationTargetException e) {
                    return false;
                }
                if (p1 == null ? p2 != null : !p1.equals(p2)) {
                    return false;
                }
            }
            return true;
        }
    }
}
